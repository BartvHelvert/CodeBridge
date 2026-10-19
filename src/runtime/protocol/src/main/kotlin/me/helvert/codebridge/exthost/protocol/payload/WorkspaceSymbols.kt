package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Workspace symbols: what VS Code's "Go to Symbol in Workspace" (Ctrl+T) searches.
 *
 * Read off the 1.135 bundle's `ExtHostLanguageFeatures` and its `NavigateTypeAdapter`. The workbench
 * calls this "navigate type" support, and unlike every other provider it carries no document
 * selector: a query is about the workspace, not a file. A reply is a list the host keeps cached under
 * an id until told to release it, because a symbol may be resolved later -- a language client whose
 * server sent a symbol with only its file gives it a placeholder range at the file's start, and the
 * real one is what resolving asks the server for.
 */
object WorkspaceSymbols {

    /** `$registerNavigateTypeSupport(handle, supportsResolve)`. */
    const val REGISTER: String = $$"$registerNavigateTypeSupport"
    const val PROVIDE: String = $$"$provideWorkspaceSymbols"
    const val RESOLVE: String = $$"$resolveWorkspaceSymbol"
    const val RELEASE: String = $$"$releaseWorkspaceSymbols"

    /** [resolves] is whether the extension implemented `resolveWorkspaceSymbol`. */
    data class Registration(val handle: Int, val resolves: Boolean)

    /**
     * One symbol, at a **1-based** range.
     *
     * [kind] is the editor's `SymbolKind`, numbered from 0 as VS Code's API numbers it -- not LSP's,
     * which starts at 1. [dto] is the symbol exactly as it arrived, which is what resolving it sends
     * back: the host finds it by the cache id inside.
     */
    data class Symbol(
        val name: String,
        val containerName: String?,
        val kind: Int,
        val deprecated: Boolean,
        val resource: UriComponents,
        val range: Range,
        val dto: JsonObject,
    )

    /** A reply: the list id to release, and the symbols in the order the extension gave them. */
    data class Offered(val cacheId: Int?, val symbols: List<Symbol>)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.boolOrNull(1) == true)
    }

    /** Arguments for `$provideWorkspaceSymbols(handle, search)`; the token is left for the host to append. */
    fun provide(handle: Int, query: String): String =
        JsonArray(listOf(JsonPrimitive(handle), JsonPrimitive(query))).toString()

    /** The reply to [provide]; a provider that knew nothing answers `{symbols: []}`, which reads as none. */
    fun offered(reply: String?): Offered {
        if (reply.isNullOrBlank()) return Offered(null, emptyList())
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject
            ?: return Offered(null, emptyList())
        val cacheId = (parsed["cacheId"] as? JsonPrimitive)?.intOrNull
        val symbols = (parsed["symbols"] as? JsonArray).orEmpty().mapNotNull { symbol(it as? JsonObject) }
        return Offered(cacheId, symbols)
    }

    /** Arguments for `$resolveWorkspaceSymbol(handle, symbol)`; the token is left for the host to append. */
    fun resolve(handle: Int, symbol: Symbol): String =
        JsonArray(listOf(JsonPrimitive(handle), symbol.dto)).toString()

    /** The reply to [resolve], or null when the extension had nothing to add. */
    fun resolved(reply: String?): Symbol? {
        if (reply.isNullOrBlank()) return null
        return symbol(runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject)
    }

    /** Arguments for `$releaseWorkspaceSymbols(handle, cacheId)`, which the host holds until told. */
    fun release(handle: Int, cacheId: Int): String = "[$handle,$cacheId]"

    private fun symbol(dto: JsonObject?): Symbol? {
        dto ?: return null
        val name = (dto["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val location = dto["location"] as? JsonObject ?: return null
        val resource = UriComponents.from(location["uri"]) ?: return null
        val containerName = (dto["containerName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        val tags = (dto["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        return Symbol(
            name = name,
            containerName = containerName,
            kind = (dto["kind"] as? JsonPrimitive)?.intOrNull ?: -1,
            deprecated = DEPRECATED in tags,
            resource = resource,
            // Absent only from an extension that built the reply by hand; the file's start stands in,
            // as a language client's placeholder does.
            range = Range.from(location["range"]) ?: Range(1, 1, 1, 1),
            dto = dto,
        )
    }

    /** `SymbolTag.Deprecated`, the only tag there is. */
    private const val DEPRECATED = 1
}
