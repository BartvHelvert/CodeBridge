package me.helvert.codebridge.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every theme color and size VS Code registers, with its defaults: `vscode-theme.json`, which
 * `generate-vscode-theme.py` reads from the VS Code the plugin bundles.
 *
 * VS Code gives a webview all of them as CSS variables. A theme sets only some colors; each of the
 * others is its default for the theme's kind, which is a color, another color's id, or a transform
 * of those. [resolve] works that out the same way on top of the colors a theme sets.
 */
internal object VsCodeThemeDefaults {
    private val theme: JsonObject by lazy {
        val text = VsCodeThemeDefaults::class.java.getResource("/webview/vscode-theme.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    val colors: Map<String, JsonElement> get() = theme.getValue("colors").jsonObject

    /** The registered sizes for a theme of [kind]: `dark`, `light`, `hcDark` or `hcLight`. */
    fun sizes(kind: String): Map<String, String> = theme.getValue("sizes").jsonObject.mapNotNull { (id, defaults) ->
        defaults.forKind(kind)?.let { (it as? JsonPrimitive)?.content }?.let { id to it }
    }.toMap()

    /**
     * Every color a theme of [kind] has: the ones it [sets] and, for the rest of those in [defaults],
     * what their defaults come to. A color whose default is nothing for that kind is left out, as
     * VS Code leaves it out.
     */
    fun resolve(kind: String, sets: Map<String, VsCodeColor>, defaults: Map<String, JsonElement>): Map<String, VsCodeColor> {
        val resolver = Resolver(kind, sets, defaults)
        return (sets.keys + defaults.keys).mapNotNull { id -> resolver.color(id)?.let { id to it } }.toMap()
    }

    private class Resolver(val kind: String, val sets: Map<String, VsCodeColor>, val defaults: Map<String, JsonElement>) {
        private val resolved = HashMap<String, VsCodeColor?>()
        private val resolving = HashSet<String>()

        /** VS Code's `IColorTheme.getColor`: a color the theme sets, or else its default. */
        fun color(id: String): VsCodeColor? {
            sets[id]?.let { return it }
            if (id in resolved) return resolved[id]
            // Registered defaults never loop, but a contributed one can name a color that names it.
            if (!resolving.add(id)) return null
            val color = defaults[id]?.forKind(kind)?.let(::value)
            resolving.remove(id)
            resolved[id] = color
            return color
        }

        /** VS Code's `resolveColorValue`. */
        private fun value(value: JsonElement): VsCodeColor? = when (value) {
            is JsonNull -> null
            is JsonPrimitive -> value.content.let { if (it.startsWith("#")) VsCodeColor.hex(it) else color(it) }
            is JsonObject -> transform(value)
            is JsonArray -> null
        }

        /** VS Code's `executeTransform`. */
        private fun transform(transform: JsonObject): VsCodeColor? {
            fun color(key: String) = transform[key]?.let(::value)
            fun number(key: String) = transform.getValue(key).jsonPrimitive.double
            return when (transform["op"]?.jsonPrimitive?.content) {
                "darken" -> color("value")?.darken(number("factor"))
                "lighten" -> color("value")?.lighten(number("factor"))
                "transparent" -> color("value")?.transparent(number("factor"))
                "mix" -> (color("color") ?: VsCodeColor.TRANSPARENT).mix(color("with") ?: VsCodeColor.TRANSPARENT, number("ratio"))
                "opaque" -> color("background").let { background ->
                    if (background == null) color("value") else color("value")?.makeOpaque(background)
                }
                "oneOf" -> (transform.getValue("values") as JsonArray).firstNotNullOfOrNull(::value)
                "ifDefinedThenElse" ->
                    color(if (transform.getValue("if").jsonPrimitive.content in sets) "then" else "else")
                "lessProminent" -> {
                    val from = color("value") ?: return null
                    val background = color("background")
                    val factor = number("factor")
                    val transparency = number("transparency")
                    when {
                        background == null -> from.transparent(factor * transparency)
                        from.isDarkerThan(background) -> VsCodeColor.lighter(from, background, factor).transparent(transparency)
                        else -> VsCodeColor.darker(from, background, factor).transparent(transparency)
                    }
                }
                else -> null
            }
        }
    }

    /** A registration's defaults are per theme kind when they name both `light` and `dark`. */
    private fun JsonElement.forKind(kind: String): JsonElement? =
        if (this is JsonObject && "light" in this && "dark" in this) this[kind] else this
}
