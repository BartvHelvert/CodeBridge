package me.helvert.codebridge.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bytes in RPC messages: one string, not a JSON number for every byte. */
class Base64BytesTest {

    private val bytes = ByteArray(256) { it.toByte() }

    @Test
    fun `a resource's bytes are one string, read back unchanged`() {
        val json = Json.encodeToJsonElement(WebviewResourceDto.serializer(), WebviewResourceDto(bytes, "text/javascript", "1"))
        val encoded = json.jsonObject.getValue("bytes").jsonPrimitive
        assertTrue(encoded.isString)
        assertContentEquals(bytes, Json.decodeFromJsonElement(WebviewResourceDto.serializer(), json).bytes)
    }

    @Test
    fun `a resource the asker has a copy of has no bytes`() {
        val text = Json.encodeToString(WebviewResourceDto.serializer(), WebviewResourceDto(null, "text/javascript", "1"))
        assertEquals(null, Json.decodeFromString(WebviewResourceDto.serializer(), text).bytes)
    }

    @Test
    fun `a message's buffers are strings, read back in order`() {
        val event = WebviewEventDto(WebviewEventDto.MESSAGE, "cats", "{}", listOf(bytes, byteArrayOf(), byteArrayOf(7)))
        val text = Json.encodeToString(WebviewEventDto.serializer(), event)
        val read = Json.decodeFromString(WebviewEventDto.serializer(), text)
        assertEquals(3, read.buffers.size)
        assertContentEquals(bytes, read.buffers[0])
        assertContentEquals(byteArrayOf(), read.buffers[1])
        assertContentEquals(byteArrayOf(7), read.buffers[2])
    }
}
