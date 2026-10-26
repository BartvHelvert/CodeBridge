package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadTextEditors`' decorations: styling an extension paints over ranges of an editor.
 *
 * Read off the 1.143 bundle's `TextEditorDecorationType` and `setDecorations`. A type is registered
 * once with its style, under a key the host makes up, and each `setDecorations` replaces the ranges
 * that type covers in one editor. Every style property is CSS text as the extension wrote it, or a
 * `ThemeColor`, which arrives as `{id}`.
 */
object TextEditorDecorations {

    /** `$registerTextEditorDecorationType(extensionId, key, options)`. */
    const val REGISTER: String = $$"$registerTextEditorDecorationType"

    /** `$removeTextEditorDecorationType(key)`, when the extension disposes of the type. */
    const val REMOVE: String = $$"$removeTextEditorDecorationType"

    /** `$trySetDecorations(id, key, options[])`: ranges with hover messages or render options. */
    const val SET: String = $$"$trySetDecorations"

    /** `$trySetDecorationsFast(id, key, ranges)`: bare ranges, four numbers each. */
    const val SET_FAST: String = $$"$trySetDecorationsFast"

    /** A CSS colour as written, or a theme colour by id. */
    sealed interface Color {
        data class Css(val text: String) : Color
        data class Theme(val id: String) : Color
    }

    /** What VS Code draws before or after a range: text, and how it looks. */
    data class Attachment(
        val contentText: String? = null,
        val color: Color? = null,
        val backgroundColor: Color? = null,
        val border: String? = null,
        val borderColor: Color? = null,
        val fontStyle: String? = null,
        val fontWeight: String? = null,
        val textDecoration: String? = null,
        val margin: String? = null,
        /** The CSS width of the box the text is drawn in; see [CssWidth]. */
        val width: String? = null,
    ) {
        /** This, with what it leaves out taken from [under]. */
        fun over(under: Attachment?): Attachment = if (under == null) this else Attachment(
            contentText ?: under.contentText,
            color ?: under.color,
            backgroundColor ?: under.backgroundColor,
            border ?: under.border,
            borderColor ?: under.borderColor,
            fontStyle ?: under.fontStyle,
            fontWeight ?: under.fontWeight,
            textDecoration ?: under.textDecoration,
            margin ?: under.margin,
            width ?: under.width,
        )
    }

    /** The style of a type, or of its light or dark variant. Absent properties are null. */
    data class Style(
        val backgroundColor: Color? = null,
        val color: Color? = null,
        val border: String? = null,
        val borderColor: Color? = null,
        val borderStyle: String? = null,
        val borderRadius: String? = null,
        val outline: String? = null,
        val outlineColor: Color? = null,
        val outlineStyle: String? = null,
        val fontStyle: String? = null,
        val fontWeight: String? = null,
        val textDecoration: String? = null,
        val gutterIconPath: UriComponents? = null,
        val gutterIconSize: String? = null,
        val overviewRulerColor: Color? = null,
        val before: Attachment? = null,
        val after: Attachment? = null,
    ) {
        /** This, with what it leaves out taken from [under] -- a theme variant over the base style. */
        fun over(under: Style): Style = Style(
            backgroundColor ?: under.backgroundColor,
            color ?: under.color,
            border ?: under.border,
            borderColor ?: under.borderColor,
            borderStyle ?: under.borderStyle,
            borderRadius ?: under.borderRadius,
            outline ?: under.outline,
            outlineColor ?: under.outlineColor,
            outlineStyle ?: under.outlineStyle,
            fontStyle ?: under.fontStyle,
            fontWeight ?: under.fontWeight,
            textDecoration ?: under.textDecoration,
            gutterIconPath ?: under.gutterIconPath,
            gutterIconSize ?: under.gutterIconSize,
            overviewRulerColor ?: under.overviewRulerColor,
            before?.over(under.before) ?: under.before,
            after?.over(under.after) ?: under.after,
        )
    }

    /**
     * A registered decoration type.
     *
     * [rangeBehavior] is VS Code's `DecorationRangeBehavior`: 0 grows when typing at either edge
     * (the default), 1 at neither, 2 only at the start, 3 only at the end.
     */
    data class Type(
        val isWholeLine: Boolean,
        val rangeBehavior: Int,
        val base: Style,
        val light: Style?,
        val dark: Style?,
    ) {
        /** The style in a light theme: the light variant over the base. */
        val inLight: Style get() = light?.over(base) ?: base

        /** The style in a dark theme. */
        val inDark: Style get() = dark?.over(base) ?: base
    }

    /** One instance's own before and after, which win over its type's; with variants, as a type has. */
    data class RenderOptions(val base: Style, val light: Style?, val dark: Style?)

    /**
     * One decorated range.
     *
     * [hoverMessages] are `MarkdownString`s, or plain strings, as the extension gave them.
     */
    data class Decoration(
        val range: Range,
        val hoverMessages: List<JsonElement> = emptyList(),
        val renderOptions: RenderOptions? = null,
    )

    /** `$registerTextEditorDecorationType`: the key, and the type it names. */
    fun register(argsJson: String): Pair<String, Type> {
        val args = Args.of(REGISTER, argsJson)
        val options = args.objOrNull(2) ?: JsonObject(emptyMap())
        return args.str(1) to Type(
            isWholeLine = (options["isWholeLine"] as? JsonPrimitive)?.booleanOrNull == true,
            rangeBehavior = (options["rangeBehavior"] as? JsonPrimitive)?.intOrNull ?: 0,
            base = style(options),
            light = (options["light"] as? JsonObject)?.let(::style),
            dark = (options["dark"] as? JsonObject)?.let(::style),
        )
    }

    /** `$removeTextEditorDecorationType`: the key. */
    fun removed(argsJson: String): String = Args.of(REMOVE, argsJson).str(0)

    /** The editor id, the type's key, and the decorations that now replace the type's in that editor. */
    data class Applied(val editorId: String, val key: String, val decorations: List<Decoration>)

    /** `$trySetDecorations` or `$trySetDecorationsFast`, whichever [method] names. */
    fun set(method: String, argsJson: String): Applied {
        val args = Args.of(method, argsJson)
        val decorations = if (method == SET_FAST) fast(args.arrayOrNull(2).orEmpty())
        else args.arrayOrNull(2).orEmpty().mapNotNull { decoration(it) }
        return Applied(args.str(0), args.str(1), decorations)
    }

    /** `[startLine, startColumn, endLine, endColumn, ...]`, 1-based like any editor range. */
    private fun fast(numbers: List<JsonElement>): List<Decoration> {
        val values = numbers.map { (it as? JsonPrimitive)?.intOrNull ?: 0 }
        return (0 until values.size / 4).map { i ->
            Decoration(Range(values[4 * i], values[4 * i + 1], values[4 * i + 2], values[4 * i + 3]))
        }
    }

    private fun decoration(element: JsonElement): Decoration? {
        val decoration = element as? JsonObject ?: return null
        val range = Range.from(decoration["range"]) ?: return null
        val hover = when (val message = decoration["hoverMessage"]) {
            is JsonArray -> message.toList()
            null -> emptyList()
            else -> listOf(message)
        }
        val render = (decoration["renderOptions"] as? JsonObject)?.let {
            RenderOptions(
                style(it),
                (it["light"] as? JsonObject)?.let(::style),
                (it["dark"] as? JsonObject)?.let(::style)
            )
        }
        return Decoration(range, hover, render)
    }

    private fun style(options: JsonObject) = Style(
        backgroundColor = options.color("backgroundColor"),
        color = options.color("color"),
        border = options.text("border"),
        borderColor = options.color("borderColor"),
        borderStyle = options.text("borderStyle"),
        borderRadius = options.text("borderRadius"),
        outline = options.text("outline"),
        outlineColor = options.color("outlineColor"),
        outlineStyle = options.text("outlineStyle"),
        fontStyle = options.text("fontStyle"),
        fontWeight = options.text("fontWeight"),
        textDecoration = options.text("textDecoration"),
        gutterIconPath = UriComponents.from(options["gutterIconPath"]),
        gutterIconSize = options.text("gutterIconSize"),
        overviewRulerColor = options.color("overviewRulerColor"),
        before = (options["before"] as? JsonObject)?.let(::attachment),
        after = (options["after"] as? JsonObject)?.let(::attachment),
    )

    private fun attachment(options: JsonObject) = Attachment(
        contentText = options.text("contentText"),
        color = options.color("color"),
        backgroundColor = options.color("backgroundColor"),
        border = options.text("border"),
        borderColor = options.color("borderColor"),
        fontStyle = options.text("fontStyle"),
        fontWeight = options.text("fontWeight"),
        textDecoration = options.text("textDecoration"),
        margin = options.text("margin"),
        width = options.text("width"),
    )

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.color(key: String): Color? = when (val value = get(key)) {
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.let { Color.Css(it) }
        is JsonObject -> (value["id"] as? JsonPrimitive)?.contentOrNull?.let { Color.Theme(it) }
        else -> null
    }
}
