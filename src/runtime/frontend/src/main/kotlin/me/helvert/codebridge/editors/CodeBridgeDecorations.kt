package me.helvert.codebridge.editors

import com.intellij.ide.ui.AntialiasingType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import com.intellij.ui.ColorChooserService
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.helvert.codebridge.exthost.protocol.payload.CssWidth
import me.helvert.codebridge.icons.ExtensionImages
import me.helvert.codebridge.rpc.*
import java.awt.*
import java.awt.geom.Rectangle2D
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** Starts painting a project's extension decorations, as soon as it opens. */
internal class CodeBridgeDecorationsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeDecorations>().start()
}

/**
 * What extensions draw with `setDecorations`, on this side's documents: the paint and the gutter
 * icon as highlighters, the text before and after a range as inlays of the editors showing it.
 *
 * Both from the same call, as VS Code draws them, so they stay together. The backend drew the text
 * as an inlay that only a highlighting pass redrew, and typing keeps restarting the pass; Error
 * Lens resets its decorations on nearly every keystroke, so its paint went to each new place at
 * once and its message stayed behind. The backend keeps its own highlighters, drawing nothing,
 * for the hovers.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeDecorations(private val project: Project, private val scope: CoroutineScope) {

    /** What is drawn, by the file's backend path and decoration type. EDT only. */
    private val painted = HashMap<Pair<String, String>, List<Disposable>>()

    /**
     * The last replacement of each, to draw again in an editor made after it arrived. In split mode
     * a file is among the open ones a moment before its editor exists; a replacement arriving then
     * got its paint, which is the document's, and no text, which is an editor's -- and the extension
     * does not send it again. EDT only.
     */
    private val latest = HashMap<Pair<String, String>, DecorationsDto>()

    private val started = AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        project.messageBus.connect(scope)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    latest.filterKeys { it.first == file.path }.values.toList().forEach(::paint)
                }
            })
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project != project) return
                val path = FileDocumentManager.getInstance().getFile(event.editor.document)?.path ?: return
                // Later, once the document is associated with the opened file. The factory knows
                // the editor before FileEditorManager has registered its TextEditor wrapper.
                scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    latest.filterKeys { it.first == path }.values.toList().forEach(::paint)
                }
            }
        }, project)
        listenForColorPicks()
        scope.launch {
            // Reconnects like the other streams: a backend restart ends it, and the new one starts
            // with everything drawn, so what this side painted before goes first.
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().decorations(project.projectId()).collect { replacement ->
                        withContext(Dispatchers.EDT) { paint(replacement) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension decoration stream; retrying", error)
                }
                withContext(Dispatchers.EDT) { clear() }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private fun paint(replacement: DecorationsDto) {
        if (project.isDisposed) return
        painted.remove(replacement.path to replacement.key)?.forEach(Disposable::dispose)
        if (replacement.ranges.isEmpty()) return run { latest.remove(replacement.path to replacement.key) }
        latest[replacement.path to replacement.key] = replacement
        // Decorations are set on editors, so the file is open here; under the backend's path, in
        // whatever file system this side shows it from.
        val file =
            FileEditorManager.getInstance(project).openFiles.firstOrNull { it.path == replacement.path } ?: return
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
        val length = document.textLength
        // Offsets on the backend's text, which may be a keystroke behind or ahead of this one.
        val ranges = replacement.ranges.map { range ->
            val start = range.start.coerceIn(0, length)
            Triple(start, range.end.coerceIn(start, length), range)
        }
        val drawn = mutableListOf<Disposable>()
        highlight(document, replacement, ranges, drawn)
        val editors = EditorFactory.getInstance().getEditors(document, project)
        for ((start, end, range) in ranges) {
            // VS Code widens a whole-line range to its lines before drawing its text, which is how
            // Error Lens has its message at the end of the line wherever the problem is in it.
            val before =
                if (replacement.wholeLine) document.getLineStartOffset(document.getLineNumber(start)) else start
            for (editor in editors) {
                range.before?.let { text ->
                    editor.inlayModel.addInlineElement(
                        before,
                        false,
                        DecorationText(text, false, picksColor = replacement.key == DecorationsDto.DOCUMENT_COLORS)
                    )?.let(drawn::add)
                }
                // A whole line's text goes after the line's end, anchored at the range rather than at
                // that end: Enter typed just before it would otherwise carry it onto the new line,
                // and nothing redraws it, the problem it describes not having moved.
                range.after?.let { text ->
                    val inlay = if (replacement.wholeLine) editor.inlayModel.addAfterLineEndElement(
                        end,
                        true,
                        DecorationText(text, true)
                    )
                    else editor.inlayModel.addInlineElement(end, true, DecorationText(text, true))
                    inlay?.let(drawn::add)
                }
            }
        }
        // With the length, so a line here can be checked against the backend's for the same call.
        LOG.debug {
            "Decorations '${replacement.key}' on ${file.name} drawn on lines ${
                ranges.map {
                    document.getLineNumber(
                        it.first
                    ) + 1
                }
            }, document length ${document.textLength}"
        }
        painted[replacement.path to replacement.key] = drawn
    }

    /** The paint and the gutter icon of [ranges], one highlighter each, if there is either. */
    private fun highlight(
        document: Document,
        replacement: DecorationsDto,
        ranges: List<Triple<Int, Int, DecorationRangeDto>>,
        drawn: MutableList<Disposable>,
    ) {
        val look = replacement.look
        val attributes = look?.let(::attributes)
        val icon = replacement.gutterIcon
        if (attributes == null && icon == null) return
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val area =
            if (replacement.wholeLine) HighlighterTargetArea.LINES_IN_RANGE else HighlighterTargetArea.EXACT_RANGE
        val stripe = attributes?.errorStripeColor
        // The stripe goes on the runs below; what is left may paint nothing at all.
        val painted = attributes?.clone()?.apply { errorStripeColor = null }?.takeUnless { it.isEmpty }
        for ((start, end, range) in ranges) {
            if (painted == null && icon == null) break
            val highlighter = markup.addRangeHighlighter(start, end, LAYER, painted, area)
            highlighter.isGreedyToLeft = look?.greedyToLeft == true
            highlighter.isGreedyToRight = look?.greedyToRight == true
            if (icon != null) gutter(highlighter, icon, range.gutterTooltip)
            drawn += Disposable { highlighter.dispose() }
        }
        val lines = ranges.map { (start, end) -> document.getLineNumber(start) to document.getLineNumber(end) }
        if (stripe != null) for ((first, last) in lineRuns(lines)) {
            val run = markup.addRangeHighlighter(
                document.getLineStartOffset(first), document.getLineEndOffset(last), LAYER,
                TextAttributes().apply { errorStripeColor = stripe }, HighlighterTargetArea.LINES_IN_RANGE,
            )
            drawn += Disposable { run.dispose() }
        }
    }


    /** Gutter icons by their key, fetched from the backend once each. EDT only. */
    private val icons = HashMap<String, Icon>()

    /** Highlighters waiting for an icon being fetched, with their tooltips. EDT only. */
    private val waiting = HashMap<String, MutableList<Pair<RangeHighlighter, String?>>>()

    /** Gives [highlighter] the gutter icon [key] names, now if it is here and once fetched if not. */
    private fun gutter(highlighter: RangeHighlighter, key: String, tooltip: String?) {
        icons[key]?.let { return run { highlighter.gutterIconRenderer = GutterIcon(it, tooltip) } }
        val fetching = waiting.containsKey(key)
        waiting.getOrPut(key) { mutableListOf() } += highlighter to tooltip
        if (fetching) return
        scope.launch {
            val icon = try {
                CodeBridgeApi.getInstance().decorationIcon(project.projectId(), key)?.let(ExtensionImages::decode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.debug("Could not fetch decoration gutter icon $key", error)
                null
            }
            withContext(Dispatchers.EDT) {
                val highlighters = waiting.remove(key).orEmpty()
                if (icon == null) return@withContext
                icons[key] = icon
                for ((waited, text) in highlighters) if (waited.isValid) waited.gutterIconRenderer =
                    GutterIcon(icon, text)
            }
        }
    }

    /**
     * Opens a colour picker on a click on a colour swatch, as VS Code opens one on its swatch, and
     * has the backend write each colour chosen as the extension writes colours. The hand cursor
     * over a swatch says it can be clicked.
     */
    private fun listenForColorPicks() {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                if (event.editor.project != project) return
                val inlay = event.inlay ?: return
                val swatch = (inlay.renderer as? DecorationText)?.takeIf { it.picksColor } ?: return
                val path = FileDocumentManager.getInstance().getFile(event.editor.document)?.path ?: return
                event.consume()
                val picks = ColorPicks(path, inlay.offset)
                ColorChooserService.getInstance().showPopup(
                    project, swatch.background, { color, _ -> picks.chosen(color) },
                    RelativePoint(event.mouseEvent), showAlpha = true
                )
            }
        }, project)
        multicaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                if (event.editor.project != project) return
                val over = (event.inlay?.renderer as? DecorationText)?.picksColor == true
                (event.editor as? EditorEx)?.setCustomCursor(
                    this@CodeBridgeDecorations,
                    if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
                )
            }
        }, project)
    }

    /**
     * The colours one picker chooses for the swatch at [offset], sent one at a time, and only the
     * latest of those chosen while one was on its way: dragging through the picker chooses dozens,
     * and each is an edit the next must find in place.
     */
    private inner class ColorPicks(private val path: String, private val offset: Int) {
        private val latest = AtomicReference<Color?>()
        private val sending = Mutex()

        fun chosen(color: Color) {
            latest.set(color)
            scope.launch {
                sending.withLock {
                    val next = latest.getAndSet(null) ?: return@withLock
                    val written = try {
                        CodeBridgeApi.getInstance().pickDocumentColor(project.projectId(), path, offset, next.rgb)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        LOG.warn("Could not write the colour picked in $path", error)
                        false
                    }
                    if (!written) LOG.debug("The colour picked at $offset in $path was not written")
                }
            }
        }
    }

    private fun clear() {
        painted.values.flatten().forEach(Disposable::dispose)
        painted.clear()
        latest.clear()
    }

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeDecorations::class.java)
        const val RETRY_MILLIS = 2_000L

        /** The layer the backend paints them on; see `CodeBridgeEditorDecorations`. */
        const val LAYER = HighlighterLayer.ADDITIONAL_SYNTAX + 1

        /** The look in either theme, colours following it; null when it paints nothing. */
        fun attributes(look: DecorationLookDto): TextAttributes? {
            val light = look.light
            val dark = look.dark
            if (light == null && dark == null) return null
            fun color(of: (DecorationPaintDto) -> Int?): Color? {
                val inLight = light?.let(of)?.let { Color(it, true) }
                val inDark = dark?.let(of)?.let { Color(it, true) }
                return when {
                    inLight == null || inDark == null -> inLight ?: inDark
                    inLight == inDark -> inLight
                    else -> JBColor(inLight, inDark)
                }
            }

            val attributes = TextAttributes()
            attributes.foregroundColor = color { it.foreground }
            attributes.backgroundColor = color { it.background }
            attributes.errorStripeColor = color { it.errorStripe }
            // As the backend reads the style: the theme now in use decides the font and the lines.
            val current = (if (JBColor.isBright()) light else dark) ?: light ?: dark ?: return null
            attributes.fontType = current.fontType
            val effects = current.effects.mapNotNull { effect ->
                runCatching { EffectType.valueOf(effect.type) }.getOrNull()?.let { it to Color(effect.color, true) }
            }
            effects.firstOrNull()?.let { (type, color) ->
                attributes.effectType = type
                attributes.effectColor = color
            }
            if (effects.size > 1) attributes.setAdditionalEffects(effects.drop(1).toMap())
            return attributes
        }
    }
}

