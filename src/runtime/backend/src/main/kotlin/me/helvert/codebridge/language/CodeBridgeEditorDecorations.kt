package me.helvert.codebridge.language

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import com.intellij.util.ui.EmptyIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Markdown
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Attachment
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Style
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.workbench.ThemeColors
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.text.html.StyleSheet

/**
 * `TextEditor.setDecorations`: styling an extension paints over ranges of an editor, drawn.
 *
 * The host has one editor per document, so a type's decorations in an editor are its decorations
 * on the document, and they are range highlighters on the document's markup model -- shown at
 * once, moved with the text as it is edited, and carried to a split-mode client as the daemon's own
 * highlights are, which is why [CodeBridgeSemanticHighlights] draws the same way. What the style says
 * becomes the highlighter's attributes, its error-stripe mark and its gutter icon. Text before or
 * after a range is drawn as a declarative inlay by [CodeBridgeDecorationInlaysProvider], the kind that
 * reaches a split-mode client, and a hover message joins the extension's hover in Quick
 * Documentation, as VS Code shows the two together.
 *
 * With a frontend subscribed to [published] -- a split-mode client, or the frontend of this same
 * process -- the frontend draws the paint and the text instead, both from the same call. Drawn
 * here, the paint followed each call at once while the text waited for a highlighting pass, which
 * typing keeps restarting; Error Lens resets its decorations on nearly every keystroke, so the two
 * came apart. The highlighters here then draw nothing and only carry the ranges, for hovers.
 *
 * CSS VS Code would apply and an editor has no equivalent for -- opacity, cursors, letter spacing --
 * is not drawn. Before and after text keeps its colour, background, width and side margins; its
 * borders and fonts are not drawn.
 */
@Service(Service.Level.PROJECT)
class CodeBridgeEditorDecorations(private val project: Project, private val scope: CoroutineScope) {

    private val features get() = HostedLanguageFeatures.getInstance(project)

    private val types = ConcurrentHashMap<String, TextEditorDecorations.Type>()

    /** What is drawn on each file, by type key. Replaced whole on the EDT, read from anywhere. */
    private val drawn = ConcurrentHashMap<VirtualFile, Map<String, List<Drawn>>>()

    /**
     * Each replacement of a type's ranges in a file, for the frontend to paint. Never full: a
     * subscriber that fell behind would otherwise lose a replacement and keep stale paint.
     */
    private val replacements = MutableSharedFlow<DecorationsDto>(extraBufferCapacity = Int.MAX_VALUE)

    /**
     * The decorations each type was last given for each file, as the extension sent them, so a
     * call repeating them can be told apart from one with something new. EDT only.
     */
    private val lastCalls = HashMap<Pair<VirtualFile, String>, List<TextEditorDecorations.Decoration>>()

    /** Whether a frontend draws the decorations, so the highlighters and inlays here must not. */
    internal val frontendPaints get() = replacements.subscriptionCount.value > 0

    init {
        // A frontend that connects or goes takes the paint over or hands it back.
        scope.launch {
            replacements.subscriptionCount.map { it > 0 }.distinctUntilChanged().drop(1)
                .collect { onEdt(::repaintHere) }
        }
    }

    /** One decoration on the document; its highlighter carries its range as the text moves. */
    internal class Drawn(
        val highlighter: RangeHighlighter,
        val wholeLine: Boolean,
        /** What the type paints, whether this highlighter or the frontend's does the painting. */
        val attributes: TextAttributes?,
        val look: DecorationLookDto?,
        /** Its gutter icon, whether this highlighter or the frontend's draws it, and the key it is fetched by. */
        val gutter: GutterIconRenderer?,
        val gutterKey: String?,
        /** Markdown, with only the command links the extension trusted left in it. */
        val hovers: List<String>,
        val inLight: Pair<Attachment?, Attachment?>,
        val inDark: Pair<Attachment?, Attachment?>,
    ) {
        val hasText: Boolean
            get() = listOf(
                inLight.first,
                inLight.second,
                inDark.first,
                inDark.second
            ).any { !it?.contentText.isNullOrEmpty() }

        /** Whether a frontend has anything of this to draw. */
        val published: Boolean get() = look != null || hasText || gutterKey != null

        /** The text before and after it in the theme now in use, if any. */
        fun texts(): Pair<Attachment?, Attachment?> {
            val (before, after) = if (JBColor.isBright()) inLight else inDark
            return before?.takeUnless { it.contentText.isNullOrEmpty() } to after?.takeUnless { it.contentText.isNullOrEmpty() }
        }
    }

