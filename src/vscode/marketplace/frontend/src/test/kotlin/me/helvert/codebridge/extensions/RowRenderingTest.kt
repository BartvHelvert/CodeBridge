package me.helvert.codebridge.extensions

import com.intellij.util.ui.JBUI
import me.helvert.codebridge.extensions.ui.BaselinePanel
import me.helvert.codebridge.extensions.ui.PluginRow
import me.helvert.codebridge.extensions.ui.TagComponent
import com.intellij.ui.components.panels.ListLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the copied layout components to PNGs under `build/screenshots`.
 *
 * These are pure Swing and need no IDE, which is the point: the layout can be looked at directly
 * rather than inferred from whether the classes load. Every previous attempt at matching the IDE's
 * page was verified only by "it constructs", which says nothing about how it looks.
 */
class RowRenderingTest {

    private val output = File("build/screenshots").apply { mkdirs() }

    init {
        runCatching {
            val laf = Class.forName("com.intellij.ide.ui.laf.IntelliJLaf")
                .getDeclaredConstructor().newInstance() as javax.swing.LookAndFeel
            javax.swing.UIManager.setLookAndFeel(laf)
        }
    }

    private fun render(component: JPanel, width: Int, height: Int, name: String): BufferedImage {
        component.size = Dimension(width, height)
        component.preferredSize = Dimension(width, height)
        component.doLayout()
        // Lay out children too: a container only lays out one level per call.
        repeat(3) { layoutDeeply(component) }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        component.paint(graphics)
        graphics.dispose()
        ImageIO.write(image, "png", File(output, "$name.png"))
        return image
    }

    private fun layoutDeeply(component: java.awt.Container) {
        component.doLayout()
        for (child in component.components) {
            if (child is java.awt.Container) layoutDeeply(child)
        }
    }

    @Test
    fun `renders an extension row`() {
        val row = PluginRow()
        val icon = JLabel().apply {
            preferredSize = Dimension(JBUI.scale(40), JBUI.scale(40))
            isOpaque = true
            background = Color(0x4A, 0x90, 0xD9)
        }
        val name = JLabel("Pyrefly - Python Language Tooling").apply {
            font = font.deriveFont(Font.BOLD)
        }
        val tag = TagComponent("installed")
        val description = JLabel("Python autocomplete, typechecking, code navigation and more!")
        val metrics = JLabel("meta  1.3.0  81,536,963 downloads  4.4").apply {
            foreground = Color.GRAY
        }

        row.setIconComponent(icon)
        row.setNameComponent(name)
        row.setTagComponent(tag)
        row.addLineComponent(description)
        row.addLineComponent(metrics)

        val image = render(row, 560, 80, "row")

        // The icon is drawn at the left inset; if the layout never ran, nothing is painted there.
        val iconPixel = image.getRGB(JBUI.scale(12), JBUI.scale(20))
        assertTrue(iconPixel != Color.WHITE.rgb, "expected the icon to be painted, got white")
        println("row.png written to ${output.absolutePath}")
    }

    @Test
    fun `renders the details header`() {
        val top = JPanel(ListLayout.vertical(8, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        top.isOpaque = true
        top.background = Color.WHITE
        top.border = JBUI.Borders.empty(15, 20, 0, 20)

        val tags = JPanel(ListLayout.horizontal(6))
        tags.isOpaque = false
        tags.add(TagComponent("Debuggers"))
        tags.add(TagComponent("Programming Languages"))

        val name = JLabel("CodeLLDB").apply { font = font.deriveFont(Font.BOLD, 18f) }

        val nameAndButtons = BaselinePanel(12, false)
        nameAndButtons.add(JLabel("1.12.3").apply { foreground = Color.GRAY })
        nameAndButtons.addButtonComponent(AccentButton("Install"))

        top.add(tags, ListLayout.GrowPolicy.GROW)
        top.add(name, ListLayout.GrowPolicy.GROW)
        top.add(nameAndButtons, ListLayout.GrowPolicy.GROW)

        render(top, 460, 140, "details-header")
        println("details-header.png written to ${output.absolutePath}")
    }
}
