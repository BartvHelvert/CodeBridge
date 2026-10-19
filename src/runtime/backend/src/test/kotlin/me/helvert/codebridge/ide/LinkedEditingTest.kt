package me.helvert.codebridge.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeLinkedEditing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkedEditingTest : EditorFixture() {

    private var asked = 0

    @AfterEach
    fun forgetSettings() = onEdt { fixture.project.service<HostedLanguageServers>().settingsForTest = null }

    @Test
    fun `typing in one tag name types into the other, and one undo takes both back`() = onEdt {
        val features = open()
        startAt(3)

        assertEquals(listOf(TextRange(1, 4), TextRange(8, 11)), linked().rangesForTest())
        assertEquals(listOf(TextRange(1, 4), TextRange(8, 11)), outlined())
        fixture.type("a")
        assertEquals("<diav>x</diav>\n", fixture.editor.document.text)
        fixture.type("b")
        assertEquals("<diabv>x</diabv>\n", fixture.editor.document.text)
        // The outline grows with what is typed at its edge.
        assertEquals(listOf(TextRange(1, 6), TextRange(10, 15)), outlined())

        val editor =
            FileEditorManagerEx.getInstanceEx(fixture.project).getSelectedEditor(fixture.file.virtualFile) as TextEditor
        UndoManager.getInstance(fixture.project).undo(editor)
        // IntelliJ undoes consecutive typing as one step; the copies go with it rather than staying behind.
        assertEquals("<div>x</div>\n", fixture.editor.document.text)
        features.hostStopped()
    }

    @Test
    fun `replacing a whole tag name replaces the other`() = onEdt {
        val features = open()
        startAt(3)

        fixture.editor.selectionModel.setSelection(1, 4)
        fixture.type("span")

        assertEquals("<span>x</span>\n", fixture.editor.document.text)
        features.hostStopped()
    }

    @Test
    fun `text the word pattern does not allow ends the session`() = onEdt {
        val features = open()
        startAt(4)

        fixture.type(" ")
        fixture.type("a")

        assertEquals("<div a>x</div>\n", fixture.editor.document.text)
        assertTrue(linked().rangesForTest().isEmpty())
        features.hostStopped()
    }

    @Test
    fun `an edit outside the ranges ends the session`() = onEdt {
        val features = open()
        startAt(3)

        WriteCommandAction.runWriteCommandAction(fixture.project) { fixture.editor.document.insertString(5, "y") }

        assertTrue(linked().rangesForTest().isEmpty())
        assertTrue(outlined().isEmpty(), "The outline goes with the session")
        features.hostStopped()
    }

    @Test
    fun `nothing is asked with linkedEditing off, as it is by default`() = onEdt {
        val features = open(settings = "{}")
        fixture.editor.caretModel.moveToOffset(3)

        assertNull(linked().askNow(fixture.editor))
        assertEquals(0, asked)
        features.hostStopped()
    }

    private fun linked() = CodeBridgeLinkedEditing.getInstance(fixture.project)

    /** The ranges painted in VS Code's linked editing background. */
    private fun outlined(): List<TextRange> =
        DocumentMarkupModel.forDocument(fixture.editor.document, fixture.project, true).allHighlighters
            .filter { it.isValid && it.getTextAttributes(null)?.backgroundColor == Color(255, 0, 0, 77) }
            .map { it.textRange }.sortedBy { it.startOffset }

    /** Puts the caret at [offset] and waits for the ranges there. */
    private fun startAt(offset: Int) {
        fixture.editor.caretModel.moveToOffset(offset)
        val job = requireNotNull(linked().askNow(fixture.editor))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!job.isCompleted && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(job.isCompleted, "No answer in time")
    }

    /** `<div>x</div>`, whose tag names an extension links, allowing only letters in them. */
    private fun open(settings: String = """{"editor.linkedEditing": true}"""): HostedLanguageFeatures {
        fixture.project.service<HostedLanguageServers>().settingsForTest = Json.parseToJsonElement(settings).jsonObject
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.hostStopped()
        val path = Files.createTempFile("codebridge-linked", ".txt")
        Files.writeString(path, "<div>x</div>\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerLinkedEditingRangeProvider",
            """[8,[{"language":"test"}]]"""
        )
        features.callHostForTest { _, method, _, _ ->
            assertEquals($$"$provideLinkedEditingRanges", method)
            asked++
            
                """{"ranges":[${range(2, 5)},${range(9, 12)}],"wordPattern":{"pattern":"[a-z]+","flags":""}}"""
            
        }
        return features
    }

    private fun range(startColumn: Int, endColumn: Int) =
        """{"startLineNumber":1,"startColumn":$startColumn,"endLineNumber":1,"endColumn":$endColumn}"""
}
