package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signature help registrations, the request with its context, and the reply. */
class SignatureHelpTest {

    @Test
    fun `reads trigger and retrigger characters`() {
        val registration = SignatureHelp.registration(
            """[4,[{"language":"rust"}],{"triggerCharacters":["(",","],"retriggerCharacters":[")"]}]""",
        )
        assertEquals(4, registration.handle)
        assertEquals(listOf("(", ","), registration.triggerCharacters)
        assertEquals(listOf(")"), registration.retriggerCharacters)
    }

    @Test
    fun `a bare list of trigger characters is the older overload`() {
        val registration = SignatureHelp.registration("""[4,[],["("]]""")
        assertEquals(listOf("("), registration.triggerCharacters)
        assertTrue(registration.retriggerCharacters.isEmpty())
    }

    @Test
    fun `the context says what triggered the request and hands back the help on screen`() {
        val resource = UriComponents.of(Path.of("source.rs").toAbsolutePath())
        val shown = Json.parseToJsonElement("""{"id":9,"signatures":[],"activeSignature":0,"activeParameter":1}""") as JsonObject
        val args = Json.parseToJsonElement(
            SignatureHelp.provide(4, resource, Position(2, 5), SignatureHelp.Trigger(SignatureHelp.TRIGGER_CHARACTER, ",", true, shown)),
        ) as JsonArray

        // No token: the host appends its own for a cancellable call.
        assertEquals(4, args.size)
        val context = args[3] as JsonObject
        assertEquals("2", context["triggerKind"]?.jsonPrimitive?.content)
        assertEquals(",", context["triggerCharacter"]?.jsonPrimitive?.content)
        assertEquals("true", context["isRetrigger"]?.jsonPrimitive?.content)
        // The id is what the host finds the extension's own object by.
        assertEquals(shown, context["activeSignatureHelp"])
    }

    @Test
    fun `an invoked request names no character and no help`() {
        val resource = UriComponents.of(Path.of("source.rs").toAbsolutePath())
        val context = (Json.parseToJsonElement(
            SignatureHelp.provide(1, resource, Position(1, 1), SignatureHelp.Trigger(SignatureHelp.TRIGGER_INVOKE)),
        ) as JsonArray)[3] as JsonObject
        assertEquals(setOf("triggerKind", "isRetrigger"), context.keys)
    }

    @Test
    fun `parameters are found in the label by text or by offsets`() {
        val help = SignatureHelp.help(
            """{"id":3,"activeSignature":0,"activeParameter":1,"signatures":[{"label":"fn add(a: i32, b: i32) -> i32",
               "documentation":{"value":"Adds."},
               "parameters":[{"label":"a: i32","documentation":"The first."},{"label":[15,21],"documentation":{"value":"The&nbsp;second."}}]}]}""",
        )!!
        assertEquals(3, help.id)
        val signature = help.signatures.single()
        assertEquals("Adds.", signature.documentation)
        assertEquals(7 until 13, signature.parameters[0].range)
        assertEquals(15 until 21, signature.parameters[1].range)
        assertEquals("The second.", signature.active(help.activeParameter)?.documentation)
    }

    @Test
    fun `a signature's own active parameter wins over the reply's`() {
        val help = SignatureHelp.help(
            """{"activeSignature":0,"activeParameter":0,"signatures":[{"label":"f(a, b)","activeParameter":1,
               "parameters":[{"label":"a"},{"label":"b"}]}]}""",
        )!!
        assertEquals(5 until 6, help.signatures.single().active(help.activeParameter)?.range)
    }

    @Test
    fun `a parameter the label does not contain is kept, without a range`() {
        val help = SignatureHelp.help(
            """{"signatures":[{"label":"f(x)","parameters":[{"label":"missing"},{"label":[2,40]}]}]}""",
        )!!
        val parameters = help.signatures.single().parameters
        // Kept, so the indices the extension uses still line up.
        assertEquals(2, parameters.size)
        assertNull(parameters[0].range)
        assertNull(parameters[1].range)
        assertNull(help.id)
    }

    @Test
    fun `nothing is no help, and a reply with no signatures is still read for its id`() {
        assertNull(SignatureHelp.help(null))
        assertNull(SignatureHelp.help(""))
        assertNull(SignatureHelp.help("null"))
        val empty = SignatureHelp.help("""{"id":5,"signatures":[],"activeSignature":0,"activeParameter":0}""")!!
        assertEquals(5, empty.id)
        assertTrue(empty.signatures.isEmpty())
    }
}
