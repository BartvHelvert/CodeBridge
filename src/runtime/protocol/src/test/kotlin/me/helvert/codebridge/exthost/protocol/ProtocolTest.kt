package me.helvert.codebridge.exthost.protocol

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameCodecTest {

    @Test
    fun `round trips a frame`() {
        val frame = Frame(FrameType.REGULAR, id = 7, ack = 3, payload = "hello".toByteArray())
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
    }

    @Test
    fun `header is thirteen bytes and big endian`() {
        val encoded = FrameCodec.encode(Frame(FrameType.REGULAR, id = 1, ack = 0, payload = byteArrayOf(2)))

        assertEquals(14, encoded.size)
        assertContentEquals(
            byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 2),
            encoded,
        )
    }

    @Test
    fun `decodes the handshake frame the host actually sends`() {
        // Captured from a live extension host: Regular, id=1, ack=0, payload 0x02.
        val bytes = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 2)

        val frame = FrameCodec.decode(bytes)!!
        assertEquals(FrameType.REGULAR, frame.type)
        assertEquals(1, frame.id)
        assertEquals(HandshakeMessage.READY, HandshakeMessage.of(frame.payload))
    }

    @Test
    fun `an incomplete frame decodes to null rather than throwing`() {
        val encoded = FrameCodec.encode(Frame(FrameType.REGULAR, 1, 0, "abcdef".toByteArray()))

        assertNull(FrameCodec.decode(encoded, 0, FrameCodec.HEADER_SIZE - 1))
        assertNull(FrameCodec.decode(encoded, 0, encoded.size - 1))
        assertNull(FrameCodec.frameSize(encoded, 0, encoded.size - 1))
        assertEquals(encoded.size, FrameCodec.frameSize(encoded))
    }

    @Test
    fun `decodes a frame that is followed by more bytes in the buffer`() {
        val first = FrameCodec.encode(Frame(FrameType.REGULAR, 1, 0, "one".toByteArray()))
        val second = FrameCodec.encode(Frame(FrameType.KEEP_ALIVE, 2, 1, ByteArray(0)))
        val stream = first + second

        val decoded = FrameCodec.decode(stream)!!
        assertEquals("one", decoded.payload.toString(StandardCharsets.UTF_8))
        assertEquals(first.size, FrameCodec.frameSize(stream))

        val next = FrameCodec.decode(stream, first.size)!!
        assertEquals(FrameType.KEEP_ALIVE, next.type)
    }

    @Test
    fun `handshake bytes are one greater than the MessageType ordinal`() {
        assertEquals(1, HandshakeMessage.INITIALIZED.wire)
        assertEquals(2, HandshakeMessage.READY.wire)
        assertEquals(3, HandshakeMessage.TERMINATE.wire)
        assertNull(HandshakeMessage.of(byteArrayOf(1, 2)))
    }
}

class RpcCodecTest {

    /**
     * Byte-for-byte reproduction of a request captured from a live extension host:
     * `MainThreadWindow.$getInitialState([])`.
     */
    private fun capturedGetInitialState(): ByteArray {
        val method = $$"$getInitialState".toByteArray(StandardCharsets.UTF_8)
        return byteArrayOf(1, 0, 0, 0, 2, 0x3b, 0x10) + method + byteArrayOf(0, 0, 0, 2) + "[]".toByteArray()
    }

    @Test
    fun `decodes a request captured from the running host`() {
        val request = assertIs<RpcMessage.Request>(RpcCodec.decode(capturedGetInitialState()))

        assertEquals(2, request.requestId)
        assertEquals(59, request.rpcId)
        assertEquals($$"$getInitialState", request.method)
        assertEquals("[]", request.argsJson)
        assertEquals("MainThreadWindow", RpcIdTable.bundled().name(request.rpcId))
    }

    @Test
    fun `re-encoding a captured request reproduces it exactly`() {
        val captured = capturedGetInitialState()
        val decoded = RpcCodec.decode(captured)!!
        assertContentEquals(captured, RpcCodec.encode(decoded))
    }

