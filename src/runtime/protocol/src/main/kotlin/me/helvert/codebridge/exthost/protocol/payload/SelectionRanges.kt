package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * The ranges a selection grows through, innermost first: VS Code's Expand Selection.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `SelectionRangeAdapter`. Nothing is
 * cached in the host, so nothing is released.
 */
object SelectionRanges {

    /** `$registerSelectionRangeProvider(handle, selector)`. */
    const val REGISTER: String = $$"$registerSelectionRangeProvider"
    const val PROVIDE: String = $$"$provideSelectionRanges"

    fun registration(argsJson: String): LanguageFeatures.Registration {
        val args = Args.of(REGISTER, argsJson)
        return LanguageFeatures.Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /**
     * Arguments for `$provideSelectionRanges(handle, resource, positions)`; the token is left for the
     * host to append. One position, so the reply holds one chain.
     */
    fun provide(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), JsonArray(listOf(position.toJson())))).toString()

    /**
     * The chain for the one position asked about, innermost first.
     *
     * The reply is an array of chains, one per position, each already flattened by the adapter from
     * the extension's `parent` links into `{range}` objects. A provider that answered for a different
     * number of positions is answered with `[]` by the adapter itself.
     */
    fun ranges(reply: String?): List<Range> {
        if (reply.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray ?: return emptyList()
        val chain = parsed.firstOrNull() as? JsonArray ?: return emptyList()
        return chain.mapNotNull { Range.from((it as? JsonObject)?.get("range")) }
    }
}
