package me.helvert.codebridge.exthost.protocol.payload

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class CssWidthTest {

    @Test
    fun `reads GitLens' blame column and plain widths`() {
        assertEquals(CssWidth(30.0, 0.0, -6.0), CssWidth.parse("calc(30ch - 6px)"))
        assertEquals(CssWidth(30.0, 0.0, 13.0), CssWidth.parse("calc(30ch + 13px)"))
        assertEquals(CssWidth(0.0, 0.0, 16.0), CssWidth.parse("16px"))
        assertEquals(CssWidth(0.0, 1.5, 0.0), CssWidth.parse("1.5em"))
    }

    @Test
    fun `a width it cannot measure is none`() {
        assertNull(CssWidth.parse(null))
        assertNull(CssWidth.parse("100%"))
        assertNull(CssWidth.parse("auto"))
        assertNull(CssWidth.parse("calc(10ch 5px)"))
    }

    @Test
    fun `reads the sides of a margin shorthand`() {
        val px = { n: Double -> CssWidth(0.0, 0.0, n) }
        // Left, then right. Error Lens' message, and GitLens' blame column and current-line blame.
        assertEquals(CssWidth(4.0, 0.0, 0.0) to CssWidth.ZERO, CssWidth.margins("0 0 0 4ch"))
        assertEquals(CssWidth.ZERO to px(26.0), CssWidth.margins("0 26px -1px 0"))
        assertEquals(CssWidth(0.0, 3.0, 0.0) to CssWidth.ZERO, CssWidth.margins("0 0 0 3em"))
        assertEquals(px(5.0) to px(5.0), CssWidth.margins("0 5px"))
        assertEquals(px(2.0) to px(2.0), CssWidth.margins("2px"))
        assertNull(CssWidth.margins("auto"))
        assertEquals(CssWidth(1.0, 0.0, 2.0) to CssWidth(1.0, 0.0, 2.0), CssWidth.margins("0 calc(1ch + 2px)"))
        // GitLens' heatmap edge, on the right of its blame column.
        assertEquals(listOf(CssWidth.ZERO, px(2.0), CssWidth.ZERO, CssWidth.ZERO), CssWidth.sides("0 2px 0 0"))
    }

    @Test
    fun `measures in the editor's pixels`() {
        assertEquals(
            30 * 8.0 - 6 * 2.0,
            CssWidth(30.0, 0.0, -6.0).pixels(zeroWidth = 8.0, fontSize = 14.0, pixel = 2.0)
        )
    }
}
