package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * Ranges that are edited together: an HTML element's opening and closing tag names.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `LinkedEditingRangeAdapter`. Nothing is
 * cached in the host.
 */
object LinkedEditing {

    /** `$registerLinkedEditingRangeProvider(handle, selector)`. */
    const val REGISTER: String = $$"$registerLinkedEditingRangeProvider"
    const val PROVIDE: String = $$"$provideLinkedEditingRanges"

    /** VS Code's setting that turns this on, off unless the user says otherwise. */
    const val SETTING: String = "editor.linkedEditing"

    /**
     * The ranges at a position, and what text may go in them: [wordPattern] is a JavaScript regular
     * expression's source and flags, or null for anything.
     */
    data class Ranges(val ranges: List<Range>, val wordPattern: Pattern?)

    data class Pattern(val source: String, val flags: String)

    fun registration(argsJson: String): LanguageFeatures.Registration {
        val args = Args.of(REGISTER, argsJson)
        return LanguageFeatures.Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /** Arguments for `$provideLinkedEditingRanges(handle, resource, position)`; the token is the host's. */
    fun provide(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), position.toJson())).toString()

    /** Reads a reply, `undefined` where nothing at the position is edited together. */
    fun ranges(reply: String?): Ranges? {
        if (reply.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val ranges = (parsed["ranges"] as? JsonArray).orEmpty().mapNotNull { Range.from(it) }
        if (ranges.isEmpty()) return null
        // `_serializeRegExp`: `{pattern, flags}`, which survive JSON where a RegExp does not.
        val pattern = (parsed["wordPattern"] as? JsonObject)?.let {
            val source = (it["pattern"] as? JsonPrimitive)?.contentOrNull ?: return@let null
            Pattern(source, (it["flags"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        return Ranges(ranges, pattern)
    }
}
