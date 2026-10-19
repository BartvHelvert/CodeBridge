package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * The links an extension finds in a document: an import's path, a dependency's page, a URL in a
 * comment.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `LinkProviderAdapter`. A provider that
 * resolves links has its answer cached in the host, under the list id the reply carries, until it is
 * released; one that does not has nothing cached and nothing to release.
 */
object DocumentLinks {

    /** `$registerDocumentLinkProvider(handle, selector, supportsResolve)`. */
    const val REGISTER: String = $$"$registerDocumentLinkProvider"
    const val PROVIDE: String = $$"$provideDocumentLinks"
    const val RESOLVE: String = $$"$resolveDocumentLink"
    const val RELEASE: String = $$"$releaseDocumentLinks"

    data class Registration(val handle: Int, val selector: JsonElement, val resolves: Boolean)

    /**
     * One link: where it is, where it leads, and what its tooltip says. [target] is null for a link
     * from a resolving provider that left it to be worked out when followed; [cacheId] -- the list
     * id and the link's index in it -- is what asks for it then.
     */
    data class Link(val range: Range, val target: UriComponents?, val tooltip: String?, val cacheId: Pair<Int, Int>?)

    /** A reply: the links, and the id of the list the host keeps them under, if it keeps them. */
    data class Links(val links: List<Link>, val cacheId: Int?)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.boolOrNull(2) == true)
    }

    /** Arguments for `$provideDocumentLinks(handle, resource)`; the token is left for the host to append. */
    fun provide(handle: Int, resource: UriComponents): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson())).toString()

    /** Reads a `$provideDocumentLinks` reply, which is `undefined` when the provider found none. */
    fun links(reply: String?): Links {
        if (reply.isNullOrBlank()) return Links(emptyList(), null)
        val parsed =
            runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return Links(emptyList(), null)
        val links = (parsed["links"] as? JsonArray).orEmpty().mapNotNull { link(it) }
        return Links(links, (parsed["cacheId"] as? JsonPrimitive)?.intOrNull)
    }

    /** Arguments for `$resolveDocumentLink(handle, [listId, index])`; the token is the host's. */
    fun resolve(handle: Int, cacheId: Pair<Int, Int>): String =
        JsonArray(
            listOf(
                JsonPrimitive(handle),
                JsonArray(listOf(JsonPrimitive(cacheId.first), JsonPrimitive(cacheId.second)))
            )
        ).toString()

    /** Reads a `$resolveDocumentLink` reply, which is `undefined` for a link the provider could not resolve. */
    fun resolved(reply: String?): Link? {
        if (reply.isNullOrBlank()) return null
        return link(runCatching { Json.parseToJsonElement(reply) }.getOrNull())
    }

    /** Arguments for `$releaseDocumentLinks(handle, listId)`. */
    fun release(handle: Int, cacheId: Int): String = "[$handle,$cacheId]"

    /**
     * The `command:` href a link to a command is, as a hover's link would spell it: the URI's own
     * string drops the query of an opaque URI, and the query is the command's arguments.
     */
    fun commandHref(target: UriComponents): String? =
        if (target.scheme != COMMAND) null
        else "$COMMAND:${target.path}" + target.query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()

    /**
     * Where in a file a link to it leads, from its fragment as VS Code's opener reads one:
     * `L12`, `12`, `12,5` or `L12,5`, 1-based, and anything after a range's `-` ignored.
     */
    fun position(target: UriComponents): Position? {
        val match = FRAGMENT.find(target.fragment) ?: return null
        val line = match.groupValues[1].toIntOrNull() ?: return null
        return Position(line, match.groupValues[2].toIntOrNull() ?: 1)
    }

    private fun link(element: JsonElement?): Link? {
        val link = element as? JsonObject ?: return null
        val range = Range.from(link["range"]) ?: return null
        val tooltip = (link["tooltip"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val cacheId = (link["cacheId"] as? JsonArray)?.let {
            val list = (it.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val index = (it.getOrNull(1) as? JsonPrimitive)?.intOrNull
            if (list != null && index != null) list to index else null
        }
        // `url`, though the adapter puts the link's `target` there, a URI rather than a string; the
        // workbench reads a string too, so this does.
        val url = link["url"]
        val target = (url as? JsonPrimitive)?.takeIf { it.isString }
            ?.let { runCatching { UriComponents.of(it.content) }.getOrNull() }
            ?: UriComponents.from(url)
        return Link(range, target, tooltip, cacheId)
    }

    const val COMMAND: String = "command"
    private val FRAGMENT = Regex("""^L?(\d+)(?:,(\d+))?""")
}
