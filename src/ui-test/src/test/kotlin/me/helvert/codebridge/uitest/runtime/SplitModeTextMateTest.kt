package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.remote.ColorRef
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.uitest.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backend syntax colours reach the client as range markup, not through its local lexer. */
@ExtendWith(RemoteDevRun::class)
class SplitModeTextMateTest {
    @Test
    fun `backend grammar colours the client and follows enablement`() {
        val project = Files.createTempDirectory("codebridge-split-grammar")
        project.resolve("example.codebridgegrammar").writeText("PROBE ordinary \"string\"\n")
        val context = Starter.newContext(
            testName = "split-mode-textmate",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.RUNTIME)
        ExtensionStore(context.paths.configDir.resolve("codebridge/extensions"))
            .install(requiredPath("codebridge.textmate.vsix"))
        context.useShortClientSystemDir()

        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = "example.codebridgegrammar",
        )
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode)
            val backend = driver.service(TextMateServiceRef::class, RdTarget.BACKEND)
            backend.startInitialization()
            awaitValue("backend grammar registration", {
                backend.getLanguageDescriptorByFileName("example.codebridgegrammar")
            }) { it != null }
            val frontend = driver.service(TextMateServiceRef::class, RdTarget.FRONTEND)
            frontend.startInitialization()
            assertNull(
                frontend.getLanguageDescriptorByFileName("example.codebridgegrammar"),
                "The client must have no fixture bundle"
            )

            val editor = driver.ideFrame { }.codeEditor()
            assertEquals("PROBE ordinary \"string\"\n", editor.text)
            fun colours(): List<Int?> = driver.withContext(OnDispatcher.EDT) {
                val client = driver.cast(editor.editor, EditorExRef::class)
                val ranges = client.getMarkupModel().getAllHighlighters() +
                        client.getFilteredDocumentMarkupModel().getAllHighlighters()
                // Keyword, unscoped identifier, string. The fixture has no diagnostics or host
                // entry point; these foreground colours can only come from the installed grammar.
                listOf(0, 6, 17).map { offset ->
                    ranges.filter { it.getStartOffset() <= offset && offset < it.getEndOffset() }
                        .firstNotNullOfOrNull { it.getTextAttributes()?.getForegroundColor()?.getRGB() }
                }
            }
            awaitValue("client syntax colours", ::colours) { it[0] != null && it[2] != null && it.distinct().size == 3 }
            driver.withContext(OnDispatcher.EDT) {
                // Check the language's type on the backend: client files use ThinClientFileType.
                // TextMate would have claimed
                // this name -- it has the grammar for it -- and called the file a TextMate file;
                // `CodeBridgeLanguageFileTypeOverrider` takes it back, and the colours asserted just above
                // are the proof that taking it back cost the grammar nothing.
                assertEquals(
                    "CodeBridge.codebridgegrammar",
                    driver.utility(LocalFileSystemRef::class, RdTarget.BACKEND).getInstance()
                        .findFileByPath(project.resolve("example.codebridgegrammar").invariantSeparatorsPathString)!!
                        .getFileType().getName()
                )
            }

            val installations = driver.service(InstallationsRef::class, RdTarget.BACKEND)
            assertTrue(installations.setEnabled("codebridge.textmate-probe", false))
            awaitValue("removed syntax colours after disable", ::colours) { it.all { colour -> colour == null } }
            assertTrue(installations.setEnabled("codebridge.textmate-probe", true))
            awaitValue(
                "restored syntax colours after enable",
                ::colours
            ) { it[0] != null && it[2] != null && it.distinct().size == 3 }
            assertTrue(installations.uninstall("codebridge.textmate-probe"))
            awaitValue("removed syntax colours after uninstall", ::colours) { it.all { colour -> colour == null } }
            assertNull(frontend.getLanguageDescriptorByFileName("example.codebridgegrammar"))
        } catch (error: Throwable) {
            failure = error
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

    private fun <T> awaitValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var last: T
        do {
            last = read()
            if (matches(last)) {
                println("$description: $last")
                return last
            }
            Thread.sleep(300)
        } while (System.nanoTime() < deadline)
        error("Timed out waiting for $description; last observed: $last")
    }

    @Remote(
        "me.helvert.codebridge.extensions.ExtensionInstallationService",
        plugin = "me.helvert.codebridge.runtime/CodeBridge.backend"
    )
    interface InstallationsRef {
        fun setEnabled(id: String, enabled: Boolean): Boolean
        fun uninstall(id: String): Boolean
    }

    @Remote(
        "org.jetbrains.plugins.textmate.TextMateService",
        plugin = "org.jetbrains.plugins.textmate/intellij.textmate"
    )
    interface TextMateServiceRef {
        fun startInitialization()
        fun getLanguageDescriptorByFileName(name: CharSequence): LanguageDescriptorRef?
    }

    @Remote(
        "org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor",
        plugin = "org.jetbrains.plugins.textmate/intellij.textmate"
    )
    interface LanguageDescriptorRef

    @Remote("com.intellij.openapi.editor.ex.EditorEx")
    interface EditorExRef {
        fun getMarkupModel(): MarkupModelRef
        fun getFilteredDocumentMarkupModel(): MarkupModelRef
    }

    @Remote("com.intellij.openapi.vfs.LocalFileSystem")
    interface LocalFileSystemRef {
        fun getInstance(): LocalFileSystemRef
        fun findFileByPath(path: String): VirtualFileRef?
    }

    @Remote("com.intellij.openapi.editor.markup.TextAttributes")
    interface TextAttributesRef {
        fun getForegroundColor(): ColorRef?
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

    @Remote("com.intellij.openapi.vfs.VirtualFile")
    interface VirtualFileRef {
        fun getFileType(): FileTypeRef
    }

    @Remote("com.intellij.openapi.fileTypes.FileType")
    interface FileTypeRef {
        fun getName(): String
    }
}
