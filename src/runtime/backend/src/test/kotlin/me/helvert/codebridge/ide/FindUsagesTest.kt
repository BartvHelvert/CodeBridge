package me.helvert.codebridge.ide

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.impl.registerSymbolSearchTargetFactoryForTesting
import com.intellij.find.usages.impl.searchTargets
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Find Usages, from the symbol at the caret to the places an extension named.
 *
 * No host runs here, so the fixture answers `$provideDefinition` and `$provideReferences` as an
 * extension would.
 */
class FindUsagesTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        features.hostStopped()
        ImplicitReferenceProvider.EP_NAME.point
            .registerExtension(CodeBridgeSymbolReferenceProvider(), fixture.testRootDisposable)
        registerSymbolSearchTargetFactoryForTesting(CodeBridgeDefinitionSymbol::class.java, CodeBridgeSymbolSearchTargetFactory(), fixture.testRootDisposable)
    }

    @Test
    fun `the plugin registers the search target and the searcher`() {
        val descriptor = Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeSymbolSearchTargetFactory"))
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeReferenceSearcher"))
    }

    @Test
    fun `no search target is offered without a reference provider`() = onEdt {
        open("call symbol")
        answer(references = null)

        assertTrue(searchTargets(fixture.file, caret()).isEmpty())
    }

    @Test
    fun `the references are asked at the symbol, and become usages`() = onEdt {
        val other = file("other.txt", "one symbol\ntwo symbol\n")
        val source = open("first line\ncall symbol")
        answer(references = listOf(
            location(source, 2, 6, 12),
            location(other, 1, 5, 11),
            location(other, 2, 5, 11),
            // Stale: the extension's copy of the file had a line this one does not.
            location(other, 40, 1, 2),
        ))

        val target = searchTargets(fixture.file, caret()).single() as CodeBridgeUsagesTarget
        // Everywhere, as a usage search in all places asks. The fixture's files are outside its
        // project, which no scope contains, so this is also what keeps a dependency's sources.
        val usages = target.usages(GlobalSearchScope.everythingScope(fixture.project)).map { it as PsiUsage }

        assertTrue(sent.any { it.contains(POSITION) && it.contains("includeDeclaration") }, "Asked at the word's start: $sent")
        assertEquals(
            listOf("source.txt" to TextRange(16, 22), "other.txt" to TextRange(4, 10), "other.txt" to TextRange(15, 21)),
            usages.map { it.file.name to it.range },
        )
    }

    @Test
    fun `usages outside the search scope are left out`() = onEdt {
        val other = file("other.txt", "one symbol\n")
        val source = open("call symbol")
        answer(references = listOf(location(source, 1, 6, 12), location(other, 1, 5, 11)))

        val target = searchTargets(fixture.file, caret()).single() as CodeBridgeUsagesTarget
        val usages = target.usages(LocalSearchScope(fixture.file)).map { (it as PsiUsage).file.name }

        assertEquals(listOf("source.txt"), usages)
    }

    /** Registers a definition provider and, unless [references] is null, a reference provider. */
    private fun answer(references: List<String>?) {
        features.handle(
            "MainThreadLanguageFeatures", $$"$registerDefinitionSupport", """[3,[{"language":"codebridgebundled"}]]""",
        )
        if (references != null) {
            features.handle(
                "MainThreadLanguageFeatures", $$"$registerReferenceSupport", """[4,[{"language":"codebridgebundled"}]]""",
            )
        }
        val definition = """[{"uri":${uri(directory.resolve("source.txt"))},"range":${range(1, 1, 2)}}]"""
        features.callHostForTest { _, method, args, _ ->
            sent.add(args)
            when (method) {
                $$"$provideDefinition" -> definition
                $$"$provideReferences" -> "[" + references.orEmpty().joinToString(",") + "]"
                else -> "[]"
            }
        }
    }

    private fun location(file: VirtualFile, line: Int, start: Int, end: Int) =
        """{"uri":${uri(file.toNioPath())},"range":${range(line, start, end)}}"""

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

    private fun caret(): Int = fixture.editor.caretModel.offset

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val sent = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-usages") }

    private companion object {
        /** The start of `symbol` on the second line, one-based, not the caret near its end. */
        const val POSITION = """{"lineNumber":2,"column":6}"""
    }
}
