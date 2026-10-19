package me.helvert.codebridge.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.VsCodeInstallation
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeEditorCreatedListener
import me.helvert.codebridge.settings.BundledRuntime
import me.helvert.codebridge.settings.VsCodeRuntime
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Serving files that are on screen but were never reported as opened.
 *
 * The bug: a split-mode project reopened with two `.rs` tabs restored by the client, and the backend
 * logged `beforeFileOpened` for both and never `fileOpened`. Everything here used to start from that
 * one event, so no host started, nothing was highlighted, and nothing in the log said why. The
 * editors themselves were created, which is what [CodeBridgeEditorCreatedListener] now watches.
 *
 * A host cannot start in a test, which is what makes this observable: a file that reaches the
 * service is queued waiting for a runtime, and one that does not never appears in the queue.
 */
class FileShownTest : EditorFixture() {

    private val files = mutableListOf<VirtualFile>()
    private val installed = mutableListOf<String>()
    private var runtimeDirectory: String? = null
    private var runtimeHidden = false

    @BeforeEach
    fun withoutARuntime() {
        // With a runtime these would start a real extension host rather than queue the file. The
        // build hands every test JVM the runtime it unpacked, so it is pointed somewhere empty here;
        // the lookup is not cached, so this holds from the next call on. An environment override
        // cannot be taken away from inside the JVM, so with one the tests cannot run.
        assumeTrue(System.getenv(VsCodeInstallation.OVERRIDE_ENV).isNullOrBlank(), "${VsCodeInstallation.OVERRIDE_ENV} names a runtime")
        runtimeDirectory = System.getProperty(BundledRuntime.DIRECTORY_PROPERTY)
        runtimeHidden = true
        System.setProperty(BundledRuntime.DIRECTORY_PROPERTY, Files.createTempDirectory("codebridge-no-runtime").resolve("absent").toString())
        check(VsCodeRuntime.getInstance().installationOrNull() == null) { "The runtime is still found" }
        onEdt {
            // The listener is declared in XML, which a light fixture does not load;
            // `DocumentSyncTest` holds the declaration in place.
            EditorFactory.getInstance().addEditorFactoryListener(CodeBridgeEditorCreatedListener(), fixture.testRootDisposable)
            install("codebridgeshown")
            // Installing queues a stop of the host on the service's worker, which drops the document
            // mirror. Left to land whenever it does, it could wipe a mirror a test had just set up,
            // and the file would then rightly be served again. Asking the worker anything waits it out.
            servers.requestedFiles()
        }
    }

    @AfterEach
    fun forget() = onEdt {
        // A light fixture can share its project, and with it this service, between tests.
        files.forEach(servers::fileClosed)
        features.hostStopped()
        installed.forEach(ExtensionInstallationService.getInstance()::uninstall)
        if (runtimeHidden) runtimeDirectory.let {
            if (it == null) System.clearProperty(BundledRuntime.DIRECTORY_PROPERTY) else System.setProperty(BundledRuntime.DIRECTORY_PROPERTY, it)
        }
    }

    @Test
    fun `an editor for a claimed file serves it with no open event at all`() = onEdt {
        val file = file("restored.codebridgeshown")

        // Straight from the editor factory, as a restored tab's editor is: no file editor manager,
        // so nothing publishes `fileOpened`.
        withEditor(file, EditorKind.MAIN_EDITOR) {}

        assertTrue(file in servers.requestedFiles(), "The file behind a new editor must be served")
    }

    @Test
    fun `diff panes and previews are not files the user opened`() = onEdt {
        val file = file("compared.codebridgeshown")

        withEditor(file, EditorKind.DIFF) {}
        withEditor(file, EditorKind.PREVIEW) {}

        assertFalse(file in servers.requestedFiles(), "Only a main editor means the file is open")
    }

    @Test
    fun `an editor for a file no extension claims is left alone`() = onEdt {
        val file = file("plain.unclaimed")

        withEditor(file, EditorKind.MAIN_EDITOR) {}

        assertFalse(file in servers.requestedFiles())
    }

    @Test
    fun `showing a file the host already holds does not serve it again`() = onEdt {
        // Runs on every tab switch and every new editor, and serving a file again re-sends its
        // whole text -- or here, with no runtime, stops the host and drops the mirror entirely.
        val file = file("mirrored.codebridgeshown")
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "codebridgeshown", HostedLanguageFeatures.Snapshot("text", dirty = false))

        servers.fileShown(file)

        assertFalse(file in servers.requestedFiles(), "A mirrored file must not be attached again")
        assertTrue(features.isMirrored(file), "And the mirror it already has must survive")
    }

    @Test
    fun `an extension installed after a file opened claims that file`() = onEdt {
        // The bug: `.rs` tabs open before rust-analyzer was installed. Installing it offered again
        // only the files something had claimed already, which none of these were, so no host
        // started until a tab was reopened.
        val file = file("early.codebridgelate")
        withEditor(file, EditorKind.MAIN_EDITOR) {
            assertFalse(file in servers.requestedFiles(), "Nothing claims the file yet")

            install("codebridgelate")

            assertTrue(file in servers.requestedFiles(), "The new extension's language must reach the open file")
        }
    }

    private fun withEditor(file: VirtualFile, kind: EditorKind, block: () -> Unit) {
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(document, fixture.project, kind)
        try {
            block()
        } finally {
            factory.releaseEditor(editor)
        }
    }

    private fun file(name: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText("content") }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)).also(files::add)
    }

    /** An extension claiming files named `*.[language]`, with code to activate, as a language extension has. */
    private fun install(language: String) {
        val name = "probe-$language"
        val manifest = buildJsonObject {
            put("publisher", "codebridge"); put("name", name); put("version", "1.0.0")
            put("main", "main.js")
            put("engines", buildJsonObject { put("vscode", "^1.90.0") })
            put("extensionDependencies", JsonArray(emptyList()))
            put("activationEvents", buildJsonArray { add(JsonPrimitive("onLanguage:$language")) })
            put("contributes", buildJsonObject {
                put("languages", buildJsonArray {
                    add(buildJsonObject {
                        put("id", language)
                        put("extensions", buildJsonArray { add(JsonPrimitive(".$language")) })
                    })
                })
            })
        }
        @Suppress("HttpUrlsUsage") // an XML namespace, not a link
        val xml = """<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">""" +
                """<Metadata><Identity Id="$name"/></Metadata></PackageManifest>"""
        val vsix = Files.createTempFile(directory, "probe-", ".vsix")
        ZipArchiveOutputStream(vsix).use { zip ->
            for ((name, bytes) in listOf(
                "extension/package.json" to manifest.toString().toByteArray(),
                "extension.vsixmanifest" to xml.toByteArray(),
                "extension/main.js" to "module.exports = {}".toByteArray(),
            )) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        ExtensionInstallationService.getInstance().install(vsix) {}
        installed += "codebridge.$name"
    }

    private val servers get() = fixture.project.service<HostedLanguageServers>()

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-file-shown") }
}