    @Test
    fun `decodes a request carrying arguments`() {
        val method = $$"$registerProcessSupport".toByteArray(StandardCharsets.UTF_8)
        val bytes = byteArrayOf(1, 0, 0, 0, 4, 0x2a, 0x17) + method + byteArrayOf(0, 0, 0, 6) + "[true]".toByteArray()

        val request = assertIs<RpcMessage.Request>(RpcCodec.decode(bytes))
        assertEquals("MainThreadTerminalService", RpcIdTable.bundled().name(request.rpcId))
        assertEquals("[true]", request.argsJson)
    }

    @Test
    fun `ReplyOkEmpty is five bytes`() {
        val encoded = RpcCodec.encode(RpcMessage.ReplyOkEmpty(42))
        assertContentEquals(byteArrayOf(7, 0, 0, 0, 42), encoded)
        assertEquals(RpcMessage.ReplyOkEmpty(42), RpcCodec.decode(encoded))
    }

    /**
     * Regression test for a real failure: omitting the length prefix made the host start parsing
     * four bytes into the JSON and report a syntax error quoting the shifted text.
     */
    @Test
    fun `ReplyOkJson carries a length prefix before the json`() {
        val encoded = RpcCodec.encode(RpcMessage.ReplyOkJson(1, "{\"isFocused\":true}"))

        assertEquals(9 + 18, encoded.size)
        assertEquals(9, encoded.toList().indexOf('{'.code.toByte()))
        assertContentEquals(byteArrayOf(9, 0, 0, 0, 1, 0, 0, 0, 18), encoded.copyOfRange(0, 9))

        val decoded = assertIs<RpcMessage.ReplyOkJson>(RpcCodec.decode(encoded))
        assertEquals("{\"isFocused\":true}", decoded.json)
    }

    @Test
    fun `round trips a request we would send into the host`() {
        val request = RpcMessage.Request(
            requestId = 1,
            rpcId = RpcIdTable.bundled().require("ExtHostWorkspace"),
            method = $$"$initializeWorkspace",
            argsJson = "[null,true]",
        )
        assertEquals(request, RpcCodec.decode(RpcCodec.encode(request)))
    }

    @Test
    fun `decodes an error reply`() {
        val json = $$"{\"$isError\":true,\"message\":\"Unknown actor\"}"
        val decoded = assertIs<RpcMessage.ReplyError>(RpcCodec.decode(RpcCodec.encode(RpcMessage.ReplyError(3, json))))
        assertEquals(json, decoded.json)
    }

    @Test
    fun `a handshake payload is not an rpc message`() {
        assertNull(RpcCodec.decode(HandshakeMessage.READY.payload()))
        assertNull(RpcCodec.decode(ByteArray(0)))
    }
}

class RpcIdTableTest {

    private val table = RpcIdTable.bundled()

    /** Every one of these was confirmed against traffic from a running extension host. */
    @Test
    fun `ids match what the live host sent`() {
        assertEquals("MainThreadCommands", table.name(10))
        assertEquals("MainThreadConsole", table.name(13))
        assertEquals("MainThreadDebugService", table.name(14))
        assertEquals("MainThreadErrors", table.name(23))
        assertEquals("MainThreadLogger", table.name(28))
        assertEquals("MainThreadMessageService", table.name(29))
        assertEquals("MainThreadExtensionService", table.name(54))
        assertEquals("MainThreadWindow", table.name(59))
    }

    @Test
    fun `ids we call into the host with are correct`() {
        assertEquals(90, table.require("ExtHostConfiguration"))
        assertEquals(117, table.require("ExtHostWorkspace"))
    }

    @Test
    fun `the bundled table covers the whole 1_132_1 namespace`() {
        assertEquals(168, table.size)
        assertEquals(87, table.mainThreadServices().size)
        assertEquals(81, table.extHostServices().size)
    }

    @Test
    fun `an unknown name fails with a message pointing at version skew`() {
        val failure = runCatching { table.require("MainThreadNotAThing") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("different VS Code build"))
    }

    @Test
    fun `ids are one based`() {
        assertNull(table.name(0))
        assertEquals(1, table.id(table.name(1)!!))
    }
}
