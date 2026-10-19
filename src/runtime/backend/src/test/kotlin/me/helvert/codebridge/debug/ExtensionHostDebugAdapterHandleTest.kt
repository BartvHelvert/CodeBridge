package me.helvert.codebridge.debug

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.RunInTerminalRequestArguments
import org.eclipse.lsp4j.debug.RunInTerminalResponse
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ExtensionHostDebugAdapterHandleTest {
    private class Transport : DapTransport {
        lateinit var receive: (String) -> Unit
        lateinit var failed: (Throwable) -> Unit
        val sent = mutableListOf<String>()
        var closes = 0
        var onSend: (String) -> Unit = {}
        override fun connect(receive: (String) -> Unit, failed: (Throwable) -> Unit) {
            this.receive = receive
            this.failed = failed
        }
        override fun send(json: String) { sent.add(json); onSend(json) }
        override fun close() { closes++ }
    }

    private fun frame(json: String): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return "Content-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes
    }

    @Test fun `frames incoming unicode using byte length and preserves payload`() {
        val transport = Transport()
        ExtensionHostDebugAdapterHandle(transport).use { handle ->
            val payload = """{"type":"event","event":"output","body":{"output":"héllo 🌍"}}"""
            transport.receive(payload)
            assertContentEquals(frame(payload), handle.input.readNBytes(frame(payload).size))
        }
    }

    @Test fun `fragmented and consecutive frames become complete RPC messages`() {
        val transport = Transport()
        ExtensionHostDebugAdapterHandle(transport).use { handle ->
            val first = """{"seq":1,"type":"request","command":"evaluate","arguments":{"expression":"é"}}"""
            val second = """{"seq":2,"type":"response","request_seq":9,"success":true,"command":"runInTerminal"}"""
            val bytes = frame(first) + frame(second)
            bytes.toList().chunked(7).forEach { handle.output.write(it.toByteArray()) }
            assertEquals(listOf(first, second), transport.sent)
        }
    }

    @Test fun `invalid length fails input and closes transport`() {
        val transport = Transport()
        val handle = ExtensionHostDebugAdapterHandle(transport)
        assertFailsWith<IOException> { handle.output.write("Content-Length: -1\r\n\r\n".toByteArray()) }
        assertFailsWith<IOException> { handle.input.read() }
        assertEquals(1, transport.closes)
    }

    @Test fun `close unblocks reader and is idempotent`() {
        val transport = Transport()
        val handle = ExtensionHostDebugAdapterHandle(transport)
        val read = CompletableFuture.supplyAsync { handle.input.read() }
        handle.close()
        handle.close()
        assertEquals(-1, read.get(2, TimeUnit.SECONDS))
        assertEquals(1, transport.closes)
        assertFailsWith<IOException> { handle.output.write(0) }
    }

    @Test fun `adapter errors unblock stream reader`() {
        val transport = Transport()
        val handle = ExtensionHostDebugAdapterHandle(transport)
        transport.failed(IOException("host exited"))
        assertEquals("host exited", assertFailsWith<IOException> { handle.input.read() }.cause?.message)
        assertEquals(1, transport.closes)
    }

    @Test fun `platform LSP4J client exchanges initialize and reverse terminal request over handle`() {
        val transport = Transport()
        val terminalReply = CompletableFuture<JsonObject>()
        transport.onSend = { raw ->
            val message = Json.parseToJsonElement(raw).jsonObject
            if (message["type"]?.jsonPrimitive?.content == "request") {
                transport.receive("""{"seq":1,"type":"response","request_seq":${message["seq"]},"success":true,"command":"initialize","body":{"supportsConfigurationDoneRequest":true}}""")
            } else terminalReply.complete(message)
        }
        ExtensionHostDebugAdapterHandle(transport).use { handle ->
            val client = object : IDebugProtocolClient {
                override fun runInTerminal(args: RunInTerminalRequestArguments): CompletableFuture<RunInTerminalResponse> {
                    assertEquals(listOf("example", "argument with spaces"), args.args.toList())
                    return CompletableFuture.completedFuture(RunInTerminalResponse().apply { processId = 42 })
                }
            }
            val launcher = DSPLauncher.createClientLauncher(client, handle.input, handle.output)
            val listening = launcher.startListening()
            try {
                val result = launcher.remoteProxy.initialize(InitializeRequestArguments().apply { adapterID = "probe" })
                    .get(5, TimeUnit.SECONDS)
                assertEquals(true, result.supportsConfigurationDoneRequest)
                transport.receive("""{"seq":2,"type":"request","command":"runInTerminal","arguments":{"cwd":".","args":["example","argument with spaces"]}}""")
                val reply = terminalReply.get(5, TimeUnit.SECONDS)
                assertEquals(true, reply["success"]?.jsonPrimitive?.boolean)
                assertEquals(2, reply["request_seq"]?.jsonPrimitive?.int)
                assertEquals(42, reply["body"]?.jsonObject?.get("processId")?.jsonPrimitive?.int)
            } finally {
                handle.close()
                listening.cancel(true)
            }
        }
    }

    /** Waits for [condition], which a reply's coroutine brings about a moment after the reply. */
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    @Test fun `RPC routing isolates adapter handles and closes after pending startup`() {
        val calls = mutableListOf<Pair<String, String>>()
        val start = CompletableFuture<String>()
        val adapters = ExtensionHostDebugAdapters { method, args ->
            calls.add(method to args)
            if (method == $$"$startDASession") start.await() else ""
        }
        val dto = buildJsonObject { put("id", "one") }
        val first = ExtensionHostDebugAdapterHandle(adapters.transport(dto))
        val second = ExtensionHostDebugAdapterHandle(adapters.transport(dto))
        val firstId = Json.parseToJsonElement(calls[0].second).jsonArray[0].jsonPrimitive.int
        val secondId = Json.parseToJsonElement(calls[1].second).jsonArray[0].jsonPrimitive.int
        val json = """{"type":"event","event":"initialized","seq":1}"""
        assertFalse(adapters.handle(MainThreadCall("OtherService", $$"$acceptDAMessage", "[1,$json]")))
        assertTrue(adapters.handle(MainThreadCall("MainThreadDebugService", $$"$acceptDAMessage", "[$secondId,$json]")))
        assertContentEquals(frame(json), second.input.readNBytes(frame(json).size))
        first.close()
        assertFalse(calls.any { it.first == $$"$stopDASession" })
        start.complete("")
        eventually { calls.contains($$"$stopDASession" to "[$firstId]") }
        adapters.hostClosed()
        assertFailsWith<IOException> { second.input.read() }
        eventually { calls.contains($$"$stopDASession" to "[$secondId]") }
    }

    @Test fun `successive debug sessions never reuse a host adapter handle`() {
        val ids = mutableListOf<Int>()
        repeat(2) {
            val adapters = ExtensionHostDebugAdapters { method, args ->
                if (method == $$"$startDASession") ids.add(Json.parseToJsonElement(args).jsonArray[0].jsonPrimitive.int)
                ""
            }
            ExtensionHostDebugAdapterHandle(adapters.transport(buildJsonObject { put("id", "session-$it") })).close()
        }
        assertEquals(2, ids.distinct().size)
    }
}
