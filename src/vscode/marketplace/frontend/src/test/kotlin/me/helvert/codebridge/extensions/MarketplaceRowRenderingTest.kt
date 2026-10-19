package me.helvert.codebridge.extensions

import me.helvert.codebridge.extensions.ui.EnableCheckBox
import me.helvert.codebridge.registry.RegistryExtension
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders the marketplace's result rows, and checks they hold what the IDE's marketplace row
 * holds: Install for an extension not yet installed; the enable switch and the installed version
 * for one that is.
 */
class MarketplaceRowRenderingTest {

    private val output = File("build/screenshots").apply { mkdirs() }

    init {
        runCatching {
            val laf = Class.forName("com.intellij.ide.ui.laf.IntelliJLaf")
                .getDeclaredConstructor().newInstance() as javax.swing.LookAndFeel
            javax.swing.UIManager.setLookAndFeel(laf)
        }
    }

    private val samples = listOf(
        RegistryExtension(
            "meta", "pyrefly", "1.3.0", "Pyrefly - Python Language Tooling",
            "Python autocomplete, typechecking, code navigation and more!", 81_591_200, 4.4, 7, true
        ),
        RegistryExtension(
            "anthropic", "claude-code", "2.1.268", "Claude Code for VS Code",
            "Harness the power of Claude Code without leaving your IDE", 48_186_497, 3.4, 12, true
        ),
        RegistryExtension(
            "vadimcn", "vscode-lldb", "1.12.3", "CodeLLDB",
            "Debugger for native code, powered by LLDB.", 1_271_340, 5.0, 3, true
        ),
    )

    @Test
    fun `renders result rows, installed ones without Install`() {
        val panel = try {
            MarketplacePanel {}
        } catch (error: Throwable) {
            assumeTrue(false, "the page needs a running IDE: ${error.message}")
            return
        }

        fun field(name: String) = MarketplacePanel::class.java.getDeclaredField(name).apply { isAccessible = true }

        // The third sample stands in for one already installed, at an older version than the latest.
        field("installedVersions").set(panel, mapOf("vadimcn.vscode-lldb" to "1.11.0"))
        @Suppress("UNCHECKED_CAST")
        (field("model").get(panel) as ArrayList<RegistryExtension>).addAll(samples)
        field("selected").set(panel, samples.first())
        MarketplacePanel::class.java.getDeclaredMethod("rebuildRows").apply { isAccessible = true }.invoke(panel)

        val rows = field("rows").get(panel) as ExtensionList<*>
        val width = 560
        val height = 240
        rows.size = Dimension(width, height)
        repeat(5) { layoutDeeply(rows) }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        rows.paint(graphics)
        graphics.dispose()
        ImageIO.write(image, "png", File(output, "marketplace-rows.png"))
        println("marketplace-rows.png written to ${output.absolutePath}")

        assertEquals(samples.size - 1, find<JButton>(rows).size, "an installed extension must not offer Install again")
        assertEquals(1, find<EnableCheckBox>(rows).size, "an installed extension shows the enable switch")
        val texts = find<JLabel>(rows).map { it.text }
        assertEquals(true, "1.11.0" in texts, "the installed version is in its metrics line: $texts")
        assertEquals(true, "81.6M" in texts, "downloads read as the IDE shows them: $texts")
    }

    private inline fun <reified T> find(container: Container): List<T> = everything(container).filterIsInstance<T>()

    /** Every visible component under [container]. */
    private fun everything(container: Container): List<java.awt.Component> =
        container.components.filter { it.isVisible }
            .flatMap { child -> listOf(child) + ((child as? Container)?.let(::everything) ?: emptyList()) }

    private fun layoutDeeply(component: Container) {
        component.doLayout()
        for (child in component.components) if (child is Container) layoutDeeply(child)
    }
}
