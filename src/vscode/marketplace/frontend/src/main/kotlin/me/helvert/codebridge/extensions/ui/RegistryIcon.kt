package me.helvert.codebridge.extensions.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.scale.ScaleContext
import com.intellij.ui.scale.ScaleType
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import javax.swing.Icon
import kotlin.math.roundToInt

/**
 * A registry icon that rasterises itself at paint time, inside the box the IDE gives a plugin logo.
 *
 * Handing the row a pre-scaled `ImageIcon` cannot be made correct, and that is what made these look
 * pixelated. `ListPluginComponent` asks for its icon in its constructor, where the row is not in a
 * window yet and so has no `GraphicsConfiguration`: the image was rasterised against the *default*
 * screen instead, and whenever the dialog is actually on a display with a different scale the
 * graphics transform stretches those too-few pixels up at paint time. A `Graphics` being painted
 * into always knows its true scale, so that is where the decision belongs -- and re-making it per
 * paint also covers the dialog being dragged between monitors.
 *
 * The image itself arrives from the network well after the row was built. The IDE has the same
 * problem with marketplace logos and solves it in `LazyPluginLogoIcon`: hand the row one stable
 * icon straight away and change what it draws later. [source] is re-read on every paint, so a row
 * whose image lands needs nothing but a repaint -- no second icon, no relayout.
 */
class RegistryIcon(
    private val size: Int = SIZE,
    private val source: () -> Image?,
) : Icon {

    companion object {
        /** `PluginLogo.width()`: the IDE draws every plugin logo in a 40x40 box. */
        const val SIZE: Int = 40
    }

    /** The last rasterisation, and the device resolution it was made for. */
    private var scaled: Icon? = null
    private var scaledFor = 0

    override fun getIconWidth(): Int = JBUI.scale(size)

    override fun getIconHeight(): Int = JBUI.scale(size)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val icon = rasterise(g) ?: AllIcons.Plugins.PluginLogo.let {
            com.intellij.util.IconUtil.scale(it, c, iconWidth.toFloat() / it.iconWidth)
        }
        // Registry icons are rarely square -- pyrefly ships 1958x1546 -- while the row's box always
        // is. Centre the image in the box; stretching it to fill was visibly squashing logos.
        icon.paintIcon(
            c, g,
            x + (iconWidth - icon.iconWidth) / 2,
            y + (iconHeight - icon.iconHeight) / 2,
        )
    }

    /** The image at the resolution [g] will really be drawn at, or null while there is no image. */
    private fun rasterise(g: Graphics): Icon? {
        val image = source() ?: return null
        val box = iconWidth
        val context = ScaleContext.create(g as? Graphics2D)
        val system = context.getScale(ScaleType.SYS_SCALE)
        // Device pixels, not logical ones. Rasterising to the logical size and letting the
        // transform scale it up by the system factor is exactly the pixelation being fixed here.
        val device = (box * system).roundToInt().coerceAtLeast(box)
        scaled?.let { if (scaledFor == device) return it }

        val sourceWidth = image.getWidth(null).coerceAtLeast(1)
        val sourceHeight = image.getHeight(null).coerceAtLeast(1)
        val factor = minOf(device.toDouble() / sourceWidth, device.toDouble() / sourceHeight)
        val width = (sourceWidth * factor).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * factor).roundToInt().coerceAtLeast(1)

        // A real BufferedImage, interpolated once. `Image.getScaledInstance` returns a lazily
        // scaled image that re-runs its area averaging on every repaint: slow, and softer.
        val raster = ImageUtil.scaleImage(image, width, height)
        // ...which `ensureHiDPI` then makes occupy `width / system` logical pixels, so those device
        // pixels land one-for-one on the display. This is the pipeline `HiDPIPluginLogoIcon` uses.
        val icon = JBImageIcon(ImageUtil.ensureHiDPI(raster, context, width / system, height / system))
        scaled = icon
        scaledFor = device
        return icon
    }
}