    /** Text an inlay draws at an offset. */
    internal data class PlacedText(val offset: Int, val after: Boolean, val text: String)

    /** Answers a decoration call, or returns null for any other method. */
    fun handle(method: String, argsJson: String): MainThreadResult? {
        when (method) {
            TextEditorDecorations.REGISTER -> TextEditorDecorations.register(argsJson)
                .let { (key, type) -> types[key] = type }

            TextEditorDecorations.REMOVE -> TextEditorDecorations.removed(argsJson).let { key ->
                types.remove(key)
                onEdt {
                    lastCalls.keys.removeIf { it.second == key }
                    drawn.keys.toList().forEach { file -> replace(file, key, emptyList()) }
                }
            }

            TextEditorDecorations.SET, TextEditorDecorations.SET_FAST -> set(
                TextEditorDecorations.set(
                    method,
                    argsJson
                )
            )

            else -> return null
        }
        return MainThreadResult()
    }

    /**
     * Draws a call, unless it only repeats the type's last one for the file.
     *
     * A repeat is left alone: what it drew has moved with the text since, which is where it
     * belongs. An extension often redraws on each keystroke from what it knew before it -- Error
     * Lens redraws whenever any problem in the file changes, and rust-analyzer's `cargo check`
     * problems keep their lines until it moves them -- and drawing the repeat put the paint back on
     * the line the code had left. Whatever is new is drawn at once, as VS Code draws it: if the
     * decorated thing has really moved, the extension names its new place, and that is a change.
     */
    private fun set(applied: TextEditorDecorations.Applied) {
        // Logged at info, as are the other calls dropped before drawing: rare, and otherwise a
        // decoration that never appears leaves nothing in the log to say where it went.
        val file = features.fileOfEditor(applied.editorId)
            ?: return LOG.info("Decorations '${applied.key}' for editor ${applied.editorId}, which shows no mirrored file; ignored")
        val type = types[applied.key]
            ?: return LOG.info("Decorations of a type never registered: ${applied.key}; ignored")
        set(file, applied, type)
    }

    /**
     * Draws decorations of a type CodeBridge itself defines, under [key], on [file], replacing that
     * type's there -- what an extension's language feature draws as VS Code's workbench decorates it,
     * such as the colour swatches of [CodeBridgeDocumentColors]. [key] must not be one the host hands out,
     * which are numbered.
     */
    internal fun setOwn(
        file: VirtualFile,
        key: String,
        type: TextEditorDecorations.Type,
        decorations: List<TextEditorDecorations.Decoration>
    ) {
        types[key] = type
        set(file, TextEditorDecorations.Applied("", key, decorations), type)
    }

    private fun set(file: VirtualFile, applied: TextEditorDecorations.Applied, type: TextEditorDecorations.Type) {
        onEdt {
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
                ?: return@onEdt LOG.info("Decorations '${applied.key}' on ${file.name}, which has no document loaded; ignored")
            if (lastCalls.put(file to applied.key, applied.decorations) == applied.decorations) {
                return@onEdt LOG.debug("Decorations '${applied.key}' on ${file.name} repeat the last call; kept where the text moved them")
            }
            val ranges = applied.decorations.map { decoration ->
                val start = offsetOf(document, decoration.range.startLineNumber, decoration.range.startColumn)
                val end =
                    offsetOf(document, decoration.range.endLineNumber, decoration.range.endColumn).coerceAtLeast(start)
                start to end
            }
            draw(file, document, applied, type, ranges)
        }
    }

