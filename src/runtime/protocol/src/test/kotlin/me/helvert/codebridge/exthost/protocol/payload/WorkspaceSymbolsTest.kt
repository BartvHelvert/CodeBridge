package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Workspace symbols, in the shapes the 1.143 host sends. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class WorkspaceSymbolsTest {

    @Test
    fun `reads a registration and whether it resolves`() {
        assertEquals(WorkspaceSymbols.Registration(6, true), WorkspaceSymbols.registration("[6,true]"))
        assertFalse(WorkspaceSymbols.registration("[6,false]").resolves)
    }

    @Test
    fun `builds the request and the release`() {
        assertEquals("""[6,"Vec"]""", WorkspaceSymbols.provide(6, "Vec"))
        assertEquals("[6,3]", WorkspaceSymbols.release(6, 3))
    }

    @Test
    fun `reads symbols, leaving out the ones with no name or file`() {
        val reply = $$"""{"cacheId":3,"symbols":[
            {"name":"Vec","kind":22,"containerName":"alloc::vec","tags":[1],
             "location":{"uri":{"$mid":1,"scheme":"file","path":"/c:/rust/vec.rs"},
                         "range":{"startLineNumber":40,"startColumn":12,"endLineNumber":40,"endColumn":15}},
             "cacheId":[3,0]},
            {"name":"new","kind":5,"containerName":"","location":{"uri":{"scheme":"file","path":"/c:/rust/vec.rs"}},"cacheId":[3,1]},
            {"name":"","kind":5,"location":{"uri":{"scheme":"file","path":"/c:/rust/vec.rs"}},"cacheId":[3,2]},
            {"name":"lost","kind":5,"cacheId":[3,3]}
        ]}"""

        val offered = WorkspaceSymbols.offered(reply)

        assertEquals(3, offered.cacheId)
        val (vec, new) = offered.symbols.also { assertEquals(2, it.size) }
        assertEquals("Vec", vec.name)
        assertEquals("alloc::vec", vec.containerName)
        assertEquals(22, vec.kind)
        assertTrue(vec.deprecated)
        assertEquals(Range(40, 12, 40, 15), vec.range)
        // No container is the same as an empty one, and a missing range is the file's start.
        assertNull(new.containerName)
        assertFalse(new.deprecated)
        assertEquals(Range(1, 1, 1, 1), new.range)
    }

    @Test
    fun `a provider with nothing to say offers nothing`() {
        assertEquals(WorkspaceSymbols.Offered(null, emptyList()), WorkspaceSymbols.offered("""{"symbols":[]}"""))
        assertEquals(WorkspaceSymbols.Offered(null, emptyList()), WorkspaceSymbols.offered(null))
        assertNull(WorkspaceSymbols.resolved(""))
    }

    @Test
    fun `resolving sends the symbol back as it came`() {
        val dto = """{"name":"new","kind":5,"location":{"uri":{"scheme":"file","path":"/c:/rust/vec.rs"}},"cacheId":[3,1]}"""
        val symbol = WorkspaceSymbols.offered("""{"cacheId":3,"symbols":[$dto]}""").symbols.single()

        assertEquals("[6,$dto]", WorkspaceSymbols.resolve(6, symbol))
        val resolved = WorkspaceSymbols.resolved(dto.replace("}},", """},"range":{"startLineNumber":9,"startColumn":5,"endLineNumber":9,"endColumn":8}},"""))
        assertEquals(Range(9, 5, 9, 8), resolved?.range)
    }
}
