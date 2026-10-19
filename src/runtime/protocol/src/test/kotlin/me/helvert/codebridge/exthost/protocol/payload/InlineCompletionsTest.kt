package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Inline completion registrations, the request, the reply and freeing it. */
class InlineCompletionsTest {

    @Test
    fun `reads the handle, selector and the debounce the extension asked for`() {
        val registration = InlineCompletions.registration(
            """[7,[{"language":"python"}],false,"acme.ghost","1.0.0",null,[],"Ghost",120,[],false,null,true,false,null,false]""",
        )
        assertEquals(7, registration.handle)
        assertEquals(Json.parseToJsonElement("""[{"language":"python"}]"""), registration.selector)
        assertEquals(120L, registration.debounceMillis)
    }

    @Test
    fun `an extension that named no debounce leaves it to the editor`() {
        assertNull(InlineCompletions.registration("""[7,[],false,"acme.ghost","1.0.0"]""").debounceMillis)
    }

    @Test
    fun `the context says whether the user asked`() {
        val resource = UriComponents.of(Path.of("main.py").toAbsolutePath())
        val args = Json.parseToJsonElement(
            InlineCompletions.provide(
                7,
                resource,
                Position(3, 9),
                explicit = true
            )
        ) as JsonArray

        // No token: the host appends its own for a cancellable call.
        assertEquals(4, args.size)
        val context = args[3] as JsonObject
        assertEquals("1", context["triggerKind"]?.jsonPrimitive?.content)
        assertNotNull(context["requestUuid"])
        val automatic = Json.parseToJsonElement(
            InlineCompletions.provide(
                7,
                resource,
                Position(3, 9),
                explicit = false
            )
        ) as JsonArray
        assertEquals("0", (automatic[3] as JsonObject)["triggerKind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reads text, snippets, ranges and commands`() {
        val answer = assertNotNull(
            InlineCompletions.answer(
                """{"pid":3,"languageId":"python","items":[
                {"insertText":"print(x)","range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":3},
                 "command":{"id":"acme.accepted","title":"","arguments":[42]},"pid":3,"idx":0},
                {"insertText":{"snippet":"print($1)"},"pid":3,"idx":1}
            ],"commands":[],"suppressSuggestions":false}""",
            )
        )
        assertEquals(3, answer.id)
        assertEquals(2, answer.items.size)
        val first = answer.items[0]
        assertEquals("print(x)", first.text)
        assertEquals(false, first.snippet)
        assertEquals(Range(2, 1, 2, 3), first.range)
        assertEquals("acme.accepted", first.command?.id)
        val second = answer.items[1]
        assertEquals(1, second.index)
        assertEquals("print($1)", second.text)
        assertEquals(true, second.snippet)
        assertNull(second.range)
    }

    @Test
    fun `an item without text is an inline edit and left out`() {
        val answer =
            assertNotNull(InlineCompletions.answer("""{"pid":3,"items":[{"pid":3,"idx":0,"isInlineEdit":true}]}"""))
        assertEquals(emptyList(), answer.items)
    }

    @Test
    fun `nothing to say is no answer`() {
        assertNull(InlineCompletions.answer(null))
        assertNull(InlineCompletions.answer(""))
        assertNull(InlineCompletions.answer("null"))
    }

    @Test
    fun `freeing names the provider and the list`() {
        val args = Json.parseToJsonElement(InlineCompletions.free(7, 3)) as JsonArray
        assertEquals("7", args[0].jsonPrimitive.content)
        assertEquals("3", args[1].jsonPrimitive.content)
    }
}
