package me.helvert.codebridge.ide

import com.intellij.ui.scale.ScaleContext
import com.intellij.util.SVGLoader
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import me.helvert.codebridge.icons.ExtensionImages
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compare public-loader rendering with the previous SVG path, including a non-square source. */
class TreeIconsTest : EditorFixture() {
    @Test
    fun `public SVG loading preserves the canvas and painted shape`() = onEdt {
        for ((width, height) in listOf(16 to 16, 32 to 16)) {
            val bytes =
                """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height"><rect width="$width" height="$height" fill="#ff0000"/></svg>""".toByteArray()
            val context = ScaleContext.create()
            val previous = JBImageIcon(
                ImageUtil.ensureHiDPI(
                    SVGLoader.load(null, bytes.inputStream(), context, 16.0, 16.0), context,
                )
            )
            val actual = requireNotNull(ExtensionImages.decode(bytes))
            assertEquals(previous.iconWidth, actual.iconWidth)
            assertEquals(previous.iconHeight, actual.iconHeight)
            val before = paint(previous)
            val after = paint(actual)
            assertTrue((0 until before.width).all { x ->
                (0 until before.height).all { y -> before.getRGB(x, y) == after.getRGB(x, y) }
            }, "The painted SVG changed for a $width by $height source")
        }
    }

    private fun paint(icon: Icon): BufferedImage {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
