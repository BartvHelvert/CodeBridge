package me.helvert.codebridge.exthost.protocol.payload

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Code lenses, in the shapes the 1.143 host sends. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class CodeLensesTest {

    private val resource = UriComponents.of(Path.of("C:\\Projects\\main.rs"))

    @Test
    fun `reads a registration with its event handle`() {
        val registration =
            CodeLenses.registration($$"""[8,[{"$serialized":true,"language":"rust","scheme":"file"}],9]""")
        assertEquals(8, registration.handle)
        assertEquals(9, registration.eventHandle)
        assertTrue(registration.selector.toString().contains("rust"))
        assertNull(CodeLenses.registration("""[8,[]]""").eventHandle)
        assertEquals(9, CodeLenses.emittedEventHandle("[9]"))
    }

    @Test
    fun `builds the request, the resolve and the release`() {
        val args = CodeLenses.provide(4, resource)
        assertTrue(args.startsWith("""[4,{"scheme":"file","""), args)
        assertEquals("[4,7]", CodeLenses.release(4, 7))
        val lens = CodeLenses.Lens(Range(2, 1, 4, 2), cacheId = 7 to 1)
        assertEquals(
            """[4,{"cacheId":[7,1],"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":4,"endColumn":2}}]""",
            CodeLenses.resolve(4, lens),
        )
    }

    @Test
    fun `reads lenses with a command, only text, and none yet`() {
        val reply = $$"""{"cacheId":7,"lenses":[
            {"cacheId":[7,0],"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":3,"endColumn":2},
             "command":{"$ident":"7,0","id":"_internal_command_delegation","title":"▶︎ Run","tooltip":"run it","arguments":["7,0"]}},
            {"cacheId":[7,1],"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":3,"endColumn":2},
             "command":{"title":"2 implementations"}},
            {"cacheId":[7,2],"range":{"startLineNumber":5,"startColumn":1,"endLineNumber":5,"endColumn":9}}
        ]}"""
        val offered = CodeLenses.offered(reply)
        assertEquals(7, offered.cacheId)
        val (run, text, unresolved) = offered.lenses
        assertEquals("▶︎ Run", run.title)
        assertEquals("run it", run.tooltip)
        assertEquals("_internal_command_delegation", run.command?.id)
        assertEquals(7 to 0, run.cacheId)
        assertEquals("2 implementations", text.title)
        assertNull(text.command)
        assertNull(unresolved.title)
        assertEquals(Range(5, 1, 5, 9), unresolved.range)
        assertEquals(CodeLenses.Offered(null, emptyList()), CodeLenses.offered(""))
    }

    @Test
    fun `reads a resolved lens, or nothing for one the host no longer holds`() {
        val resolved = CodeLenses.resolved(
            """{"cacheId":[7,2],"range":{"startLineNumber":5,"startColumn":1,"endLineNumber":5,"endColumn":9},
               "command":{"id":"x.show","title":"3 references"}}"""
        )
        assertEquals("3 references", resolved?.title)
        assertEquals("x.show", resolved?.command?.id)
        assertNull(CodeLenses.resolved(""))
        assertNull(CodeLenses.resolved("""{"cacheId":[7,2],"range":{"startLineNumber":5}}"""))
    }

    @Test
    fun `a title reads without its icons`() {
        assertEquals("Run Test", CodeLenses.plainTitle("$(play) Run Test"))
        assertEquals("Debug", CodeLenses.plainTitle("$(debug-alt~spin)  Debug"))
        assertEquals("▶︎ Run", CodeLenses.plainTitle("▶︎ Run"))
        assertEquals("", CodeLenses.plainTitle("$(play)"))
    }
}
