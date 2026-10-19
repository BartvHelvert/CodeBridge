package me.helvert.codebridge.exthost.protocol.payload

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class EncodingsTest {

    @Test
    fun `UTF-8 is named with and without its byte-order mark`() {
        assertEquals("utf8", Encodings.idOf(Charsets.UTF_8))
        assertEquals("utf8bom", Encodings.idOf(Charsets.UTF_8, bom = true))
    }

    @Test
    fun `a charset is named the way VS Code names it, whichever of its names that is`() {
        assertEquals("windows1252", Encodings.idOf(Charset.forName("windows-1252")))
        assertEquals("shiftjis", Encodings.idOf(Charset.forName("Shift_JIS")))
        assertEquals("utf16le", Encodings.idOf(Charsets.UTF_16LE))
        // Java's canonical name is IBM437; VS Code knows it by an alias.
        assertEquals("cp437", Encodings.idOf(Charset.forName("IBM437")))
    }

    @Test
    fun `an id names the charset it came from`() {
        for (charset in listOf(
            Charset.forName("windows-1252"),
            Charset.forName("Shift_JIS"),
            Charset.forName("IBM437"),
            Charsets.ISO_8859_1
        )) {
            assertEquals(charset, Encodings.charsetOf(Encodings.idOf(charset)))
        }
        assertEquals(Charsets.UTF_8, Encodings.charsetOf("utf8bom"))
    }

    @Test
    fun `an id no charset answers to is not one`() {
        assertNull(Encodings.charsetOf("klingon"))
    }
}
