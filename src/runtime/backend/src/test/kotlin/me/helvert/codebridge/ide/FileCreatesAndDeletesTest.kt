package me.helvert.codebridge.ide

import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.protocol.payload.FileOperations
import me.helvert.codebridge.exthost.protocol.payload.FileOperations.Operation
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.WorkspaceEdit
import me.helvert.codebridge.language.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Creating, copying and deleting files, as extensions hear of it: `onWillCreateFiles` and
 * `onWillDeleteFiles`, whose edits are applied, then `onDidCreateFiles` and `onDidDeleteFiles`.
 *
 * No host runs here, so the fixture answers as a language client would, with an edit to a file that
 * lists the others.
 */
class FileCreatesAndDeletesTest : EditorFixture() {

    @BeforeEach
    fun listen() = onEdt {
        calls.clear()
        features.hostStopped()
        // The descriptor is not loaded here; what it registers is, as the platform would.
        val bus = ApplicationManager.getApplication().messageBus.connect(fixture.testRootDisposable)
        bus.subscribe(CommandListener.TOPIC, CodeBridgeFileCommandListener())
        bus.subscribe(VirtualFileManager.VFS_CHANGES, CodeBridgeFileOperationsListener())
        service.extraRoots = listOf(directory)
        participants.choiceForTest(object : CodeBridgeFileParticipants.Choice {
            override fun preview(
                project: Project,
                operation: Operation,
                edit: WorkspaceEdit,
                extensionNames: List<String>
            ) = false
        })
        CodeBridgeEditPreview.chooseForTest { fail("Previewed $it") }
    }

    @AfterEach
    fun forget() = onEdt {
        participants.choiceForTest(null)
        CodeBridgeEditPreview.chooseForTest(null)
        service.extraRoots = emptyList()
    }