    /** Draws [applied] at [ranges], one per decoration, replacing its type's decorations on [file]. EDT only. */
    private fun draw(
        file: VirtualFile,
        document: Document,
        applied: TextEditorDecorations.Applied,
        type: TextEditorDecorations.Type,
        ranges: List<Pair<Int, Int>>,
    ) {
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val attributes = DecorationStyles.attributes(type.inLight, type.inDark)
        val look = attributes?.let { DecorationStyles.look(type) }
        val painted = attributes.takeUnless { frontendPaints }
        val iconPath = gutterIconPath(type)
        val icon = iconPath?.let(::gutterIcon)
        val gutterKey = iconPath?.takeIf { icon != null }?.let(::gutterKey)
        val area = if (type.isWholeLine) HighlighterTargetArea.LINES_IN_RANGE else HighlighterTargetArea.EXACT_RANGE
        val added = applied.decorations.zip(ranges) { decoration, (start, end) ->
            val highlighter = markup.addRangeHighlighter(start, end, LAYER, painted, area)
            // DecorationRangeBehavior: whether typing at an edge grows the range.
            highlighter.isGreedyToLeft = type.rangeBehavior == 0 || type.rangeBehavior == 2
            highlighter.isGreedyToRight = type.rangeBehavior == 0 || type.rangeBehavior == 3
            val hovers =
                decoration.hoverMessages.mapNotNull { Markdown.trustedText(it)?.takeIf(String::isNotBlank) }
            if (hovers.isNotEmpty()) highlighter.errorStripeTooltip = hovers.joinToString("\n\n")
            val gutter = icon?.let { DecorationGutterIcon(it, hovers.firstOrNull()) }
            if (!frontendPaints) highlighter.gutterIconRenderer = gutter
            val render = decoration.renderOptions
            Drawn(
                highlighter,
                type.isWholeLine,
                attributes,
                look,
                gutter,
                gutterKey,
                hovers,
                attachments(type.inLight, render?.let { it.light?.over(it.base) ?: it.base }),
                attachments(type.inDark, render?.let { it.dark?.over(it.base) ?: it.base }),
            )
        }
        LOG.debug {
            "Decorations '${applied.key}' on ${file.name} drawn on lines ${
                ranges.map {
                    document.getLineNumber(
                        it.first
                    ) + 1
                }
            }"
        }
        replace(file, applied.key, added)
    }

    /** A type's before and after with an instance's own laid over them. */
    private fun attachments(type: Style, instance: Style?): Pair<Attachment?, Attachment?> =
        (instance?.before?.over(type.before) ?: type.before) to (instance?.after?.over(type.after) ?: type.after)

    /** Puts [added] where [key]'s decorations on [file] were. EDT only. */
    private fun replace(file: VirtualFile, key: String, added: List<Drawn>) {
        val before = drawn[file].orEmpty()
        val removed = before[key].orEmpty()
        removed.forEach { it.highlighter.dispose() }
        val after = if (added.isEmpty()) before - key else before + (key to added)
        if (after.isEmpty()) drawn.remove(file) else drawn[file] = after
        if (!frontendPaints && (removed.any { it.hasText } || added.any { it.hasText })) features.redrawInlays(file)
        if (removed.any { it.published } || added.any { it.published }) replacements.tryEmit(
            published(
                file,
                key,
                added
            )
        )
    }

    /**
     * Every replacement of a type's ranges, for a frontend to paint, starting with what is drawn
     * now. While any is collected, the highlighters here paint nothing.
     */
    fun published(): Flow<DecorationsDto> = replacements.onSubscription {
        // Read where the highlighters are now, not where they were set: the text has moved since.
        val now = readAction {
            drawn.entries.flatMap { (file, byKey) ->
                byKey.filter { (_, list) -> list.any { it.published } }
                    .map { (key, list) -> published(file, key, list) }
            }
        }
        now.forEach { emit(it) }
    }

    private fun published(file: VirtualFile, key: String, list: List<Drawn>) = DecorationsDto(
        file.path,
        key,
        list.filter { it.highlighter.isValid }.map {
            val (before, after) = it.texts()
            DecorationRangeDto(
                it.highlighter.startOffset, it.highlighter.endOffset, text(before), text(after),
                it.hovers.firstOrNull().takeIf { _ -> it.gutterKey != null },
            )
        },
        list.firstNotNullOfOrNull { it.look },
        list.any { it.wholeLine },
        list.firstNotNullOfOrNull { it.gutterKey },
    )

    /** [attachment]'s text, in its colour in the theme now in use. */
    private fun text(attachment: Attachment?): DecorationTextDto? = attachment?.contentText?.let { text ->
        DecorationStyles.box(attachment).let { box ->
            DecorationTextDto(
                text,
                attachment.color?.let(DecorationStyles::resolve)?.rgb,
                attachment.width,
                attachment.backgroundColor?.let(DecorationStyles::resolve)?.rgb,
                attachment.margin,
                box.borderWidth,
                box.borderColor?.rgb,
                box.overline?.rgb,
            )
        }
    }

    /** Has the highlighters and inlays here draw, or stop drawing, as [frontendPaints] now says. EDT only. */
    private fun repaintHere() {
        val paint = !frontendPaints
        drawn.values.flatMap { it.values.flatten() }.forEach { decoration ->
            val highlighter = (decoration.highlighter as? RangeHighlighterEx)?.takeIf { it.isValid } ?: return@forEach
            highlighter.textAttributes = decoration.attributes.takeIf { paint }
            highlighter.gutterIconRenderer = decoration.gutter.takeIf { paint }
        }
        drawn.keys.filter(::hasText).forEach(features::redrawInlays)
    }

    /** Hover messages of the decorations at [offset] in [file]. Under a read action. */
    internal fun hoversAt(file: VirtualFile, document: Document, offset: Int): List<String> =
        drawn[file].orEmpty().values.flatten().filter { it.hovers.isNotEmpty() && covers(it, document, offset) }
            .flatMap { it.hovers }

    internal fun hasHovers(file: VirtualFile): Boolean =
        drawn[file].orEmpty().values.any { list -> list.any { it.hovers.isNotEmpty() } }

    internal fun hasText(file: VirtualFile): Boolean =
        drawn[file].orEmpty().values.any { list -> list.any { it.hasText } }

    /**
     * Text before and after each decoration on [file], in the theme now in use. Under a read action.
     *
     * A whole-line decoration's text goes at the start of its first line and the end of its last,
     * wherever in the line its range starts and ends: VS Code widens a whole-line range to the full
     * lines before drawing it. Error Lens relies on that, putting each message on a range at the
     * diagnostic and having it shown at the end of the line.
     */
    internal fun textOn(file: VirtualFile): List<PlacedText> {
        return drawn[file].orEmpty().values.flatten().filter { it.highlighter.isValid }.flatMap { decoration ->
            val (before, after) = decoration.texts()
            val highlighter = decoration.highlighter
            val document = highlighter.document
            val start =
                if (decoration.wholeLine) document.getLineStartOffset(document.getLineNumber(highlighter.startOffset)) else highlighter.startOffset
            val end =
                if (decoration.wholeLine) document.getLineEndOffset(document.getLineNumber(highlighter.endOffset)) else highlighter.endOffset
            listOfNotNull(
                before?.contentText?.let { PlacedText(start, false, it) },
                after?.contentText?.let { PlacedText(end, true, it) },
            )
        }
    }

    private fun covers(decoration: Drawn, document: Document, offset: Int): Boolean {
        val highlighter = decoration.highlighter
        if (!highlighter.isValid) return false
        if (!decoration.wholeLine) return offset in highlighter.startOffset..highlighter.endOffset
        val line = document.getLineNumber(offset)
        return line in document.getLineNumber(highlighter.startOffset)..document.getLineNumber(highlighter.endOffset)
    }

    /** Takes [file]'s decorations off, for a document the host no longer has an editor for. */
    fun forget(file: VirtualFile) = onEdt {
        lastCalls.keys.removeIf { it.first == file }
        drawn[file]?.keys?.toList()?.forEach { replace(file, it, emptyList()) }
    }

    fun hostStopped() {
        types.clear()
        onEdt {
            lastCalls.clear()
            drawn.keys.toList()
                .forEach { file -> drawn[file]?.keys?.toList()?.forEach { replace(file, it, emptyList()) } }
        }
    }

    /** The type's gutter icon file in the theme now in use, if it names one. */
    private fun gutterIconPath(type: TextEditorDecorations.Type): UriComponents? =
        (if (JBColor.isBright()) type.inLight else type.inDark).gutterIconPath

    /** The icon at [path], at most the size of the IDE's own; null when it cannot be read. */
    private fun gutterIcon(path: UriComponents): Icon? =
        icons.computeIfAbsent(path.uri) { loadIcon(path) ?: NO_ICON }.takeIf { it !== NO_ICON }

    private val icons = ConcurrentHashMap<String, Icon>()

    /** Gutter icon files by the key a frontend fetches them with; see [gutterIconBytes]. */
    private val iconFiles = ConcurrentHashMap<String, Path>()

    /** The key [path] is fetched by: a hash, so a frontend learns nothing of the backend's paths. */
    private fun gutterKey(path: UriComponents): String? {
        val file = runCatching { path.toPath() }.getOrNull() ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(path.uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        iconFiles[key] = file
        return key
    }

    /** The gutter icon file registered as [key], or null when it has gone or is too large to be an icon. */
    fun gutterIconBytes(key: String): ByteArray? {
        val file = iconFiles[key] ?: return null
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_ICON_BYTES) return null
        return Files.readAllBytes(file)
    }

    private fun loadIcon(path: UriComponents): Icon? {
        val icon = runCatching { IconLoader.findIcon(path.toPath().toUri().toURL(), true) }
            .onFailure { LOG.debug("Could not load a decoration's gutter icon from ${path.uri}", it) }.getOrNull()
            ?: return null
        val limit = JBUIScale.scale(GUTTER_ICON_SIZE)
        val largest = maxOf(icon.iconWidth, icon.iconHeight)
        return if (largest <= limit) icon else IconUtil.scale(icon, null, limit.toFloat() / largest)
    }

    private fun onEdt(action: () -> Unit) {
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) { action() }
    }

    /** VS Code's 1-based position as an offset, clamped to the document. */
    private fun offsetOf(document: Document, line: Int, column: Int): Int {
        if (document.lineCount == 0) return 0
        val index = (line - 1).coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(index)
        return (start + (column - 1).coerceAtLeast(0)).coerceAtMost(document.getLineEndOffset(index))
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeEditorDecorations::class.java)

        /** Over the grammar's colours and semantic tokens, as a decoration's inline style is in VS Code; under errors and the selection. */
        private const val LAYER = HighlighterLayer.ADDITIONAL_SYNTAX + 1

        private const val GUTTER_ICON_SIZE = 16

        /** The largest gutter icon file a frontend is sent; as for tree view icons. */
        private const val MAX_ICON_BYTES = 1024 * 1024

        private val NO_ICON: Icon = EmptyIcon.ICON_0

        fun getInstance(project: Project): CodeBridgeEditorDecorations = project.service()
    }
}

