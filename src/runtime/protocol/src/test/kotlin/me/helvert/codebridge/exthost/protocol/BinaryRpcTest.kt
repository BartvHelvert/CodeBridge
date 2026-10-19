package me.helvert.codebridge.exthost.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import kotlin.test.*

class BinaryRpcTest {
    @Test
    fun `decodes the mixed argument layout used by workspace fs writeFile`() {
        val uri = "{\"scheme\":\"file\",\"path\":\"/tmp/a.bin\"}".toByteArray()
        val name = $$"$writeFile".toByteArray()
        val bytes = byteArrayOf(0, -1, -128, 65)
        // Independent wire fixture: request header, count, JSON argument, VSBuffer argument.
        val wire = ByteBuffer.allocate(7 + name.size + 1 + 5 + uri.size + 5 + bytes.size)
            .put(3.toByte()).putInt(42).put(10.toByte()).put(name.size.toByte()).put(name)
            .put(2.toByte()).put(1.toByte()).putInt(uri.size).put(uri)
            .put(2.toByte()).putInt(bytes.size).put(bytes).array()
        val request = assertIs<RpcMessage.Request>(RpcCodec.decode(wire))
        assertEquals($$"$writeFile", request.method)
        assertEquals(
            "/tmp/a.bin",
            Json.parseToJsonElement(request.argsJson).jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content
        )
        assertContentEquals(bytes, request.buffers[1])
        assertContentEquals(wire, RpcCodec.encode(request))
    }

    @Test
    fun `file bytes are returned as VSBuffer not JSON or UTF8`() {
        val bytes = byteArrayOf(0, -1, -128, 65)
        val wire = RpcCodec.encode(RpcMessage.ReplyOkBuffer(42, bytes))
        assertContentEquals(byteArrayOf(8, 0, 0, 0, 42, 0, 0, 0, 4) + bytes, wire)
        assertContentEquals(bytes, assertIs<RpcMessage.ReplyOkBuffer>(RpcCodec.decode(wire)).bytes)
    }

    @Test
    fun `nested buffers are decoded without interpreting binary as JSON`() {
        val request = RpcMessage.Request(
            1, 2, $$"$executeCommand", "[\"command\",[{\"bufferRef\":0}],true]",
            objectBuffers = mapOf(1 to listOf(byteArrayOf(0, -1)))
        )
        val decoded = assertIs<RpcMessage.Request>(RpcCodec.decode(RpcCodec.encode(request)))
        assertEquals(request.argsJson, decoded.argsJson)
        assertContentEquals(byteArrayOf(0, -1), decoded.objectBuffers.getValue(1).single())
    }

    @Test
    fun `invalid binary lengths are rejected before allocating`() {
        assertFailsWith<IllegalArgumentException> { RpcCodec.decode(byteArrayOf(8, 0, 0, 0, 1, -1, -1, -1, -1)) }
        assertFailsWith<IllegalArgumentException> { RpcCodec.decode(byteArrayOf(8, 0, 0, 0, 1, 127, -1, -1, -1)) }
    }

    @Test
    fun `acknowledgement and cancellation keep their protocol opcodes`() {
        assertContentEquals(byteArrayOf(5, 0, 0, 0, 3), RpcCodec.encode(RpcMessage.Acknowledged(3)))
        assertEquals(RpcMessage.Cancel(4), RpcCodec.decode(byteArrayOf(6, 0, 0, 0, 4)))
    }
}
