package me.helvert.codebridge.ide

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeHighlightUsagesHandlerFactory
import me.helvert.codebridge.language.identifierRange
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolHighlightingTest : EditorFixture() {
    @Test
    fun `caret highlights distinguish reads and writes and discard stale ranges`() = onEdt {
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        features.hostStopped()
        val path = Files.createTempFile("codebridge-highlights", ".txt")
        Files.writeString(path, "value = 1\nvalue\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        fixture.editor.caretModel.moveToOffset(12)
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerDocumentHighlightProvider",
            """[7,[{"language":"test"}]]"""
        )
        var reply = """[
            {"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":6},"kind":3},
            {"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":6},"kind":2},
            {"range":{"startLineNumber":99,"startColumn":1,"endLineNumber":99,"endColumn":6},"kind":2},
            {"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1}}
        ]"""
        features.callHostForTest { service, method, args, cancellable ->
            assertEquals("ExtHostLanguageFeatures", service)
            assertEquals($$"$provideDocumentHighlights", method)
            assertTrue(args.contains("\"lineNumber\":2,\"column\":3"))
            assertTrue(cancellable)
            reply
        }
        val handler = requireNotNull(
            CodeBridgeHighlightUsagesHandlerFactory().createHighlightUsagesHandler(
                fixture.editor,
                fixture.file
            )
        )
        handler.computeUsages(handler.targets)
        assertEquals(listOf(TextRange(0, 5)), handler.writeUsages)
        assertEquals(listOf(TextRange(10, 15)), handler.readUsages)
        reply = "[]"
        handler.computeUsages(handler.targets)
        assertTrue(handler.readUsages.isEmpty())
        assertTrue(handler.writeUsages.isEmpty())
        features.handle("MainThreadLanguageFeatures", $$"$unregister", "[7]")
        assertNull(CodeBridgeHighlightUsagesHandlerFactory().createHighlightUsagesHandler(fixture.editor, fixture.file))
        features.hostStopped()
    }

    @Test
    fun `fallback range handles word ends unicode and punctuation`() {
        assertEquals(TextRange(2, 7), identifierRange("  naïve()", 4))
        assertEquals(TextRange(2, 7), identifierRange("  naïve()", 7))
        assertNull(identifierRange("  naïve()", 8))
        assertNull(identifierRange("", 0))
        assertNull(identifierRange("abc", -1))
        assertNull(identifierRange("abc", 4))
    }
}
