package me.helvert.codebridge.exthost.protocol.payload

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Document symbols, in the shapes the host sends. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class DocumentSymbolsTest {

    private val resource = UriComponents.of(Path.of("C:\\Projects\\main.rs"))

    @Test
    fun `reads a registration and builds the request`() {
        val registration = DocumentSymbols.registration($$"""[5,[{"$serialized":true,"language":"rust","scheme":"file"}],"rust-analyzer"]""")
        assertEquals(5, registration.handle)
        assertEquals("rust-analyzer", registration.label)
        assertTrue(registration.selector.toString().contains("rust"))
        assertTrue(DocumentSymbols.provide(5, resource).startsWith("""[5,{"scheme":"file","""))
    }

    @Test
    fun `reads a nested reply, and nothing as no symbols`() {
        val symbols = DocumentSymbols.symbols("""[{"name":"main","kind":11,"tags":[],"detail":"fn()",
            "range":{"startLineNumber":1,"startColumn":1,"endLineNumber":5,"endColumn":2},
            "selectionRange":{"startLineNumber":2,"startColumn":4,"endLineNumber":2,"endColumn":8},
            "children":[{"name":"x","kind":12,"range":{"startLineNumber":3,"startColumn":5,"endLineNumber":3,"endColumn":15},
                "selectionRange":{"startLineNumber":3,"startColumn":9,"endLineNumber":3,"endColumn":10}}]}]""")
        val main = symbols.single()
        assertEquals("main", main.name)
        assertEquals(11, main.kind)
        assertEquals("fn()", main.detail)
        assertEquals(Range(1, 1, 5, 2), main.range)
        assertEquals(Range(2, 4, 2, 8), main.selectionRange)
        assertEquals(listOf("x"), main.children.map { it.name })
        assertEquals(emptyList(), DocumentSymbols.symbols(""))
        assertEquals(emptyList(), DocumentSymbols.symbols(null))
    }
}
