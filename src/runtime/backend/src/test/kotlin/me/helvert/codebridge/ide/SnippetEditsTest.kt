package me.helvert.codebridge.ide

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.started
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Snippets an extension puts into an editor itself, rather than through completion:
 * `TextEditor.insertSnippet`, and `SnippetTextEdit`s in `workspace.applyEdit` -- how rust-analyzer
 * applies an assist that leaves the caret, or a placeholder, where the user goes on typing.
 *
 * VS Code walks their stops as a snippet session in the active editor; here it is a live template
 * laid over the inserted text.
 */
class SnippetEditsTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val editors get() = CodeBridgeTextEditors.getInstance(fixture.project)
    private lateinit var path: Path

    @BeforeEach
    fun setUp() = onEdt {
        features.hostStopped()
        // Templates otherwise finish the moment they start in a test, and a snippet's stops with them.
        TemplateManagerImpl.setTemplateTesting(fixture.testRootDisposable)
    }

    @Test
    fun `insertSnippet walks the stops in number order, mirrored, and ends on the final stop`() = onEdt {
        val id = open("fn main() {\n    \n}\n")

        insert(id, $$"let ${2:x} = ${1:1};\nprintln!(\"{}\", $2);$0", range(2, 5))

        // Indented to the line it went into, as VS Code adjusts a snippet's whitespace by default.
        assertEquals("fn main() {\n    let x = 1;\n    println!(\"{}\", x);\n}\n", text())
        assertEquals("1", selected())
        fixture.type("2")
        template().nextTab()
        assertEquals("x", selected())
        fixture.type("y")
        assertEquals("fn main() {\n    let y = 2;\n    println!(\"{}\", y);\n}\n", text())
        template().nextTab()

        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertEquals(text().indexOf(");\n}") + 2, fixture.editor.caretModel.offset)
    }

    @Test
    fun `a snippet inserted at several ranges is typed into all of them at once`() = onEdt {
        val id = open("a\nb\n")

        insert(id, $$"[${1:v}]", range(1, 1), range(2, 1))

        assertEquals("[v]a\n[v]b\n", text())
        fixture.type("w")
        assertEquals("[w]a\n[w]b\n", text())
    }

    @Test
    fun `a snippet with no stops leaves the caret on its final stop`() = onEdt {
        val id = open("x\n")

        insert(id, $$"call($0)", range(1, 2))

        assertEquals("xcall()\n", text())
        assertEquals(6, fixture.editor.caretModel.offset)
        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
    }

    @Test
    fun `a final stop written twice puts a caret on each`() = onEdt {
        val id = open("\n")

        insert(id, $$"eprintln!(\"$0 = {:?}\", $0);", range(1, 1))
        fixture.type("x")

        assertEquals("eprintln!(\"x = {:?}\", x);\n", text())
        assertEquals(listOf(12, 23), fixture.editor.caretModel.allCarets.map { it.offset })
    }

    @Test
    fun `leaving the last stop puts a caret on every final stop`() = onEdt {
        val id = open("\n")

        insert(id, $$"f(${1:a}, $0, $0)", range(1, 1))
        template().nextTab()

        assertEquals(listOf(5, 7), fixture.editor.caretModel.allCarets.map { it.offset })
    }

    @Test
    fun `escaping the template adds no carets, as VS Code leaves its cursors alone`() = onEdt {
        val id = open("\n")

        insert(id, $$"f(${1:a}, $0, $0)", range(1, 1))
        template().gotoEnd(true)

        assertEquals(1, fixture.editor.caretModel.caretCount)
    }

    @Test
    fun `a final stop with a value has it selected, as VS Code selects it`() = onEdt {
        val id = open("call()\n")

        insert(id, $$"${0:todo!()}", range(1, 6))

        assertEquals("call(todo!())\n", text())
        assertEquals("todo!()", selected())
    }

    @Test
    fun `leaving the last stop selects the final stop's value`() = onEdt {
        val id = open("\n")

        insert(id, $$"f(${1:a}, ${0:b})", range(1, 1))
        template().nextTab()

        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertEquals("f(a, b)\n", text())
        assertEquals("b", selected())
    }

    @Test
    fun `whitespace is kept as written when the snippet says so`() = onEdt {
        val id = open("    \n")

        insert(id, "a\nb$1", range(1, 5), options = """{"keepWhitespace":true}""")

        assertEquals("    a\nb\n", text())
    }

    @Test
    fun `a snippet edit to the active editor is walked there, one to another file is text`() = onEdt {
        open("fn f() {}\n")
        val other = Files.createTempFile("codebridge-snippets-other", ".txt")
        Files.writeString(other, "\n")
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other))
        val here = UriComponents.of(path).toJson()
        val there = UriComponents.of(other).toJson()

        val reply = started { features.applyWorkspaceEdit(
            $$"""[{"edits":[
            {"resource":$$here,"textEdit":{"range":$${range(1, 1)},"text":"use x;\n"},"versionId":1},
            {"resource":$$here,"textEdit":{"range":$${
                range(
                    1,
                    9
                )
            },"text":"${1:body}$0","insertAsSnippet":true,"keepWhitespace":true},"versionId":1},
            {"resource":$$there,"textEdit":{"range":$${range(1, 1)},"text":"${1:plain}","insertAsSnippet":true}}
        ]},null,false]"""
        ) }

        assertEquals("true", await(reply).json)
        assertEquals("use x;\nfn f() {body}\n", text())
        assertEquals("body", selected())
        fixture.type("1")
        template().nextTab()
        assertEquals("use x;\nfn f() {1}\n", text())
        val otherFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(other))
        assertEquals("plain\n", FileDocumentManager.getInstance().getDocument(otherFile)!!.text)
    }

    @Test
    fun `the snippet is one undo step, the typing into it another`() = onEdt {
        val id = open("x\n")
        insert(id, $$"(${1:a})", range(1, 2))
        fixture.type("b")
        template().gotoEnd(false)
        assertEquals("x(b)\n", text())

        val states = mutableListOf<String>()
        repeat(4) {
            val undo = UndoManager.getInstance(fixture.project)
            val editor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)
            if (undo.isUndoAvailable(editor)) {
                undo.undo(editor); states += text()
            }
        }

        // The first undo is the template finishing, which changes no text; the platform records it.
        assertEquals(listOf("x(b)\n", "x(a)\n", "x\n"), states)
    }

    /** Opens a mirrored file holding [content], returning the id its editor has in the host. */
    private fun open(content: String): String {
        path = Files.createTempFile("codebridge-snippets", ".txt")
        Files.writeString(path, content)
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(fixture.file.virtualFile, "rust", HostedLanguageFeatures.Snapshot(content, false))
        return "\"${features.editorOf(fixture.file.virtualFile)}\""
    }

    /** `$tryInsertSnippet` at [ranges], against the text the file was opened with. */
    private fun insert(id: String, snippet: String, vararg ranges: String, options: String = "{}") {
        val template = kotlinx.serialization.json.JsonPrimitive(snippet).toString()
        val reply = started { editors.handle($$"$tryInsertSnippet", "[$id,1,$template,[${ranges.joinToString(",")}],$options]") }
        assertEquals("true", await(reply).json)
    }

    private fun range(line: Int, column: Int) =
        """{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$line,"endColumn":$column}"""

    private fun text() = fixture.editor.document.text

    private fun selected() = fixture.editor.selectionModel.selectedText

    private fun template(): TemplateState = assertNotNull(TemplateManagerImpl.getTemplateState(fixture.editor))

    private fun await(future: CompletableFuture<MainThreadResult>): MainThreadResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }
}
