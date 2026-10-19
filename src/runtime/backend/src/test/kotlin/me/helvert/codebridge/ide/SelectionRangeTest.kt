package me.helvert.codebridge.ide

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeSelectionRangeHandler
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SelectionRangeTest : EditorFixture() {

    @Test
    fun `extend selection grows through the ranges the extension names`() = onEdt {
        val features = open()
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerSelectionRangeProvider",
            """[4,[{"language":"test"}]]"""
        )
        var asked = 0
        features.callHostForTest { service, method, args, cancellable ->
            assertEquals("ExtHostLanguageFeatures", service)
            assertEquals($$"$provideSelectionRanges", method)
            assertTrue(args.contains("""[{"lineNumber":1,"column":6}]"""), args)
            assertTrue(cancellable)
            asked++
            
                """[[
                ${range(1, 5, 1, 8)},
                ${range(1, 5, 1, 14)},
                ${range(99, 1, 99, 5)},
                ${range(1, 1, 1, 19)}
            ]]"""
            
        }
        fixture.editor.caretModel.moveToOffset(5)

        assertEquals(TextRange(4, 7), extend())
        // "two three": no range of the IDE's own, so only the extension can have offered it.
        assertEquals(TextRange(4, 13), extend())
        assertEquals(TextRange(0, 18), extend())
        // Once per press, not once per element the platform walks through.
        assertEquals(3, asked)
        features.hostStopped()
    }

    @Test
    fun `a provider for another language is not asked`() = onEdt {
        val features = open()
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerSelectionRangeProvider",
            """[4,[{"language":"other"}]]"""
        )
        features.callHostForTest { _, method, _, _ -> error("$method should not have been asked") }
        fixture.editor.caretModel.moveToOffset(5)

        assertEquals(TextRange(4, 7), extend())
        assertNotEquals(TextRange(4, 13), extend())
        features.hostStopped()
    }

    @Test
    fun `an unregistered provider is not asked`() = onEdt {
        val features = open()
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerSelectionRangeProvider",
            """[4,[{"language":"test"}]]"""
        )
        features.handle("MainThreadLanguageFeatures", $$"$unregister", "[4]")
        features.callHostForTest { _, method, _, _ -> error("$method should not have been asked") }
        fixture.editor.caretModel.moveToOffset(5)

        assertEquals(TextRange(4, 7), extend())
        assertNotEquals(TextRange(4, 13), extend())
        features.hostStopped()
    }

    private fun open(): HostedLanguageFeatures {
        ExtendWordSelectionHandler.EP_NAME.point.registerExtension(
            CodeBridgeSelectionRangeHandler(),
            fixture.testRootDisposable
        )
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.hostStopped()
        val path = Files.createTempFile("codebridge-selection", ".txt")
        Files.writeString(path, "one two three four\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        return features
    }

    private fun extend(): TextRange {
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
        val selection = fixture.editor.selectionModel
        return TextRange(selection.selectionStart, selection.selectionEnd)
    }

    private fun range(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        """{"range":{"startLineNumber":$startLine,"startColumn":$startColumn,"endLineNumber":$endLine,"endColumn":$endColumn}}"""
}
