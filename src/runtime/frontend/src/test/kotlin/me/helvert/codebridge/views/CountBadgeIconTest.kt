package me.helvert.codebridge.views

import com.intellij.openapi.util.ScalableIcon
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CountBadgeIconTest {
    private class EmptyIcon(private val factor: Float = 1f) : ScalableIcon {
        override fun getIconWidth() = (16 * factor).roundToInt()
        override fun getIconHeight() = iconWidth
        override fun getScale() = factor
        override fun scale(scaleFactor: Float): Icon = EmptyIcon(scaleFactor)
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = Unit
    }

    @Test
    fun `clearing the count restores the original icon`() {
        val base = EmptyIcon()
        assertSame(base, CountBadgeIcon.withCount(base, 0))
        assertSame(base, CountBadgeIcon.withCount(base, -1))
        assertEquals("2", assertIs<CountBadgeIcon>(CountBadgeIcon.withCount(base, 2)).label)
        assertEquals("99+", assertIs<CountBadgeIcon>(CountBadgeIcon.withCount(base, 1234)).label)
    }

    @Test
    fun `badge paints at bottom right within the stripe bounds at each scale`() {
        for (factor in listOf(1f, 1.5f, 2f)) {
            val icon = CountBadgeIcon(EmptyIcon(), 2).scale(factor)
            assertEquals((16 * factor).roundToInt(), icon.iconWidth)
            assertEquals(factor, assertIs<ScalableIcon>(icon).scale)
            val image = BufferedImage(icon.iconWidth + 4, icon.iconHeight + 4, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                icon.paintIcon(null, graphics, 2, 2)
            } finally {
                graphics.dispose()
            }
            assertEquals(0, image.getRGB(2, 2), "The badge must not cover the upper left")
            // The single-digit badge is half the icon height, leaving the main glyph's centre clear.
            assertEquals(0, image.getRGB(2 + icon.iconWidth / 2 - 1, 2 + icon.iconHeight / 2 - 1))
            val painted = (0 until image.width).flatMap { x ->
                (0 until image.height).filter { y -> image.getRGB(x, y) ushr 24 > 0 }.map { y -> x to y }
            }
            assertEquals((8 * factor).roundToInt(), painted.maxOf { it.second } - painted.minOf { it.second } + 1)
            assertTrue(
                image.getRGB(
                    2 + icon.iconWidth - (5 * factor).roundToInt(),
                    2 + icon.iconHeight - (5 * factor).roundToInt()
                ) ushr 24 > 0,
                "The badge must paint inside the lower right corner at scale $factor"
            )
            for (position in 0 until image.width) {
                assertEquals(0, image.getRGB(position, image.height - 1), "Painting must stay in the icon bounds")
                assertEquals(0, image.getRGB(image.width - 1, position))
            }
        }
    }
}
