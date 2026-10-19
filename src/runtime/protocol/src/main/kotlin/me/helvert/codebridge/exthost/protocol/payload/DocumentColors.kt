package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * The colours an extension finds written in a document -- `#ff0000` in CSS, `Color(0xFF6200EE)` in
 * Kotlin -- which VS Code marks with a swatch before each.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `ColorProviderAdapter`. Nothing is cached in
 * the host.
 */
object DocumentColors {

    /** `$registerDocumentColorProvider(handle, selector)`. */
    const val REGISTER: String = $$"$registerDocumentColorProvider"
    const val PROVIDE: String = $$"$provideDocumentColors"

    /** A colour at [range]; each channel from 0 to 1, as VS Code's `Color` holds them. */
    data class Found(val range: Range, val red: Double, val green: Double, val blue: Double, val alpha: Double) {

        /** As CSS, which is how a decoration takes a colour. */
        val css: String
            get() = "rgba(${channel(red)}, ${channel(green)}, ${channel(blue)}, ${alpha.coerceIn(0.0, 1.0)})"

        private fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * 255).toInt()
    }

    fun registration(argsJson: String): LanguageFeatures.Registration {
        val args = Args.of(REGISTER, argsJson)
        return LanguageFeatures.Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /** Arguments for `$provideDocumentColors(handle, resource)`; the token is left for the host to append. */
    fun provide(handle: Int, resource: UriComponents): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson())).toString()

    const val PRESENT: String = $$"$provideColorPresentations"

    /**
     * Arguments for `$provideColorPresentations(handle, resource, {color, range})`: the colour as the
     * adapter reads one back, its four channels from 0 to 1. The token is the host's.
     */
    fun present(
        handle: Int,
        resource: UriComponents,
        red: Double,
        green: Double,
        blue: Double,
        alpha: Double,
        range: Range
    ): String =
        JsonArray(
            listOf(
                JsonPrimitive(handle),
                resource.toJson(),
                buildJsonObject {
                    put("color", JsonArray(listOf(red, green, blue, alpha).map(::JsonPrimitive)))
                    put("range", range.toJson())
                },
            )
        ).toString()

    /**
     * The edits the first way of writing the colour makes, as VS Code's picker applies the one it
     * offers first: its own edit, or else its label in place of the colour, then any others it
     * carries. Null for a provider that offered none.
     */
    fun presentation(reply: String?, range: Range): List<TextEdit>? {
        if (reply.isNullOrBlank()) return null
        val first =
            (runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return null
        val label = (first["label"] as? JsonPrimitive)?.contentOrNull ?: return null
        val main = TextEdit.from(first["textEdit"]) ?: TextEdit(range, label)
        val additional = (first["additionalTextEdits"] as? JsonArray).orEmpty().mapNotNull { TextEdit.from(it) }
        return listOf(main) + additional
    }

    /** Reads a reply: `[{color: [r, g, b, a], range}]`, the colour flattened to its channels by the adapter. */
    fun colors(reply: String?): List<Found> {
        if (reply.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray ?: return emptyList()
        return parsed.mapNotNull { element ->
            val found = element as? JsonObject ?: return@mapNotNull null
            val range = Range.from(found["range"]) ?: return@mapNotNull null
            val channels =
                (found["color"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null }
            if (channels == null || channels.size != 4) return@mapNotNull null
            Found(range, channels[0], channels[1], channels[2], channels[3])
        }
    }
}
