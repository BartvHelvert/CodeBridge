package me.helvert.codebridge.ide

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.Hierarchies
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.*
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
 * Call and type hierarchies.
 *
 * No host runs here, so the fixture answers the hierarchy requests as an extension would, and the
 * trees are built the way the hierarchy view builds them, a level at a time.
 */
class HierarchyTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        features.hostStopped()
    }

    @Test
    fun `the plugin registers both hierarchies for every language`() {
        val backend = Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(backend.contains("""implementationClass="me.helvert.codebridge.language.CodeBridgeCallHierarchyProvider""""))
        assertTrue(backend.contains("""implementationClass="me.helvert.codebridge.language.CodeBridgeTypeHierarchyProvider""""))
    }

    @Test
    fun `a hierarchy is offered at the caret of a claimed file, without asking anything yet`() = onEdt {
        register(Hierarchies.Kind.CALL)
        open("first line\ncall symbol")

        val target = CodeBridgeCallHierarchyProvider().getTarget(editorContext()) as CodeBridgeHierarchyElement

        assertEquals("symbol", target.name)
        assertNull(CodeBridgeTypeHierarchyProvider().getTarget(editorContext()), "No type hierarchy provider is registered")
        assertTrue(sent.isEmpty(), "$sent")
    }

    @Test
    fun `a file no provider claims offers no hierarchy`() = onEdt {
        register(Hierarchies.Kind.CALL)
        open("call symbol", mirrored = false)

        assertNull(CodeBridgeCallHierarchyProvider().getTarget(editorContext()))
    }

    @Test
    fun `a node rebased on is its own target`() = onEdt {
        open("call symbol")
        val element = CodeBridgeHierarchyElement.at(fixture.project, UriComponents.of(source), Range(1, 6, 1, 12), "symbol")!!

        val context = SimpleDataContext.getSimpleContext(CommonDataKeys.PSI_ELEMENT, element)

        assertEquals(element, CodeBridgeCallHierarchyProvider().getTarget(context))
    }

    @Test
    fun `callers are the extension's incoming calls, each leading to where it calls`() = onEdt {
        register(Hierarchies.Kind.CALL)
        open("fn area() {}\ncall area")
        val other = target("fn first() {\n    area(); area();\n}\n")
        reply(Hierarchies.Kind.CALL.prepare, "[${item("0", "area", source, range(1, 4, 1, 8))}]")
        reply(
            Hierarchies.PROVIDE_INCOMING_CALLS,
            """[{"from":${item("1", "first", other, range(1, 4, 1, 9))},"fromRanges":[${range(2, 5, 2, 9)},${range(2, 13, 2, 17)}]}]""",
        )

        val root = tree(CodeBridgeHierarchyTreeStructure::callers).single()
        val callers = children(root)

        assertEquals("area", root.item!!.name)
        val prepare = sent.first()
        assertTrue(prepare.contains("""{"lineNumber":2,"column":9}"""), "Asked at the caret: $prepare")
        assertEquals("""[3,"s","0"]""", sent.last())
        val caller = callers.single()
        assertEquals("first fn() (2 usages)", presentation(caller))
        assertEquals(other.toFile().name, caller.psiElement!!.containingFile.name)
        assertEquals(3, caller.psiElement!!.textOffset, "The node is the caller itself")
        caller.navigate(true)
        // Navigation is submitted, not done on the spot.
        PlatformTestUtil.waitWithEventsDispatching(
            "Navigating never reached the first call",
            { fixture.project.selectedEditorOffset() == 17 },
            10,
        )
        assertEquals(17, fixture.project.selectedEditorOffset(), "Navigating goes to the first call")
    }

    @Test
    fun `callees are the extension's outgoing calls`() = onEdt {
        register(Hierarchies.Kind.CALL)
        open("fn area() {}\ncall area")
        val other = target("fn helper() {}\n")
        reply(Hierarchies.Kind.CALL.prepare, "[${item("0", "area", source, range(1, 4, 1, 8))}]")
        reply(Hierarchies.PROVIDE_OUTGOING_CALLS, """[{"to":${item("1", "helper", other, range(1, 4, 1, 10))},"fromRanges":[${range(1, 11, 1, 12)}]}]""")

        val callee = children(tree(CodeBridgeHierarchyTreeStructure::callees).single()).single()

        assertEquals("helper fn()", presentation(callee))
        assertTrue(asked.contains(Hierarchies.PROVIDE_OUTGOING_CALLS), "$asked")
    }

    @Test
    fun `supertypes and subtypes are the extension's`() = onEdt {
        register(Hierarchies.Kind.TYPE)
        open("struct Circle\ncall Circle")
        val other = target("trait Shape {}\nstruct Square {}\n")
        reply(Hierarchies.Kind.TYPE.prepare, "[${item("0", "Circle", source, range(1, 8, 1, 14))}]")
        reply(Hierarchies.PROVIDE_SUPERTYPES, "[${item("1", "Shape", other, range(1, 7, 1, 12))}]")
        reply(Hierarchies.PROVIDE_SUBTYPES, "[]")

        val supertypes = children(tree(CodeBridgeHierarchyTreeStructure::supertypes, Hierarchies.Kind.TYPE).single())
        val subtypes = children(tree(CodeBridgeHierarchyTreeStructure::subtypes, Hierarchies.Kind.TYPE).single())

        assertEquals(listOf("Shape"), supertypes.map { it.item!!.name })
        assertTrue(subtypes.isEmpty())
    }

    @Test
    fun `nothing prepared is an empty tree`() = onEdt {
        register(Hierarchies.Kind.CALL)
        open("call symbol")
        reply(Hierarchies.Kind.CALL.prepare, "[]")

        assertTrue(tree(CodeBridgeHierarchyTreeStructure::callers).isEmpty())
    }

    @Test
    fun `sessions are released when the browser closes, and late ones at once`() = onEdt {
        register(Hierarchies.Kind.CALL)
        open("call symbol")
        val sessions = CodeBridgeHierarchySessions(fixture.project, Hierarchies.Kind.CALL)
        sessions.hold(3, "a")
        sessions.hold(3, "a")
        assertTrue(released.isEmpty())

        Disposer.dispose(sessions)
        sessions.hold(3, "b")

        assertEquals(listOf("""[3,"a"]""", """[3,"b"]"""), released.toList())
    }

    /** The visible root of a tree of [kind] at the caret, as the browser builds it. */
    private fun tree(
        children: (HostedLanguageFeatures, CodeBridgeHierarchyNode, Hierarchies.Item) -> List<CodeBridgeHierarchyTreeStructure.Child>,
        kind: Hierarchies.Kind = Hierarchies.Kind.CALL,
    ): List<CodeBridgeHierarchyNode> {
        val provider = if (kind == Hierarchies.Kind.CALL) CodeBridgeCallHierarchyProvider() else CodeBridgeTypeHierarchyProvider()
        val element = provider.getTarget(editorContext())!!
        val sessions = CodeBridgeHierarchySessions(fixture.project, kind).also { Disposer.register(fixture.testRootDisposable, it) }
        structure = CodeBridgeHierarchyTreeStructure(fixture.project, CodeBridgeHierarchyTreeStructure.base(fixture.project, element), kind, sessions, children)
        return children(structure!!.baseDescriptor)
    }

    private var structure: CodeBridgeHierarchyTreeStructure? = null

    private fun children(node: Any): List<CodeBridgeHierarchyNode> =
        structure!!.getChildElements(node).map { it as CodeBridgeHierarchyNode }

    private fun presentation(node: CodeBridgeHierarchyNode): String {
        node.update()
        return node.highlightedText.text
    }

    private fun com.intellij.openapi.project.Project.selectedEditorOffset(): Int =
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(this).selectedTextEditor!!.caretModel.offset

    private fun editorContext() = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, fixture.project)
        .add(CommonDataKeys.EDITOR, fixture.editor)
        .add(CommonDataKeys.PSI_FILE, fixture.file)
        .build()

    private fun register(kind: Hierarchies.Kind) {
        features.handle("MainThreadLanguageFeatures", kind.register, """[3,[{"language":"codebridgebundled"}]]""")
        features.callHostForTest { _, method, args, _ ->
            if (method.startsWith($$"$release")) {
                released.add(args)
                return@callHostForTest "null"
            }
            asked.add(method)
            sent.add(args)
            replies[method] ?: "[]"
        }
    }

    private fun reply(method: String, json: String) {
        replies[method] = json
    }

    private fun item(id: String, name: String, path: Path, selection: String) =
        """{"_sessionId":"s","_itemId":"$id","name":"$name","detail":"fn()","kind":11,"uri":${UriComponents.of(path).toJson()},"range":$selection,"selectionRange":$selection}"""

    private fun range(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        """{"startLineNumber":$startLine,"startColumn":$startColumn,"endLineNumber":$endLine,"endColumn":$endColumn}"""

    private fun target(text: String): Path =
        directory.resolve("target.txt").also {
            it.writeText(text)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }

    /** A real file, mirrored to the host as a claimed file is, with the caret inside its last word. */
    private fun open(text: String, mirrored: Boolean = true) {
        source.writeText(text)
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
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
    private val released = ConcurrentLinkedQueue<String>()
    private val replies = mutableMapOf<String, String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-hierarchy") }
    private val source: Path by lazy { directory.resolve("source.txt") }
}
