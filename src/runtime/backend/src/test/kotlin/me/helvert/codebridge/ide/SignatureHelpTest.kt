package me.helvert.codebridge.ide

import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lexer.EmptyLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.SignatureHelp
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeSignatureHelpHandler
import me.helvert.codebridge.language.CodeBridgeSignatureHelpTypedHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signature help, as Parameter Info.
 *
 * No host runs here, so the fixture answers `$provideSignatureHelp` as an extension would. What is
 * covered is the IntelliJ side: when the extension is asked, what it is told about why, what the
 * popup draws, and that the popup follows the extension rather than the PSI -- which, in a file an
 * extension claims, knows nothing about calls.
 */
class SignatureHelpTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        released.clear()
        // The light fixture's project is reused between tests in this class, and so is the service.
        features.hostStopped()
        // Light fixtures do not load our module descriptor. Register its bean through the real EP.
        ExtensionPointName.create<LanguageExtensionPoint<ParameterInfoHandler<*, *>>>("com.intellij.codeInsight.parameterInfo")
            .point.registerExtension(
                LanguageExtensionPoint(
                    "", CodeBridgeSignatureHelpHandler::class.java.name,
                    DefaultPluginDescriptor(PluginId.getId("me.helvert.codebridge"), handler.javaClass.classLoader)
                ),
                fixture.testRootDisposable,
            )
    }

    @Test
    fun `the plugin registers the handler for every language and the typing trigger`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("""<codeInsight.parameterInfo language="" implementationClass="me.helvert.codebridge.language.CodeBridgeSignatureHelpHandler"/>"""))
        assertTrue(descriptor.contains("""<typedHandler implementation="me.helvert.codebridge.language.CodeBridgeSignatureHelpTypedHandler"/>"""))
    }

    @Test
    fun `parameter info discovers the handler for a mirrored plain text file`() = onEdt {
        open("add(")
        assertTrue(
            ShowParameterInfoHandler.getHandlers(fixture.project, fixture.file.language)
                .any { it is CodeBridgeSignatureHelpHandler })
    }

    @Test
    fun `the owner anchors the remote popup at the call and survives typing`() = onEdt {
        open("earlier line\nadd(")
        answer(help(activeParameter = 0))
        val context = create()
        val owner = requireNotNull(handler.findElementForParameterInfo(context))
        val items = requireNotNull(context.itemsToShow)
        val anchor = fixture.editor.caretModel.offset - 1

        // Split mode sends this range to the frontend, which uses its start as the popup anchor.
        assertEquals(anchor, owner.textRange.startOffset)
        assertEquals(fixture.editor.document.textLength, owner.textRange.endOffset)
        type("1, ")
        assertTrue(owner.isValid)
        assertEquals(owner, handler.findElementForUpdatingParameterInfo(update(items)))
        assertEquals(anchor, owner.textRange.startOffset)
        assertEquals(fixture.editor.document.textLength, owner.textRange.endOffset)
    }

    @Test
    fun `nothing is asked when no extension offers signature help`() = onEdt {
        open("add(")
        features.callHostForTest { _, _, args, _ -> sent.add(args); NONE }

        assertNull(handler.findElementForParameterInfo(create()))
        assertTrue(sent.isEmpty(), "No provider, no request: $sent")
    }

    @Test
    fun `typing a trigger character asks with that character, at the caret`() = onEdt {
        open("line\nadd(")
        answer(help(activeParameter = 0))

        val context = create()
        assertEquals(fixture.file, handler.findElementForParameterInfo(context)?.containingFile)

        val provide = sent.single()
        assertTrue(provide.contains("""{"lineNumber":2,"column":5}"""), "After the bracket on line 2: $provide")
        assertTrue(provide.contains(""""triggerKind":2"""), provide)
        assertTrue(provide.contains(""""triggerCharacter":"(""""), provide)
        assertEquals(1, context.itemsToShow?.size)
    }

    @Test
    fun `asked for anywhere else, it is invoked`() = onEdt {
        open("add(a")
        answer(help(activeParameter = 0))

        handler.findElementForParameterInfo(create())

        assertTrue(sent.single().contains(""""triggerKind":1"""), sent.single())
        assertFalse(sent.single().contains("triggerCharacter"))
    }

    @Test
    fun `the parameter being typed is drawn in bold, with its documentation`() = onEdt {
        open("add(1, ")
        answer(help(activeParameter = 1))
        val items = requireNotNull(create().also { handler.findElementForParameterInfo(it) }.itemsToShow)

        val ui = MockParameterInfoUIContext(fixture.file)
        handler.updateUI(items.single() as CodeBridgeSignatureHelpHandler.Item, ui)

        assertTrue(ui.isTextRaw)
        assertEquals(
            "add(a: i32, <b>b: i32</b>)<br>The second &lt;one&gt;.",
            ui.text.replace(Regex("</?span\\b[^>]*>"), "")
        )
    }

    @Test
    fun `syntax colors preserve active emphasis and escape the signature`() = onEdt {
        // A token covering the whole label verifies splitting a token at parameter boundaries.
        val syntax = object : SyntaxHighlighterBase() {
            override fun getHighlightingLexer() = EmptyLexer()
            override fun getTokenHighlights(tokenType: IElementType) = arrayOf(DefaultLanguageHighlighterColors.KEYWORD)
        }
        val highlighter = LexerEditorHighlighter(syntax, EditorColorsManager.getInstance().globalScheme)
        val signature = SignatureHelp.Signature(
            "f(x: Vec<T>)", null,
            listOf(SignatureHelp.Parameter(2 until 11, null)), null
        )
        val html = CodeBridgeSignatureHelpHandler.html(signature, 0, highlighter)
        assertTrue(html.contains("color:"), html)
        assertEquals("f(<b>x: Vec&lt;T&gt;</b>)", html.replace(Regex("</?span\\b[^>]*>"), ""))
    }

    @Test
    fun `an update asks again, as a retrigger that hands back the help on screen`() = onEdt {
        open("add(1")
        answer(help(activeParameter = 0, id = 7))
        val items = requireNotNull(create().also { handler.findElementForParameterInfo(it) }.itemsToShow)

        type(", ")
        answer(help(activeParameter = 1, id = 8))
        val update = update(items)
        assertEquals(owner(items), handler.findElementForUpdatingParameterInfo(update))
        handler.updateParameterInfo(owner(items), update)

        val provide = sent.last()
        assertTrue(provide.contains(""""triggerKind":3"""), "The caret moved past a space: $provide")
        assertTrue(provide.contains(""""isRetrigger":true"""), provide)
        assertTrue(provide.contains(""""activeSignatureHelp":{"id":7"""), provide)
        assertEquals(1, update.currentParameter)
        assertEquals(listOf("[3,7]"), released.toList(), "The help it replaced is let go")
    }

    @Test
    fun `an update about the text the popup was opened for does not ask again`() = onEdt {
        open("add(")
        answer(help(activeParameter = 0))
        val items = requireNotNull(create().also { handler.findElementForParameterInfo(it) }.itemsToShow)

        // The controller updates the moment it shows the popup.
        handler.updateParameterInfo(owner(items), update(items))

        assertEquals(1, sent.size, "One request for one popup: $sent")
    }

    @Test
    fun `the popup closes when the extension has nothing more to say`() = onEdt {
        open("add(1")
        answer(help(activeParameter = 0))
        val items = requireNotNull(create().also { handler.findElementForParameterInfo(it) }.itemsToShow)

        type(")")
        answer(NONE)
        val update = update(items)
        handler.updateParameterInfo(owner(items), update)

        assertTrue(sent.last().contains(""""triggerCharacter":")""""), "`)` is a retrigger character: ${sent.last()}")
        assertTrue(update.removed)
    }

    @Test
    fun `a reply without signatures is let go and shows nothing`() = onEdt {
        open("add(")
        answer("""{"id":4,"signatures":[],"activeSignature":0,"activeParameter":0}""")

        assertNull(handler.findElementForParameterInfo(create()))
        assertEquals(listOf("[3,4]"), released.toList())
    }

    @Test
    fun `the characters that open the popup are those of the file's providers`() = onEdt {
        open("add")
        answer(help(activeParameter = 0))
        val typed = CodeBridgeSignatureHelpTypedHandler()

        // A popup only opens in a shown editor, which a light fixture has none of; what can go
        // wrong here is reading the triggers.
        typed.checkAutoPopup('(', fixture.project, fixture.editor, fixture.file)
        typed.checkAutoPopup('x', fixture.project, fixture.editor, fixture.file)
        val triggers = features.signatureHelpTriggers(fixture.file.virtualFile)
        assertEquals(setOf("(", ","), triggers?.triggers)
        assertEquals(setOf(")"), triggers?.retriggers)
    }

    private val handler = CodeBridgeSignatureHelpHandler()

    private fun create() = MockCreateParameterInfoContext(fixture.editor, fixture.file)

    private fun owner(items: Array<Any>) = (items.first() as CodeBridgeSignatureHelpHandler.Item).held.owner

    private fun update(items: Array<Any>) = RecordingUpdateContext(items).also { it.parameterOwner = owner(items) }

    /** Records what the platform's own mock throws away: whether the popup was told to close. */
    private inner class RecordingUpdateContext(items: Array<Any>) :
        MockUpdateParameterInfoContext(fixture.editor, fixture.file, items) {
        var removed = false
        override fun removeHint() {
            removed = true
        }
    }

    private fun type(text: String) {
        fixture.type(text)
        features.documentChanged(
            fixture.file.virtualFile,
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, true)
        )
    }

    /** Registers a provider under handle 3 for the mirrored language, answering every request with [reply]. */
    private fun answer(reply: String) {
        features.handle(
            "MainThreadLanguageFeatures", SignatureHelp.REGISTER,
            """[3,[{"language":"codebridgebundled"}],{"triggerCharacters":["(",","],"retriggerCharacters":[")"]}]""",
        )
        features.callHostForTest { _, method, args, _ ->
            when (method) {
                SignatureHelp.PROVIDE -> sent.add(args)
                SignatureHelp.RELEASE -> released.add(args)
            }
            if (method == SignatureHelp.PROVIDE) reply else NONE
        }
    }

    private fun help(activeParameter: Int, id: Int = 1) =
        """{"id":$id,"activeSignature":0,"activeParameter":$activeParameter,"signatures":[{"label":"add(a: i32, b: i32)",
           "parameters":[{"label":"a: i32"},{"label":"b: i32","documentation":"The second <one>."}]}]}"""

    /** A real file, as for definitions: the URI is built from `VirtualFile.toNioPath`. Caret at the end. */
    private fun open(text: String) {
        val path = directory.resolve("source.txt").also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        mirror(file)
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.textLength)
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private fun mirror(file: VirtualFile) {
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            file,
            "codebridgebundled",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
    }

    private val sent = ConcurrentLinkedQueue<String>()
    private val released = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-signature-help") }

    private companion object {
        const val NONE = "null"
    }
}
