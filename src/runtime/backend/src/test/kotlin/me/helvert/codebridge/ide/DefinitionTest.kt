package me.helvert.codebridge.ide

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeDefinitionSymbol
import me.helvert.codebridge.language.CodeBridgeSymbolReferenceProvider
import me.helvert.codebridge.workbench.HostedContentProviders
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Go To Declaration, from the caret to the place an extension named.
 *
 * The round trip to the extension needs a live host, so that half is covered by
 * `LanguageFeaturesIntegrationTest` against a real one. What is covered here is everything on the
 * IntelliJ side: whether the host is asked at all, the caret becoming the 1-based position VS Code
 * expects, and -- the part with any arithmetic in it -- the reply's line and column becoming an
 * offset in a file that was never open.
 *
 * No host runs here, so the fixture answers `$provideDefinition` as an extension would.
 */
class DefinitionTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        // The light fixture's project is reused between tests in this class, and so is the service.
        features.hostStopped()
    }

    @Test
    fun `the plugin registers the reference provider`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(
            descriptor.contains(
                "<psi.implicitReferenceProvider implementation=" + QUOTE +
                        "me.helvert.codebridge.language.CodeBridgeSymbolReferenceProvider" + QUOTE + "/>",
            ),
            "Nothing in a light fixture would notice this being deleted",
        )
    }

    @Test
    fun `nothing is asked of the host when no extension offered definitions`() = onEdt {
        open("some text")
        features.callHostForTest { _, _, _, _ -> NONE }

        assertNull(targets())
        assertTrue(sent.isEmpty(), "A file no extension claimed must not reach the host: $sent")
    }

    @Test
    fun `a file the host does not hold is not asked about`() = onEdt {
        // The reference provider runs for every file in the IDE. Ctrl+hover in one no extension
        // opened -- the user settings.json -- asked every provider, and each failed with "Unable to
        // retrieve document".
        open("some text", mirrored = false)
        answer(link(target(), line = 1, column = 1))

        assertNull(targets())
        assertTrue(sent.isEmpty(), "The host has no such document: $sent")
    }

    @Test
    fun `stops asking once the provider is unregistered`() = onEdt {
        open("some text")
        answer(link(target(), line = 1, column = 1))
        assertEquals(1, targets()?.size)

        // Every provider is withdrawn with the same bare `$unregister(handle)`, whatever kind it was.
        features.handle("MainThreadLanguageFeatures", $$"$unregister", "[3]")
        assertNull(targets())
    }

    @Test
    fun `the position sent is the caret, in VS Code's one-based terms`() = onEdt {
        open("first line\nsecond line")
        answer(link(target(), line = 1, column = 1))

        targets()

        val provide = sent.first { it.contains("lineNumber") }
        assertTrue(provide.contains(POSITION), "Line 2, one past the third character: $provide")
    }

    @Test
    fun `a definition in another file resolves to that file and offset`() = onEdt {
        val definition = target("first line\nsecond line\n")
        open("some text")
        answer(link(definition, line = 2, column = 3))

        val element = targets()?.single()

        assertEquals(definition.fileName.toString(), element?.containingFile?.name)
        // Offset 13: ten characters of the first line, its newline, and two columns in.
        assertEquals(13, element?.textOffset)
    }

    @Test
    fun `the caret lands on the identifier the extension picked out`() = onEdt {
        // A `LocationLink` names the whole definition and the name inside it; VS Code puts the
        // caret on the name, so a body starting with a comment does not swallow the jump.
        val definition = target("line one\nline two\n")
        open("some text")
        answer(
            """{"uri":${uri(definition)},"range":${range(1, 1)},"targetSelectionRange":${range(2, 6)}}""",
        )

        assertEquals(14, targets()?.single()?.textOffset, "The selection range wins over the target range")
    }

    @Test
    fun `a line the target file no longer has is clamped rather than dropped`() = onEdt {
        // Extensions answer from their own copy of a file, which can be older than the one on disk.
        val definition = target("only one line\n")
        open("some text")
        answer(link(definition, line = 40, column = 80))

        assertEquals(14, targets()?.single()?.textOffset, "Clamped to the end of the last line")
    }

    @Test
    fun `every provider's answer is offered, without duplicates`() = onEdt {
        val definition = target("first line\nsecond line\n")
        open("some text")
        // Two providers backed by the same server answer identically; one of them also knows a
        // second place. The user must be offered two choices, not three.
        answer(link(definition, line = 1, column = 1), link(definition, line = 2, column = 1), handles = listOf(3, 4))

        assertEquals(listOf(0, 11), targets()?.map { it.textOffset })
    }

    @Test
    fun `the platform asks the reference provider for a plain text file`() = onEdt {
        // The one thing this file cannot assert by calling the provider directly: that Go To
        // Declaration consults this extension point at all for a file with no PSI to speak of.
        ImplicitReferenceProvider.EP_NAME.point
            .registerExtension(CodeBridgeSymbolReferenceProvider(), fixture.testRootDisposable)
        val definition = target("first line\n")
        open("some text")
        answer(link(definition, line = 1, column = 6))

        val data = GotoDeclarationAction().getCtrlMouseData(fixture.editor, fixture.file, caret())

        assertEquals(listOf(TextRange(5, 9)), data?.ranges)
        assertEquals(true, data?.isNavigatable)
    }

    @Test
    fun `a definition in a library resolves to the document its scheme's provider makes up`() = onEdt {
        // Java's language server names a class in a jar under `jdt:`, and its client registers a
        // content provider for the scheme that serves the attached source or a decompiled one.
        val providers = HostedContentProviders.getInstance(fixture.project)
        providers.handle(
            MainThreadCall(
                "MainThreadDocumentContentProviders",
                $$"$registerTextContentProvider",
                """[7,"jdt"]"""
            )
        )
        val asked = ConcurrentLinkedQueue<String>()
        providers.callHostForTest { _, method, args ->
            asked.add(args)
            if (method == $$"$provideTextDocumentContent") "\"package java.lang;\\nclass String {}\\n\"" else "null"
        }
        open("some text")
        answer(
            """{"uri":{"scheme":"jdt","authority":"contents","path":"/java.base/java.lang/String.class","query":"=demo/jre","fragment":""},"range":${
                range(
                    2,
                    7
                )
            }}""",
        )

        val element = targets()?.single()
        targets() // A second jump into the same class

        assertEquals("String.class", element?.containingFile?.name)
        assertEquals("class String {}\n", element?.containingFile?.text?.substring(19))
        // Offset 25: the eighteen characters of the first line, its newline, and six columns in.
        assertEquals(25, element?.textOffset)
        assertEquals(1, asked.size, "The provider is asked once per document: $asked")
        assertTrue(asked.single().startsWith("[7,"), "Asked of the handle registered for the scheme: $asked")
        providers.hostStopped()
    }

    @Test
    fun `the extension origin range wins over the word fallback`() = onEdt {
        open("first line\nfoo-bar")
        answer(
            """{"uri":${uri(target())},"range":${
                range(
                    1,
                    1
                )
            },"originSelectionRange":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":8}}"""
        )
        val reference = CodeBridgeSymbolReferenceProvider().getImplicitReference(fixture.file, caret())
        assertEquals(TextRange(11, 18), reference?.absoluteRange)
    }

    @Test
    fun `a word inside a file of many leaves is underlined where it is`() = onEdt {
        // Plain text is one leaf at offset 0, which hides where a range is measured from; XML is
        // not, and the platform asks the leaf at the caret first.
        ImplicitReferenceProvider.EP_NAME.point
            .registerExtension(CodeBridgeSymbolReferenceProvider(), fixture.testRootDisposable)
        open("<a>first</a>\n<b>call symbol</b>", name = "source.xml")
        answer(link(target(), 1, 1))
        fixture.editor.caretModel.moveToOffset(23)
        val leaf = requireNotNull(fixture.file.findElementAt(23))
        assertTrue(leaf.textRange.startOffset > 0, "The leaf at the caret should not start the file: ${leaf.textRange}")

        val reference = CodeBridgeSymbolReferenceProvider().getImplicitReference(leaf, 23 - leaf.textRange.startOffset)
        val data = GotoDeclarationAction().getCtrlMouseData(fixture.editor, fixture.file, 23)

        assertEquals(TextRange(21, 27), reference?.absoluteRange)
        assertEquals(listOf(TextRange(21, 27)), data?.ranges)
    }

    @Test
    fun `multiple destinations still underline only the source word`() = onEdt {
        ImplicitReferenceProvider.EP_NAME.point
            .registerExtension(CodeBridgeSymbolReferenceProvider(), fixture.testRootDisposable)
        open("first line\ncall symbol")
        val destination = target("one\ntwo")
        answer(link(destination, 1, 1), link(destination, 2, 1))
        val data = GotoDeclarationAction().getCtrlMouseData(fixture.editor, fixture.file, caret())
        assertEquals(listOf(TextRange(16, 22)), data?.ranges)
    }

    @Test
    fun `whitespace is never a whole file fallback reference`() = onEdt {
        open("word   ")
        answer(link(target(), 1, 1))
        assertNull(CodeBridgeSymbolReferenceProvider().getImplicitReference(fixture.file, caret()))
    }

    /** The destinations offered by the symbol reference at the caret. */
    private fun targets(): Array<PsiElement>? {
        val offset = caret()
        return CodeBridgeSymbolReferenceProvider().getImplicitReference(fixture.file, offset)
            ?.resolveReference()?.map { (it as CodeBridgeDefinitionSymbol).target }?.toTypedArray()
    }

    /** Registers one definition provider per handle in [handles], all answering with [links]. */
    private fun answer(vararg links: String, handles: List<Int> = listOf(3)) {
        for (handle in handles) {
            features.handle(
                "MainThreadLanguageFeatures", $$"$registerDefinitionSupport",
                """[$handle,[{"language":"codebridgebundled"}]]""",
            )
        }
        val reply = "[" + links.joinToString(",") + "]"
        features.callHostForTest { _, method, args, _ ->
            sent.add(args)
            if (method == $$"$provideDefinition") reply else NONE
        }
    }

    /** A `LocationLink` with no separate selection, which is what a plain LSP reply becomes. */
    private fun link(path: Path, line: Int, column: Int) =
        """{"uri":${uri(path)},"range":${range(line, column)}}"""

    private fun uri(path: Path) = UriComponents.of(path).toJson().toString()

    private fun range(line: Int, column: Int) =
        """{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$line,"endColumn":$column}"""

    /** A file on disk that no editor has open, which is the ordinary case for a definition. */
    private fun target(text: String = "definition\n"): Path =
        directory.resolve("target.txt").also {
            it.writeText(text)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }

    private fun caret(): Int = fixture.editor.caretModel.offset

    /**
     * A real file, not the fixture's in-memory one: the URI is built from `VirtualFile.toNioPath`,
     * which the temporary filesystem a light fixture uses does not support.
     */
    private fun open(text: String, mirrored: Boolean = true, name: String = "source.txt") {
        val path = directory.resolve(name).also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        if (mirrored) mirror(file)
        // Not the end of the document: `findElementAt` answers null there, and the caret the user
        // presses the key at is inside a word.
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.textLength - 1)
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    /**
     * Gives the host a copy of [file], as opening a claimed file does: providers are asked only
     * about documents the host holds. As the selector's language, so it is the file's claim that
     * decides who is asked.
     */
    private fun mirror(file: com.intellij.openapi.vfs.VirtualFile) {
        features.mirrorForTest(me.helvert.codebridge.exthost.ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            file,
            "codebridgebundled",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
    }

    private val sent = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-definition") }

    private companion object {
        const val QUOTE = "\""
        const val NONE = "[]"
        const val POSITION = """{"lineNumber":2,"column":11}"""
    }
}
