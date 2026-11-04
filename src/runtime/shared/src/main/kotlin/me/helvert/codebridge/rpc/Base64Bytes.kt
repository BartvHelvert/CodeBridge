package me.helvert.codebridge.rpc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Bytes as one Base64 string. RPC messages are JSON, where a plain `ByteArray` is an array of
 * numbers: an element for every byte, which for a webview's script of megabytes takes seconds to
 * build and read again.
 */
object Base64BytesSerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.helvert.codebridge.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))

    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}

/** A `ByteArray` sent as Base64; see [Base64BytesSerializer]. */
typealias Base64Bytes = @Serializable(with = Base64BytesSerializer::class) ByteArray
