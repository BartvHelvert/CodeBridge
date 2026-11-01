package me.helvert.codebridge.ide

import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.runBlocking
import me.helvert.codebridge.exthost.protocol.payload.Notebooks
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A document the host holds that is no file -- a notebook cell -- asked about as VS Code asks: by its
 * own URI, of the providers whose selectors claim it by its notebook's type, its scheme or its language.
 *
 * No host runs here, so the fixture answers as an extension would.
 */
class HostDocumentFeaturesTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val sent = CopyOnWriteArrayList<Pair<String, String>>()

    private val notebook = UriComponents.of(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "a.ipynb"))
    private val cellUri = Notebooks.cellUri(notebook, 1).uri
    private val cell = HostedLanguageFeatures.HostDocument(cellUri, "python", "jupyter-notebook")

    @BeforeEach
    fun answerAsAnExtension() = onEdt {
        features.hostStopped()
        sent.clear()
        features.callHostForTest { _, method, args, _ ->
            sent += method to args
            when (method) {
                $$"$provideCompletionItems" -> """{"x":7,"b":[{"a":"zeta","h":"zeta","e":"2"},{"a":"alpha","h":"alpha","e":"1"}]}"""
                $$"$provideHover" -> """{"contents":[{"value":"PROBE HOVER"}]}"""
                $$"$provideDefinition" ->
                    """[{"uri":${Notebooks.cellUri(notebook, 0).toJson()},"range":{"startLineNumber":1,"startColumn":3,"endLineNumber":1,"endColumn":3}}]"""
                else -> "null"
            }
        }
    }

    private fun register(method: String, handle: Int, selector: String) =
        features.handle("MainThreadLanguageFeatures", method, "[$handle,$selector]")

    @Test
    fun `a cell is asked about by its own URI, of the providers claiming its notebook's type`() {
        onEdt {
            register($$"$registerCompletionsProvider", 1, """[{"language":"python","notebookType":"jupyter-notebook"}],[],false,{"value":"probe"}""")
            // Claims files on disk only, which a cell is not.
            register($$"$registerCompletionsProvider", 2, """[{"language":"python","scheme":"file"}],[],false,{"value":"files"}""")
        }
        val items = runBlocking { features.completions(cell, 1, 1) }

        assertEquals(listOf("zeta", "alpha"), items.map { it.label }, "Only provider 1's answer, in the order it gave")
        val asked = sent.filter { it.first == $$"$provideCompletionItems" }
        assertEquals(1, asked.size, "Only the provider claiming the cell is asked: $asked")
        assertTrue(asked.single().second.startsWith("[1,"), "Asked of provider 1: $asked")
        assertTrue(asked.single().second.contains(Notebooks.CELL_SCHEME), "Asked about the cell's URI: $asked")
    }

    @Test
    fun `a cell's hover and definitions come from the providers that claim it`() {
        onEdt {
            register($$"$registerHoverProvider", 3, """[{"language":"python"}]""")
            register($$"$registerDefinitionSupport", 4, """[{"notebookType":"*"}]""")
        }
        val hover = runBlocking { features.hover(cell, 1, 2) }
        val links = runBlocking { features.definitions(cell, 1, 2) }

        assertEquals("PROBE HOVER", hover)
        assertEquals(UriComponents.key(Notebooks.cellUri(notebook, 0).uri), UriComponents.key(links.single().resource.uri))
        assertEquals(1 to 3, links.single().caret.lineNumber to links.single().caret.column)
    }

    @Test
    fun `nothing is asked about a cell no provider claims`() {
        onEdt { register($$"$registerHoverProvider", 5, """[{"language":"python","notebookType":"interactive"}]""") }

        assertEquals(null, runBlocking { features.hover(cell, 1, 1) })
        assertTrue(sent.none { it.first == $$"$provideHover" }, "Asked anyway: $sent")
    }

    @Test
    fun `problems pushed for a cell are kept by its URI and announced to listeners`() {
        val heard = CopyOnWriteArrayList<String>()
        val listening = Disposer.newDisposable()
        try {
            features.onProblemsChanged(listening) { heard += it }
            val marker = """{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":5,"severity":8,"message":"oops"}"""
            onEdt { features.handle("MainThreadDiagnostics", $$"$changeMany", """["probe",[[${Notebooks.cellUri(notebook, 1).toJson()},[$marker]]]]""") }

            assertEquals(listOf("oops"), features.problemsFor(cellUri).map { it.message })
            assertEquals(listOf(UriComponents.key(cellUri)), heard)

            onEdt { features.handle("MainThreadDiagnostics", $$"$changeMany", """["probe",[[${Notebooks.cellUri(notebook, 1).toJson()},null]]]""") }
            assertTrue(features.problemsFor(cellUri).isEmpty(), "Withdrawn with null")
            assertEquals(2, heard.size, "The withdrawal announced too: $heard")
        } finally {
            Disposer.dispose(listening)
        }
    }
}
