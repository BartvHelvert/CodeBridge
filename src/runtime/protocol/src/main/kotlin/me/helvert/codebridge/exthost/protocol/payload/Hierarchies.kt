package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Call and type hierarchies: what VS Code's Show Call Hierarchy and Show Type Hierarchy ask.
 *
 * Read off the 1.135 bundle's `CallHierarchyAdapter` and `TypeHierarchyAdapter`, which work alike.
 * Preparing asks at a position, as a definition is asked, and opens a *session* in the host: the
 * items it answers carry the session's id and their own, and the host keeps the extension's objects
 * under them, since asking for an item's callers or supertypes hands the extension the very object
 * it gave. Every item those answer joins the same session. The session is held until released.
 */
object Hierarchies {

    /** The two kinds, which differ only in their method names. */
    enum class Kind(val register: String, val prepare: String, val release: String) {
        CALL($$"$registerCallHierarchyProvider", $$"$prepareCallHierarchy", $$"$releaseCallHierarchy"),
        TYPE($$"$registerTypeHierarchyProvider", $$"$prepareTypeHierarchy", $$"$releaseTypeHierarchy"),
    }

    /** `(handle, sessionId, itemId)`, answered with [Call]s whose item is the caller. */
    const val PROVIDE_INCOMING_CALLS: String = $$"$provideCallHierarchyIncomingCalls"

    /** `(handle, sessionId, itemId)`, answered with [Call]s whose item is the callee. */
    const val PROVIDE_OUTGOING_CALLS: String = $$"$provideCallHierarchyOutgoingCalls"

    /** `(handle, sessionId, itemId)`, answered with bare [Item]s. */
    const val PROVIDE_SUPERTYPES: String = $$"$provideTypeHierarchySupertypes"
    const val PROVIDE_SUBTYPES: String = $$"$provideTypeHierarchySubtypes"

    /**
     * One symbol in a hierarchy, at **1-based** ranges.
     *
     * [kind] is the editor's `SymbolKind`, numbered from 0. [range] is the whole symbol and
     * [selectionRange] its name inside it, which is where VS Code goes to it.
     */
    data class Item(
        val sessionId: String,
        val itemId: String,
        val name: String,
        val detail: String?,
        val kind: Int,
        val deprecated: Boolean,
        val resource: UriComponents,
        val range: Range,
        val selectionRange: Range,
    )

    /**
     * A call between [item] and the item it was asked about, made at [ranges].
     *
     * For an incoming call [item] is the caller, and the ranges are in its file; for an outgoing
     * call [item] is the callee, and the ranges are where the item asked about calls it -- in that
     * item's file, not this one's.
     */
    data class Call(val item: Item, val ranges: List<Range>)

    /** `$registerCallHierarchyProvider(handle, selector)` or the type hierarchy's, alike. */
    fun registration(kind: Kind, argsJson: String): LanguageFeatures.Registration =
        LanguageFeatures.locationRegistration(kind.register, argsJson)

    /** Arguments for `$prepare...Hierarchy(handle, resource, position)`; the token is the host's. */
    fun prepare(handle: Int, resource: UriComponents, position: Position): String =
        LanguageFeatures.provideDefinition(handle, resource, position)

    /** The reply to [prepare], or any type hierarchy reply: items, or nothing from a provider with none. */
    fun items(reply: String?): List<Item> = array(reply).mapNotNull(::item)

    /** Arguments for asking about [item]: its session and its id, which is how the host finds it. */
    fun ask(handle: Int, item: Item): String =
        JsonArray(listOf(JsonPrimitive(handle), JsonPrimitive(item.sessionId), JsonPrimitive(item.itemId))).toString()

    /** The reply to [PROVIDE_INCOMING_CALLS]: `{from, fromRanges}`. */
    fun incomingCalls(reply: String?): List<Call> = calls(reply, "from")

    /** The reply to [PROVIDE_OUTGOING_CALLS]: `{to, fromRanges}`. */
    fun outgoingCalls(reply: String?): List<Call> = calls(reply, "to")

    /** Arguments for `$release...Hierarchy(handle, sessionId)`. */
    fun release(handle: Int, sessionId: String): String =
        JsonArray(listOf(JsonPrimitive(handle), JsonPrimitive(sessionId))).toString()

    private fun calls(reply: String?, key: String): List<Call> = array(reply).mapNotNull { entry ->
        val call = entry as? JsonObject ?: return@mapNotNull null
        val item = item(call[key]) ?: return@mapNotNull null
        Call(item, (call["fromRanges"] as? JsonArray).orEmpty().mapNotNull(Range::from))
    }

    private fun array(reply: String?): JsonArray {
        if (reply.isNullOrBlank()) return JsonArray(emptyList())
        return runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray ?: JsonArray(emptyList())
    }

    private fun item(element: JsonElement?): Item? {
        val dto = element as? JsonObject ?: return null
        val sessionId = dto.string("_sessionId") ?: return null
        val itemId = dto.string("_itemId") ?: return null
        val resource = UriComponents.from(dto["uri"]) ?: return null
        val range = Range.from(dto["range"]) ?: return null
        val tags = (dto["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        return Item(
            sessionId = sessionId,
            itemId = itemId,
            name = dto.string("name").orEmpty(),
            detail = dto.string("detail")?.takeIf { it.isNotBlank() },
            kind = (dto["kind"] as? JsonPrimitive)?.intOrNull ?: -1,
            deprecated = DEPRECATED in tags,
            resource = resource,
            range = range,
            selectionRange = Range.from(dto["selectionRange"]) ?: range,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** `SymbolTag.Deprecated`, the only tag there is. */
    private const val DEPRECATED = 1
}
