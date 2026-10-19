package me.helvert.codebridge.exthost.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*

/**
 * A message of the RPC protocol carried inside a [FrameType.REGULAR] frame.
 *
 * Arguments and results stay as raw JSON text. Deserializing them belongs to whichever
 * `MainThread*` implementation handles the call, because every method has its own shape and there
 * is nothing useful to say about them generically.
 */
sealed interface RpcMessage {

    val requestId: Int

    /** A call, in either direction. `argsJson` is always a JSON array. */
    data class Request(
        override val requestId: Int,
        val rpcId: Int,
        val method: String,
        val argsJson: String,
        val cancellable: Boolean = false,
        /** Binary arguments retain their position; their JSON slot is null. */
        val buffers: Map<Int, ByteArray> = emptyMap(),
        val objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
    ) : RpcMessage

    /** A successful reply carrying no value; the caller's promise resolves with `undefined`. */
    data class ReplyOkEmpty(override val requestId: Int) : RpcMessage

    /** A successful reply carrying a JSON value. */
    data class ReplyOkJson(override val requestId: Int, val json: String) : RpcMessage

    data class ReplyOkBuffer(override val requestId: Int, val bytes: ByteArray) : RpcMessage {
        override fun equals(other: Any?): Boolean =
            other is ReplyOkBuffer && requestId == other.requestId && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * requestId + bytes.contentHashCode()
    }
    data class ReplyOkJsonWithBuffers(override val requestId: Int, val json: String, val buffers: List<ByteArray>) : RpcMessage
    data class Acknowledged(override val requestId: Int) : RpcMessage
    data class Cancel(override val requestId: Int) : RpcMessage

    /** A failed reply. `json` is VS Code's serialized error shape. */
    data class ReplyError(override val requestId: Int, val json: String) : RpcMessage
}

/**
 * Encodes and decodes [RpcMessage]s.
 *
 * Request layout:
 * ```
 * 0      uint8   type
 * 1      uint32  request id
 * 5      uint8   rpcId
 * 6      uint8   method name length
 * 7      bytes   method name
 * 7+n    uint32  args length
 * 11+n   bytes   args (JSON array)
 * ```
 *
 * Reply layout:
 * ```
 * ReplyOkEmpty:  uint8 type, uint32 request id
 * ReplyOkJson:   uint8 type, uint32 request id, uint32 json length, bytes json
 * ReplyError:    uint8 type, uint32 request id, uint32 json length, bytes json
 * ```
 *
 * The length prefix on the JSON replies is easy to overlook. Without it the host begins parsing
 * four bytes into the payload and reports a syntax error quoting the shifted text.
 */
object RpcCodec {

    private const val REQUEST_JSON = 1
    private const val REQUEST_JSON_CANCELLABLE = 2
    private const val REQUEST_MIXED = 3
    private const val REQUEST_MIXED_CANCELLABLE = 4
    private const val REPLY_OK_EMPTY = 7
    private const val REPLY_OK_JSON = 9
    private const val REPLY_ERROR = 11

    fun encode(message: RpcMessage): ByteArray = when (message) {
        is RpcMessage.Request -> {
            val name = message.method.toByteArray(StandardCharsets.UTF_8)
            val mixed = message.buffers.isNotEmpty() || message.objectBuffers.isNotEmpty()
            val args = if (!mixed) {
                val json = message.argsJson.toByteArray(StandardCharsets.UTF_8)
                ByteBuffer.allocate(4 + json.size).putInt(json.size).put(json).array()
            } else {
                val values = Json.parseToJsonElement(message.argsJson).jsonArray
                require(values.size <= 255)
                require(message.buffers.keys.all { it in values.indices })
                require(message.objectBuffers.keys.all { it in values.indices && it !in message.buffers })
                val parts = values.mapIndexed { index, value ->
                    val bytes = message.buffers[index] ?: value.toString().toByteArray(StandardCharsets.UTF_8)
                    val nested = message.objectBuffers[index]
                    if (nested != null) ByteBuffer.allocate(9 + bytes.size + nested.sumOf { 4 + it.size })
                        .put(3.toByte()).putInt(nested.size).putInt(bytes.size).put(bytes)
                        .apply { nested.forEach { putInt(it.size); put(it) } }.array()
                    else ByteBuffer.allocate(5 + bytes.size).put((if (index in message.buffers) 2 else 1).toByte())
                        .putInt(bytes.size).put(bytes).array()
                }
                byteArrayOf(values.size.toByte()) + parts.fold(ByteArray(0)) { a, b -> a + b }
            }
            require(name.size <= 0xFF) { "method name too long: ${message.method}" }
            require(message.rpcId in 0..0xFF) { "rpcId out of range: ${message.rpcId}" }
            val type = if (mixed) REQUEST_MIXED else REQUEST_JSON
            ByteBuffer.allocate(7 + name.size + args.size)
                .put((type + if (message.cancellable) 1 else 0).toByte())
                .putInt(message.requestId)
                .put(message.rpcId.toByte())
                .put(name.size.toByte())
                .put(name)
                .put(args)
                .array()
        }

        is RpcMessage.ReplyOkEmpty ->
            ByteBuffer.allocate(5).put(REPLY_OK_EMPTY.toByte()).putInt(message.requestId).array()

        is RpcMessage.ReplyOkJson -> encodeJsonReply(REPLY_OK_JSON, message.requestId, message.json)
        is RpcMessage.ReplyOkBuffer -> ByteBuffer.allocate(9 + message.bytes.size)
            .put(8.toByte()).putInt(message.requestId).putInt(message.bytes.size).put(message.bytes).array()
        is RpcMessage.ReplyOkJsonWithBuffers -> {
            val json = message.json.toByteArray(StandardCharsets.UTF_8)
            ByteBuffer.allocate(13 + json.size + message.buffers.sumOf { 4 + it.size })
                .put(10.toByte()).putInt(message.requestId).putInt(message.buffers.size).putInt(json.size).put(json)
                .apply { message.buffers.forEach { putInt(it.size); put(it) } }.array()
        }
        is RpcMessage.Acknowledged -> ByteBuffer.allocate(5).put(5.toByte()).putInt(message.requestId).array()
        is RpcMessage.Cancel -> ByteBuffer.allocate(5).put(6.toByte()).putInt(message.requestId).array()
        is RpcMessage.ReplyError -> encodeJsonReply(REPLY_ERROR, message.requestId, message.json)
    }