/** A decoration type's gutter icon. Equal by what it shows, so an unchanged icon is not redrawn. */
private class DecorationGutterIcon(private val icon: Icon, private val tooltip: String?) : GutterIconRenderer() {
    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String? = tooltip
    override fun equals(other: Any?): Boolean =
        other is DecorationGutterIcon && other.icon == icon && other.tooltip == tooltip

    override fun hashCode(): Int = icon.hashCode() * 31 + tooltip.hashCode()
}

/**
 * A decoration's CSS as editor text attributes.
 *
 * Colours follow the theme: where the light and dark variants differ, the attribute is a [JBColor]
 * of the two. Only what an editor can draw is read.
 */
internal object DecorationStyles {

    /** The attributes for a style in either theme, or null when it draws nothing on the text. */
    fun attributes(
        light: Style,
        dark: Style,
        resolve: (TextEditorDecorations.Color) -> Color? = ::resolve
    ): TextAttributes? {
        fun color(of: (Style) -> TextEditorDecorations.Color?): Color? =
            themed(of(light)?.let(resolve), of(dark)?.let(resolve))

        val attributes = TextAttributes()
        attributes.foregroundColor = color { it.color }
        attributes.backgroundColor = color { it.backgroundColor }
        attributes.errorStripeColor = color { it.overviewRulerColor }
        val style = dark.takeIf { JBColor.isBright().not() } ?: light
        attributes.fontType = fontType(style.fontWeight, style.fontStyle)

        val lineColor =
            color { textDecorationColor(it.textDecoration) } ?: attributes.foregroundColor ?: DEFAULT_FOREGROUND
        val line = textDecoration(style.textDecoration)
        val boxColor = color { it.borderColor ?: shorthandColor(it.border) } ?: color {
            it.outlineColor ?: shorthandColor(it.outline)
        }
        val boxed =
            boxColor != null && (drawsLine(style.border) || drawsLine(style.outline) || style.borderStyle != null || style.outlineStyle != null)
        val box = if (!style.borderRadius.isNullOrBlank() && style.borderRadius?.trim() !in setOf(
                "0",
                "0px"
            )
        ) EffectType.ROUNDED_BOX else EffectType.BOXED
        when {
            line != null -> {
                attributes.effectType = line
                attributes.effectColor = lineColor
                if (boxed) attributes.withAdditionalEffect(box, boxColor)
            }

            boxed -> {
                attributes.effectType = box
                attributes.effectColor = boxColor
            }
        }
        // An error-stripe colour alone does not count towards `isEmpty`, and is still a mark to draw.
        return attributes.takeUnless { it.isEmpty && it.errorStripeColor == null }
    }

