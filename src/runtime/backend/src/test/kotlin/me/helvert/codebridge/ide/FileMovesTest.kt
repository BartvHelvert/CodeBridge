package me.helvert.codebridge.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.listeners.RefactoringEventListener
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.protocol.payload.*
import me.helvert.codebridge.language.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Renaming and moving files with the IDE's own refactorings, as extensions hear of it:
 * `onWillRenameFiles`, whose edits are applied, then `onDidRenameFiles`.
 *
 * No host runs here, so the fixture answers as a language client would, with an edit to the import
 * that the move breaks.
 */
class FileMovesTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        calls.clear()
        features.hostStopped()
        // The descriptor is not loaded here; what it registers is, as the platform would.
        val disposable = fixture.testRootDisposable
        AutomaticRenamerFactory.EP_NAME.point.registerExtension(CodeBridgeFileRenameNotes(), disposable)
        ExtensionPointName<MoveFileHandler>("com.intellij.moveFileHandler").point.registerExtension(
            CodeBridgeFileMoveNotes(),
            disposable
        )
        fixture.project.messageBus.connect(disposable)
            .subscribe(RefactoringEventListener.REFACTORING_EVENT_TOPIC, CodeBridgeFileMoveListener(fixture.project))
        // Applied without asking, as after "OK" and "Do not ask me again"; the tests of asking say otherwise.
        participants.choiceForTest(object : CodeBridgeFileParticipants.Choice {
            override fun preview(
                project: Project,
                operation: FileOperations.Operation,
                edit: WorkspaceEdit,
                extensionNames: List<String>
            ) = false
        })
        CodeBridgeEditPreview.chooseForTest { fail("Previewed $it") }
    }

    @AfterEach
    fun forgetChoices() = onEdt {
        participants.choiceForTest(null)
        CodeBridgeEditPreview.chooseForTest(null)
        CodeBridgeFileParticipants.remember(null)
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }

    @Test
    fun `the plugin registers the hooks`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeFileRenameNotes"))
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeFileMoveNotes"))
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeFileMoveListener"))
    }

    @Test
    fun `a renamed file is asked about first, its edits applied, and then told of`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        answer(importIn = main, becomes = "./b")

        RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).run()

        assertEquals("b.txt", moved.name)
        assertEquals(listOf(FileOperations.WILL_RUN, FileOperations.DID_RUN), calls.map { it.first })
        assertEquals(listOf(directory.resolve("a.txt") to directory.resolve("b.txt")), moves(calls.first().second))
        assertEquals(moves(calls.first().second), moves(calls.last().second))
        assertEquals("import ./b\n", text(main))
        // Saved too, since a language server reads the disk for part of what it reports.
        assertEquals("import ./b\n", Files.readString(main.toNioPath()))
    }

    @Test
    fun `a moved file is asked about with where it goes`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        val lib = folder("lib")
        answer(importIn = main, becomes = "./lib/a")

        MoveFilesOrDirectoriesProcessor(
            fixture.project,
            arrayOf(psi(moved)),
            psiFolder(lib),
            false,
            false,
            null,
            null
        ).run()

        assertEquals(lib, moved.parent)
        assertEquals(listOf(directory.resolve("a.txt") to directory.resolve("lib/a.txt")), moves(calls.first().second))
        assertEquals(FileOperations.DID_RUN, calls.last().first)
        assertEquals("import ./lib/a\n", text(main))
    }

    @Test
    fun `a moved folder is asked about as one folder, not file by file`() = onEdt {
        val src = folder("src")
        file("src/a.txt", "export a\n")
        file("src/b.txt", "export b\n")
        val lib = folder("lib")
        answer(importIn = null, becomes = "")

        MoveFilesOrDirectoriesProcessor(
            fixture.project,
            arrayOf(psiFolder(src)),
            psiFolder(lib),
            false,
            false,
            null,
            null
        ).run()

        assertEquals(listOf(directory.resolve("src") to directory.resolve("lib/src")), moves(calls.first().second))
        assertEquals(FileOperations.DID_RUN, calls.last().first)
    }

    @Test
    fun `without a host the move still happens`() = onEdt {
        val moved = file("a.txt", "export a\n")

        RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).run()

        assertEquals("b.txt", moved.name)
    }

    @Test
    fun `a file an extension's edit renames is asked about after the edit's earlier changes`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        var seen: String? = null
        answerWith {
            seen = ReadAction.computeBlocking<String, RuntimeException> { text(moved) }
            participation(importEdit(main, "./b"))
        }

        val applied = applyExtensionEdit(textEdit(moved, 1, 8, 9, "A"), rename(moved, directory.resolve("b.txt")))

        assertTrue(applied)
        assertEquals("export A\n", seen, "The participants were asked before the edit's own change was made")
        assertEquals("b.txt", moved.name)
        assertEquals("import ./b\n", text(main))
        assertEquals(listOf(FileOperations.WILL_RUN, FileOperations.DID_RUN), calls.map { it.first })
        assertEquals(listOf(directory.resolve("a.txt") to directory.resolve("b.txt")), moves(calls.last().second))
    }

    @Test
    fun `an edit the participants answered with is not asked about again`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val other = file("other.txt", "x\n")
        answerWith {
            participation(
                """{"oldResource":${UriComponents.of(other.toNioPath()).toJson()},""" +
                        """"newResource":${UriComponents.of(directory.resolve("renamed.txt")).toJson()}}"""
            )
        }

        applyExtensionEdit(rename(moved, directory.resolve("b.txt")))

        assertEquals("renamed.txt", other.name)
        assertEquals(1, calls.count { it.first == FileOperations.WILL_RUN })
    }

    @Test
    fun `the participants' edits to a file the extension's edit changes after the move are left out`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        answer(importIn = main, becomes = "./b")

        applyExtensionEdit(rename(moved, directory.resolve("b.txt")), textEdit(main, 1, 1, 7, "use"))

        assertEquals("use ./a\n", text(main))
    }

    @Test
    fun `an edit asking to be confirmed is previewed, with that change unticked`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        answer(importIn = main, becomes = "./b", metadata = """{"needsConfirmation":true,"label":"Update imports"}""")
        var previewed: WorkspaceEdit? = null
        CodeBridgeEditPreview.chooseForTest { previewed = it; null }

        RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).run()

        assertFalse(CodeBridgeEditPreview.checkedByDefault(previewed!!.entries.single()))
        // Discarded: the file still moves, and the extensions hear that it did.
        assertEquals("b.txt", moved.name)
        assertEquals("import ./a\n", text(main))
        assertEquals(FileOperations.DID_RUN, calls.last().first)
    }

    @Test
    fun `the user is asked how to apply the edits, and can skip them`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        answer(importIn = main, becomes = "./b")
        participants.choiceForTest(null)
        val asked = mutableListOf<String>()
        TestDialogManager.setTestDialog { asked += it; 2 } // Skip Changes

        RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).run()

        assertEquals(listOf("Extension 'Fixture' wants to make refactoring changes with this file move"), asked)
        assertEquals("b.txt", moved.name)
        assertEquals("import ./a\n", text(main))
    }

    @Test
    fun `a remembered choice is not asked again`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        answer(importIn = main, becomes = "./b")
        participants.choiceForTest(null)
        CodeBridgeFileParticipants.remember(false)
        TestDialogManager.setTestDialog { fail("Asked: $it") }

        RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).run()

        assertEquals("import ./b\n", text(main))
    }

    @Test
    fun `a rename that renames other files with it asks about all of them`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val related = file("a.spec.txt", "test a\n")
        answer(importIn = null, becomes = "")

        RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).apply {
            addElement(psi(related), "b.spec.txt")
        }.run()

        assertEquals(
            setOf(
                directory.resolve("a.txt") to directory.resolve("b.txt"),
                directory.resolve("a.spec.txt") to directory.resolve("b.spec.txt"),
            ),
            moves(calls.first().second).toSet(),
        )
        assertEquals(2, moves(calls.last().second).size)
    }

    @Test
    fun `a move without searching for references is only reported`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        val lib = folder("lib")
        answer(importIn = main, becomes = "./lib/a")

        withSetting({ MOVE_SEARCH_FOR_REFERENCES_FOR_FILE = it }, { MOVE_SEARCH_FOR_REFERENCES_FOR_FILE }) {
            MoveFilesOrDirectoriesProcessor(
                fixture.project,
                arrayOf(psi(moved)),
                psiFolder(lib),
                false,
                false,
                null,
                null
            ).run()
        }

        assertEquals(lib, moved.parent)
        assertEquals(listOf(FileOperations.DID_RUN), calls.map { it.first })
        assertEquals("import ./a\n", text(main))
    }

    @Test
    fun `a rename without searching for references is only reported`() = onEdt {
        val moved = file("a.txt", "export a\n")
        val main = file("main.txt", "import ./a\n")
        answer(importIn = main, becomes = "./b")

        withSetting({ RENAME_SEARCH_FOR_REFERENCES_FOR_FILE = it }, { RENAME_SEARCH_FOR_REFERENCES_FOR_FILE }) {
            RenameProcessor(fixture.project, psi(moved), "b.txt", false, false).run()
        }

        assertEquals("b.txt", moved.name)
        assertEquals(listOf(FileOperations.DID_RUN), calls.map { it.first })
        assertEquals("import ./a\n", text(main))
    }

    /** Runs [block] with a "Search for references" setting unticked, and puts it back after. */
    private fun withSetting(
        set: RefactoringSettings.(Boolean) -> Unit,
        get: RefactoringSettings.() -> Boolean,
        block: () -> Unit
    ) {
        val settings = RefactoringSettings.getInstance()
        val before = settings.get()
        settings.set(false)
        try {
            block()
        } finally {
            settings.set(before)
        }
    }

    /** Answers the will-event with [becomes] in place of `./a` in [importIn], or with nothing when there is none. */
    private fun answer(importIn: VirtualFile?, becomes: String, metadata: String? = null) =
        answerWith { if (importIn == null) "" else participation(importEdit(importIn, becomes, metadata)) }

    /** Answers the will-event with what [reply] makes of its arguments, and the did-event with nothing. */
    private fun answerWith(reply: (String) -> String) {
        features.callHostForTest { _, method, args, _ ->
            calls.add(method to args)
            if (method == FileOperations.WILL_RUN) reply(args) else ""
        }
    }

    private fun participation(vararg edits: String) =
        """{"edit":{"edits":[${edits.joinToString(",")}]},"extensionNames":["Fixture"]}"""

    /** Replaces `./a` in [file]'s first line, as a language server updating the import would. */
    private fun importEdit(file: VirtualFile, becomes: String, metadata: String? = null): String {
        val extra = if (metadata == null) "" else ""","metadata":$metadata"""
        return """{"resource":${UriComponents.of(file.toNioPath()).toJson()},""" +
                """"textEdit":{"range":{"startLineNumber":1,"startColumn":8,"endLineNumber":1,"endColumn":11},"text":"$becomes"}$extra}"""
    }

    private fun rename(from: VirtualFile, to: Path) =
        WorkspaceEdit.FileEdit(UriComponents.of(from.toNioPath()), UriComponents.of(to))

    @Suppress("SameParameterValue") // A builder: the call reads as what it makes.
    private fun textEdit(file: VirtualFile, line: Int, start: Int, end: Int, text: String) =
        WorkspaceEdit.ResourceEdit(
            UriComponents.of(file.toNioPath()),
            TextEdit(Range(line, start, line, end), text),
            null
        )

    private fun applyExtensionEdit(vararg entries: WorkspaceEdit.Entry) =
        CodeBridgeWorkspaceEdits.apply(fixture.project, WorkspaceEdit(entries.toList()), "Extension Edit", features::isCurrent)

    /** The source and target paths of each pair in a will- or did-event's arguments. */
    private fun moves(args: String): List<Pair<Path, Path>> =
        Json.parseToJsonElement(args).jsonArray[1].jsonArray.map {
            fun path(key: String) = UriComponents.from(it.jsonObject[key])!!.toPath()
            path("source") to path("target")
        }.also {
            assertEquals(
                FileOperations.Operation.MOVE.code,
                Json.parseToJsonElement(args).jsonArray[0].jsonPrimitive.content.toInt()
            )
        }

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)!!.text

    private fun psi(file: VirtualFile) = PsiManager.getInstance(fixture.project).findFile(file)!!

    private fun psiFolder(folder: VirtualFile) = PsiManager.getInstance(fixture.project).findDirectory(folder)!!

    private fun file(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.parent.createDirectories(); it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun folder(name: String): VirtualFile =
        requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve(name).createDirectories())
        )

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val participants get() = CodeBridgeFileParticipants.getInstance(fixture.project)

    private val calls = ConcurrentLinkedQueue<Pair<String, String>>()

    // Real paths: a temp directory's short 8.3 name on Windows would not match the moved file's path.
    private val directory: Path by lazy { Files.createTempDirectory("codebridge-moves").toRealPath() }
}
