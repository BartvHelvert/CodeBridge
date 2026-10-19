package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.test.*

/** Inlay hints, in the shapes the 1.135 host sends. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class InlayHintsTest {

    private val resource = UriComponents.of(Path.of("C:\\Projects\\main.rs"))

    @Test
    fun `reads a registration with its event handle and name`() {
        val registration = InlayHints.registration(
            $$"""[12,[{"$serialized":true,"language":"rust","scheme":"file"}],true,13,"rust-analyzer"]"""
        )
        assertEquals(12, registration.handle)
        assertTrue(registration.supportsResolve)
        assertEquals(13, registration.eventHandle)
        assertEquals("rust-analyzer", registration.displayName)
        assertTrue(registration.selector.toString().contains("rust"))
        val plain = InlayHints.registration("""[12,[],false,null,"x"]""")
        assertNull(plain.eventHandle)
        assertFalse(plain.supportsResolve)
        assertEquals(13, InlayHints.emittedEventHandle("[13]"))
    }

    @Test
    fun `builds the request over a range and the release`() {
        val args = InlayHints.provide(4, resource, Range(1, 1, 3, 2))
        assertTrue(args.startsWith("""[4,{"scheme":"file","""), args)
        assertTrue(args.endsWith(""""startLineNumber":1,"startColumn":1,"endLineNumber":3,"endColumn":2}]"""), args)
        assertEquals("[4,7]", InlayHints.release(4, 7))
        assertEquals("[4,[7,2]]", InlayHints.resolve(4, 7 to 2))
    }

    @Test
    fun `reads hints with plain and split labels`() {
        val reply = """{"cacheId":7,"hints":[
            {"label":": i32","cacheId":[7,0],"position":{"lineNumber":1,"column":6},"kind":1,"tooltip":{"value":"a&nbsp;type"}},
            {"label":[{"label":"count"},{"label":":","tooltip":"the count"}],"cacheId":[7,1],
             "position":{"lineNumber":2,"column":9},"kind":2,"paddingRight":true},
            {"label":"// end","cacheId":[7,2],"position":{"lineNumber":3,"column":2},"paddingLeft":true}
        ]}"""
        val offered = InlayHints.offered(reply)
        assertEquals(7, offered.cacheId)
        val (type, parameter, other) = offered.hints
        assertEquals(
            InlayHints.Hint(
                Position(1, 6),
                listOf(InlayHints.Part(": i32")),
                "a type",
                InlayHints.KIND_TYPE,
                paddingLeft = false,
                paddingRight = false,
                cacheId = 7 to 0
            ),
            type,
        )
        assertFalse(type.split)
        assertTrue(parameter.split)
        assertEquals(7 to 1, parameter.cacheId)
        assertEquals(listOf("count", ":"), parameter.parts.map { it.text })
        assertEquals("count:", parameter.label)
        assertEquals("the count", parameter.tooltip)
        assertEquals(InlayHints.KIND_PARAMETER, parameter.kind)
        assertTrue(parameter.paddingRight)
        assertNull(other.kind)
        assertTrue(other.paddingLeft)
    }

    @Test
    fun `a part leads to its place or runs its command, and says so in a payload`() {
        val uri = resource.toJson()
        val reply = $$"""{"cacheId":3,"hints":[{"position":{"lineNumber":1,"column":6},"kind":1,"label":[
            {"label":": "},
            {"label":"Vec","location":{"uri":$$uri,"range":{"startLineNumber":40,"startColumn":12,"endLineNumber":40,"endColumn":15}}},
            {"label":"<u8>","command":{"$ident":"x","id":"__vsc7","title":"Show","arguments":["a",1]}},
            {"label":"!","location":{"uri":$$uri,"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":1}},
             "command":{"id":"ext.run","title":"Run"}}
        ]}]}"""
        val hint = InlayHints.offered(reply).hints.single()
        fun click(part: Int) = InlayHints.click(hint, part, resolvableBy = null)?.let(InlayHints::click)

        assertNull(click(0))
        val location = LanguageFeatures.Location(resource, Range(40, 12, 40, 15))
        assertEquals(location, hint.parts[1].location)
        assertEquals(InlayHints.Click.GoTo(location), click(1))
        assertEquals(
            InlayHints.Click.Run(
                CodeActions.Command(
                    "__vsc7",
                    "Show",
                    JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1)))
                )
            ),
            click(2),
        )
        // Ctrl+click goes to the place, as VS Code's does; the command is a plain click's there.
        assertTrue(click(3) is InlayHints.Click.GoTo)
        assertNull(InlayHints.click("not json"))
        assertNull(InlayHints.click("{}"))
    }

    @Test
    fun `a part that says nothing yet is resolved when clicked, if its provider can`() {
        // What rust-analyzer sends: VS Code's client tells it places may wait for `inlayHint/resolve`.
        val reply = """{"cacheId":3,"hints":[
            {"position":{"lineNumber":1,"column":6},"kind":1,"cacheId":[3,0],"label":[{"label":": "},{"label":"Vec"}]},
            {"position":{"lineNumber":2,"column":6},"kind":1,"cacheId":[3,1],"label":": i32"}
        ]}"""
        val (split, plain) = InlayHints.offered(reply).hints

        assertEquals(
            InlayHints.Click.Resolve(9, 3 to 0, 1),
            InlayHints.click(split, 1, resolvableBy = 9)?.let(InlayHints::click)
        )
        assertNull(
            InlayHints.click(split, 1, resolvableBy = null),
            "A provider that cannot resolve has nothing more to say"
        )
        assertNull(InlayHints.click(plain, 0, resolvableBy = 9), "Only a part of a split label can lead anywhere")

        val uri = resource.toJson()
        val resolved = InlayHints.resolved(
            """{"position":{"lineNumber":1,"column":6},"kind":1,"cacheId":[3,0],"label":[
            {"label":": "},
            {"label":"Vec","location":{"uri":$uri,"range":{"startLineNumber":40,"startColumn":12,"endLineNumber":40,"endColumn":15}}}
        ]}"""
        )
        val location = LanguageFeatures.Location(resource, Range(40, 12, 40, 15))
        assertEquals(InlayHints.Click.GoTo(location), resolved?.parts?.get(1)?.action)
        assertNull(InlayHints.resolved(null))
        assertNull(InlayHints.resolved(""))
    }

    @Test
    fun `drops hints without a label or a position and reads nothing as nothing`() {
        val offered = InlayHints.offered(
            """{"cacheId":2,"hints":[
            {"label":"","position":{"lineNumber":1,"column":1}},
            {"label":[{"label":""}],"position":{"lineNumber":1,"column":1}},
            {"label":"x"}
        ]}"""
        )
        assertEquals(2, offered.cacheId)
        assertEquals(emptyList(), offered.hints)
        assertEquals(InlayHints.Offered(null, emptyList()), InlayHints.offered(null))
        assertEquals(InlayHints.Offered(null, emptyList()), InlayHints.offered(""))
    }
}
