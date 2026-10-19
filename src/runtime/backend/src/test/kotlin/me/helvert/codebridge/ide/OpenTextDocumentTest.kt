package me.helvert.codebridge.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import com.intellij.openapi.vfs.encoding.EncodingProjectManagerImpl
import com.intellij.openapi.vfs.impl.VirtualFilePointerImpl
import com.intellij.openapi.vfs.pointers.VirtualFilePointer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.started
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `workspace.openTextDocument`, from the workbench's side.
 *
 * The host asks for a document only when no editor has it open, and then looks it up by the answer
 * at once: answered before the host holds it, the extension's call fails. A host cannot be started
 * here, so the fixture records what would have been sent to one.
 */
class OpenTextDocumentTest : EditorFixture() {

    @BeforeEach
    fun mirrorWithoutAHost() = onEdt {
        features.hostStopped()
        features.mirrorForTest(ExtensionHostDocuments { service, method, argsJson ->
            sent.add("$service.$method" to Json.parseToJsonElement(argsJson).jsonArray)
            ""
        })
        // Creating the service is what registers the listener that sends edits on.
        fixture.project.service<HostedLanguageServers>()
    }

    @AfterEach
    fun stopWorker() {
        documents.hostStopped()
        scope.cancel()
        // Reading a file in another encoding is remembered for it, which outlives the light project.
        onEdt {
            val encodings = EncodingProjectManager.getInstance(fixture.project) as EncodingProjectManagerImpl
            // The platform disposes these only with the project, which a light fixture keeps between tests.
            val pointers = encodings.allPointersMappings.keys.toList()
            if (pointers.isEmpty()) return@onEdt
            encodings.setPointerMapping(emptyMap<VirtualFilePointer, Charset>())
            pointers.forEach { (it as? VirtualFilePointerImpl)?.dispose() }
        }
    }

