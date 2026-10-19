package me.helvert.codebridge.ide

import com.intellij.formatting.service.FormattingService
import com.intellij.formatting.service.FormattingServiceUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.Formatting
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeFormattingService
import me.helvert.codebridge.language.CodeBridgeTextEdits
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reformat Code, answered by an extension's formatter through the platform's own formatting path.
 *
 * The fixture answers as the formatter would. What is checked is the IntelliJ half: that a file is
 * claimed only by a formatter whose selector names it, the edits landing as offsets into the right
 * text, and a range reformat going to the range formatter with the range it asked about.
 */
class FormattingTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val sent = ConcurrentLinkedQueue<Pair<String, String>>()

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        features.hostStopped()
        // The fixture does not load the plugin's descriptor; `CodeActionTest` checks the registration.
        FormattingService.EP_NAME.point.registerExtension(CodeBridgeFormattingService(), LoadingOrder.FIRST, fixture.testRootDisposable)
    }

    @Test
    fun `a formatter for another language leaves the file alone`() = onEdt {
        open("a  =  1\n")
        features.handle("MainThreadLanguageFeatures", $$"$registerDocumentFormattingSupport", """[3,[{"language":"python"}],{},"Other"]""")
        assertFalse(features.canFormat(fixture.file.virtualFile))
        reformat()
        assertEquals("a  =  1\n", fixture.editor.document.text)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `reformat applies the whole-document formatter's edits`() = onEdt {
        open("a  =  1\nb=2\n")
        features.handle("MainThreadLanguageFeatures", $$"$registerDocumentFormattingSupport", """[3,[{"language":"test","scheme":"file"}],{},"Test"]""")
        answer("""[
            {"text":" ","range":{"startLineNumber":1,"startColumn":2,"endLineNumber":1,"endColumn":4}},
            {"text":" ","range":{"startLineNumber":1,"startColumn":5,"endLineNumber":1,"endColumn":7}},
            {"text":"b = 2","range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":4}}
        ]""")
        assertTrue(features.canFormat(fixture.file.virtualFile))
        assertTrue(FormattingServiceUtil.findService(fixture.file, true, true) is CodeBridgeFormattingService)
        reformat()
        assertEquals("a = 1\nb = 2\n", fixture.editor.document.text)
        val (method, args) = sent.single()
        assertEquals($$"$provideDocumentFormattingEdits", method)
        assertTrue(args.endsWith("""{"tabSize":4,"insertSpaces":true}]"""), args)
    }

    @Test
    fun `reformatting a selection asks the range formatter about that range`() = onEdt {
        open("a  =  1\nb=2\n")
        features.handle("MainThreadLanguageFeatures", $$"$registerRangeFormattingSupport", """[4,[{"language":"test"}],{},"Test",false]""")
        answer("""[{"text":"b = 2","range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":4}}]""")
        reformat(TextRange(8, 11))
        assertEquals("a  =  1\nb = 2\n", fixture.editor.document.text)
        val (method, args) = sent.single()
        assertEquals($$"$provideDocumentRangeFormattingEdits", method)
        assertTrue(args.contains(""""startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":4"""), args)
    }

    @Test
    fun `a whole-document formatter asked about a selection changes only the selection`() = onEdt {
        open("a  =  1\nb=2\n")
        features.handle("MainThreadLanguageFeatures", $$"$registerDocumentFormattingSupport", """[3,[{"language":"test"}],{},"Test"]""")
        answer("""[
            {"text":" ","range":{"startLineNumber":1,"startColumn":2,"endLineNumber":1,"endColumn":4}},
            {"text":"b = 2","range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":4}}
        ]""")
        reformat(TextRange(8, 11))
        assertEquals("a  =  1\nb = 2\n", fixture.editor.document.text)
    }

    @Test
    fun `a formatter with nothing to change leaves the file alone without complaint`() = onEdt {
        open("a = 1\n")
        features.handle("MainThreadLanguageFeatures", $$"$registerDocumentFormattingSupport", """[3,[{"language":"test"}],{},"Test"]""")
        // rust-analyzer's answer for a file that is already formatted: no edits, rather than no answer.
        answer("null")
        val text = fixture.editor.document.text
        val result = features.formatting(fixture.file.virtualFile, null, CodeBridgeTextEdits.whole(text), Formatting.Options(4, true))
        assertEquals(emptyList(), assertNotNull(result).edits)
        reformat()
        assertEquals("a = 1\n", fixture.editor.document.text)
    }

    private fun open(text: String) {
        // Inside the project: the platform does not let an asynchronous formatter touch an untrusted file.
        val path = Files.createTempFile(java.nio.file.Path.of(fixture.tempDirPath), "codebridge-format", ".txt")
        Files.writeString(path, text)
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(fixture.file.virtualFile, "test", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
    }

    private fun answer(reply: String) = features.callHostForTest { _, method, args, _ ->
        sent += method to args
        reply
    }

    private fun reformat(range: TextRange? = null) {
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            val manager = CodeStyleManager.getInstance(fixture.project)
            if (range == null) manager.reformat(fixture.file)
            else manager.reformatText(fixture.file, range.startOffset, range.endOffset)
        }
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    }
}