    /** What [type] paints in either theme, for a frontend to draw; see [CodeBridgeEditorDecorations.published]. */
    fun look(type: TextEditorDecorations.Type): DecorationLookDto = DecorationLookDto(
        light = attributes(type.inLight, type.inLight)?.let(::paint),
        dark = attributes(type.inDark, type.inDark)?.let(::paint),
        greedyToLeft = type.rangeBehavior == 0 || type.rangeBehavior == 2,
        greedyToRight = type.rangeBehavior == 0 || type.rangeBehavior == 3,
    )

    private fun paint(attributes: TextAttributes): DecorationPaintDto {
        val effects = mutableListOf<DecorationEffectDto>()
        val main = attributes.effectType
        val mainColor = attributes.effectColor
        if (main != null && mainColor != null) effects += DecorationEffectDto(main.name, mainColor.rgb)
        attributes.forEachAdditionalEffect { type, color -> effects += DecorationEffectDto(type.name, color.rgb) }
        return DecorationPaintDto(
            attributes.foregroundColor?.rgb,
            attributes.backgroundColor?.rgb,
            attributes.errorStripeColor?.rgb,
            attributes.fontType,
            effects,
        )
    }

    /** The light and dark colours as one, or whichever there is. */
    private fun themed(light: Color?, dark: Color?): Color? = when {
        light == null || dark == null -> light ?: dark
        light == dark -> light
        else -> JBColor(light, dark)
    }

