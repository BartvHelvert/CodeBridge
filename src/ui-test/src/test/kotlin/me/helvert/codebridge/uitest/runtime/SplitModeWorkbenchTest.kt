package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.uitest.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What GitLens and Error Lens draw and open, as the user sees it in a JetBrains Client.
 *
 * Driven by a probe extension (`src/test-extensions/workbench-probe-extension`) rather than by GitLens or
 * Error Lens themselves: the same API calls, in colours no theme uses, so each can be looked for in
 * an image of the editor. The client's model says what was handed to a renderer; only its painting says what
 * the renderer painted -- the Error Lens message once came out grey, its colour handed over correctly
 * and then lost while painting.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeWorkbenchTest {

    @Remote("me.helvert.codebridge.language.HostedLanguageServers", plugin = "$PLUGIN_ID/CodeBridge.backend")
    interface HostedLanguageServersRef {
        fun executeCommandForTest(command: String, argumentsJson: String): String?
    }

    @Test
    fun `decorations draw in the client in the extension's colours, and a diff of its revision has text`() {
        val project = Files.createTempDirectory("codebridge-split-workbench")
        // A file a built-in language claims, so it is mirrored into the host: VS Code's `plaintext`
        // comes from its core, not from an extension, and claims nothing here.
        project.resolve("example.json").writeText("{\n  \"first\": 1,\n  \"second\": 2\n}\n")
        val context = Starter.newContext(
            testName = "split-mode-workbench",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.RUNTIME)
        ExtensionStore(context.paths.configDir.resolve("codebridge/extensions")).install(requiredPath("codebridge.workbench.vsix"))
        // Short, for the reason SplitModeExtensionTest gives.
        val clientSystem =
            Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("codebridge.test.work"))), "c")
        context.frontendIDEContext.applyVMOptionsPatch { addSystemProperty("idea.system.path", clientSystem) }
        val screenshots = Files.createDirectories(Path.of(System.getProperty("codebridge.test.work")).resolve("screenshots"))

        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode(), open = "example.json")
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode, "This must run through a JetBrains Client")
            val editor = driver.ideFrame { }.codeEditor()
            awaitValue("example.json in the client's editor", { editor.text }) {
                it == "{\n  \"first\": 1,\n  \"second\": 2\n}\n"
            }
            val servers = driver.service(HostedLanguageServersRef::class, backendProject(driver), RdTarget.BACKEND)
            val client = { driver.cast(editor.editor, EditorExRef::class) }
            val undecorated = decorationState(driver, client())

            // The probe starts with the window; the command answers once its editor is visible to it.
            awaitValue(
                "the probe to decorate example.json",
                { runCatching { servers.executeCommandForTest("probe.decorate", "[]") }.getOrNull() }) {
                it == "1"
            }

            // The model: the blame column before every line, the message after the first.
            awaitValue("the decoration text in the client", {
                driver.withContext(OnDispatcher.EDT) {
                    val inlays = client().getInlayModel()
                    val length = client().getDocument().getTextLength()
                    inlays.getInlineElementsInRange(0, length).size to inlays.getAfterLineEndElementsInRange(
                        0,
                        length
                    ).size
                }
            }) { (before, after) -> before >= 3 && after >= 1 }

            // One scrollbar mark for the three highlighted lines, as VS Code's ruler bands them.
            val marks = driver.withContext(OnDispatcher.EDT) {
                val document = client().getDocument()
                client().getFilteredDocumentMarkupModel().getAllHighlighters()
                    .filter { it.getTextAttributes()?.getErrorStripeColor()?.getRGB()?.and(0xFFFFFF) == RULER }
                    .map { document.getLineNumber(it.getStartOffset()) to document.getLineNumber(it.getEndOffset()) }
            }
            assertEquals(listOf(0 to 2), marks, "The highlighted lines' scrollbar marks")

            // The paint: each colour where the user would see it.
            val image = awaitValue("the probe's colours painted by the client editor", {
                captureEditor(driver, editor, screenshots.resolve("workbench-editor.png"))
            }) { Colours.of(it).all { (_, count) -> count > 0 } }
            val seen = Colours.of(image)
            println("Probe colours in the client editor: $seen")
            assertTrue(
                seen.getValue("message") >= MIN_TEXT_PIXELS,
                "The message is not in the extension's magenta: $seen"
            )
            assertTrue(seen.getValue("column") >= MIN_FILL_PIXELS, "The blame column has no background: $seen")
            assertTrue(seen.getValue("edge") >= MIN_EDGE_PIXELS, "The blame column has no heatmap edge: $seen")
            assertTrue(seen.getValue("overline") >= MIN_EDGE_PIXELS, "The blame column has no line above it: $seen")

            // Replacing a type must remove its old inlays and ruler bands, rather than append to them.
            assertEquals("1", servers.executeCommandForTest("probe.decorate", "[[2]]"))
            val replaced = undecorated.copy(
                before = (undecorated.before + 2).sorted(),
                after = (undecorated.after + 2).sorted(),
                ruler = listOf(2 to 2),
                background = listOf(2 to 2),
            )
            awaitValue("only the replacement decoration on line three", {
                decorationState(driver, client())
            }) { it == replaced }

            assertEquals("1", servers.executeCommandForTest("probe.decorate", "[[]]"))
            awaitValue("empty ranges removing the client's decorations", {
                decorationState(driver, client())
            }) { it == undecorated }

            // Reuse the same types after clearing, then withdraw them while they are painted.
            assertEquals("1", servers.executeCommandForTest("probe.decorate", "[[2]]"))
            awaitValue("decorations restored after clearing", {
                decorationState(driver, client())
            }) { it == replaced }
            assertEquals("true", servers.executeCommandForTest("probe.disposeDecorations", "[]"))
            awaitValue("disposed types removing the client's decorations", {
                decorationState(driver, client())
            }) { it == undecorated }

            // A diff of the probe's own revision, read through the file system it provides.
            assertEquals("true", servers.executeCommandForTest("probe.diff", "[]"))
            awaitValue("a diff editor holding the revision's text", {
                driver.withContext(OnDispatcher.EDT) {
                    driver.service(EditorFactoryRef::class, RdTarget.BACKEND).getAllEditors()
                        .map { it.getDocument().getText() }
                }
            }) { texts -> "revision text\n" in texts }
        } catch (error: Throwable) {
            failure = error
            dumpHostOutput(context, error)
            throw error
        } finally {
            try {
                run.closeIdeAndWait()
            } catch (closeError: Throwable) {
                if (failure == null) throw closeError
                failure.addSuppressed(closeError)
            }
        }
    }

    private data class DecorationState(
        val before: List<Int>,
        val after: List<Int>,
        val ruler: List<Pair<Int, Int>>,
        val background: List<Pair<Int, Int>>,
    )

    private fun decorationState(driver: Driver, editor: EditorExRef): DecorationState =
        driver.withContext(OnDispatcher.EDT) {
            val document = editor.getDocument()
            val inlays = editor.getInlayModel()
            val length = document.getTextLength()
            DecorationState(
                inlays.getInlineElementsInRange(0, length).map { document.getLineNumber(it.getOffset()) }.sorted(),
                inlays.getAfterLineEndElementsInRange(0, length).map { document.getLineNumber(it.getOffset()) }
                    .sorted(),
                editor.getFilteredDocumentMarkupModel().getAllHighlighters()
                    .filter { it.getTextAttributes()?.getErrorStripeColor()?.getRGB()?.and(0xFFFFFF) == RULER }
                    .map { document.getLineNumber(it.getStartOffset()) to document.getLineNumber(it.getEndOffset()) },
                editor.getFilteredDocumentMarkupModel().getAllHighlighters()
                    .filter { it.getTextAttributes()?.getBackgroundColor()?.getRGB()?.and(0xFFFFFF) == BACKGROUND }
                    .map { document.getLineNumber(it.getStartOffset()) to document.getLineNumber(it.getEndOffset()) },
            )
        }

    /** Paint the real client component, without capturing the developer's desktop or OS cursor. */
    private fun captureEditor(driver: Driver, editor: JEditorUiComponent, output: Path): BufferedImage {
        driver.withContext(OnDispatcher.EDT) {
            val component = driver.cast(editor.component, PaintComponentRef::class)
            val image = driver.new(
                BufferedImageRef::class, component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB,
                rdTarget = RdTarget.FRONTEND,
            )
            val graphics = image.createGraphics()
            try {
                component.paintAll(graphics)
            } finally {
                graphics.dispose()
            }
            val file = driver.new(FileRef::class, output.toString(), rdTarget = RdTarget.FRONTEND)
            check(driver.utility(ImageIORef::class, RdTarget.FRONTEND).write(image, "png", file))
        }
        return ImageIO.read(output.toFile())
    }

    @Remote("java.awt.Component")
    interface PaintComponentRef {
        fun getWidth(): Int
        fun getHeight(): Int
        fun paintAll(graphics: GraphicsRef)
    }

    @Remote("java.awt.image.BufferedImage")
    interface BufferedImageRef {
        fun createGraphics(): GraphicsRef
    }

    @Remote("java.awt.Graphics")
    interface GraphicsRef {
        fun dispose()
    }

    @Remote("java.io.File")
    interface FileRef

    @Remote("javax.imageio.ImageIO")
    interface ImageIORef {
        fun write(image: BufferedImageRef, format: String, file: FileRef): Boolean
    }

    /**
     * Counts the probe's colours on the lines holding its blame column. Restricting the rows keeps
     * unrelated syntax colours elsewhere in the editor from satisfying the message and edge checks.
     *
     * The column's green and its edge's blue fill whole pixels. The line above it is one pixel tall,
     * which at a fractional display scale lands between pixel rows and blends with the green under
     * it; and the message is antialiased text. Those two are matched by hue.
     */
    private object Colours {
        fun of(image: BufferedImage): Map<String, Int> {
            val counts = linkedMapOf("message" to 0, "column" to 0, "edge" to 0, "overline" to 0)
            var top = Int.MAX_VALUE
            var bottom = -1
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (image.getRGB(x, y) and 0xFFFFFF == 0x00FF00) {
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            if (bottom < 0) return counts
            for (y in maxOf(0, top - 2)..minOf(image.height - 1, bottom + 2)) for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y) and 0xFFFFFF
                val r = rgb shr 16 and 0xFF
                val g = rgb shr 8 and 0xFF
                val b = rgb and 0xFF
                val name = when {
                    rgb == 0x00FF00 -> "column"
                    rgb == 0x0000FF -> "edge"
                    // Antialiased text blends into what is under it; magenta stays red and blue over green.
                    r - g > 90 && b - g > 90 && abs(r - b) < 70 -> "message"
                    // Yellow, or yellow blended into the green below it.
                    r > 150 && g > 200 && b < 90 -> "overline"
                    else -> null
                } ?: continue
                counts[name] = counts.getValue(name) + 1
            }
            return counts
        }
    }

    @Remote("com.intellij.openapi.editor.ex.EditorEx")
    interface EditorExRef {
        fun getInlayModel(): InlayModelRef
        fun getDocument(): DocumentRef
        fun getFilteredDocumentMarkupModel(): MarkupModelRef
    }

    @Remote("com.intellij.openapi.editor.InlayModel")
    interface InlayModelRef {
        fun getInlineElementsInRange(startOffset: Int, endOffset: Int): List<InlayRef>
        fun getAfterLineEndElementsInRange(startOffset: Int, endOffset: Int): List<InlayRef>
    }

    @Remote("com.intellij.openapi.editor.Inlay")
    interface InlayRef {
        fun getOffset(): Int
    }

    @Remote("com.intellij.openapi.editor.Document")
    interface DocumentRef {
        fun getTextLength(): Int
        fun getLineNumber(offset: Int): Int
        fun getText(): String
    }

    @Remote("com.intellij.openapi.editor.markup.MarkupModel")
    interface MarkupModelRef {
        fun getAllHighlighters(): Array<RangeHighlighterRef>
    }

    @Remote("com.intellij.openapi.editor.markup.RangeHighlighter")
    interface RangeHighlighterRef {
        fun getStartOffset(): Int
        fun getEndOffset(): Int
        fun getTextAttributes(): TextAttributesRef?
    }

    @Remote("com.intellij.openapi.editor.markup.TextAttributes")
    interface TextAttributesRef {
        fun getErrorStripeColor(): ColorRef?
        fun getBackgroundColor(): ColorRef?
    }

    @Remote("java.awt.Color")
    interface ColorRef {
        fun getRGB(): Int
    }

    @Remote("com.intellij.openapi.editor.EditorFactory")
    interface EditorFactoryRef {
        fun getAllEditors(): Array<EditorRef>
    }

    @Remote("com.intellij.openapi.editor.Editor")
    interface EditorRef {
        fun getDocument(): DocumentRef
    }

    /** The project, once the backend has opened it; the client holds none. */
    private fun backendProject(driver: Driver): Project = awaitValue("the backend to open the project", {
        runCatching { driver.singleProject(RdTarget.BACKEND) }.getOrNull()?.takeIf { it.isInitialized() }
    }) { it != null }!!

    private fun dumpHostOutput(context: IDERemDevTestContext, error: Throwable) {
        val hostRoot = context.paths.systemDir.resolve("codebridge/hosts")
        if (!Files.isDirectory(hostRoot)) return
        runCatching {
            Files.walk(hostRoot).use { paths ->
                paths.filter { it.fileName.toString() == "exthost-stdout.log" }.forEach {
                    println("Extension host output ($it):\n${it.readText().takeLast(8_000)}")
                }
            }
        }.onFailure(error::addSuppressed)
    }

    private fun <T> awaitValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        var last: T? = null
        while (System.nanoTime() < deadline) {
            last = read()
            if (matches(last)) return last
            Thread.sleep(500)
        }
        error("Timed out waiting for $description; last observed: $last")
    }

    private companion object {
        const val PLUGIN_ID = "me.helvert.codebridge.runtime"

        /** The probe's overview ruler colour. */
        const val RULER = 0x00BCF2
        const val BACKGROUND = 0x202060

        /** "probe message" is thirteen characters; antialiased, a few pixels of each are near magenta. */
        const val MIN_TEXT_PIXELS = 30

        /** The column is 12ch wide on three lines. */
        const val MIN_FILL_PIXELS = 500

        /** A 3px edge or a 1px line, three lines long or a column wide. */
        const val MIN_EDGE_PIXELS = 20
    }
}
