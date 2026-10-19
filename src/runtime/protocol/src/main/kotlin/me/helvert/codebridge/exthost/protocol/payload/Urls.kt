package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * `MainThreadUrls`: links into the editor -- `vscode://publisher.name/path?query` -- that an
 * extension's `window.registerUriHandler` receives. A browser sign-in returns this way.
 *
 * Every URI goes to the extension its authority names. The links the workbench gives out for the
 * extension to hand to a browser (`env.asExternalUri`, which the host turns into [CREATE_APP_URI]
 * for the editor's own scheme) are an [appLink] instead of the scheme itself: the operating system
 * gives that scheme to whichever editor registered it, and that is not this IDE.
 */
object Urls {

    const val SERVICE: String = "MainThreadUrls"
    const val EXT_HOST: String = "ExtHostUrls"

    /** `$registerUriHandler(handle, extensionId, extensionDisplayName)`; one handler per extension. */
    const val REGISTER: String = $$"$registerUriHandler"

    /** `$unregisterUriHandler(handle)`. */
    const val UNREGISTER: String = $$"$unregisterUriHandler"

    /** `$createAppUri(uri)`, answered with an address that comes back to the editor as `uri`. */
    const val CREATE_APP_URI: String = $$"$createAppUri"

    /** `$handleExternalUri(handle, uri)`, sent to the host for the handler [handle] to receive. */
    const val HANDLE: String = $$"$handleExternalUri"

    sealed interface Request {
        data class Register(val handle: Int, val extensionId: String, val displayName: String) : Request
        data class Unregister(val handle: Int) : Request
        data class CreateAppUri(val uri: UriComponents) : Request
    }

    fun request(method: String, argsJson: String): Request {
        val args = Args.of(method, argsJson)
        return when (method) {
            REGISTER -> {
                // An `ExtensionIdentifier` crosses as `{value, _lower}`.
                val id = (args.element(1) as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull ?: args.str(1)
                Request.Register(args.int(0), id, args.strOrNull(2)?.takeIf { it.isNotBlank() } ?: id)
            }

            UNREGISTER -> Request.Unregister(args.int(0))
            CREATE_APP_URI -> Request.CreateAppUri(args.uri(0))
            else -> throw PayloadException("Unknown $SERVICE method $method")
        }
    }

    fun handle(handle: Int, uri: UriComponents): String = buildJsonArray { add(handle); add(uri.toJson()) }.toString()

    /** Where [appLink]s are served, under the IDE's built-in web server. */
    const val LINK_PREFIX: String = "codebridge/uri"

    /**
     * The address a browser can open to deliver [uri]: on this machine's built-in web server at
     * [port], carrying [token] so the delivery can tell a link the workbench gave out from one a
     * web page made up. The authority and path follow the token, and the query and fragment are
     * kept, so an OAuth provider's additions to the query arrive with the rest.
     */
    fun appLink(uri: UriComponents, port: Int, token: String): UriComponents =
        UriComponents(
            "http",
            "127.0.0.1:$port",
            "/$LINK_PREFIX/$token/${uri.authority}${uri.path}",
            uri.query,
            uri.fragment
        )

    /**
     * The query parameter a [schemeLink] carries its way back in, as VS Code's own links carry
     * `windowId`: `<port>.<token>`. Taken off again before the extension sees the URI.
     */
    const val LINK_PARAM: String = "codebridge"

    /**
     * [uri] itself, under the editor's own scheme, for an operating system that hands that scheme to
     * this IDE's launcher: some sign-in pages offer to return only to a scheme they know, and an
     * [appLink] is not one. The launcher reads the client's [port] and the host's [token] off it and
     * opens the [appLink] they make.
     */
    fun schemeLink(uri: UriComponents, port: Int, token: String): UriComponents =
        uri.copy(query = listOf(uri.query, "$LINK_PARAM=$port.$token").filter { it.isNotEmpty() }.joinToString("&"))

    /** An [appLink] the browser opened: the token it carried, and the URI it is to deliver. */
    data class Opened(val token: String, val uri: UriComponents)

    /**
     * Reads [requestUri], the path and query of a request for an [appLink], back into the URI under
     * [scheme]; null when it is not one. The fragment never reaches a server, so none is restored.
     */
    fun opened(requestUri: String, scheme: String): Opened? {
        val parsed = runCatching { URI(requestUri) }.getOrNull() ?: return null
        val path = parsed.path ?: return null
        val rest = path.removePrefix("/").takeIf { it.startsWith("$LINK_PREFIX/") }?.removePrefix("$LINK_PREFIX/")
            ?: return null
        val token = rest.substringBefore('/')
        val resource = rest.substringAfter('/', "")
        val authority = resource.substringBefore('/')
        if (token.isEmpty() || authority.isEmpty()) return null
        return Opened(
            token,
            UriComponents(scheme, authority, resource.substring(authority.length), parsed.query.orEmpty(), "")
        )
    }

    /** The extension a URI is for: its authority, which VS Code compares ignoring case. */
    fun extensionOf(uri: UriComponents): String = uri.authority.lowercase()
}