    @Test
    fun `a file no editor has open is put into the host before the answer`() = onEdt {
        val file = file("fn main() {}\n")

        val reply = call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{}]""")

        val delta = deltas().single()
        val added = delta.getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals(listOf("fn main() {}", ""), added.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("codebridgebundled", added.getValue("languageId").jsonPrimitive.content)
        // Held, not shown: nobody opened an editor on it.
        assertFalse("addedEditors" in delta)
        // The answer names the resource the host was given, which is what it looks the document up by.
        assertEquals(added.getValue("uri"), Json.parseToJsonElement(reply.json!!))
        assertTrue(features.isMirrored(file))
        assertFalse(features.isShown(file))
        // Opening a document of a language wakes the extensions for it, as an editor would.
        assertEquals(listOf("codebridgebundled"), activated)
    }

    @Test
    fun `an edit to a held file reaches the host`() = onEdt {
        val file = file("one")
        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{}]""")

        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(3, " two") }

        val change = await($$"ExtHostDocuments.$acceptModelChanged")
        assertEquals(" two", change[1].jsonObject.getValue("changes").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `opening a held file in an editor shows it, and closing the editor keeps it held`() = onEdt {
        val file = file("one")
        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{}]""")
        sent.clear()

        features.documentOpened(file, "codebridgebundled", HostedLanguageFeatures.Snapshot("one", dirty = false))

        val shown = deltas().single()
        assertFalse("addedDocuments" in shown)
        assertTrue("addedEditors" in shown)
        assertTrue(features.isShown(file))
        sent.clear()

        features.documentClosed(file)

        val hidden = deltas().single()
        assertFalse("removedDocuments" in hidden)
        assertTrue("removedEditors" in hidden)
        // The extension still holds it, so it goes on hearing about edits until it lets go.
        assertTrue(features.isMirrored(file))
        assertFalse(features.isShown(file))
    }

    @Test
    fun `an untitled buffer is created with its content`() = onEdt {
        val reply = call($$"$tryCreateDocument", """[{"language":"markdown","content":"# notes"}]""")

        val uri = Json.parseToJsonElement(reply.json!!).jsonObject
        assertEquals("untitled", uri.getValue("scheme").jsonPrimitive.content)
        val added = deltas().single().getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals(uri, added.getValue("uri"))
        assertEquals("markdown", added.getValue("languageId").jsonPrimitive.content)
        assertEquals(listOf("# notes"), added.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
        // Unsaved, as VS Code's are: the content is saved nowhere.
        assertEquals(true, added.getValue("isDirty").jsonPrimitive.boolean)
        assertFalse("addedEditors" in deltas().single())
        // Asked for again by the host, it is the same buffer rather than an error.
        assertEquals(reply.json, call($$"$tryOpenDocument", "[$uri,{}]").json)
        // Stood in for by an in-memory file, which is what an editor can open.
        assertTrue(features.fileOf("untitled:${uri.getValue("path").jsonPrimitive.content}") is LightVirtualFile)
    }

    @Test
    fun `an untitled buffer is shown, edited, and discarded with its editor`() = onEdt {
        // The project's own handler, so closing the editor reaches it the way the listener's close does.
        val servers = fixture.project.service<HostedLanguageServers>()
        val documents = servers.documents
        val created = started { documents.handle(MainThreadCall("MainThreadDocuments", $$"$tryCreateDocument", """[{"content":"draft"}]""")) }
        val uri = Json.parseToJsonElement(pump(created).json!!).jsonObject
        val file = requireNotNull(features.fileOf("untitled:${uri.getValue("path").jsonPrimitive.content}"))
        sent.clear()

        // `showTextDocument`: nothing in the fixture listens for the frontend, so the backend opens it.
        val shown = started { CodeBridgeTextEditors.getInstance(fixture.project).handle($$"$tryShowTextDocument", """[$uri,{"preserveFocus":true}]""") }
        // The light fixture's editor manager publishes no `fileOpened`, so the listener is stood in for.
        assertTrue(waitFor { FileEditorManager.getInstance(fixture.project).isFileOpen(file) })
        servers.fileOpened(file)
        val editorId = pump(shown).json

        assertEquals("\"${features.editorOf(file)}\"", editorId)
        val delta = deltas().single()
        assertFalse("addedDocuments" in delta, "The host already holds it: $delta")
        assertEquals(uri, delta.getValue("addedEditors").jsonArray.single().jsonObject.getValue("documentUri"))

        // Typed into like any editor, and the host is told.
        val editor = FileEditorManager.getInstance(fixture.project).selectedTextEditor!!
        assertEquals(file, FileDocumentManager.getInstance().getFile(editor.document))
        WriteCommandAction.runWriteCommandAction(fixture.project) { editor.document.insertString(5, "!") }
        val change = await($$"ExtHostDocuments.$acceptModelChanged")
        assertEquals("!", change[1].jsonObject.getValue("changes").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content)
        assertEquals(uri, change[0])
        sent.clear()

        // Closing the editor discards the buffer, as VS Code does when the user does not save it.
        FileEditorManager.getInstance(fixture.project).closeFile(file)
        servers.fileClosed(file)
        val removed = awaitDelta { "removedDocuments" in it }
        assertEquals(uri, removed.getValue("removedDocuments").jsonArray.single())
        assertEquals(null, features.fileOf("untitled:${uri.getValue("path").jsonPrimitive.content}"))
    }

    @Test
    fun `a file is reported in the encoding IntelliJ reads it in`() = onEdt {
        file("plain")

        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{}]""")

        val added = deltas().single().getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals("utf8", added.getValue("encoding").jsonPrimitive.content)
    }

    @Test
    fun `asking for another encoding reads the file in it`() = onEdt {
        // "caf\u00e9" in windows-1252, which is not valid UTF-8.
        bytes("caf\u00e9 au lait".toByteArray(Charset.forName("windows-1252")))

        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{"encoding":"windows1252"}]""")

        val added = deltas().single().getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals(listOf("caf\u00e9 au lait"), added.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("windows1252", added.getValue("encoding").jsonPrimitive.content)
    }

    @Test
    fun `a held document asked for in another encoding is sent the new text and encoding first`() = onEdt {
        bytes("caf\u00e9 au lait".toByteArray(Charset.forName("windows-1252")))
        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{}]""")
        sent.clear()

        // What the host does when the extension's encoding differs from the document's.
        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{"encoding":"windows1252"}]""")

        // Both before the answer: the extension reads the document as soon as it has one.
        val change = sent.first { it.first == $$"ExtHostDocuments.$acceptModelChanged" }.second
        // Only the character the two decodings disagree on.
        assertEquals("\u00e9", change[1].jsonObject.getValue("changes").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content)
        val encoding = sent.first { it.first == $$"ExtHostDocuments.$acceptEncodingChanged" }.second
        assertEquals("windows1252", encoding[1].jsonPrimitive.content)
    }

    @Test
    fun `a document with unsaved changes is not read again in another encoding`() = onEdt {
        val file = file("plain")
        call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{}]""")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "unsaved ") }

        val failure = runCatching {
            call($$"$tryOpenDocument", """[${UriComponents.of(path).toJson()},{"encoding":"windows1252"}]""")
        }.exceptionOrNull()

        assertTrue(failure?.cause?.message.orEmpty().contains("unsaved changes"), "got $failure")
        assertEquals("unsaved plain", document.text)
    }

    @Test
    fun `an untitled buffer is reported in the encoding asked for`() = onEdt {
        call($$"$tryCreateDocument", """[{"content":"x","encoding":"windows1252"}]""")

        val added = deltas().single().getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals("windows1252", added.getValue("encoding").jsonPrimitive.content)
    }

    @Test
    fun `saving an untitled buffer writes it where the user chose, and the file takes its place`() = onEdt {
        val target = directory.resolve("saved").resolve("notes.txt")
        saveTarget = target
        val created = Json.parseToJsonElement(call($$"$tryCreateDocument", """[{"content":"draft\nnotes"}]""").json!!).jsonObject
        val buffer = requireNotNull(features.fileOf("untitled:${created.getValue("path").jsonPrimitive.content}"))
        // Shown in an editor, as `showTextDocument` would leave it.
        fixture.openFileInEditor(buffer)
        features.documentOpened(buffer, "plaintext", HostedLanguageFeatures.Snapshot("draft\nnotes", dirty = true))
        sent.clear()

        assertEquals("true", call($$"$trySaveDocument", "[$created]").json)

        assertEquals("draft\nnotes", Files.readString(target))
        // The file is in the host, and the buffer is no longer an extension's to hold.
        val added = deltas().first { "addedDocuments" in it }.getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals(UriComponents.of(target).toJson(), added.getValue("uri"))
        assertEquals(choices, listOf(buffer))
        // Its editor shows the file now.
        val saved = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(target))
        assertTrue(waitFor { FileEditorManager.getInstance(fixture.project).isFileOpen(saved) })
        assertFalse(FileEditorManager.getInstance(fixture.project).isFileOpen(buffer))
    }

    @Test
    fun `an untitled buffer the user declines to save stays as it was`() = onEdt {
        val created = Json.parseToJsonElement(call($$"$tryCreateDocument", """[{"content":"draft"}]""").json!!).jsonObject
        saveTarget = null
        sent.clear()

        assertEquals("false", call($$"$trySaveDocument", "[$created]").json)

        assertTrue(sent.isEmpty(), "Sent: $sent")
        assertTrue(features.fileOf("untitled:${created.getValue("path").jsonPrimitive.content}") != null)
    }

    @Test
    fun `a file that does not exist is refused, and nothing is sent`() = onEdt {
        val missing = directory.resolve("missing.txt")

        val failure = runCatching { call($$"$tryOpenDocument", """[${UriComponents.of(missing).toJson()},{}]""") }
            .exceptionOrNull()

        assertTrue(failure?.cause?.message.orEmpty().startsWith("cannot open"), "got $failure")
        assertTrue(sent.isEmpty(), "Sent: $sent")
    }

    private fun file(text: String): VirtualFile {
        path = directory.resolve("example.txt").also { it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun bytes(content: ByteArray): VirtualFile {
        path = directory.resolve("example.txt").also { Files.write(it, content) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    /** Answered on the documents' worker, so the editor thread is pumped while it waits, in case it is needed. */
    private fun call(method: String, argsJson: String): MainThreadResult =
        pump(started { documents.handle(MainThreadCall("MainThreadDocuments", method, argsJson)) })

    private fun pump(reply: CompletableFuture<MainThreadResult>): MainThreadResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!reply.isDone && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        return reply.get(0, TimeUnit.SECONDS)
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        return condition()
    }

    /** A delta sent from a worker after an editor event, so the editor thread is pumped meanwhile. */
    private fun awaitDelta(matching: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            deltas().firstOrNull(matching)?.let { return it }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for a delta; saw $sent")
    }

    @Suppress("SameParameterValue")
    private fun await(call: String): JsonArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            sent.firstOrNull { it.first == call }?.let { return it.second }
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $call; saw $sent")
    }

    private fun deltas(): List<JsonObject> =
        sent.filter { it.first == $$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta" }
            .map { it.second.single().jsonObject }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val sent = ConcurrentLinkedQueue<Pair<String, JsonArray>>()

    private val activated = mutableListOf<String>()

    /** What the save dialog answers, and which buffers it was shown for. */
    private var saveTarget: Path? = null
    private val choices = mutableListOf<VirtualFile>()

    private val scope = CoroutineScope(SupervisorJob())

    private val documents by lazy {
        HostedDocuments(fixture.project, scope, Dispatchers.IO.limitedParallelism(1), languageOf = { "codebridgebundled" }, activate = { activated += it },
            chooseSaveTarget = { choices += it; saveTarget })
    }

    private lateinit var path: Path

    private val directory: Path by lazy {
        Files.createTempDirectory("codebridge-open-text-document").toRealPath()
    }
}