/**
 * Text before or after a decorated range, drawn as VS Code draws it: in the editor's font, on the
 * line's baseline, without a background, in the colour the extension gave it -- Error Lens colours
 * a message by its severity -- or else the one the theme gives inlay text without a background. A
 * space's width apart from the text it is next to, where VS Code would apply the extension's CSS
 * margin.
 *
 * At least as wide as the extension's CSS width, when it gave one this can measure: GitLens' blame
 * column is the same width on every line, though only a commit's first line has more than a space.
 * Its background fills that box, the line's full height, with its border and top line inside it --
 * GitLens' heatmap edge and the line between commits -- and its CSS margins, when it gave some, stand
 * in for the space: Error Lens' message sits `4ch` from the code, as in VS Code.
 */
private class DecorationText(
    text: DecorationTextDto,
    private val after: Boolean,
    /** Whether it is a colour swatch, which a click opens a colour picker on. */
    val picksColor: Boolean = false,
) : EditorCustomElementRenderer {
    private val text = text.text
    private val color = text.color?.let { Color(it, true) }
    private val width = CssWidth.parse(text.width)
    val background = text.background?.let { Color(it, true) }
    private val margins = CssWidth.margins(text.margin)
    private val borders = text.borderWidth?.let(CssWidth::sides)
    private val borderColor = text.borderColor?.let { Color(it, true) }
    private val overline = text.overline?.let { Color(it, true) }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val (left, box, right) = layout(inlay.editor)
        return left + box + right
    }

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val (left, box, _) = layout(editor)
        val x = targetRegion.x + left
        background?.let {
            g.color = it
            g.fill(Rectangle2D.Double(x, targetRegion.y, box.toDouble(), targetRegion.height))
        }
        // Inside the box, as GitLens sizes its column with `box-sizing: border-box`.
        val edge = borderColor
        if (borders != null && edge != null) {
            val (top, right, bottom, left) = borders.map { pixels(editor, it).toDouble() }
            g.color = edge
            val y = targetRegion.y
            val height = targetRegion.height
            if (top > 0) g.fill(Rectangle2D.Double(x, y, box.toDouble(), top))
            if (bottom > 0) g.fill(Rectangle2D.Double(x, y + height - bottom, box.toDouble(), bottom))
            if (left > 0) g.fill(Rectangle2D.Double(x, y, left, height))
            if (right > 0) g.fill(Rectangle2D.Double(x + box - right, y, right, height))
        }
        overline?.let {
            g.color = it
            g.fill(Rectangle2D.Double(x, targetRegion.y, box.toDouble(), JBUIScale.scale(1f).toDouble()))
        }
        g.font = font(editor)
        g.color = color
            ?: editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)?.foregroundColor
                    ?: editor.colorsScheme.defaultForeground
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, AntialiasingType.getKeyForCurrentScope(true))
        g.drawString(text, x.toFloat(), (targetRegion.y + editor.ascent).toFloat())
    }

    /**
     * The left margin, the box and the right margin, in pixels. Without margins of its own, a space
     * on the side facing the code: before the text after a range, after the text before one.
     */
    private fun layout(editor: Editor): Triple<Int, Int, Int> {
        val metrics = editor.contentComponent.getFontMetrics(font(editor))
        val box = maxOf(metrics.stringWidth(text), width?.let { pixels(editor, it) } ?: 0)
        val (left, right) = margins?.let { (left, right) -> pixels(editor, left) to pixels(editor, right) }
            ?: metrics.charWidth(' ').let { gap -> if (after) gap to 0 else 0 to gap }
        return Triple(left, box, right)
    }

    /** [css] in [editor]'s pixels: `ch` by its font's `0`, `em` by its font size. */
    private fun pixels(editor: Editor, css: CssWidth): Int = css.pixels(
        editor.contentComponent.getFontMetrics(font(editor)).charWidth('0').toDouble(),
        editor.colorsScheme.getFont(EditorFontType.PLAIN).size2D.toDouble(),
        JBUIScale.scale(1f).toDouble(),
    ).roundToInt().coerceAtLeast(0)

    /** The editor's font, falling back for characters it lacks -- Error Lens draws line breaks as `⏎`. */
    private fun font(editor: Editor): Font =
        UIUtil.getFontWithFallback(editor.colorsScheme.getFont(EditorFontType.PLAIN))
}

/** A decoration's gutter icon. Equal by what it shows, so an unchanged icon is not redrawn. */
private class GutterIcon(private val icon: Icon, private val tooltip: String?) : GutterIconRenderer() {
    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String? = tooltip
    override fun equals(other: Any?): Boolean = other is GutterIcon && other.icon == icon && other.tooltip == tooltip
    override fun hashCode(): Int = icon.hashCode() * 31 + tooltip.hashCode()
}

/**
 * Line spans, first to last, as runs of adjacent lines: one error stripe mark each.
 *
 * VS Code's overview ruler draws the marks of adjacent lines as one band. A mark per range drew a
 * comb down the scrollbar instead -- GitLens highlights a commit's lines one decoration each, every
 * line of a file of one commit.
 */
internal fun lineRuns(lines: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val runs = mutableListOf<Pair<Int, Int>>()
    for ((first, last) in lines.sortedBy { it.first }) {
        val previous = runs.lastOrNull()
        if (previous != null && first <= previous.second + 1) runs[runs.lastIndex] =
            previous.first to maxOf(previous.second, last)
        else runs += first to last
    }
    return runs
}