    @Test
    fun `the plugin registers the listeners`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeDeleteActionListener"))
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeFileCommandListener"))
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeFileOperationsListener"))
    }

    @Test
    fun `a file an extension's edit creates is asked about before it exists`() = onEdt {
        val index = file("index.txt", "a\n")
        val created = directory.resolve("b.txt")
        var existed: Boolean? = null
        answerWith { existed = created.exists(); append(index, "b") }

        assertTrue(apply(WorkspaceEdit.FileEdit(null, UriComponents.of(created))))

        assertEquals(false, existed)
        assertEquals("a\nb\n", text(index))
        assertEquals(
            listOf(Operation.CREATE to FileOperations.WILL_RUN, Operation.CREATE to FileOperations.DID_RUN),
            operations()
        )
        assertEquals(listOf(null to created), changes(calls.last()))
    }

    @Test
    fun `a file an extension's edit deletes is asked about while it is still there`() = onEdt {
        val index = file("index.txt", "a\nb\n")
        val deleted = file("b.txt", "export b\n")
        var existed: Boolean? = null
        answerWith { existed = deleted.toNioPath().exists(); replaceLine(index, 2, "") }

        assertTrue(apply(WorkspaceEdit.FileEdit(UriComponents.of(deleted.toNioPath()), null)))

        assertEquals(true, existed)
        assertFalse(deleted.isValid)
        assertEquals("a\n\n", text(index))
        assertEquals(
            listOf(Operation.DELETE to FileOperations.WILL_RUN, Operation.DELETE to FileOperations.DID_RUN),
            operations()
        )
    }

    @Test
    fun `a file the user deletes is asked about before, in the same undo`() = onEdt {
        val index = file("index.txt", "a\nb\n")
        val deleted = file("b.txt", "export b\n")
        var existed: Boolean? = null
        answerWith { existed = deleted.toNioPath().exists(); replaceLine(index, 2, "") }
        val elements = arrayOf(PsiManager.getInstance(fixture.project).findFile(deleted)!!)
        fixture.openFileInEditor(index)
        val editor = FileEditorManager.getInstance(fixture.project).getSelectedEditor(index) as TextEditor

        // As the Delete action does: the listener notes the selection, then the handler deletes it.
        service.deleteActionStarted(elements)
        DeleteHandler.deletePsiElement(elements, fixture.project, false)
        service.deleteActionEnded()

        assertEquals(true, existed)
        assertFalse(deleted.isValid)
        assertEquals("a\n\n", text(index))
        assertEquals(
            listOf(Operation.DELETE to FileOperations.WILL_RUN, Operation.DELETE to FileOperations.DID_RUN),
            operations()
        )
        assertEquals(listOf(null to directory.resolve("b.txt")), changes(calls.last()))
        // The undo that takes back the deletion takes back the extension's edit too.
        // Putting the file itself back is the platform's, from local history, which a light fixture does not keep.
        val asked = mutableListOf<String>()
        TestDialogManager.setTestDialog { asked += it; Messages.OK }
        try {
            UndoManager.getInstance(fixture.project).undo(editor)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        assertEquals(listOf("Undo Deleting file b.txt?"), asked)
        assertEquals("a\nb\n", text(index))
    }

    @Test
    fun `a file the user creates is asked about as its command finishes, and reported`() = onEdt {
        val index = file("index.txt", "a\n")
        val folder = folder()
        answerWith { append(index, "b") }

        WriteCommandAction.runWriteCommandAction(fixture.project) { folder.createChildData(this, "b.txt") }

        assertEquals("a\nb\n", text(index))
        assertEquals(
            listOf(Operation.CREATE to FileOperations.WILL_RUN, Operation.CREATE to FileOperations.DID_RUN),
            operations()
        )
        assertEquals(listOf(null to directory.resolve("b.txt")), changes(calls.last()))
    }

    @Test
    fun `a copy is reported as one, from where it was copied`() = onEdt {
        val original = file("a.txt", "export a\n")
        val lib = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("lib").createDirectories())
        )
        answerWith { "" }

        WriteCommandAction.runWriteCommandAction(fixture.project) { original.copy(this, lib, "a.txt") }

        assertEquals(
            listOf(Operation.COPY to FileOperations.WILL_RUN, Operation.COPY to FileOperations.DID_RUN),
            operations()
        )
        val copied: List<Pair<Path?, Path>> = listOf(directory.resolve("a.txt") to directory.resolve("lib/a.txt"))
        assertEquals(copied, changes(calls.last()))
    }

    @Test
    fun `a folder made with its contents is reported as the folder alone`() = onEdt {
        val folder = folder()
        answerWith { "" }

        WriteCommandAction.runWriteCommandAction(fixture.project) {
            folder.createChildDirectory(this, "src").createChildData(this, "a.txt")
        }

        assertEquals(listOf(null to directory.resolve("src")), changes(calls.last()))
    }

    @Test
    fun `files that appear on disk are not the user's`() = onEdt {
        answerWith { fail("Asked about a file a refresh found") }

        directory.resolve("generated.txt").writeText("x")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve("generated.txt"))

        assertTrue(calls.isEmpty())
    }

    /** Answers the will-event with what [reply] gives, wrapped as a participation, and the did-event with nothing. */
    private fun answerWith(reply: () -> String) {
        features.callHostForTest { _, method, args, _ ->
            calls.add(method to args)
            val edit =
                if (method == FileOperations.WILL_RUN) ReadAction.computeBlocking<String, RuntimeException>(reply) else ""
            if (edit.isEmpty()) "" else """{"edit":{"edits":[$edit]},"extensionNames":["Fixture"]}"""
        }
    }

    /** Adds a line to the end of [file], as an extension keeping an index of the others would. */
    @Suppress("SameParameterValue") // A builder: the call reads as what it makes.
    private fun append(file: VirtualFile, line: String): String {
        val lines = text(file).count { it == '\n' }
        return edit(file, lines + 1, 1, 1, "$line\n")
    }

    @Suppress("SameParameterValue") // A builder: the call reads as what it makes.
    private fun replaceLine(file: VirtualFile, line: Int, text: String): String {
        val length = text(file).split('\n')[line - 1].length
        return edit(file, line, 1, length + 1, text)
    }

    @Suppress("SameParameterValue") // A builder: the call reads as what it makes.
    private fun edit(file: VirtualFile, line: Int, start: Int, end: Int, text: String) =
        """{"resource":${UriComponents.of(file.toNioPath()).toJson()},"textEdit":{"range":""" +
                """{"startLineNumber":$line,"startColumn":$start,"endLineNumber":$line,"endColumn":$end},"text":${
                    Json.encodeToString(
                        text
                    )
                }}}"""

    private fun apply(vararg entries: WorkspaceEdit.Entry) =
        CodeBridgeWorkspaceEdits.apply(fixture.project, WorkspaceEdit(entries.toList()), "Extension Edit", features::isCurrent)

    /** Each call's operation and method, in order. */
    private fun operations() = calls.map { (method, args) ->
        val code = Json.parseToJsonElement(args).jsonArray[0].jsonPrimitive.content.toInt()
        Operation.entries.single { it.code == code } to method
    }

    /** The source and target of each file a call names. */
    private fun changes(call: Pair<String, String>): List<Pair<Path?, Path>> =
        Json.parseToJsonElement(call.second).jsonArray[1].jsonArray.map {
            fun path(key: String) = UriComponents.from(it.jsonObject[key])?.toPath()
            path("source") to path("target")!!
        }

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)!!.text

    private fun file(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.parent.createDirectories(); it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun folder(): VirtualFile =
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory))

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val participants get() = CodeBridgeFileParticipants.getInstance(fixture.project)

    private val service get() = CodeBridgeFileCreatesAndDeletes.getInstance(fixture.project)

    private val calls = ConcurrentLinkedQueue<Pair<String, String>>()

    // Real paths: a temp directory's short 8.3 name on Windows would not match the files' paths.
    private val directory: Path by lazy { Files.createTempDirectory("codebridge-creates").toRealPath() }
}
