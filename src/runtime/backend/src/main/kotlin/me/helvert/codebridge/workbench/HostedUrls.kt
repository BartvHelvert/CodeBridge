package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.Urls
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadUrls`: the URI handlers extensions register, and the links into the editor they receive.
 *
 * VS Code desktop is the operating system's handler for its own scheme. This IDE is not, so the
 * links handed out for a browser to open ([Urls.CREATE_APP_URI]) point at an IDE's built-in web
 * server instead: the client's, which [serving] names, since the browser that opens them is on the
 * client's machine -- in split mode, not the backend's. The client reads an opened link back and
 * asks for it to be [deliver]ed here.
 *
 * Each host's links carry its own [token]. A link that does, the workbench gave out and goes to
 * that host unasked. One that does not -- or whose host has since stopped -- any web page could
 * have made, so the user is asked first, as VS Code asks before an extension handles a URI it
 * has not been confirmed for, and it goes to the host of the project the client has in front.
 *
 * An extension that has not registered its handler yet is activated (`onUri`) and waited for, as
 * VS Code does: a sign-in callback may be the first thing that needs it.
 */
internal class HostedUrls(
    /** The editor's own scheme, `vscodium`: what the extensions receive the links under. */
    private val scheme: String,
    private val callHost: suspend (String, String, String) -> String,
    private val activate: suspend (extensionId: String, event: String) -> Unit,
    private val ask: suspend (Messages.Message) -> Int?,
    /** The project this host is for, which a link without a token is matched against; see [deliver]. */
    val owner: Any? = null,
    /** The client links are given out for; see [serving]. */
    private val client: () -> Client = LATEST_CLIENT,
    private val registrationTimeoutMillis: Long = 10_000,
) : AutoCloseable {

    /** Names this host in its links; unguessable, so a web page cannot pass one off as given out. */
    val token: String = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))

    private class Handler(val handle: Int, val displayName: String)

    /** By lower-cased extension id; the host allows one handler per extension. */
    private val handlers = ConcurrentHashMap<String, Handler>()

    /** Deliveries waiting for the extension to register, by lower-cased extension id. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Handler>>()

    /** Deliveries and activations under way; they end with this host. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        instances.add(this)
    }

    suspend fun handle(call: MainThreadCall): MainThreadResult =
        when (val request = Urls.request(call.method, call.argsJson)) {
            is Urls.Request.Register -> {
                val id = request.extensionId.lowercase()
                val handler = Handler(request.handle, request.displayName)
                handlers[id] = handler
                waiting.remove(id)?.complete(handler)
                MainThreadResult()
            }

            is Urls.Request.Unregister -> {
                handlers.values.removeIf { it.handle == request.handle }
                MainThreadResult()
            }
            // Under the editor's own scheme when the client's machine hands that scheme to its launcher:
            // a sign-in page may offer to return only to a scheme it knows. Otherwise on its web server.
            is Urls.Request.CreateAppUri -> withContext(Dispatchers.IO) {
                val client = client()
                val link = if (scheme in client.schemes) Urls.schemeLink(request.uri, client.port, token)
                else Urls.appLink(request.uri, client.port, token)
                MainThreadResult(link.toJson().toString())
            }
        }

    /**
     * Hands [uri] to the extension its authority names, asking the user first unless [trusted].
     * Answers with the extension's display name once its handler has it; fails with [Declined]
     * when the user says no.
     */
    suspend fun open(uri: UriComponents, trusted: Boolean): String {
        val id = Urls.extensionOf(uri)
        if (!trusted) {
            val choice = ask(
                Messages.Message(
                    Messages.Severity.INFO, "Allow '${handlers[id]?.displayName ?: id}' extension to open this URI?",
                    uri.uri, modal = true, source = null,
                    items = listOf(Messages.Item("Open", 0, false), Messages.Item("Cancel", 1, true))
                )
            )
            if (choice != 0) throw Declined()
        }
        val handler = handler(id)
        callHost(Urls.EXT_HOST, Urls.HANDLE, Urls.handle(handler.handle, uri))
        return handler.displayName
    }

    /** [open], started now and handed back to be waited for; see [Delivery]. */
    private fun opening(uri: UriComponents, trusted: Boolean): Deferred<String> = scope.async { open(uri, trusted) }

    private suspend fun handler(id: String): Handler {
        handlers[id]?.let { return it }
        val waiter = waiting.computeIfAbsent(id) { CompletableDeferred() }
        waiter.invokeOnCompletion { waiting.remove(id, waiter) }
        // Registered between the first look and the wait.
        handlers[id]?.let { waiter.complete(it) }
        scope.launch {
            try {
                activate(id, "onUri")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                waiter.completeExceptionally(error)
            }
        }
        return withTimeoutOrNull(registrationTimeoutMillis.milliseconds) { waiter.await() }
            ?: throw TimeoutException("'$id' registered no URI handler within ${registrationTimeoutMillis}ms")
    }

    override fun close() {
        instances.remove(this)
        waiting.values.forEach { it.completeExceptionally(IllegalStateException("The extension host stopped")) }
        waiting.clear()
        scope.cancel()
    }

    /** The user did not allow the extension to open the URI. */
    class Declined : RuntimeException("The user did not allow the extension to open the URI")

    /** A link on its way: the project of the host it went to, and the extension's name once it has it. */
    class Delivery(val owner: Any?, val extension: Deferred<String>)

    /**
     * A connected client: its built-in server's [port], and the [schemes] its machine hands to its
     * launcher -- the editor's own, `vscodium`, when nothing else there had claimed it. Compared by
     * identity: two clients on one machine may report the same.
     */
    class Client(val port: Int, val schemes: Set<String> = emptySet())

    companion object {
        private val instances = CopyOnWriteArrayList<HostedUrls>()

        /** The clients connected, the latest last. */
        private val clients = CopyOnWriteArrayList<Client>()

        /**
         * Gives out links for a client until the result is closed: on its built-in server at [port],
         * or under one of [schemes]. The latest client's, while several are connected: it is the one
         * the user just came from.
         */
        fun serving(port: Int, schemes: Set<String> = emptySet()): AutoCloseable {
            val client = Client(port, schemes)
            clients.add(client)
            LOG.info(
                "Giving out links into the editor for a client at port $port" +
                    if (schemes.isEmpty()) "" else ", under ${schemes.joinToString { "$it:" }}"
            )
            return AutoCloseable { clients.remove(client); LOG.info("No longer giving out links on port $port") }
        }

        /** Whether [port] is a connected client's built-in server, rather than one on the backend's machine. */
        fun isClientPort(port: Int): Boolean = clients.any { it.port == port }

        /** The latest connected client, which links are given out for; see [serving]. */
        val LATEST_CLIENT: () -> Client = {
            clients.lastOrNull() ?: throw MainThreadException(
                "Unavailable",
                "No IDE window is connected to receive the link"
            )
        }

        /**
         * Delivers the link a browser opened, [requestUri] being its path and query; a link without a
         * token of ours goes to the host whose [owner] is [focused]. Null when it is no link of ours,
         * or no host runs to take it.
         */
        fun deliver(requestUri: String, focused: Any?): Delivery? {
            val opened = Urls.opened(requestUri, "") ?: return null
            val given = instances.firstOrNull { it.token == opened.token }
            val target =
                given ?: instances.firstOrNull { focused != null && it.owner == focused } ?: instances.lastOrNull()
                ?: return null
            // Not the query: a sign-in's carries its authorization code.
            LOG.info("Opening ${opened.uri.authority}${opened.uri.path} in ${if (given != null) "the host that gave it out" else "the focused host"}")
            return Delivery(target.owner, target.opening(opened.uri.copy(scheme = target.scheme), trusted = given != null))
        }

        /**
         * Hands [uri], a link into the editor followed inside the workbench of [owner] -- not one
         * the workbench gave out -- to the extension it names, once the user allows it, as
         * [deliver] does. Null when no host runs for [owner].
         */
        fun open(owner: Any, uri: UriComponents): Deferred<String>? =
            instances.firstOrNull { it.owner == owner }?.let { it.opening(uri.copy(scheme = it.scheme), trusted = false) }

        private val LOG = Logger.getInstance(HostedUrls::class.java)
    }
}
