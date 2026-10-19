package me.helvert.codebridge.ide

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.future.await
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeCodeAction0
import me.helvert.codebridge.language.CodeBridgeCodeAction1
import me.helvert.codebridge.language.CodeBridgeCodeAction2
import me.helvert.codebridge.language.CodeBridgeCodeActionIntention
import me.helvert.codebridge.started
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Code actions, from the intention menu to the edit landing in the document.
 *
 * No host runs here, so the fixture answers as an extension would. What is covered is the IntelliJ
 * half: one request however many slots ask, the edit applied as offsets into the right text, an
 * action resolved only once chosen, a command run after its edit, and an edit refused once the text
 * it was computed against has changed.
 */
class CodeActionTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val sent = ConcurrentLinkedQueue<Pair<String, String>>()
    /** The reply to a command an action runs, completed by the test that wants it to finish. */
    private var command = CompletableFuture<String>()
    private lateinit var path: Path

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        command = CompletableFuture()
        features.hostStopped()
    }

    @Test
    fun `the plugin registers the slots and the formatter`() {
        val descriptor = Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        for (slot in 0 until 20) assertTrue(descriptor.contains("<className>me.helvert.codebridge.language.CodeBridgeCodeAction$slot</className>"), "slot $slot")
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeFormattingService"))
        // Without it the module cannot see the extension point, and the formatter is skipped silently.
        assertTrue(descriptor.contains("<module name=\"intellij.platform.codeStyle.impl\"/>"))
    }

    @Test
    fun `offers each action in its own slot and asks the host once`() = onEdt {
        open()
        val (first, second, third) = listOf(CodeBridgeCodeAction0(), CodeBridgeCodeAction1(), CodeBridgeCodeAction2())
        assertTrue(available(first))
        assertTrue(available(second))
        // The disabled action is not offered, so the third slot has nothing.
        assertFalse(available(third))
        assertEquals("Rename to total", first.text)
        assertEquals(PriorityAction.Priority.HIGH, first.priority)
        assertEquals("Add a comment", second.text)
        assertEquals(PriorityAction.Priority.LOW, second.priority)
        assertEquals(1, sent.count { it.first == $$"$provideCodeActions" })
        // Caret at the start of the second word: a zero-width range, 1-based.
        assertTrue(sent.first().second.contains(""""startLineNumber":1,"startColumn":9,"endLineNumber":1,"endColumn":9"""), sent.first().second)
    }

    @Test
    fun `applies an action's own edit`() = onEdt {
        open()
        val action = CodeBridgeCodeAction0()
        assertTrue(available(action))
        action.invoke(fixture.project, fixture.editor, fixture.file)
        assertEquals("let total = 1\n", fixture.editor.document.text)
        assertOnDisk("let total = 1\n", path)
        // Nothing to resolve, and the list released once an action from it ran.
        assertFalse(sent.any { it.first == $$"$resolveCodeAction" })
        assertTrue(sent.any { it.first == $$"$releaseCodeActions" && it.second == "[5,12]" })
    }

    @Test
    fun `resolves an action with no edit and then runs its command`() = onEdt {
        open()
        val action = CodeBridgeCodeAction1()
        assertTrue(available(action))
        action.invoke(fixture.project, fixture.editor, fixture.file)
        assertEquals("// checked\nlet value = 1\n", fixture.editor.document.text)
        assertTrue(sent.any { it.first == $$"$resolveCodeAction" && it.second == "[5,[12,1]]" })
        assertEquals("""["ext.after","done"]""", sent.single { it.first == $$"$executeContributedCommand" }.second)
        // The host deletes an action's command with its list, so the list outlives the command --
        // including the availability pass the edit itself provokes.
        assertTrue(available(CodeBridgeCodeAction0()))
        assertFalse(sent.any { it.first == $$"$releaseCodeActions" && it.second == "[5,12]" })
        command.complete("")
        // Once the command's answer is in, which is taken off this thread.
        PlatformTestUtil.waitWithEventsDispatching("The list was not released after its command", {
            sent.any { it.first == $$"$releaseCodeActions" && it.second == "[5,12]" }
        }, 5)
    }

    @Test
    fun `refuses an edit computed against text that has since changed`() = onEdt {
        open()
        val action = CodeBridgeCodeAction0()
        assertTrue(available(action))
        WriteCommandAction.runWriteCommandAction(fixture.project) { fixture.editor.document.insertString(0, "  ") }
        action.invoke(fixture.project, fixture.editor, fixture.file)
        assertEquals("  let value = 1\n", fixture.editor.document.text)
    }

    @Test
    fun `workspace applyEdit writes to the files it names`() = onEdt {
        open()
        val created = path.resolveSibling("created-${System.nanoTime()}.txt")
        val resource = UriComponents.of(path).toJson()
        val newFile = UriComponents.of(created).toJson()
        val reply = started { features.applyWorkspaceEdit("""[{"edits":[
            {"resource":$resource,"textEdit":{"range":{"startLineNumber":1,"startColumn":13,"endLineNumber":1,"endColumn":14},"text":"2"},"versionId":1},
            {"newResource":$newFile,"options":{}},
            {"resource":$newFile,"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1},"text":"made\r\n"}}
        ]},null,false]""") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("true", reply.get(5, TimeUnit.SECONDS).json)
        assertEquals("let value = 2\n", fixture.editor.document.text)
        assertOnDisk("let value = 2\n", path)
        val made = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(created))
        assertEquals("made\n", FileDocumentManager.getInstance().getDocument(made)!!.text)

        // A version the host no longer holds is refused as a whole.
        val stale = started { features.applyWorkspaceEdit("""[{"edits":[
            {"resource":$resource,"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":4},"text":"var"},"versionId":7}
        ]},null,false]""") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("false", stale.get(5, TimeUnit.SECONDS).json)
        assertEquals("let value = 2\n", fixture.editor.document.text)
    }

    /** Opens a mirrored file with the caret on `value`, and a provider that answers like a language server. */
    private fun open() {
        path = Files.createTempFile("codebridge-actions", ".txt")
        Files.writeString(path, "let value = 1\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        fixture.editor.caretModel.moveToOffset(8)
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(fixture.file.virtualFile, "test", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
        features.handle("MainThreadLanguageFeatures", $$"$registerCodeActionSupport", """[5,[{"language":"test"}],{},"Test","me.test",true]""")
        val uri = UriComponents.of(path).toJson()
        features.callHostForTest { _, method, args, _ ->
            sent += method to args
            if (method == $$"$executeContributedCommand") return@callHostForTest command.await()
            when (method) {
                $$"$provideCodeActions" -> """{"cacheId":12,"actions":[
                    {"cacheId":[12,0],"title":"Rename to total","kind":"quickfix","isPreferred":true,
                     "edit":{"edits":[{"resource":$uri,"textEdit":{"range":{"startLineNumber":1,"startColumn":5,"endLineNumber":1,"endColumn":10},"text":"total"},"versionId":1}]}},
                    {"cacheId":[12,1],"title":"Add a comment","kind":"source"},
                    {"cacheId":[12,2],"title":"Inline","disabled":"Nothing to inline"}
                ]}"""
                $$"$resolveCodeAction" -> """{"edit":{"edits":[{"resource":$uri,"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1},"text":"// checked\n"}}]},
                    "command":{"id":"ext.after","title":"After","arguments":["done"]}}"""
                else -> ""
            }
        }
    }

    /** Availability is asked off the editor thread, as the daemon's intention pass does. */
    private fun available(action: CodeBridgeCodeActionIntention): Boolean =
        ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
            ReadAction.computeBlocking<Boolean, RuntimeException> { action.isAvailable(fixture.project, fixture.editor, fixture.file) }
        }.get(10, TimeUnit.SECONDS)

    /** The platform finishes writing a saved document asynchronously, so the disk is waited for. */
    private fun assertOnDisk(expected: String, path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && runCatching { Files.readString(path) }.getOrNull() != expected) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertEquals(expected, Files.readString(path))
    }
}
