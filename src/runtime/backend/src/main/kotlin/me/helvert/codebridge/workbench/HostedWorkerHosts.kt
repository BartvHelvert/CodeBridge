package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import me.helvert.codebridge.exthost.ExtensionHostConnection
import me.helvert.codebridge.exthost.VsCodeInstallation
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.rpc.WebviewResourceDto
import me.helvert.codebridge.rpc.WorkerHostPage
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * VS Code's web worker extension hosts, which run in a browser -- the client's -- and so are reached
 * through it: each a connection whose messages wait here for the client running the host to fetch
 * them, and which the client hands what the host sends. VS Code's desktop workbench runs this host
 * in a hidden frame of its window; CodeBridge's client runs it in a hidden page of its browser.
 *
 * The host fetches its own script and its extensions' code over the network, from addresses the
 * workbench gives out; the client answers those of the host's page by asking here, and only the
 * host's own files and its extensions' are given.
 */
@Service(Service.Level.PROJECT)
class HostedWorkerHosts(private val scope: CoroutineScope) {
    private val hosts = ConcurrentHashMap<String, Host>()

    /** The hosts waiting for, or run by, a client; see [me.helvert.codebridge.rpc.CodeBridgeApi.workerHosts]. */
    val wanted: StateFlow<List<String>> field = MutableStateFlow(emptyList())

    /**
     * A new worker host, run from [installation], whose extensions' files are those under
     * [locations], by extension id. It starts once opened, when a client runs it.
     */
    fun connection(installation: VsCodeInstallation, locations: Map<String, Path>): WorkerHostConnection =
        Host(UUID.randomUUID().toString().replace("-", ""), installation, locations)

    /** What host [id] is sent, while the one client running it collects it; see [me.helvert.codebridge.rpc.CodeBridgeApi.workerHostOutbox]. */
    fun outbox(id: String): Flow<List<ByteArray>> = flow {
        val host = hosts[id] ?: return@flow
        if (!host.claimed.compareAndSet(false, true)) return@flow
        try {
            for (first in host.outgoing) {
                val batch = mutableListOf(first)
                while (true) batch += host.outgoing.tryReceive().getOrNull() ?: break
                emit(batch)
            }
        } finally {
            host.finish(ClientGoneException())
        }
    }

    /** What host [id] sent, in order. */
    fun inbox(id: String, messages: List<ByteArray>) {
        val host = hosts[id] ?: return
        synchronized(host) { messages.forEach(host.received) }
    }

    fun failed(id: String, reason: String) {
        hosts[id]?.finish(IOException("The web worker extension host stopped: $reason"))
    }

    /** A file host [id]'s page fetched, by its path there; null for any other. */
    fun resource(id: String, path: String): WebviewResourceDto? {
        val host = hosts[id] ?: return null
        val file = when {
            path == WorkerHostPage.SCRIPT -> host.installation.workerHostScript
            path == WorkerHostPage.MESSAGES -> host.installation.workerHostMessages
            path.startsWith(WorkerHostPage.EXTENSIONS) -> host.extensionFile(path.removePrefix(WorkerHostPage.EXTENSIONS))
            else -> null
        } ?: return null
        if (!Files.isRegularFile(file)) return null
        return WebviewResourceDto(Files.readAllBytes(file), HostedWebviews.mimeType(file))
    }

    /**
     * The client running a host went away -- it disconnected, or the IDE is closing -- and the host
     * with it: nothing went wrong in the host.
     */
    class ClientGoneException : IOException("The client running the web worker extension host went away")

    /** A connection to one worker host, which also says where the host may fetch an extension's files. */
    sealed interface WorkerHostConnection : ExtensionHostConnection {
        /**
         * `MainThreadExtensionService.$asBrowserUri`: the address [uri] -- a file of one of the host's
         * extensions -- is fetched from by the host, or [uri] itself for any other.
         */
        fun browserUri(uri: JsonElement?): JsonElement?
    }

    private inner class Host(
        val id: String,
        val installation: VsCodeInstallation,
        private val locations: Map<String, Path>,
    ) : WorkerHostConnection {
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        val claimed = AtomicBoolean()
        private val closed = AtomicBoolean()
        var received: (ByteArray) -> Unit = {}
        private var failed: (Throwable) -> Unit = {}
        private val real = locations.mapValues { (_, location) -> location.toAbsolutePath().normalize() }

        override fun open(received: (ByteArray) -> Unit, failed: (Throwable) -> Unit) {
            this.received = received
            this.failed = failed
            hosts[id] = this
            wanted.update { it + id }
            scope.launch {
                delay(CLAIM_SECONDS.seconds)
                if (!claimed.get()) finish(IOException(
                    "No client ran the web worker extension host within $CLAIM_SECONDS seconds; it runs in the IDE's browser (JCEF)"
                ))
            }
        }

        override fun send(message: ByteArray) {
            if (closed.get()) throw IOException("The web worker extension host is closed")
            outgoing.trySend(message)
        }

        override fun browserUri(uri: JsonElement?): JsonElement? {
            val resource = UriComponents.from(uri)?.takeIf { it.scheme == UriComponents.FILE } ?: return uri
            val file = runCatching { resource.toPath().toAbsolutePath().normalize() }.getOrNull() ?: return uri
            val (extension, location) = real.entries.firstOrNull { (_, location) -> file.startsWith(location) } ?: return uri
            val path = WorkerHostPage.EXTENSIONS + extension + "/" + location.relativize(file).joinToString("/")
            return UriComponents.of(URI("https", WorkerHostPage.host(id), path, null).toASCIIString()).toJson()
        }

        /** The file of one of the host's extensions at [path] -- `<id>/<file>`, decoded -- or null for one outside it. */
        fun extensionFile(path: String): Path? {
            val location = real[path.substringBefore('/')] ?: return null
            val file = runCatching { location.resolve(path.substringAfter('/', "")).normalize() }.getOrNull() ?: return null
            return file.takeIf { it.startsWith(location) }
        }

        fun finish(error: Throwable) {
            if (!closed.compareAndSet(false, true)) return
            hosts.remove(id, this)
            wanted.update { it - id }
            outgoing.close()
            failed(error)
        }

        override fun close() = finish(IOException("Extension host session closed"))
    }

    companion object {
        /** How long a host waits for a client to run it. */
        private const val CLAIM_SECONDS = 30L

        fun getInstance(project: Project): HostedWorkerHosts = project.service()
    }
}