    private fun encodeJsonReply(type: Int, requestId: Int, json: String): ByteArray {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(9 + body.size)
            .put(type.toByte())
            .putInt(requestId)
            .putInt(body.size)
            .put(body)
            .array()
    }

    /** Returns `null` for a payload that is not an RPC message, such as a handshake byte. */
    fun decode(payload: ByteArray): RpcMessage? {
        if (payload.size < 5) return null
        val buffer = ByteBuffer.wrap(payload)
        val type = buffer.get().toInt() and 0xFF
        val requestId = buffer.int

        return when (type) {
            REQUEST_JSON, REQUEST_JSON_CANCELLABLE, REQUEST_MIXED, REQUEST_MIXED_CANCELLABLE -> {
                if (payload.size < 7) return null
                val rpcId = buffer.get().toInt() and 0xFF
                val nameSize = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < nameSize) return null
                val name = String(payload, 7, nameSize, StandardCharsets.UTF_8)
                buffer.position(7 + nameSize)
                val buffers = linkedMapOf<Int, ByteArray>()
                val objectBuffers = linkedMapOf<Int, List<ByteArray>>()
                val args = if (type == REQUEST_MIXED || type == REQUEST_MIXED_CANCELLABLE) {
                    require(buffer.hasRemaining()) { "Missing mixed argument count" }
                    buildJsonArray {
                        repeat(buffer.get().toInt() and 255) { index ->
                            when (val argType = buffer.get().toInt()) {
                                1 -> add(Json.parseToJsonElement(readBytes(buffer).toString(StandardCharsets.UTF_8)))
                                2 -> { buffers[index] = readBytes(buffer); add(JsonNull) }
                                3 -> {
                                    val count = buffer.int
                                    val json = readBytes(buffer).toString(StandardCharsets.UTF_8)
                                    require(count >= 0 && count <= buffer.remaining() / 4) { "Invalid RPC buffer count" }
                                    objectBuffers[index] = List(count) { readBytes(buffer) }
                                    add(Json.parseToJsonElement(json))
                                }
                                4 -> add(JsonNull)
                                else -> error("Unknown mixed argument type $argType")
                            }
                        }
                    }.toString()
                } else readBytes(buffer).toString(StandardCharsets.UTF_8)
                RpcMessage.Request(
                    requestId = requestId,
                    rpcId = rpcId,
                    method = name,
                    argsJson = args,
                    cancellable = type == REQUEST_JSON_CANCELLABLE || type == REQUEST_MIXED_CANCELLABLE,
                    buffers = buffers,
                    objectBuffers = objectBuffers,
                )
            }

            REPLY_OK_EMPTY -> RpcMessage.ReplyOkEmpty(requestId)
            5 -> RpcMessage.Acknowledged(requestId)
            6 -> RpcMessage.Cancel(requestId)
            8 -> RpcMessage.ReplyOkBuffer(requestId, readBytes(buffer))
            10 -> {
                val count = buffer.int
                val json = readBytes(buffer).toString(StandardCharsets.UTF_8)
                require(count >= 0 && count <= buffer.remaining() / 4) { "Invalid RPC buffer count" }
                RpcMessage.ReplyOkJsonWithBuffers(requestId, json, List(count) { readBytes(buffer) })
            }
            REPLY_OK_JSON -> RpcMessage.ReplyOkJson(requestId, readLengthPrefixedJson(payload))
            REPLY_ERROR -> RpcMessage.ReplyError(requestId, readLengthPrefixedJson(payload))
            else -> null
        }
    }

    private fun readBytes(buffer: ByteBuffer): ByteArray {
        require(buffer.remaining() >= 4) { "Missing RPC length" }
        val size = buffer.int
        require(size >= 0 && size <= buffer.remaining()) { "Invalid RPC length: $size" }
        return ByteArray(size).also(buffer::get)
    }

    private fun readLengthPrefixedJson(payload: ByteArray): String {
        if (payload.size < 9) return ""
        val size = ByteBuffer.wrap(payload, 5, 4).int
        val available = minOf(size, payload.size - 9)
        return String(payload, 9, available, StandardCharsets.UTF_8)
    }
}
