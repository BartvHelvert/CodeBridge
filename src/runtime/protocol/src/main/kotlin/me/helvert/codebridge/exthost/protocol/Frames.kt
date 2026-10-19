package me.helvert.codebridge.exthost.protocol

import java.nio.ByteBuffer

/**
 * Frame types of the transport VS Code's extension host speaks (`PersistentProtocol`).
 *
 * Values are the bytes seen on the wire; note that 0 and 4 are unused.
 */
enum class FrameType(val wire: Int) {
    REGULAR(1),
    CONTROL(2),
    ACK(3),
    DISCONNECT(5),
    REPLAY_REQUEST(6),
    PAUSE(7),
    RESUME(8),
    KEEP_ALIVE(9),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        fun of(wire: Int): FrameType? = BY_WIRE[wire]
    }
}

/**
 * A single framed message.
 *
 * [id] must start at 1 for the first frame we send and increase by exactly one thereafter. A gap
 * makes the host stop responding without reporting anything: it is waiting for the frame it
 * believes was lost.
 */
class Frame(
    val type: FrameType,
    val id: Int,
    val ack: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Frame &&
                type == other.type && id == other.id && ack == other.ack && payload.contentEquals(other.payload))

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + id
        result = 31 * result + ack
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = "Frame($type, id=$id, ack=$ack, ${payload.size} bytes)"
}

/**
 * Reads and writes the 13-byte frame header:
 *
 * ```
 * 0   uint8   type
 * 1   uint32  id
 * 5   uint32  ack
 * 9   uint32  payload length
 * 13  bytes   payload
 * ```
 *
 * All integers are big-endian.
 */
object FrameCodec {

    const val HEADER_SIZE: Int = 13

    fun encode(frame: Frame): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + frame.payload.size)
            .put(frame.type.wire.toByte())
            .putInt(frame.id)
            .putInt(frame.ack)
            .putInt(frame.payload.size)
            .put(frame.payload)
            .array()

    /**
     * Decodes the frame starting at [offset], or returns `null` when [buffer] does not yet hold a
     * complete one. Callers accumulate bytes and retry; the transport delivers partial frames.
     */
    fun decode(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Frame? {
        if (length < HEADER_SIZE) return null

        val header = ByteBuffer.wrap(buffer, offset, HEADER_SIZE)
        val wire = header.get().toInt() and 0xFF
        val id = header.int
        val ack = header.int
        val payloadSize = header.int

        if (length < HEADER_SIZE + payloadSize) return null
        val type = FrameType.of(wire) ?: throw IllegalArgumentException("unknown frame type $wire")

        val payload = buffer.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + payloadSize)
        return Frame(type, id, ack, payload)
    }

    /** Total size on the wire of the frame starting at [offset], or `null` if it is incomplete. */
    fun frameSize(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int? {
        if (length < HEADER_SIZE) return null
        val payloadSize = ByteBuffer.wrap(buffer, offset + 9, 4).int
        val total = HEADER_SIZE + payloadSize
        return if (length < total) null else total
    }
}

/**
 * The three single-byte payloads exchanged before the RPC protocol takes over.
 *
 * The byte on the wire is one **greater** than the corresponding `MessageType` ordinal in VS Code's
 * source, which is easy to get wrong when reading it.
 */
enum class HandshakeMessage(val wire: Byte) {
    INITIALIZED(1),
    READY(2),
    TERMINATE(3),
    ;

    fun payload(): ByteArray = byteArrayOf(wire)

    companion object {
        fun of(payload: ByteArray): HandshakeMessage? =
            if (payload.size != 1) null else entries.firstOrNull { it.wire == payload[0] }
    }
}
