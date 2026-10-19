package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * `MainThreadDocumentContentProviders`: the read-only documents an extension makes up for a URI
 * scheme of its own (`workspace.registerTextDocumentContentProvider`).
 *
 * rust-analyzer serves the compiler's full output for a diagnostic this way, under
 * `rust-analyzer-diagnostics-view:`, and links to it from the diagnostic's code. VS Code opens such a
 * URI by asking the provider registered for its scheme, over `ExtHostDocumentContentProviders`.
 */
object DocumentContentProviders {

    const val SERVICE: String = "MainThreadDocumentContentProviders"
    const val EXT_HOST: String = "ExtHostDocumentContentProviders"

    const val REGISTER: String = $$"$registerTextContentProvider"
    const val UNREGISTER: String = $$"$unregisterTextContentProvider"

    /** Sent only for a document the host holds, which these are not; read so it is not reported. */
    @Suppress("unused") // part of the protocol, not called yet
    const val CHANGED: String = $$"$onVirtualDocumentChange"

    const val PROVIDE: String = $$"$provideTextDocumentContent"

    /** `$registerTextContentProvider(handle, scheme)`. */
    data class Registration(val handle: Int, val scheme: String)

    fun registration(argsJson: String): Registration =
        Args.of(REGISTER, argsJson).let { Registration(it.int(0), it.str(1)) }

    fun unregistered(argsJson: String): Int = Args.of(UNREGISTER, argsJson).int(0)

    /** `$provideTextDocumentContent(handle, uri)`. */
    fun provide(handle: Int, uri: UriComponents): String = buildJsonArray {
        add(JsonPrimitive(handle))
        add(uri.toJson())
    }.toString()

    /** The provider's text, or null when it had none -- which VS Code shows as an error. */
    fun content(json: String?): String? =
        json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonPrimitive }
            ?.takeIf { it.isString }?.contentOrNull

    /**
     * The components of a URI of any scheme, as [UriComponents.uri] spells them, or null if [value]
     * is not one. Decoded, as VS Code's own `URI.parse` leaves them -- the provider reads its query
     * and fragment, and rust-analyzer's fragment is a whole file URI of its own.
     */
    fun resource(value: String): UriComponents? {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme ?: return null
        if (uri.isOpaque) return UriComponents(scheme, "", uri.schemeSpecificPart, "", uri.fragment.orEmpty())
        return UriComponents(
            scheme,
            uri.authority.orEmpty(),
            uri.path.orEmpty(),
            uri.query.orEmpty(),
            uri.fragment.orEmpty()
        )
    }
}
