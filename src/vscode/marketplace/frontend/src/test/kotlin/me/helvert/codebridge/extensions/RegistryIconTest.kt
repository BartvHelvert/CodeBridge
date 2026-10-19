package me.helvert.codebridge.extensions

import com.intellij.ui.JreHiDpiUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.extensions.ui.RegistryIcon
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the defect that kept coming back: icons rasterised for the wrong display scale.
 *
 * Every previous fix was argued from API contracts and every one of them was wrong, because the
 * mistake was never in the scaling itself -- it was in *when* the scale was decided. So this paints
 * the icon into a graphics that is genuinely scaled up, the way a HiDPI display does, and measures
 * whether the result actually carries that many pixels.
 *
 * The measure is how many neighbouring pixels differ. An image rasterised at half the resolution
 * and stretched is made of 2x2 blocks of one colour, so half its neighbours are identical -- which
 * is exactly what "pixelated" means, and unlike a pixel-for-pixel comparison it does not break on a
 * one-pixel alignment difference.
 *
 * Needs no `Application`: [RegistryIcon] touches none, which is the whole reason the coverage that
 * the forked row lost can live here instead. It does need a display, though: headless, the platform
 * reports no system scale for any graphics, so there is no HiDPI rasterisation to measure.
 */
class RegistryIconTest {

    @BeforeTest
    fun needsHiDpiScaling() = assumeTrue(JreHiDpiUtil.isJreHiDPIEnabled(), "No JRE HiDPI scaling without a display")

    private val output = File("build/screenshots").apply { mkdirs() }

    /** The scale a 200% display paints at. */
    private val hidpi = 2.0

    @Test
    fun `rasterises for the scale of the graphics it is painted into`() {
        val source = detailedSource()
        val box = JBUI.scale(40)

        val ours = paint(RegistryIcon(40) { source }, box, hidpi)

        // The same icon, rasterised once at logical size and left for the transform to stretch --
        // which is what a pre-scaled ImageIcon built before the row knew its display amounts to.
        val prescaled = JBImageIcon(ImageUtil.scaleImage(source, box, box))
        val stretched = paint(prescaled, box, hidpi)

        ImageIO.write(ours, "png", File(output, "icon-hidpi.png"))
        ImageIO.write(stretched, "png", File(output, "icon-hidpi-stretched.png"))

        val sharp = detail(ours)
        val blocky = detail(stretched)
        println("distinct neighbours at ${hidpi}x: paint-time ${percent(sharp)}, pre-scaled ${percent(blocky)}")
        assertTrue(
            sharp > blocky * 1.5,
            "icon must rasterise at the painted scale: $sharp distinct vs $blocky is not sharper",
        )
    }

    @Test
    fun `re-rasterises when the scale changes`() {
        val source = detailedSource()
        val box = JBUI.scale(40)
        val icon = RegistryIcon(40) { source }

        // Painted at 1x first, exactly as it would be before the dialog is dragged to another
        // monitor. A cached rasterisation that survived that move is the original bug.
        paint(icon, box, 1.0)
        val afterMove = detail(paint(icon, box, hidpi))
        val fresh = detail(paint(RegistryIcon(40) { source }, box, hidpi))

        assertTrue(
            afterMove > fresh * 0.9,
            "a scale change must be re-rasterised: $afterMove after a 1x paint vs $fresh fresh",
        )
    }

    @Test
    fun `keeps a non-square icon's proportions`() {
        // The registry serves whatever the publisher uploaded; pyrefly's is 1958x1546.
        val source = detailedSource(width = 400, height = 300)
        val painted = paint(RegistryIcon(40) { source }, JBUI.scale(40), 1.0)

        val bounds = drawnBounds(painted)
        val ratio = bounds.width.toDouble() / bounds.height
        assertTrue(
            abs(ratio - 400.0 / 300.0) < 0.12,
            "a 4:3 icon was drawn at $ratio (${bounds.width}x${bounds.height})",
        )
    }

    /** Paints [icon] into a canvas scaled by [scale], the way a HiDPI display scales a window. */
    private fun paint(icon: javax.swing.Icon, box: Int, scale: Double): BufferedImage {
        val canvas = BufferedImage((box * scale).toInt(), (box * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        graphics.color = BACKGROUND
        graphics.fillRect(0, 0, canvas.width, canvas.height)
        // No interpolation hint on purpose: Swing paints components without one, so an icon that
        // has to be stretched gets nearest-neighbour -- the hard blocks in the bug report.
        graphics.scale(scale, scale)
        icon.paintIcon(null, graphics, 0, 0)
        graphics.dispose()
        return canvas
    }

    private fun percent(fraction: Double): String = "%.1f%%".format(fraction * 100)

    /**
     * The share of horizontally adjacent pixels that actually differ.
     *
     * This, not the size of the steps between them, is what separates a real rasterisation from a
     * stretched one: an image blown up 2x repeats every column, so half its neighbours are
     * identical however hard the edges look. Measuring the *size* of the steps says the opposite,
     * because nearest-neighbour upscaling makes edges harsher, not softer.
     */
    private fun detail(image: BufferedImage): Double {
        var distinct = 0
        var count = 0
        for (y in 0 until image.height) {
            for (x in 1 until image.width) {
                if (image.getRGB(x, y) != image.getRGB(x - 1, y)) distinct++
                count++
            }
        }
        return if (count == 0) 0.0 else distinct.toDouble() / count
    }

    /** The area of the canvas that is not background, which is where the image landed. */
    private fun drawnBounds(image: BufferedImage): java.awt.Rectangle {
        var minX = image.width; var minY = image.height; var maxX = -1; var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) != BACKGROUND.rgb) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        return java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /**
     * A source with detail far finer than the icon box, so anything the pipeline throws away shows.
     * A photograph would do the same job; this one cannot go stale or be missing.
     */
    private fun detailedSource(width: Int = 512, height: Int = 512): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cell = ((x * 16 / width) + (y * 16 / height)) % 2
                val shade = if (cell == 0) 0x20 else 0xE0
                image.setRGB(x, y, Color(shade, shade, (x * 255 / width)).rgb)
            }
        }
        return image
    }

    private companion object {
        /** `Plugins.background` is close enough; it only has to differ from the image. */
        val BACKGROUND: Color = Color(0x3C, 0x3F, 0x41)
    }
}