    fun resolve(color: TextEditorDecorations.Color): Color? = when (color) {
        is TextEditorDecorations.Color.Css -> CssColors.parse(color.text)
        is TextEditorDecorations.Color.Theme -> ThemeColors.installed().resolve(color.id)
    }

    private fun fontType(weight: String?, style: String?): Int {
        val bold = weight?.trim()?.let { it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 0) >= 600 } == true
        val italic = style?.trim()?.let { it == "italic" || it.startsWith("oblique") } == true
        return (if (bold) Font.BOLD else 0) or (if (italic) Font.ITALIC else 0)
    }

    /**
     * `text-decoration`'s line, if it draws one.
     *
     * Only the part before a `;`: extensions put further CSS after one, which VS Code's style
     * sheet takes as more declarations.
     */
    private fun textDecoration(value: String?): EffectType? {
        val words = value?.substringBefore(';')?.lowercase()?.split(Regex("\\s+")).orEmpty()
        return when {
            "line-through" in words -> EffectType.STRIKEOUT
            "underline" !in words -> null
            "wavy" in words -> EffectType.WAVE_UNDERSCORE
            "dotted" in words || "dashed" in words -> EffectType.BOLD_DOTTED_LINE
            "double" in words -> EffectType.BOLD_LINE_UNDERSCORE
            else -> EffectType.LINE_UNDERSCORE
        }
    }

    /** The border and top line of the box text is drawn in. */
    data class Box(val borderWidth: String?, val borderColor: Color?, val overline: Color?)

    /**
     * What an attachment draws around its text. A decoration's text takes only `border` and
     * `borderColor`, so extensions write the rest into `textDecoration`, after its value, as more
     * declarations VS Code's style sheet takes as they are: GitLens separates commits in its blame
     * column with `text-decoration: overline`, and puts its heatmap on the column's edge with
     * `border-style: solid; border-width: 0 2px 0 0`, coloured per line by `borderColor`.
     */
    fun box(attachment: Attachment): Box {
        val parts = attachment.textDecoration?.split(';').orEmpty()
        val declarations = parts.drop(1).mapNotNull { part ->
            part.substringBefore(':', "").trim().lowercase().takeIf { it.isNotEmpty() }
                ?.let { it to part.substringAfter(':').trim() }
        }.toMap()
        val line = declarations["text-decoration"] ?: parts.firstOrNull()
        val overline = line?.takeIf { "overline" in it.lowercase() }
            ?.let { shorthandColor(it)?.let(::resolve) ?: DEFAULT_FOREGROUND }
        val shorthand = attachment.border?.takeIf(::drawsLine)
        val style = declarations["border-style"]?.takeIf(::drawsLine)
        val width = when {
            shorthand != null -> words(shorthand).firstOrNull { it.first().isDigit() } ?: "1px"
            style != null -> declarations["border-width"] ?: "1px"
            else -> null
        }
        val color = (attachment.borderColor ?: shorthand?.let(::shorthandColor))?.let(::resolve)
        return Box(width?.takeIf { color != null }, color.takeIf { width != null }, overline)
    }

    private fun textDecorationColor(value: String?): TextEditorDecorations.Color? =
        shorthandColor(value?.substringBefore(';'))

    /** A colour among the words of a shorthand -- `1px solid red`. */
    private fun shorthandColor(value: String?): TextEditorDecorations.Color? = value?.let { words(it) }
        ?.firstOrNull { it !in LINE_KEYWORDS && !it.first().isDigit() && CssColors.parse(it) != null }
        ?.let { TextEditorDecorations.Color.Css(it) }

    /** Whether a `border` or `outline` shorthand draws anything. */
    private fun drawsLine(value: String?): Boolean {
        val words = value?.let(::words) ?: return false
        return words.isNotEmpty() && "none" !in words && "hidden" !in words && words != listOf("0")
    }

    /** CSS words, keeping a function such as `rgba(0, 0, 0, 0.5)` whole. */
    private fun words(value: String): List<String> =
        Regex("""[a-zA-Z-]+\([^)]*\)|[^\s()]+""").findAll(value.trim()).map { it.value }.toList()

    private val LINE_KEYWORDS = setOf(
        "none", "hidden", "solid", "dotted", "dashed", "double", "groove", "ridge", "inset", "outset",
        "thin", "medium", "thick", "underline", "overline", "line-through", "wavy", "blink",
    )

    private val DEFAULT_FOREGROUND: Color =
        JBColor.lazy { EditorColorsManager.getInstance().globalScheme.defaultForeground }
}

