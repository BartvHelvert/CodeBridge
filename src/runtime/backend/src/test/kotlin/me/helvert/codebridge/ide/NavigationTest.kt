package me.helvert.codebridge.ide

import com.intellij.codeInsight.navigation.GotoImplementationHandler
import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.PomDeclarationSearcher
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.testFramework.TestActionEvent
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Go To Type Declaration and Go To Implementation.
 *
 * No host runs here, so the fixture answers `$provideDefinition`, `$provideTypeDefinition` and
 * `$provideImplementation` as an extension would.
 */
class NavigationTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        features.hostStopped()
    }

    @Test
    fun `the plugin registers type and implementation navigation`() {
        val backend = Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(backend.contains("me.helvert.codebridge.language.CodeBridgeSymbolTypeProvider"))
        assertTrue(backend.contains("me.helvert.codebridge.language.CodeBridgeImplementationSearch"))
        assertTrue(backend.contains("""<pom.declarationSearcher implementation="me.helvert.codebridge.language.CodeBridgeImplementationDeclarationSearcher"/>"""))
        assertTrue(backend.contains("""<listener class="me.helvert.codebridge.language.CodeBridgeCurrentActionListener""""))
    }

    @Test
    fun `the type is asked for at the caret, once however many definitions there are`() = onEdt {
        open("first line\ncall symbol")
        val destination = target("one\ntwo\nthree\n")
        answer(
            mapOf(
                DEFINITION to listOf(link(destination, 1, 1), link(destination, 2, 1)),
                TYPE_DEFINITION to listOf(link(destination, 3, 2)),
            ),
        )
        val symbols = reference()!!.resolveReference()

        val types = symbols.flatMap { CodeBridgeSymbolTypeProvider().getSymbolTypes(fixture.project, it) }.distinct()

        assertEquals(listOf(9), types.map { (it as CodeBridgeDefinitionSymbol).target.textOffset })
        assertEquals(1, asked.count { it == PROVIDE_TYPE_DEFINITION })
        val provide = sent.last { it.contains("lineNumber") }
        assertTrue(provide.contains("""{"lineNumber":2,"column":11}"""), provide)
    }

    @Test
    fun `no type is offered when the extension knows none`() = onEdt {
        open("call symbol")
        answer(mapOf(DEFINITION to listOf(link(target(), 1, 1))))

        val symbol = reference()!!.resolveReference().first()

        assertTrue(CodeBridgeSymbolTypeProvider().getSymbolTypes(fixture.project, symbol).isEmpty())
    }

    @Test
    fun `go to implementation offers what the extension names`() = onEdt {
        registerImplementations()
        open("first line\ncall symbol")
        val destination = target("one\ntwo\n")
        answer(mapOf(IMPLEMENTATION to listOf(link(destination, 1, 1), link(destination, 2, 2))))

        val data = gotoImplementation()!!

        assertEquals("symbol", (data.source as PsiNamedElement).name)
        // The handler finds the first at once and the rest in the background, as the search does.
        assertEquals(listOf(0), data.targets.map { it.textOffset })
        assertEquals(listOf(0, 5), DefinitionsScopedSearch.search(data.source).findAll().map { it.textOffset }.sorted())
        val provide = sent.last { it.contains("lineNumber") }
        assertTrue(provide.contains("""{"lineNumber":2,"column":6}"""), "Asked at the symbol's start: $provide")
    }

    @Test
    fun `a destination is named by its word and place where the file has no outline`() = onEdt {
        registerImplementations()
        open("call symbol")
        answer(mapOf(IMPLEMENTATION to listOf(link(target("one\ntwo\n"), 2, 1))))

        val destination = gotoImplementation()!!.targets.single() as NavigationItem

        assertEquals("two", destination.presentation?.presentableText)
        assertEquals("target.txt:2", destination.presentation?.locationString)
    }

    @Test
    fun `a destination is named by its outline where the extension gave one`() = onEdt {
        // Two implementations of `area` are told apart by what holds them, as two Java methods are.
        registerImplementations()
        val source = directory.resolve("source.txt")
        answer(
            mapOf(IMPLEMENTATION to listOf("""{"uri":${UriComponents.of(source).toJson()},"range":${range(2, 5, 2, 17)},"targetSelectionRange":${range(2, 8, 2, 12)}}""")),
            outline = """[{"name":"impl Shape for Circle","detail":"","kind":4,"range":${range(1, 1, 3, 2)},"selectionRange":${range(1, 16, 1, 22)},"children":[
                {"name":"area","detail":"fn()","kind":11,"range":${range(2, 5, 2, 17)},"selectionRange":${range(2, 8, 2, 12)},"children":[]}
            ]}]""",
        )
        open("impl Shape for Circle {\n    fn area() {}\n}\ncall area")
        assertTrue(features.awaitDocumentSymbolsForTest(10, TimeUnit.SECONDS), "The outline never arrived")

        val destination = gotoImplementation()!!.targets.single() as NavigationItem

        assertEquals("area", destination.presentation?.presentableText)
        assertEquals("impl Shape for Circle", destination.presentation?.locationString)
        assertEquals(CodeBridgeWorkspaceSymbol.icon(11), destination.presentation?.getIcon(false))
    }

    @Test
    fun `with no implementation there is nowhere to go`() = onEdt {
        // Not the symbol itself, which the platform offers for an element a reference resolved to:
        // found as declared at the caret, it says "No implementations found", as VS Code does.
        registerImplementations()
        open("call symbol")
        answer(mapOf(IMPLEMENTATION to emptyList()))

        assertEquals(0, gotoImplementation()!!.targets.size)
    }

    @Test
    fun `a file no implementation provider claims has nothing to start from`() = onEdt {
        registerImplementations()
        open("call symbol", mirrored = false)
        answer(mapOf(IMPLEMENTATION to listOf(link(target(), 1, 1))))

        assertNull(gotoImplementation())
        assertTrue(sent.isEmpty(), "$sent")
    }

    @Test
    fun `nothing is declared at the caret outside go to implementation`() = onEdt {
        // Go To Declaration asks for a declaration at the caret too; one there would make Ctrl+click
        // show usages wherever the extension knew no definition.
        registerImplementations()
        ImplicitReferenceProvider.EP_NAME.point
            .registerExtension(CodeBridgeSymbolReferenceProvider(), fixture.testRootDisposable)
        open("call symbol")
        answer(mapOf(IMPLEMENTATION to listOf(link(target(), 1, 1))))

        assertNull(GotoImplementationHandler().getSourceAndTargetElements(fixture.editor, fixture.file))
        assertNull(GotoDeclarationAction().getCtrlMouseData(fixture.editor, fixture.file, caret()))
        assertTrue(sent.isEmpty(), "$sent")
    }

    @Test
    fun `the listener tracks go to implementation by its id`() = onEdt {
        val listener = CodeBridgeCurrentActionListener()
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_IMPLEMENTATION)
        val event = TestActionEvent.createTestEvent(action)

        listener.beforeActionPerformed(action, event)
        assertTrue(CodeBridgeCurrentAction.isGotoImplementation)
        listener.afterActionPerformed(action, event, AnActionResult.PERFORMED)
        assertFalse(CodeBridgeCurrentAction.isGotoImplementation)

        val other = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION)
        listener.beforeActionPerformed(other, TestActionEvent.createTestEvent(other))
        assertFalse(CodeBridgeCurrentAction.isGotoImplementation)
        CodeBridgeCurrentAction.id = null
    }

    /** What the handler finds while the action runs, as the listener marks it. */
    private fun gotoImplementation(): GotoTargetHandler.GotoData? {
        CodeBridgeCurrentAction.id = IdeActions.ACTION_GOTO_IMPLEMENTATION
        try {
            return GotoImplementationHandler().getSourceAndTargetElements(fixture.editor, fixture.file)
        } finally {
            CodeBridgeCurrentAction.id = null
        }
    }

    private fun registerImplementations() {
        PomDeclarationSearcher.EP_NAME.point
            .registerExtension(CodeBridgeImplementationDeclarationSearcher(), fixture.testRootDisposable)
        DefinitionsScopedSearch.EP_NAME.point.registerExtension(CodeBridgeImplementationSearch(), fixture.testRootDisposable)
    }

    private fun reference() = CodeBridgeSymbolReferenceProvider().getImplicitReference(fixture.file, caret())

    /**
     * Registers one provider of each kind in [replies], each answering with its links, and a
     * document symbol provider answering with [outline] when there is one.
     */
    private fun answer(replies: Map<String, List<String>>, outline: String? = null) {
        replies.keys.forEachIndexed { index, register ->
            features.handle("MainThreadLanguageFeatures", register, """[${index + 3},[{"language":"codebridgebundled"}]]""")
        }
        if (outline != null) {
            features.handle("MainThreadLanguageFeatures", $$"$registerDocumentSymbolProvider", """[9,[{"language":"codebridgebundled"}],"Test"]""")
        }
        val byMethod = replies.mapKeys { (register, _) -> register.replace("register", "provide").removeSuffix("Support") }
        features.callHostForTest { _, method, args, _ ->
            if (method == $$"$provideDocumentSymbols") return@callHostForTest outline ?: "[]"
            asked.add(method)
            sent.add(args)
            val links = byMethod[method] ?: emptyList()
            "[" + links.joinToString(",") + "]"
        }
    }

    private fun range(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        """{"startLineNumber":$startLine,"startColumn":$startColumn,"endLineNumber":$endLine,"endColumn":$endColumn}"""

    private fun link(path: Path, line: Int, column: Int) =
        """{"uri":${UriComponents.of(path).toJson()},"range":{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$line,"endColumn":$column}}"""

    private fun target(text: String = "definition\n"): Path =
        directory.resolve("target.txt").also {
            it.writeText(text)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }

    private fun caret(): Int = fixture.editor.caretModel.offset

    /** A real file, mirrored to the host as a claimed file is, with the caret inside its last word. */
    private fun open(text: String, mirrored: Boolean = true) {
        val path = directory.resolve("source.txt").also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        if (mirrored) mirror(file)
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.textLength - 1)
    }

    private fun mirror(file: VirtualFile) {
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "codebridgebundled", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val sent = ConcurrentLinkedQueue<String>()
    private val asked = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-navigation") }

    private companion object {
        const val DEFINITION = $$"$registerDefinitionSupport"
        const val TYPE_DEFINITION = $$"$registerTypeDefinitionSupport"
        const val IMPLEMENTATION = $$"$registerImplementationSupport"
        const val PROVIDE_TYPE_DEFINITION = $$"$provideTypeDefinition"
    }
}
