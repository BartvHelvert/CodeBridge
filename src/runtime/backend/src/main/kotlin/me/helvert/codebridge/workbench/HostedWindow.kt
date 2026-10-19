package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Args

/**
 * `MainThreadWindow`'s links out: `env.openExternal` and `env.asExternalUri`.
 *
 * Every OAuth sign-in starts by opening the provider's page in the user's browser, and most ask
 * for an external form of a callback address first. Unanswered, `openExternal` resolved to
 * "nothing opened" without a word, and `asExternalUri` threw in the host reviving a missing URI.
 */
internal object HostedWindow {

    const val SERVICE: String = "MainThreadWindow"
    const val OPEN_URI: String = $$"$openUri"
    const val AS_EXTERNAL_URI: String = $$"$asExternalUri"

    private val BROWSED = setOf("http", "https", "mailto")

    /** Whether a link with [scheme] is for the user's browser or mail client. */
    fun browses(scheme: String) = scheme.lowercase() in BROWSED

    fun handles(call: MainThreadCall) = call.service == SERVICE && (call.method == OPEN_URI || call.method == AS_EXTERNAL_URI)

    /** [browse] opens a web or mail link where the user is, answering whether it did; see [HostedClient]. */
    suspend fun handle(call: MainThreadCall, browse: suspend (String) -> Boolean): MainThreadResult {
        val args = Args.of(call.method, call.argsJson)
        return when (call.method) {
            // `(uri, uriString?, options)`, answered with whether it was opened, once it was -- GitLens
            // waits for that before offering to take the code by hand. The string, when given, is
            // what the extension wrote, which a round trip through the components could re-encode.
            OPEN_URI -> {
                val uri = args.uri(0)
                val text = args.strOrNull(1) ?: uri.uri
                if (browses(uri.scheme)) MainThreadResult(browse(text).toString())
                else {
                    LOG.warn("Not opening $text: only web and mail links are opened")
                    MainThreadResult("false")
                }
            }
            // A local host's addresses are already external: VS Code desktop answers the same URI
            // for a local host, and the loopback servers OAuth flows start are on this machine, where
            // the browser is -- except in split mode, where they are not. The editor's own scheme
            // never comes here: the host turns that into `MainThreadUrls.$createAppUri`.
            AS_EXTERNAL_URI -> MainThreadResult(args.uri(0).toJson().toString())
            else -> MainThreadResult()
        }
    }

    private val LOG = Logger.getInstance(HostedWindow::class.java)
}
