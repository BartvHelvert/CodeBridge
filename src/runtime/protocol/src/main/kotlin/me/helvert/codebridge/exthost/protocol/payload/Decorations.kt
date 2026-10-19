package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadDecorations` and `ExtHostDecorations.$provideDecorations`: the badges, colours and
 * tooltips a `FileDecorationProvider` puts on files -- Git's "M" and "U", a linter's error count.
 *
 * The provider is asked about resources in batches, each named by an id the asker picks, and
 * answers only for those it decorates: an object from id to `[propagate, tooltip, badge, color]`.
 * `$onDidChange` says which resources to ask about again, or null for all of them.
 */
object Decorations {
    const val SERVICE: String = "MainThreadDecorations"
    const val EXT_HOST: String = "ExtHostDecorations"

    const val REGISTER: String = $$"$registerDecorationProvider"
    const val UNREGISTER: String = $$"$unregisterDecorationProvider"
    const val CHANGED: String = $$"$onDidChange"
    const val PROVIDE: String = $$"$provideDecorations"

    /**
     * What a provider said about one resource. [badge] is its text, at most two characters; a
     * codicon badge, which only a proposed API can give, is left out. [color] is a theme colour id,
     * and [propagate] passes the colour up to the folders holding the resource.
     */
    data class Decoration(
        val propagate: Boolean = false,
        val tooltip: String? = null,
        val badge: String? = null,
        val color: String? = null,
    )

    /** `$registerDecorationProvider(handle, extensionId)`. */
    fun registration(argsJson: String): Pair<Int, String?> {
        val args = Args.of(REGISTER, argsJson)
        return args.int(0) to args.strOrNull(1)
    }

    fun unregistered(argsJson: String): Int = Args.of(UNREGISTER, argsJson).int(0)

    /** `$onDidChange(handle, resources)`: the resources to ask about again, or null for every one. */
    fun changed(argsJson: String): Pair<Int, List<UriComponents>?> {
        val args = Args.of(CHANGED, argsJson)
        return args.int(0) to args.arrayOrNull(1)?.mapNotNull { UriComponents.from(it) }
    }

    /** Arguments for `$provideDecorations(handle, requests)`, each resource asked about by its index. */
    fun provide(handle: Int, resources: List<UriComponents>): String = buildJsonArray {
        add(JsonPrimitive(handle))
        add(JsonArray(resources.mapIndexed { id, resource ->
            buildJsonObject {
                put("id", id); put(
                "uri",
                resource.toJson()
            )
            }
        }))
    }.toString()

    /** A `$provideDecorations` reply, by the index of the resource each decoration is for. */
    fun decorations(reply: String?): Map<Int, Decoration> {
        if (reply.isNullOrBlank()) return emptyMap()
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return emptyMap()
        return parsed.entries.mapNotNull { (id, data) ->
            val index = id.toIntOrNull() ?: return@mapNotNull null
            val values = data as? JsonArray ?: return@mapNotNull null
            fun text(at: Int) =
                (values.getOrNull(at) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            index to Decoration(
                propagate = (values.getOrNull(0) as? JsonPrimitive)?.booleanOrNull == true,
                tooltip = text(1),
                badge = text(2),
                color = ((values.getOrNull(3) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull,
            )
        }.toMap()
    }
}
