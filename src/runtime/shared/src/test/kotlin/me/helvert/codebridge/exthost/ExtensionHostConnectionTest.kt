package me.helvert.codebridge.exthost

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.exthost.protocol.HandshakeMessage
import me.helvert.codebridge.exthost.protocol.RpcCodec
import me.helvert.codebridge.exthost.protocol.RpcIdTable
import me.helvert.codebridge.exthost.protocol.RpcMessage
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A session over a connection that carries whole messages, as the web worker host's does: no process, no frames. */
class ExtensionHostConnectionTest {
    private val storage = Files.createTempDirectory("codebridge-connection")
    private val table = RpcIdTable.bundled()

    /** The host's end: what the session sent, and a way to answer it. */
    private class Messages : ExtensionHostConnection {
        val sent = LinkedBlockingQueue<ByteArray>()
        lateinit var deliver: (ByteArray) -> Unit
        lateinit var fail: (Throwable) -> Unit
        var closed = false

        override fun open(received: (ByteArray) -> Unit, failed: (Throwable) -> Unit) {
            deliver = received
            fail = failed
        }

        override fun send(message: ByteArray) {
            if (closed) throw IOException("closed")
            sent.put(message)
        }

        override fun close() {
            closed = true
        }

        fun next(): ByteArray = assertNotNull(sent.poll(10, TimeUnit.SECONDS), "the session sent nothing")
    }

    private fun extension(name: String): JsonObject = buildJsonObject {
        put("name", name)
        put("identifier", ExtensionHostInitData.identifier("acme.$name"))
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = storage.deleteRecursively()

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the session completes the handshake over whole messages and serves the host's calls`() {
        val node = extension("node")
        val web = extension("web")
        val connection = Messages()
        val session = ExtensionHostSession(
            VsCodeInstallation(storage.resolve("node"), storage), listOf(node, web), storage,
            { call -> if (call.method == "\$ping") "\"pong\"" else null },
            table = table, myExtensions = listOf(web), connection = connection,
        )
        session.start()

        connection.deliver(HandshakeMessage.READY.payload())
        val initData = Json.parseToJsonElement(connection.next().decodeToString()).jsonObject
        val extensions = initData.getValue("extensions").jsonObject
        assertEquals(2, extensions.getValue("allExtensions").jsonArray.size)
        assertEquals(listOf(web["identifier"]), extensions.getValue("myExtensions").jsonArray.toList())

        connection.deliver(HandshakeMessage.INITIALIZED.payload())
        // The configuration and workspace barriers, which the host waits on before activating anything.
        repeat(2) {
            val barrier = assertIs<RpcMessage.Request>(RpcCodec.decode(connection.next()))
            connection.deliver(RpcCodec.encode(RpcMessage.ReplyOkEmpty(barrier.requestId)))
        }
        assertTrue(session.awaitInitialized(10, TimeUnit.SECONDS))

        val ping = RpcMessage.Request(requestId = 7, rpcId = table.require("MainThreadCommands"), method = "\$ping", argsJson = "[]")
        connection.deliver(RpcCodec.encode(ping))
        assertEquals(RpcMessage.Acknowledged(7), RpcCodec.decode(connection.next()))
        assertEquals(RpcMessage.ReplyOkJson(7, "\"pong\""), RpcCodec.decode(connection.next()))

        connection.fail(IOException("the worker went"))
        assertEquals("the worker went", session.termination.getCompleted().message)
        assertTrue(connection.closed)
    }
}
