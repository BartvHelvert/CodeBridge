package me.helvert.codebridge.textmate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.language.CodeBridgeLanguageFileTypeOverrider
import org.jetbrains.plugins.textmate.TextMateLanguage
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file an extension claims is named after its language and still read by TextMate.
 *
 * These two pull against each other, which is why they are asserted together. TextMate claims every
 * name it has a grammar for, ahead of anything else the platform would ask, and calls all of them
 * `textmate`; taking the file type back is what puts the extension's own language name on the file.
 * But everything the file had came from TextMate, so taking it back is only worth doing if the
 * grammar still reaches it -- which it does through the language the file type carries, not through
 * the file type itself.
 */
class CodeBridgeTextMateFileTypeTest {
    private lateinit var fixture: CodeInsightTestFixture

    @Test
    fun `a claimed file is typed as its language and keeps its grammar`() = withClaimedFile { observed ->
        assertEquals("CodeBridge Grammar", observed.label)
        assertEquals(TextMateLanguage.LANGUAGE.id, observed.language)
    }

    /**
     * A lookup for some other type, made after the extension is disabled but before its grammar is
     * unloaded, must not leave the file typed `textmate`.
     *
     * `isFileOfType` runs content detection without asking `TextMateFileType` first, TextMate's
     * detector answers from the grammars it has loaded, and the platform keeps that answer in the
     * file's user data, which no file-type change clears. When the claim was released at once and
     * the grammar unloaded later, a lookup in between left the file `textmate` for good, coloured
     * without a grammar -- what failed `SplitModeTextMateTest` now and then.
     */
    @Test
    fun `a file released while its grammar is still loaded is not left typed textmate`() = withClaimedFile {
        val file = fixture.file.virtualFile
        // Hold the EDT, where the change is applied, so the lookup below lands before it does.
        val holding = CountDownLatch(1)
        val held = CountDownLatch(1)
        ApplicationManager.getApplication().invokeLater({
            holding.countDown()
            held.await()
        }, ModalityState.any())
        assertTrue(holding.await(20, TimeUnit.SECONDS))
        try {
            assertTrue(ExtensionInstallationService.getInstance().setEnabled(PROBE, false))
            ReadAction.computeBlocking<Boolean, RuntimeException> {
                FileTypeManager.getInstance().isFileOfType(file, PlainTextFileType.INSTANCE)
            }
        } finally {
            held.countDown()
        }

        awaitValue<String>("the released file's type", { typeName(file) }) { it == PlainTextFileType.INSTANCE.name }
    }

    /** Installs the probe for a file opened in the editor, and runs [test] once the file is claimed and coloured. */
    private fun withClaimedFile(test: (State) -> Unit) {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-file-type").fixture,
        )
        val path = Files.createTempDirectory("codebridge-file-type-test").resolve("example.codebridgegrammar")
        path.writeText("PROBE ordinary \"string\"\n")
        onEdt { fixture.setUp() }
        val service = ExtensionInstallationService.getInstance()
        try {
            onEdt {
                // The light fixture doesn't load CodeBridge's descriptor, so the registrations the
                // two plugin XMLs make have to be stood up by hand.
                registerFileLanguage(fixture.testRootDisposable)
                FileTypeOverrider.EP_NAME.point
                    .registerExtension(CodeBridgeLanguageFileTypeOverrider(), fixture.testRootDisposable)
                TextMateBundleProvider.EP_NAME.point
                    .registerExtension(CodeBridgeTextMateBundles(), fixture.testRootDisposable)
                fixture.openFileInEditor(checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
            }
            service.install(Path.of(checkNotNull(System.getProperty("codebridge.textmate.vsix")))) {}

            // Installed off the EDT, the claim and the grammar arrive together once the EDT gets to them.
            test(awaitValue("the claimed file type and its grammar", ::state) {
                it.type == "CodeBridge.codebridgegrammar" && it.colours.distinct().size == 3
            })
        } finally {
            service.uninstall(PROBE)
            awaitBundleGone()
            onEdt { fixture.tearDown() }
        }
    }

    /**
     * Waits for the bundle reload the uninstall queued, without failing on it.
     *
     * The reload runs on a background dispatcher and the TextMate service it reloads is the
     * application's one, shared with every other test in this JVM. Left in flight, it lands in
     * whichever test runs next and takes its grammar away mid-assertion. Quiet on timeout: this
     * runs in a `finally`, where raising would hide the failure that sent us here.
     */
    private fun awaitBundleGone() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (TextMateService.getInstance().getLanguageDescriptorByFileName("example.codebridgegrammar") == null) return
            Thread.sleep(50)
        }
    }

    private data class State(val type: String, val label: String, val language: String, val colours: List<Int?>)

    /** What the editor makes of the file right now: its type, and the colours the grammar gives it. */
    private fun state(): State {
        lateinit var result: State
        onEdt {
            val file = fixture.file.virtualFile
            val type = file.fileType
            // A light fixture owns its editor directly, without the file-type listener that would
            // rebuild the highlighter; ask for a fresh one, as the highlighting test does.
            val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(fixture.project, file)
            highlighter.setText("PROBE ordinary \"string\"\n")
            result = State(
                type = type.name,
                label = type.displayName,
                language = fixture.file.language.id,
                colours = listOf(0, 6, 17).map { highlighter.createIterator(it).textAttributes.foregroundColor?.rgb },
            )
        }
        return result
    }

    @Suppress("SameParameterValue")
    private fun <T> awaitValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var last: T
        do {
            last = read()
            if (matches(last)) return last
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        error("Timed out waiting for $description; last observed: $last")
    }

    private fun typeName(file: VirtualFile): String {
        lateinit var name: String
        onEdt { name = file.fileType.name }
        return name
    }

    private fun onEdt(block: () -> Unit) = runInEdtAndWait(block)

    private companion object {
        const val PROBE = "codebridge.textmate-probe"
    }
}
