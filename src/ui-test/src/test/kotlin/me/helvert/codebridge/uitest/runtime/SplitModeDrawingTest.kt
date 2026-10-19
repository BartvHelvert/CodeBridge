package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.uitest.Installed
import me.helvert.codebridge.uitest.builtAgainst
import me.helvert.codebridge.uitest.install
import me.helvert.codebridge.uitest.runSplitIde
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * Does a problem computed on the backend reach the editor the user is looking at?
 *
 * This is the question the split endangers, and the one `SplitModeSmokeTest` deliberately does not
 * ask. `DiagnosticsHighlightingTest` already proves the payload becomes a highlight *in a single
 * process*; what no in-process fixture can show is whether that highlight survives the trip to a
 * JetBrains Client, because the module computing it is one the client never loads.
 *
 * The push is hand-fed through the backend service rather than earned from a real extension, for the
 * same reason `DiagnosticsHighlightingTest` hand-feeds it: the extension host is a separate axis,
 * covered by `LanguageFeaturesIntegrationTest`, and mixing the two would leave a failure ambiguous
 * between "the host did not answer" and "the client did not draw". A plain `.txt` file is used for
 * the same reason -- claiming a `.codebridgebundled` file needs an installed extension, which needs a
 * running host.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeDrawingTest {

    /**
     * The backend half of the plugin, reached across the driver.
     *
     * Two things have to be right, and each was wrong once. `RdTarget.BACKEND`, because that is
     * where the service lives; the client holds no copy of it. And the plugin coordinate has to
     * name the *content module*, not just the plugin -- `me.helvert.codebridge` alone resolves to
     * the main plugin's classloader, which cannot see a class in `CodeBridge.backend` and answers
     * "No such class". The driver splits this on `/` and looks the module up by name.
     */
    @Remote("me.helvert.codebridge.language.HostedLanguageFeatures", plugin = "$PLUGIN_ID/CodeBridge.backend")
    interface HostedLanguageFeaturesRef {
        fun handle(service: String, method: String, argsJson: String): Boolean
        fun drawColourForTest(path: String, line: Int, character: Int, length: Int, type: String): Boolean
    }

    @Test
    fun `a problem pushed on the backend is drawn in the client`() {
        val project = projectWith("example.txt", "ERROR replace this word\nsecond line")

        val context = Starter.newContext(
            testName = "split-mode-drawing",
            testCase = builtAgainst(project),
        )
        (context as IDERemDevTestContext).install(Installed.RUNTIME)

        // Opened by a command inside the IDE rather than through the driver. The driver's own
        // `openFile` resolves the path against a project the *client* cannot see, and fails in the
        // IDE with `IllegalStateException at ProjectUtil.kt:29`.
        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = "example.txt",
        )
        try {
            val driver = run.driver
            // The backend owns the project. Asking the client for it answers "No projects are
            // opened", which is not a failure -- it is the split being exactly what it claims.
            val opened = backendProject(driver)
            push(driver, opened, project.resolve("example.txt"))

            // Polled: the push restarts the daemon, the annotator then runs on the backend, and the
            // markup it produces has to reach the client. None of that is synchronous with the call.
            val drawn = await(driver) { it.contains(MESSAGE) }
            assertTrue(drawn.isNotEmpty(), "The client drew no highlight carrying '$MESSAGE'")
        } finally {
            run.closeIdeAndWait()
        }
    }

    /**
     * Semantic colour is not an annotation: it is put straight onto the backend document as range
     * highlighters, so that an answer is drawn without restarting the daemon -- which waits 300ms and
     * then runs every pass. Annotations are known to reach the client; this is what holds that
     * highlighters placed on the backend document do too.
     */
    @Test
    fun `a colour drawn on the backend document is drawn in the client`() {
        val project = projectWith("example.txt", "ERROR replace this word\nsecond line")

        val context = Starter.newContext(
            testName = "split-mode-colour",
            testCase = builtAgainst(project),
        )
        (context as IDERemDevTestContext).install(Installed.RUNTIME)

        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = "example.txt",
        )
        try {
            val driver = run.driver
            val opened = backendProject(driver)
            val features = driver.service(HostedLanguageFeaturesRef::class, opened, RdTarget.BACKEND)
            // `replace`: line 0, character 6, seven long. A keyword, because every scheme gives
            // keywords a colour of their own, and a plain-text file has no other colour to confuse
            // it with.
            val file = project.resolve("example.txt").toAbsolutePath().toString()
            val editor = driver.ideFrame { }.codeEditor()
            fun colour(): String? = driver.withContext(OnDispatcher.EDT) {
                val client = driver.cast(editor.editor, EditorExRef::class)
                (client.getMarkupModel().getAllHighlighters() + client.getFilteredDocumentMarkupModel()
                    .getAllHighlighters())
                    .filter { it.getStartOffset() <= 6 && 13 <= it.getEndOffset() }
                    .firstNotNullOfOrNull { highlighter ->
                        highlighter.getTextAttributesKey()?.getExternalName()
                            ?: highlighter.getTextAttributes()?.getForegroundColor()?.getRGB()?.toString()
                    }
            }
            // Asked until it lands: the backend editor may still be opening when the driver connects.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var seen: String? = null
            while (System.nanoTime() < deadline && seen == null) {
                assertTrue(features.drawColourForTest(file, 0, 6, 7, "keyword"), "The backend could not find $file")
                Thread.sleep(500)
                seen = colour()
            }
            println("client colour over `replace`: $seen")
            assertTrue(seen != null, "The client drew nothing over the token the backend coloured")
        } finally {
            run.closeIdeAndWait()
        }
    }

    @Remote("com.intellij.openapi.editor.ex.EditorEx")
    interface EditorExRef {
        fun getMarkupModel(): MarkupModelRef
        fun getFilteredDocumentMarkupModel(): MarkupModelRef
    }

    @Remote("com.intellij.openapi.editor.markup.MarkupModel")
    interface MarkupModelRef {
        fun getAllHighlighters(): Array<RangeHighlighterRef>
    }

    @Remote("com.intellij.openapi.editor.markup.RangeHighlighter")
    interface RangeHighlighterRef {
        fun getStartOffset(): Int
        fun getEndOffset(): Int
        fun getTextAttributesKey(): TextAttributesKeyRef?
        fun getTextAttributes(): TextAttributesRef?
    }

    @Remote("com.intellij.openapi.editor.colors.TextAttributesKey")
    interface TextAttributesKeyRef {
        fun getExternalName(): String
    }

    @Remote("com.intellij.openapi.editor.markup.TextAttributes")
    interface TextAttributesRef {
        fun getForegroundColor(): ColorRef?
    }

    @Remote("java.awt.Color")
    interface ColorRef {
        fun getRGB(): Int
    }

    /**
     * `$changeMany(owner, [[uri, markers]])`, spelled the way VS Code spells it.
     *
     * Deliberately not built with the plugin's own URI helper: that is a function under test
     * elsewhere, and using it here would let both sides be wrong in the same way.
     */
    private fun push(driver: Driver, project: Project, file: Path) {
        val path = "/" + file.toAbsolutePath().toString().replace(File.separatorChar, '/').trimStart('/')
        val payload = """[1,[[{"scheme":"file","authority":"","path":"$path","query":"","fragment":""},""" +
                """[{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":6,"severity":8,""" +
                """"message":"$MESSAGE","source":"CodeBridge prototype"}]]]]"""
        val features = driver.service(HostedLanguageFeaturesRef::class, project, RdTarget.BACKEND)
        assertTrue(
            features.handle("MainThreadDiagnostics", $$"$changeMany", payload),
            "The backend service did not accept the push",
        )
    }

    /**
     * The project, once the backend has opened it.
     *
     * Opening is not finished when the driver connects, and the client attaches to it later still,
     * so the first few asks legitimately find nothing.
     */
    private fun backendProject(driver: Driver): Project {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            runCatching { driver.singleProject(RdTarget.BACKEND) }
                .getOrNull()?.takeIf { it.isInitialized() }?.let { return it }
            Thread.sleep(500)
        }
        error("The backend never opened a project")
    }

    /** The highlight descriptions the client's editor holds, once one of them matches. */
    private fun await(driver: Driver, matches: (String) -> Boolean): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var seen = emptyList<String>()
        while (System.nanoTime() < deadline) {
            seen = driver.ideFrame { }.codeEditor().getAllHighlights().mapNotNull { it.getDescription() }
            if (seen.any(matches)) return seen
            Thread.sleep(500)
        }
        return seen.filter(matches)
    }

    /** A throwaway project, so the repository's own demo is not edited by a test run. */
    @Suppress("SameParameterValue")
    private fun projectWith(name: String, text: String): Path {
        val directory = Files.createTempDirectory("codebridge-split-drawing")
        directory.resolve(name).writeText(text)
        return directory
    }

    private companion object {
        const val PLUGIN_ID = "me.helvert.codebridge.runtime"
        const val MESSAGE = "Replace ERROR to clear this diagnostic"
    }
}
