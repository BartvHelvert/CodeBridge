package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * The ranges of lines an editor can fold.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `FoldingProviderAdapter`. Nothing is
 * cached in the host, so nothing is released. A provider that can announce a change is registered
 * with a second handle of its own, the event handle, and announces it under that one rather than
 * the provider's.
 */
object FoldingRanges {

    /** `$registerFoldingRangeProvider(handle, selector, extensionId, eventHandle)`. */
    const val REGISTER: String = $$"$registerFoldingRangeProvider"
    const val PROVIDE: String = $$"$provideFoldingRanges"

    /** `$emitFoldingRangeEvent(eventHandle)`: the provider's ranges have changed. */
    const val EMIT_EVENT: String = $$"$emitFoldingRangeEvent"

    /** `FoldingRangeKind` values a range may carry. */
    const val KIND_COMMENT: String = "comment"
    const val KIND_IMPORTS: String = "imports"
    const val KIND_REGION: String = "region"

    /** A provider's registration; [eventHandle] is null for one that never announces a change. */
    data class Registration(val handle: Int, val selector: JsonElement, val eventHandle: Int?)

    /**
     * Lines [start] to [end], **1-based** and both included: [start] stays shown and the lines after
     * it, through [end], are folded away. [kind] is one of the `KIND_` values, or another an
     * extension made up, or null.
     */
    data class Range(val start: Int, val end: Int, val kind: String? = null)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.intOrNull(3))
    }

    fun emittedEventHandle(argsJson: String): Int? = Args.ofOrNull(EMIT_EVENT, argsJson)?.intOrNull(0)

    /**
     * Arguments for `$provideFoldingRanges(handle, resource, context)`; the token is left for the
     * host to append. VS Code's own folding model passes an empty context.
     */
    fun provide(handle: Int, resource: UriComponents): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), JsonObject(emptyMap()))).toString()

    /** Reads a `$provideFoldingRanges` reply, which is `undefined` when the provider had nothing. */
    fun ranges(reply: String?): List<Range> {
        if (reply.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray ?: return emptyList()
        return parsed.mapNotNull { element ->
            val range = element as? JsonObject ?: return@mapNotNull null
            val start = (range["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val end = (range["end"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            // A `FoldingRangeKind` instance, serialized as its one field.
            val kind = ((range["kind"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
            Range(start, end, kind)
        }
    }
}
