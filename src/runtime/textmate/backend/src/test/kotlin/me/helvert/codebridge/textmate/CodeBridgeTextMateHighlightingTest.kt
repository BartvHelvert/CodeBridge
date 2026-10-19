package me.helvert.codebridge.textmate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeEvent
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import me.helvert.codebridge.extensions.ExtensionInstallationService
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodeBridgeTextMateHighlightingTest {
    @Test
    fun `install and grammar-only replacement reload syntax and notify editors`() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-grammar").fixture,
        )
        val directory = Files.createTempDirectory("codebridge-grammar-test")
        val path = directory.resolve("example.codebridgegrammar")
        path.writeText("PROBE ordinary \"string\"\n")
        onEdt { fixture.setUp() }
        val service = ExtensionInstallationService.getInstance()
        val fileTypeChanges = AtomicInteger()
        try {
            onEdt {
                // The light fixture doesn't load CodeBridge's descriptor. The split test exercises
                // the real registrations; here the editor and the reload itself are under test.
                TextMateBundleProvider.EP_NAME.point.registerExtension(CodeBridgeTextMateBundles(), fixture.testRootDisposable)
                ApplicationManager.getApplication().messageBus.connect(fixture.testRootDisposable)
                    .subscribe(ExtensionInstallationService.CHANGED, CodeBridgeTextMateInstallationListener())
                ApplicationManager.getApplication().messageBus.connect(fixture.testRootDisposable)
                    .subscribe(FileTypeManager.TOPIC, object : FileTypeListener {
                        override fun fileTypesChanged(event: FileTypeEvent) {
                            fileTypeChanges.incrementAndGet()
                        }
                    })
                fixture.openFileInEditor(checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
            }
            fun colours(): List<Int?> {
                var result = emptyList<Int?>()
                onEdt {
                    // A light fixture owns its editor directly, without PsiAwareTextEditorImpl's
                    // file-type listener. Check the rebuild signal and the highlighter it creates.
                    val highlighter = EditorHighlighterFactory.getInstance()
                        .createEditorHighlighter(fixture.project, fixture.file.virtualFile)
                    highlighter.setText("PROBE ordinary \"string\"\n")
                    result = listOf(0, 6, 17).map { highlighter.createIterator(it).textAttributes.foregroundColor?.rgb }
                }
                return result
            }

            val vsix = Path.of(checkNotNull(System.getProperty("codebridge.textmate.vsix")))
            val first = service.install(vsix) {}.extension
            val originalColours = awaitValue("initial grammar", ::colours) {
                it[0] != null && it[2] != null && it.distinct().size == 3
            }

            // The fixture's own entries, not the IDE's ~1900. Builtin bundles disagree among
            // themselves about a few names -- two of them claim `.jsp` -- and which one wins is
            // settled per reload rather than fixed, so comparing the whole mapping asserts that
            // coin flip alongside the thing under test.
            fun probeMapping() = TextMateService.getInstance().fileNameMatcherToScopeNameMapping
                .filterValues { it.contains("codebridgegrammar") }

            val mapping = probeMapping()
            assertTrue(mapping.isNotEmpty(), "Without the fixture's own entries there is nothing to compare")
            val initialChanges = fileTypeChanges.get()

            // Same extension version and filename/scope mapping, but PROBE changes to string colour.
            val replacement = directory.resolve("replacement.vsix")
            ZipFile(vsix.toFile()).use { source ->
                ZipOutputStream(Files.newOutputStream(replacement)).use { target ->
                    source.entries().asSequence().forEach { entry ->
                        target.putNextEntry(ZipEntry(entry.name))
                        val bytes = source.getInputStream(entry).use { it.readAllBytes() }
                        target.write(
                            if (entry.name.endsWith(".tmLanguage.json")) {
                                bytes.toString(Charsets.UTF_8)
                                    .replace("keyword.control.codebridgegrammar", "string.quoted.double.codebridgegrammar")
                                    .toByteArray()
                            } else bytes
                        )
                        target.closeEntry()
                    }
                }
            }
            val second = service.install(replacement) {}.extension
            assertEquals(first.manifest.version, second.manifest.version)
            assertNotEquals(first.directory, second.directory)
            awaitValue("replacement grammar and editor notification", ::colours) {
                it[0] == originalColours[2] && it[2] == originalColours[2] && fileTypeChanges.get() > initialChanges
            }
            assertEquals(mapping, probeMapping())
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
