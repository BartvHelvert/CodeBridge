package me.helvert.codebridge.icons

import java.nio.ByteBuffer
import kotlin.test.*

/**
 * Icon fonts in the formats VS Code reads them in, from `/icon-fonts`: one font of four icons -- a
 * square, a ring of two curved contours, a dot and a composite of two dots -- made by its
 * `generate.py`.
 */
class IconFontsTest {

    private fun bytes(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/icon-fonts/$name")) { name }.use { it.readAllBytes() }

    private val icons = 0xF101..0xF104

    private fun outlines(name: String): List<String> {
        val font = assertNotNull(IconFonts.load(bytes(name)), "$name loads")
        return icons.map { ContributedIcons.pathAttributes(ContributedIcons.outline(font, it)) }
    }

    @Test
    fun `a TrueType font is read as it is`() {
        val font = assertNotNull(IconFonts.load(bytes("icons.ttf")))
        icons.forEach { assertTrue(font.canDisplay(it), "U+%X".format(it)) }
        assertContentEquals(bytes("icons.ttf"), IconFonts.sfnt(bytes("icons.ttf")))
    }

    @Test
    fun `a WOFF draws the same icons as the font it was made from`() {
        assertEquals(outlines("icons.ttf"), outlines("icons.woff"))
    }

    @Test
    fun `a WOFF2 draws the same icons as the font it was made from, its glyphs rebuilt`() {
        val expected = outlines("icons.ttf")
        assertTrue(expected.all { it.contains('M') }, "Every icon has an outline")
        assertEquals(expected, outlines("icons.woff2"))
        assertEquals(expected, outlines("icons-hmtx.woff2"))
    }

    @Test
    fun `a rebuilt WOFF2 has the tables of the font it was made from`() {
        val original = tables(bytes("icons.ttf"))
        for (name in listOf("icons.woff2", "icons-hmtx.woff2")) {
            val rebuilt = tables(IconFonts.sfnt(bytes(name))!!)
            assertEquals(original.keys, rebuilt.keys, name)
            // `glyf` may encode the same points differently, and `loca` points into it; the rest is as it was.
            for (tag in original.keys - setOf("glyf", "loca", "head")) {
                assertContentEquals(original.getValue(tag), rebuilt.getValue(tag), "$name: $tag")
            }
        }
    }

    @Test
    fun `anything else is no font`() {
        assertNull(IconFonts.load(byteArrayOf()))
        assertNull(IconFonts.load("<svg/>".toByteArray()))
        assertNull(IconFonts.load(bytes("icons.woff2").copyOf(60)), "A truncated WOFF2")
    }

    /** The tables of a TrueType font, by tag. */
    private fun tables(font: ByteArray): Map<String, ByteArray> {
        val buffer = ByteBuffer.wrap(font)
        val count = buffer.getShort(4).toInt()
        return (0 until count).associate { index ->
            val record = 12 + 16 * index
            val tag = String(font, record, 4, Charsets.ISO_8859_1)
            val offset = buffer.getInt(record + 8)
            tag to font.copyOfRange(offset, offset + buffer.getInt(record + 12))
        }
    }
}
