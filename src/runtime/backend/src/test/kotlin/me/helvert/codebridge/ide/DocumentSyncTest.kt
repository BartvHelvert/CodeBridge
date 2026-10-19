package me.helvert.codebridge.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.HostedStartupExtensions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor half of document synchronization, in a real IDE.
 *
 * `ExtensionHostDocumentsTest` covers the payloads and `LanguageFeaturesIntegrationTest` proves they reach a
 * real extension's server. Neither can see the part that only exists inside an IDE: which platform
 * signal reports an edit, that a save is a separate event from the edit before it, and that the
 * whole path is driven off the editor thread. That is where the mirror stops matching the buffer,
 * and an extension answering from stale text looks exactly like an extension that is simply wrong.
 *
 * A host cannot be started here, so the fixture records what would have been sent to one.
 */
class DocumentSyncTest : EditorFixture() {

    @BeforeEach
    fun mirrorWithoutAHost() = onEdt {
        sent.clear()
        features.hostStopped()
        features.mirrorForTest(ExtensionHostDocuments { service, method, argsJson ->
            sent.add("$service.$method" to Json.parseToJsonElement(argsJson).jsonArray)
            ""
        })
        // Creating the service is what registers the document and save listeners.
        fixture.project.service<HostedLanguageServers>()
    }

    @Test
    fun `the plugin registers the editor listener the close path needs`() {
        // Registered in XML, so nothing in this fixture would notice it being deleted.
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(
            descriptor.contains("""class="me.helvert.codebridge.language.CodeBridgeFileOpenListener""""),
            "Opening and closing a file both arrive through this listener",
        )
        // The backstop for tabs restored without a `fileOpened` event, which is how a reopened
        // split-mode project once came up with every file in it unserved and nothing in the log.
        assertTrue(
            descriptor.contains("""<editorFactoryListener implementation="me.helvert.codebridge.language.CodeBridgeEditorCreatedListener"/>"""),
            "Files a project reopens with must be served even when their open is never reported",
        )
        // GitLens and Error Lens run with the window in VS Code, whether or not a file is open.
        assertTrue(
            descriptor.contains("""<postStartupActivity implementation="${HostedStartupExtensions::class.java.name}"/>"""),
            "Extensions that run at startup must start with the project",
        )
    }

    @Test
    fun `typing reaches the extension host`() = onEdt {
        open("hello from IntelliJ")

        type("!")

        val change = await($$"ExtHostDocuments.$acceptModelChanged")
        assertEquals(
            "!", change[1].jsonObject.getValue("changes").jsonArray
                .single().jsonObject.getValue("text").jsonPrimitive.content
        )
        assertEquals(2, change[1].jsonObject.getValue("versionId").jsonPrimitive.int)
        assertEquals(true, change[2].jsonPrimitive.boolean, "An unsaved buffer is dirty")
    }

    @Test
    fun `an edit to a file no extension claimed is not sent`() = onEdt {
        // Both listeners are application-wide -- the platform has no project-scoped document
        // listener -- so every keystroke in every project arrives, and almost none of them matter.
        open("hello from IntelliJ", mirror = false)

        type("!")

        Thread.sleep(QUIET_MILLIS)
        assertTrue(sent.isEmpty(), "Nothing was mirrored, so there is nothing to synchronize: $sent")
    }

    @Test
    fun `saving is reported after the edit it saves`() = onEdt {
        open("hello from IntelliJ")
        type("!")
        await($$"ExtHostDocuments.$acceptModelChanged")

        FileDocumentManager.getInstance().saveDocument(fixture.editor.document)
        await($$"ExtHostDocuments.$acceptModelSaved")

        // Order matters: a save reported before the edit it saves would have the extension re-read
        // a file it has not yet been told changed.
        assertEquals(
            listOf(
                $$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta",
                $$"ExtHostDocuments.$acceptModelChanged",
                $$"ExtHostDocuments.$acceptModelSaved",
            ),
            documentCalls(),
        )
    }

    @Test
    fun `with afterDelay an edited document is saved once it has been quiet`() = onEdt {
        // However the edit was made -- typing, a completion, a reformat all arrive as this -- the
        // extension's on-disk analysis only catches up once the file is saved.
        autosaveAfter(300)
        open("hello from IntelliJ")
        type("!")
        val document = fixture.editor.document
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document), "Not on every keystroke")