/** CSS colour values: hex, `rgb()` and `rgba()`, and the named colours. */
internal object CssColors {

    private val RGB = Regex("""rgba?\(\s*([\d.]+%?)[\s,]+([\d.]+%?)[\s,]+([\d.]+%?)(?:[\s,/]+([\d.]+%?))?\s*\)""")
    private val named = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    /** [text] as a colour, or null for one this cannot read or that draws nothing. */
    fun parse(text: String): Color? {
        val value = text.trim().lowercase()
        if (value.isEmpty() || value == "transparent" || value == "none" || value == "inherit" || value == "currentcolor") return null
        if (value.startsWith("#")) return ThemeColors.hex(value)
        RGB.matchEntire(value)?.let { match ->
            val (r, g, b) = (1..3).map { channel(match.groupValues[it], 255f) ?: return null }
            val a = match.groupValues[4].takeIf { it.isNotEmpty() }?.let { channel(it, 1f) }?.let { (it * 255).toInt() }
                ?: 255
            return Color(
                r.toInt().coerceIn(0, 255),
                g.toInt().coerceIn(0, 255),
                b.toInt().coerceIn(0, 255),
                a.coerceIn(0, 255)
            )
        }
        if (!value.all { it.isLetter() }) return null
        return named.computeIfAbsent(value) { StyleSheet().stringToColor(it) ?: NONE } as? Color
    }

