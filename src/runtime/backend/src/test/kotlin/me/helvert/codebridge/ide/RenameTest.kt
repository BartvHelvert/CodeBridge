package me.helvert.codebridge.ide

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.refactoring.rename.Renamer
import com.intellij.testFramework.PlatformTestUtil
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeRenamerFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * Rename, from the symbol at the caret to the edit an extension answered with.
 *
 * No host runs here, so the fixture answers `$resolveRenameLocation` and `$provideRenameEdits` as an
 * extension would. The new name is typed into the template, or into the dialog where there is none.
 */
@Suppress("OverrideOnly")
class RenameTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        asked.clear()
        features.hostStopped()
        TemplateManagerImpl.setTemplateTesting(fixture.testRootDisposable)
    }

    @AfterEach
    fun stopTyping() {
        TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }

    @Test
    fun `the plugin registers the renamer`() {
        val descriptor = Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeRenamerFactory"))
    }

    @Test
    fun `nothing is offered without a rename provider`() = onEdt {
        open("call symbol")

        assertTrue(renamers().isEmpty())
    }

    @Test
    fun `the name is typed in place, and the extension's edit is applied across files`() = onEdt {
        val other = file("other.txt", "one symbol\n")
        val source = open("first line\ncall symbol")
        register(handle = 4, resolvesLocation = true)
        answer(
            location = """{"range":${range(2, 6, 12)},"text":"symbol"}""",
            edits = mapOf(4 to edit(edit(source, 2, 6, 12, "total"), edit(other, 1, 5, 11, "total"))),
        )

        renamers().single().performRename()
        val template = template()
        assertEquals("symbol", fixture.editor.selectionModel.selectedText, "The name is selected for typing over")
        fixture.type("total")
        assertEquals("first line\ncall total", fixture.editor.document.text)
        finish(template)

        assertTrue(sent.any { it.contains(POSITION) && it.endsWith(""","total"]""") }, "Asked with the new name: $sent")
        assertEquals("first line\ncall total", fixture.editor.document.text)
        assertEquals("one total\n", FileDocumentManager.getInstance().getDocument(other)?.text)
    }

    @Test
    fun `the occurrences the extension highlights follow the typing`() = onEdt {
        val source = open("symbol = 1\ncall symbol")
        register(handle = 4, resolvesLocation = true)
        features.handle(
            "MainThreadLanguageFeatures", $$"$registerDocumentHighlightProvider", """[6,[{"language":"codebridgebundled"}]]""",
        )
        answer(
            location = """{"range":${range(2, 6, 12)},"text":"symbol"}""",
            edits = mapOf(4 to edit(edit(source, 1, 1, 7, "total"), edit(source, 2, 6, 12, "total"))),
            highlights = """[{"range":${range(1, 1, 7)},"kind":3},{"range":${range(2, 6, 12)},"kind":2}]""",
        )

        renamers().single().performRename()
        val template = template()
        fixture.type("tot")
        assertEquals("tot = 1\ncall tot", fixture.editor.document.text)
        fixture.type("al")
        finish(template)

        assertEquals("total = 1\ncall total", fixture.editor.document.text)
    }

    @Test
    fun `one undo takes the whole rename back`() = onEdt {
        val source = open("call symbol")
        register(handle = 4, resolvesLocation = true)
        answer(
            location = """{"range":${range(1, 6, 12)},"text":"symbol"}""",
            edits = mapOf(4 to edit(edit(source, 1, 6, 12, "total"))),
        )

        renamers().single().performRename()
        val template = template()
        fixture.type("total")
        finish(template)
        assertEquals("call total", fixture.editor.document.text)

        // The edit is a global command, which the platform asks before undoing, as it does a refactoring.
        TestDialogManager.setTestDialog(TestDialog.OK)
        UndoManager.getInstance(fixture.project).undo(textEditor())

        assertEquals("call symbol", fixture.editor.document.text)
    }

    @Test
    fun `breaking off the template puts the name back and renames nothing`() = onEdt {
        open("call symbol")
        register(handle = 4, resolvesLocation = true)
        answer(location = """{"range":${range(1, 6, 12)},"text":"symbol"}""", edits = emptyMap())

        renamers().single().performRename()
        val template = template()
        fixture.type("total")
        template.gotoEnd(true)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertTrue(sent.none { it.contains("total") })
        assertEquals("call symbol", fixture.editor.document.text)
    }

    @Test
    fun `a refusal is shown instead of asking for a name`() = onEdt {
        open("call symbol")
        register(handle = 4, resolvesLocation = true)
        answer(location = """{"rejectReason":"Cannot rename a keyword"}""", edits = emptyMap())
        TestDialogManager.setTestInputDialog { fail("Asked for a name after the extension refused: $it") }

        renamers().single().performRename()

        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertTrue(sent.none { it.contains("total") })
        assertEquals("call symbol", fixture.editor.document.text)
    }

    @Test
    fun `without a location the word at the caret is renamed, by the first provider that will`() = onEdt {
        val source = open("call symbol")
        register(handle = 4, resolvesLocation = false)
        register(handle = 5, resolvesLocation = false)
        answer(
            location = null,
            edits = mapOf(
                4 to """{"rejectReason":"Not mine"}""",
                5 to edit(edit(source, 1, 6, 12, "total")),
            ),
        )

        renamers().single().performRename()
        val template = template()
        fixture.type("total")
        finish(template)

        assertEquals("call total", fixture.editor.document.text)
    }

    @Test
    fun `a name that is not the text there is asked for in a dialog`() = onEdt {
        val source = open("call r#symbol")
        register(handle = 4, resolvesLocation = true)
        answer(
            location = """{"range":${range(1, 6, 14)},"text":"symbol"}""",
            edits = mapOf(4 to edit(edit(source, 1, 6, 14, "total"))),
        )
        type("total")

        renamers().single().performRename()

        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertEquals(listOf("Rename 'symbol' to:"), asked.toList())
        assertEquals("call total", fixture.editor.document.text)
    }

    private fun template(): TemplateState =
        assertNotNull(TemplateManagerImpl.getTemplateState(fixture.editor), "No template was started")

    /** Enter, as the user finishes the template, and whatever the rename then queued. */
    private fun finish(template: TemplateState) {
        template.gotoEnd(false)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun textEditor(): TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)

    private fun renamers(): Collection<Renamer> =
        CodeBridgeRenamerFactory().createRenamers(SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, fixture.editor))

    private fun register(handle: Int, resolvesLocation: Boolean) = features.handle(
        "MainThreadLanguageFeatures", $$"$registerRenameSupport", """[$handle,[{"language":"codebridgebundled"}],$resolvesLocation]""",
    )

    /**
     * Answers rename requests: [location] for every provider, each provider's own [edits], and the
     * document [highlights] for every highlight provider.
     */
    private fun answer(location: String?, edits: Map<Int, String>, highlights: String = "[]") {
        features.callHostForTest { _, method, args, _ ->
            sent.add(args)
            val handle = args.removePrefix("[").substringBefore(",").toInt()
            when (method) {
                $$"$resolveRenameLocation" -> location ?: "null"
                $$"$provideRenameEdits" -> edits[handle] ?: "null"
                $$"$provideDocumentHighlights" -> highlights
                else -> "null"
            }
        }
    }

    /** Types [name] into the dialog, and remembers what it asked. */
    @Suppress("SameParameterValue")
    private fun type(name: String) {
        TestDialogManager.setTestInputDialog(object : TestInputDialog {
            override fun show(message: String): String = name.also { asked.add(message) }
            override fun show(message: String, validator: InputValidator?): String {
                assertTrue(validator?.checkInput(name) != false, "'$name' was refused")
                return show(message)
            }
        })
    }

    private fun edit(vararg entries: String) = """{"edits":[${entries.joinToString(",")}]}"""

    @Suppress("SameParameterValue")
    private fun edit(file: VirtualFile, line: Int, start: Int, end: Int, text: String) =
        """{"resource":${uri(file.toNioPath())},"textEdit":{"range":${range(line, start, end)},"text":"$text"}}"""

    private fun uri(path: Path) = UriComponents.of(path).toJson().toString()

    private fun range(line: Int, start: Int, end: Int) =
        """{"startLineNumber":$line,"startColumn":$start,"endLineNumber":$line,"endColumn":$end}"""

    private fun file(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    /** Opens and mirrors [text], with the caret inside its last word. */
    private fun open(text: String): VirtualFile {
        val file = file("source.txt", text)
        fixture.openFileInEditor(file)
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "codebridgebundled", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.textLength - 1)
        return file
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val sent = ConcurrentLinkedQueue<String>()

    private val asked = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-rename") }

    private companion object {
        /** The caret, near the end of `symbol` on the second line: rename is asked where the caret is. */
        const val POSITION = """{"lineNumber":2,"column":11}"""
    }
}
