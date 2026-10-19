package me.helvert.codebridge.extensions

import me.helvert.codebridge.registry.RegistryDetail
import me.helvert.codebridge.registry.RegistryExtension
import java.awt.Color
import java.awt.Container
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Renders the whole Marketplace page to a PNG so the composed layout can actually be looked at.
 *
 * Constructing it outside an IDE may fail on a service lookup; when it does, the test reports why
 * rather than failing, because its value is the image and not a pass mark.
 */
class PageRenderingTest {

    private val output = File("build/screenshots").apply { mkdirs() }

    init {
        // Render under the IDE's own look and feel. Under the default Metal LaF the accent button
        // ignores its client properties, AllIcons resolve to nothing and every namedColor falls
        // back, so the screenshot answers a question nobody asked.
        runCatching {
            val laf = Class.forName("com.intellij.ide.ui.laf.IntelliJLaf")
                .getDeclaredConstructor().newInstance() as javax.swing.LookAndFeel
            javax.swing.UIManager.setLookAndFeel(laf)
        }
    }

    private val samples = listOf(
        sample("meta", "pyrefly", "Pyrefly - Python Language Tooling", "1.3.0",
            "Python autocomplete, typechecking, code navigation and more! Powered by Pyrefly.", 81_536_963, 4.4),
        sample("anthropic", "claude-code", "Claude Code for VS Code", "2.1.268",
            "Harness the power of Claude Code without leaving your IDE", 1_204_551, 4.8),
        sample("redhat", "java", "Language Support for Java(TM) by Red Hat", "1.57.2026091108",
            "Java Linting, Intellisense, formatting, refactoring, Maven/Gradle support and more...", 9_112_004, 4.1),
        sample("shopify", "ruby-lsp", "Ruby LSP", "0.10.6",
            "VS Code plugin for connecting with the Ruby LSP", 512_338, null),
        sample("vadimcn", "vscode-lldb", "CodeLLDB", "1.12.3",
            "Debugger for native code, powered by LLDB. Debug C++, Rust and other compiled languages.",
            1_271_340, 5.0),
    )

    private fun sample(
        namespace: String, name: String, display: String, version: String,
        description: String, downloads: Int, rating: Double?,
    ) = RegistryExtension(
        namespace = namespace, name = name, version = version, displayName = display,
        description = description, downloadCount = downloads, averageRating = rating,
        reviewCount = 7, verified = true,
    )



    /**
     * The install page on its own, at a size where it can be read.
     *
     * Rendered from the real `DetailsPane`, populated the way a selection populates it, so what is
     * shown is what a user sees after clicking a row -- not a mock-up of it.
     */
    @Test
    fun `renders the install page`() {
        val panel = try {
            MarketplacePanel {}
        } catch (error: Throwable) {
            assumeTrue(false, "the page needs a running IDE: ${error.message}")
            return
        }
        val details = MarketplacePanel::class.java.getDeclaredField("details")
            .apply { isAccessible = true }.get(panel) as JPanel
        details.javaClass.getDeclaredMethod("show", RegistryDetail::class.java)
            .apply { isAccessible = true }.invoke(details, codelldb())

        val width = 620
        val height = 360
        details.size = Dimension(width, height)
        details.preferredSize = Dimension(width, height)
        repeat(5) { layoutDeeply(details) }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        details.paint(graphics)
        graphics.dispose()
        ImageIO.write(image, "png", File(output, "install-page.png"))
        println("install-page.png written to ${output.absolutePath}")
    }

    private fun codelldb() = RegistryDetail(
        extension = samples.last(),
        license = "MIT",
        repository = "https://github.com/vadimcn/codelldb.git",
        homepage = "https://github.com/vadimcn/codelldb#readme",
        categories = listOf("Debuggers"),
        engineRange = "^1.61.0",
        extensionKind = listOf("workspace"),
        downloads = mapOf("universal" to "https://open-vsx.org/x.vsix"),
        download = "https://open-vsx.org/x.vsix",
    )

    private fun layoutDeeply(component: Container) {
        component.doLayout()
        for (child in component.components) if (child is Container) layoutDeeply(child)
    }

    @Suppress("unused")
    private fun unusedPanelHint(): JPanel = JPanel()
}
