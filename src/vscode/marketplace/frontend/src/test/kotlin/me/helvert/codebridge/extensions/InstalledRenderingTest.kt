package me.helvert.codebridge.extensions

import me.helvert.codebridge.rpc.InstalledExtensionDto
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the Installed tab, which went four rounds without anyone looking at it.
 *
 * It was the last hand-rolled panel on the page -- a toolbar, a `JBList` and a vertical
 * `JSplitPane` -- while the IDE builds both tabs from the same `PluginsTab.createPanel()`. Having
 * a screenshot of only the Marketplace tab is exactly how that survived.
 */
class InstalledRenderingTest {

    private val output = File("build/screenshots").apply { mkdirs() }

    init {
        runCatching {
            val laf = Class.forName("com.intellij.ide.ui.laf.IntelliJLaf")
                .getDeclaredConstructor().newInstance() as javax.swing.LookAndFeel
            javax.swing.UIManager.setLookAndFeel(laf)
        }
    }

    private fun installed(
        publisher: String, name: String, display: String, version: String, description: String,
        platform: String? = null,
    ): InstalledExtensionDto {
        return InstalledExtensionDto(
            id = "$publisher.$name", name = name, publisher = publisher, version = version,
            displayName = display, description = description, categories = listOf("Debuggers"),
            engineRange = "^1.61.0", directory = "C:/codebridge/$publisher.$name",
            targetPlatform = platform, installedAt = "2026-09-11T10:12:00Z",
        )
    }

    private val samples = listOf(
        installed(
            "vadimcn", "vscode-lldb", "CodeLLDB", "1.12.3",
            "Debugger for native code, powered by LLDB."
        ),
        installed(
            "llvm-vs-code-extensions", "lldb-dap", "LLDB DAP", "0.7.20260910",
            "Debugging with LLDB in Visual Studio Code", platform = "win32-x64"
        ),
        installed(
            "redhat", "java", "Language Support for Java(TM) by Red Hat", "1.57.2026091108",
            "Java Linting, Intellisense, formatting, refactoring and more."
        ),
    )

    @Test
    fun `renders the installed tab`() {
        val page = try {
            MarketplacePanel {}
        } catch (error: Throwable) {
            assumeTrue(false, "the installed tab needs a running IDE: ${error.message}")
            return
        }
        val panel = MarketplacePanel::class.java.getDeclaredField("installed")
            .apply { isAccessible = true }.get(page) as InstalledPanel

        fun field(name: String) = InstalledPanel::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("model").set(panel, samples)
        field("selected").set(panel, samples.first())
        InstalledPanel::class.java.getDeclaredMethod("rebuildRows").apply { isAccessible = true }.invoke(panel)

        InstalledPanel::class.java.getDeclaredMethod("choose", InstalledExtensionDto::class.java)
            .apply { isAccessible = true }.invoke(panel, samples.first())

        val width = 1000
        val height = 320
        page.size = Dimension(width, height)
        repeat(5) { layoutDeeply(page) }
        val detailsComponent = panel.detailsComponent
        assertTrue(detailsComponent.isVisible, "the selected installed extension's detail card is visible")
        assertTrue(
            detailsComponent.width > 0 && detailsComponent.height > 0,
            "the selected detail card has bounds: ${detailsComponent.bounds}; parent=${detailsComponent.parent?.bounds}"
        )

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        page.paint(graphics)
        graphics.dispose()
        ImageIO.write(image, "png", File(output, "installed-tab.png"))
        println("installed-tab.png written to ${output.absolutePath}")

        assertTrue(
            page.preferredSize.width <= width,
            "the marketplace demanded ${page.preferredSize.width}px in a ${width}px pane",
        )
    }

    @Test
    fun `an installed row contains what the IDE puts in one`() {
        val panel = InstalledPanel()
        val rowClass = Class.forName($$"me.helvert.codebridge.extensions.InstalledPanel$ExtensionRow")
        val constructor = rowClass.getDeclaredConstructor(InstalledPanel::class.java, InstalledExtensionDto::class.java)
        constructor.isAccessible = true
        val row = constructor.newInstance(panel, samples.first()) as Container

        // The same shape as a marketplace row, with Uninstall in place of Install:
        // icon, name, button, metrics line. No description line.
        assertEquals(
            listOf("JLabel", "JLabel", "EnableCheckBox", "JPanel"),
            row.components.map { it.javaClass.simpleName },
            "an installed row must hold icon, name, enable switch and the metrics line",
        )
    }

    private fun layoutDeeply(component: Container) {
        component.doLayout()
        for (child in component.components) if (child is Container) layoutDeeply(child)
    }
}
