package me.helvert.codebridge.workbench

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.VsCodeInstallation
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.rpc.WorkerHostPage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The backend's half of a web worker extension host: its messages each way, and the files it may fetch. */
class HostedWorkerHostsTest {
    private val root = Files.createTempDirectory("codebridge-worker-hosts")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hosts = HostedWorkerHosts(scope)
    private val runtime = root.resolve("runtime").also {
        it.resolve("worker-host").createDirectories()
        it.resolve("worker-host/extensionHostWorkerMain.js").writeText("// the host")
    }
    private val extension: Path = root.resolve("extensions/acme.web").also {
        it.resolve("dist").createDirectories()
        it.resolve("dist/extension.js").writeText("exports.activate = () => {}")
    }
    private val secret = root.resolve("extensions/secret.txt").also { it.writeText("not the host's") }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun connection() = hosts.connection(VsCodeInstallation(runtime.resolve("node"), runtime), mapOf("acme.web" to extension))

    @Test
    fun `messages reach the client running the host in order, and the host's reach the session`() = runBlocking {
        val connection = connection()
        val received = mutableListOf<String>()
        var failure: Throwable? = null
        connection.open({ received += it.decodeToString() }, { failure = it })
        val id = hosts.wanted.value.single()

        hosts.inbox(id, listOf("ready".encodeToByteArray(), "reply".encodeToByteArray()))
        assertEquals(listOf("ready", "reply"), received)

        connection.send("one".encodeToByteArray())
        connection.send("two".encodeToByteArray())
        val batch = CompletableDeferred<List<ByteArray>>()
        val client = launch { hosts.outbox(id).collect { batch.complete(it) } }
        assertEquals(listOf("one", "two"), withTimeout(5.seconds) { batch.await() }.map { it.decodeToString() })
        // A second client gets nothing: the host is the first one's.
        assertEquals(emptyList(), hosts.outbox(id).toList())
        assertNull(failure)

        // The client going takes the host with it, and says so apart from a failure.
        client.cancelAndJoin()
        assertIs<HostedWorkerHosts.ClientGoneException>(failure)
        assertEquals(emptyList(), hosts.wanted.value)
    }

    @Test
    fun `a client listening is told the hosts to run, and is no longer counted once it stops`() = runBlocking {
        val connection = connection()
        connection.open({}, {})
        val id = hosts.wanted.value.single()

        // What a client collects: the hosts wanted now, and each change while it listens.
        val listened = CompletableDeferred<List<String>>()
        val client = launch { hosts.hosts().collect { if (it.isNotEmpty()) listened.complete(it) } }
        assertEquals(listOf(id), withTimeout(5.seconds) { listened.await() })
        client.cancelAndJoin()

        // Another client is told the same: the first one going left nothing behind.
        val again = CompletableDeferred<List<String>>()
        val second = launch { hosts.hosts().collect { if (it.isNotEmpty()) again.complete(it) } }
        assertEquals(listOf(id), withTimeout(5.seconds) { again.await() })
        second.cancelAndJoin()
    }

    @Test
    fun `the host fetches its own script and its extensions' files, and nothing else`() {
        val connection = connection()
        connection.open({}, {})
        val id = hosts.wanted.value.single()

        assertEquals("// the host", hosts.resource(id, WorkerHostPage.SCRIPT)?.bytes?.decodeToString())
        assertEquals("text/javascript", hosts.resource(id, WorkerHostPage.EXTENSIONS + "acme.web/dist/extension.js")?.mimeType)
        assertNull(hosts.resource(id, WorkerHostPage.EXTENSIONS + "acme.web/../secret.txt"))
        assertNull(hosts.resource(id, WorkerHostPage.EXTENSIONS + "other.extension/dist/extension.js"))
        assertNull(hosts.resource(id, "/" + secret.toString()))
        assertNull(hosts.resource("another host", WorkerHostPage.SCRIPT))
    }

    @Test
    fun `an extension's files are given out at the host's own address`() {
        val connection = connection()
        connection.open({}, {})
        val id = hosts.wanted.value.single()

        val code = connection.browserUri(UriComponents.of(extension.resolve("dist/extension.js")).toJson())!!.jsonObject
        assertEquals("https", code.getValue("scheme").jsonPrimitive.content)
        assertEquals(WorkerHostPage.host(id), code.getValue("authority").jsonPrimitive.content)
        val path = code.getValue("path").jsonPrimitive.content
        assertEquals(WorkerHostPage.EXTENSIONS + "acme.web/dist/extension.js", path)
        assertTrue(hosts.resource(id, path) != null)

        // Any other file stays what it was, and is not the host's to fetch.
        val other = UriComponents.of(secret).toJson()
        assertEquals(other, connection.browserUri(other))
    }
}
