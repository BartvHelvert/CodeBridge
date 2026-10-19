package me.helvert.codebridge.ide

import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.started
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `showTextDocument` followed by `editor.edit`: how rust-analyzer applies an assist.
 *
 * `showTextDocument` fails in the host unless the answer is an editor id it was told about, and the
 * edit then names that id with the document version it was computed against.
 */
class TextEditorsTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val editors get() = CodeBridgeTextEditors.getInstance(fixture.project)

    @BeforeEach
    fun forgetPreviousHost() = onEdt { features.hostStopped() }

    @Test
    fun `shows a mirrored document and applies edits through its editor`() = onEdt {
        val path = Files.createTempFile("codebridge-editors", ".txt")
        Files.writeString(path, "let x = 1;\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(fixture.file.virtualFile, "rust", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
        val uri = UriComponents.of(path).toJson()

        val showing = started { editors.handle($$"$tryShowTextDocument", """[$uri,{"preserveFocus":true}]""") }
        // Answered at once for a document the host already holds: nothing is opened, so nothing
        // can be closed again behind the user's back.
        assertTrue(showing.isDone)
        val id = assertNotNull(showing.get().json)
        assertEquals("\"${features.editorOf(fixture.file.virtualFile)}\"", id)

        val edit = """[{"range":{"startLineNumber":1,"startColumn":6,"endLineNumber":1,"endColumn":6},"text":": i32","forceMoveMarkers":false}]"""
        assertEquals("true", await(started { editors.handle($$"$tryApplyEdits", """[$id,1,$edit,{"undoStopBefore":true,"undoStopAfter":true}]""") }).json)
        assertEquals("let x: i32 = 1;\n", fixture.editor.document.text)
        // Saved, so a server that checks the file on disk -- `cargo check` -- sees the fix at once.
        assertTrue(!FileDocumentManager.getInstance().isDocumentUnsaved(fixture.editor.document))
        assertOnDisk("let x: i32 = 1;\n", path)

        // The host has not seen that edit, so a second one computed against version 1 is stale now.
        assertEquals("false", await(started { editors.handle($$"$tryApplyEdits", """[$id,1,$edit,{}]""") }).json)
        assertEquals("false", await(started { editors.handle($$"$tryApplyEdits", """["no-such-editor",1,$edit,{}]""") }).json)
        // A decoration type is registered without an answer, as the host expects.
        assertEquals(null, await(started { editors.handle($$"$registerTextEditorDecorationType", """["ext","key",{}]""") }).json)

        // Undoing the fix is saved as well, or `cargo check` goes on reporting on the fixed text.
        fixture.project.getService(me.helvert.codebridge.language.HostedLanguageServers::class.java)
        val textEditor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(fixture.editor)
        com.intellij.openapi.command.undo.UndoManager.getInstance(fixture.project).undo(textEditor)
        assertEquals("let x = 1;\n", fixture.editor.document.text)
        assertOnDisk("let x = 1;\n", path)
        assertTrue(!FileDocumentManager.getInstance().isDocumentUnsaved(fixture.editor.document))
    }

    @Test
    fun `a file that is not open is opened by the frontend and answered once mirrored`() = onEdt {
        val path = Files.createTempFile("codebridge-editors", ".txt")
        Files.writeString(path, "fn main() {}\n")
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        val requests = java.util.concurrent.LinkedBlockingQueue<me.helvert.codebridge.rpc.OpenFileDto>()
        val listening = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            editors.openRequests.collect { requests.add(it) }
        }
        try {
            while (editors.openRequests.subscriptionCount.value == 0) Thread.sleep(5)
            val uri = UriComponents.of(path).toJson()
            val showing = started { editors.handle($$"$tryShowTextDocument",
                """[$uri,{"selection":{"startLineNumber":1,"startColumn":4,"endLineNumber":1,"endColumn":4}}]""") }
            val request = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals(file, request.file.virtualFile())
            assertEquals(0 to 3, request.line to request.column)
            assertTrue(request.focus)
            // Nothing was opened on the backend; the frontend's editor is what gets mirrored.
            assertTrue(!com.intellij.openapi.fileEditor.FileEditorManager.getInstance(fixture.project).isFileOpen(file))
            features.documentOpened(file, "rust", HostedLanguageFeatures.Snapshot("fn main() {}\n", false))
            assertEquals("\"${features.editorOf(file)}\"", await(showing).json)
        } finally {
            listening.cancel()
        }
    }

    @Test
    fun `client actions run on the editor thread and report failures to the caller`() {
        val ran = CompletableFuture<com.intellij.codeWithMe.ClientId>()
        started {
            editors.asClient {
                assertTrue(com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread)
                ran.complete(com.intellij.codeWithMe.ClientId.current)
            }
        }.get(5, TimeUnit.SECONDS)
        assertEquals(com.intellij.codeWithMe.ClientId.ownerId, ran.get(1, TimeUnit.SECONDS))

        val failure = IllegalStateException("client action failed")
        val thrown = kotlin.test.assertFailsWith<java.util.concurrent.ExecutionException> {
            started { editors.asClient { throw failure } }.get(5, TimeUnit.SECONDS)
        }
        // The same failure, though not the same instance: coroutines copy it to add the caller's stack.
        assertTrue(thrown.cause is IllegalStateException)
        assertEquals(failure.message, thrown.cause?.message)
    }

    private fun await(future: CompletableFuture<MainThreadResult>): MainThreadResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    /** The platform finishes writing a saved document asynchronously, so the disk is waited for. */
    private fun assertOnDisk(expected: String, path: java.nio.file.Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && runCatching { Files.readString(path) }.getOrNull() != expected) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals(expected, Files.readString(path))
    }
}
