package me.helvert.codebridge.textmate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.language.CodeBridgeFileLanguageProvider
import me.helvert.codebridge.language.CodeBridgeLanguageFileTypeOverrider
import org.jetbrains.plugins.textmate.TextMateLanguage
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals

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
    fun `a claimed file is typed as its language and keeps its grammar`() {
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
                // two plugin XMLs make have to be stood up by hand -- including the point itself,
                // which is declared in `CodeBridge.backend.xml`.
                val area = ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl
                area.registerExtensionPoint(
                    CodeBridgeFileLanguageProvider.EP_NAME, CodeBridgeFileLanguageProvider::class.java.name,
                    ExtensionPoint.Kind.INTERFACE, fixture.testRootDisposable,
                )
                CodeBridgeFileLanguageProvider.EP_NAME.point
                    .registerExtension(CodeBridgeTextMateFileLanguage(), fixture.testRootDisposable)
                FileTypeOverrider.EP_NAME.point
                    .registerExtension(CodeBridgeLanguageFileTypeOverrider(), fixture.testRootDisposable)
                TextMateBundleProvider.EP_NAME.point
                    .registerExtension(CodeBridgeTextMateBundles(), fixture.testRootDisposable)
                ApplicationManager.getApplication().messageBus.connect(fixture.testRootDisposable)
                    .subscribe(ExtensionInstallationService.CHANGED, CodeBridgeTextMateInstallationListener())
                fixture.openFileInEditor(checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
            }
            service.install(Path.of(checkNotNull(System.getProperty("codebridge.textmate.vsix")))) {}

            // Installing reloads the bundles on a background dispatcher, so both halves arrive when
            // they arrive: the claim as soon as the manifest is in the store, the grammar behind it.
            val observed = awaitValue("the claimed file type and its grammar", ::state) {
                it.type == "CodeBridge.codebridgegrammar" && it.colours.distinct().size == 3
            }
            assertEquals("CodeBridge Grammar", observed.label)
            assertEquals(TextMateLanguage.LANGUAGE.id, observed.language)
        } finally {
            service.uninstall("codebridge.textmate-probe")
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

    private fun onEdt(block: () -> Unit) = runInEdtAndWait(block)
}