    /** A channel out of [max], or a percentage of it. */
    private fun channel(text: String, max: Float): Float? =
        if (text.endsWith("%")) text.removeSuffix("%").toFloatOrNull()
            ?.let { it / 100f * max } else text.toFloatOrNull()
}

/**
 * Draws the text extensions put before and after decorated ranges, as declarative inlays.
 *
 * Declarative for the reason [CodeBridgeInlayHintsProvider] is: they reach a split-mode client. The pass
 * that collects them only reads what [CodeBridgeEditorDecorations] holds, and is asked to run again when
 * that changes.
 */
internal class CodeBridgeDecorationInlaysProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val virtualFile = file.virtualFile ?: return null
        val decorations = CodeBridgeEditorDecorations.getInstance(file.project)
        // A frontend draws the text itself while it is connected; see [CodeBridgeEditorDecorations.published].
        if (!decorations.hasText(virtualFile) || decorations.frontendPaints) return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                for ((offset, after, content) in decorations.textOn(virtualFile)) {
                    sink.addPresentation(
                        InlineInlayPosition(offset, relatedToPrevious = after),
                        null,
                        null,
                        FORMAT
                    ) {
                        content.chunked(MAX_TEXT_NODE).forEach { text(it) }
                    }
                }
            }
        }
    }

    companion object {
        const val ID: String = "codebridge.decorations"

        /** Without the hint background: VS Code draws decoration text as text. */
        private val FORMAT = HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground)

        /** The longest text node the platform draws whole. */
        private const val MAX_TEXT_NODE = 30

        val INFO =
            InlayProviderInfo(CodeBridgeDecorationInlaysProvider(), ID, emptySet(), true, "VS Code extension decorations")
    }
}