        assertTrue(waitFor(300 + 5_000) { !FileDocumentManager.getInstance().isDocumentUnsaved(document) })
        // The platform finishes writing a saved document asynchronously.
        waitFor(5_000) { runCatching { Files.readString(path) }.getOrNull() == "hello from IntelliJ!" }
        assertEquals("hello from IntelliJ!", Files.readString(path))
        await($$"ExtHostDocuments.$acceptModelSaved")
        assertEquals(
            $$"ExtHostDocuments.$acceptModelSaved",
            documentCalls().last(),
            "Saved after the edit it saves: $sent"
        )
    }

    @Test
    fun `with autoSave off saving is left to the IDE`() = onEdt {
        // On a large project every pause would restart a long `cargo check`, and a dev server or
        // test watcher reacts to every save.
        autosaveAfter(0)
        open("hello from IntelliJ")
        type("!")
        waitFor(HostedLanguageServers.AUTOSAVE_DELAY_MILLIS * 2) { false }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(fixture.editor.document))
    }

    @Test
    fun `the save delay comes from VS Code's settings`() {
        fun delay(settings: String) = HostedLanguageServers.autosaveMillis(Json.parseToJsonElement(settings).jsonObject)
        assertEquals(300, delay("{}"), "On unless the settings turn it off")
        assertEquals(1000, delay("""{"files.autoSaveDelay": 1000}"""))
        assertEquals(0, delay("""{"files.autoSave": "off", "files.autoSaveDelay": 300}"""))
        assertEquals(0, delay("""{"files.autoSave": "onFocusChange"}"""), "What IntelliJ does already")
        assertEquals(300, delay("""{"files.autoSave": "afterDelay"}"""))
        assertEquals(1000, delay("""{"files.autoSave": "afterDelay", "files.autoSaveDelay": 1000}"""))
    }

    @Test
    fun `a file no extension claimed is left to save as it always has`() = onEdt {
        autosaveAfter(300)
        open("hello from IntelliJ", mirror = false)
        type("!")
        waitFor(300 * 2) { false }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(fixture.editor.document))
    }

    @Test
    fun `a file with only a built-in language id is left to save as IntelliJ saves it`() = onEdt {
        // Java with no Java extension installed: mirrored for Error Lens and the like, but IntelliJ's.
        autosaveAfter(300)
        open("hello from IntelliJ", contributed = false)
        type("!")
        waitFor(300 * 2) { false }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(fixture.editor.document))
    }

    @Test
    fun `closing withdraws the document`() = onEdt {
        open("hello from IntelliJ")

        fixture.project.service<HostedLanguageServers>().fileClosed(opened())

        val delta = await($$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta", skip = 1)
        assertTrue(delta.single().jsonObject.containsKey("removedDocuments"), "Expected a removal: $delta")

        // And the edits that follow a close belong to nobody.
        type("!")
        Thread.sleep(QUIET_MILLIS)
        assertEquals(2, documentCalls().size, "A withdrawn document must not keep synchronizing: $sent")
    }

    /**
     * A real file, not the fixture's in-memory one: the URI is built from `VirtualFile.toNioPath`,
     * which the temporary filesystem a light fixture uses does not support.
     */
    /**
     * Opens the file, mirrored unless [mirror] is false, and as a language an installed extension
     * contributes unless [contributed] is false -- the only files autosaved.
     */
    private fun open(text: String, mirror: Boolean = true, contributed: Boolean = true) {
        path = directory.resolve("example.txt").also { it.writeText(text) }
        fixture.openFileInEditor(opened())
        if (mirror && contributed) fixture.project.service<HostedLanguageServers>().contributedForTest(opened())
        if (mirror) {
            features.documentOpened(opened(), "codebridgebundled", HostedLanguageFeatures.Snapshot(text, dirty = false))
            assertEquals(
                1, documentCalls().size,
                "The document has to be mirrored before anything can sync it",
            )
        }
    }

    /** `files.autoSave: "afterDelay"` with [millis]; the fixture has no workbench to read settings from. */
    private fun autosaveAfter(millis: Long) {
        HostedLanguageServers.autosaveMillisForTest = millis
        com.intellij.openapi.util.Disposer.register(fixture.testRootDisposable) {
            HostedLanguageServers.autosaveMillisForTest = null
        }
    }

    private fun opened(): VirtualFile =
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

    @Suppress("SameParameterValue")
    private fun type(text: String) = WriteCommandAction.runWriteCommandAction(fixture.project) {
        fixture.editor.document.insertString(fixture.editor.document.textLength, text)
    }

    /** Synchronization runs off the editor thread, so the call it makes has to be waited for. */
    private fun await(call: String, skip: Int = 0): JsonArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            sent.filter { it.first == call }.drop(skip).firstOrNull()?.let { return it.second }
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $call; saw $sent")
    }

    /** Pumps the editor thread, where the save runs, until [condition] holds or [millis] pass. */
    private fun waitFor(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    /**
     * The document half of what was sent, without the tab model that accompanies an open or close.
     *
     * Tabs are a separate concern with their own coverage, and counting them here would make every
     * assertion about document synchronization depend on how many messages that concern happens to
     * send.
     */
    private fun documentCalls(): List<String> =
        sent.map { it.first }.filterNot { it.startsWith("ExtHostEditorTabs.") }

    private val sent = ConcurrentLinkedQueue<Pair<String, JsonArray>>()

    private lateinit var path: Path

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-document-sync") }

    private companion object {
        /** Long enough for a call that should not happen to have happened. */
        const val QUIET_MILLIS = 500L
    }
}
