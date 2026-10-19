package me.helvert.codebridge.exthost.protocol.payload

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider registrations, the requests made back to them, and the replies that come home. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class LanguageFeaturesTest {

    @Test
    fun `document highlights retain access kinds and ignore malformed entries`() {
        val range = Range(2, 3, 2, 8)
        val reply =
            """[{"range":${range.toJson()},"kind":3},{"range":${range.toJson()}},null,{"range":{"startLineNumber":{}}}]"""
        assertEquals(
            listOf(LanguageFeatures.DocumentHighlight(range, 3), LanguageFeatures.DocumentHighlight(range, 1)),
            LanguageFeatures.documentHighlights(reply),
        )
        assertTrue(LanguageFeatures.documentHighlights("null").isEmpty())
        assertTrue(LanguageFeatures.documentHighlights("invalid").isEmpty())
        assertEquals(7, LanguageFeatures.highlightRegistration("[7,[]]").handle)
    }

    @Test
    fun `definition links preserve the origin selection range`() {
        val range = Range(2, 3, 2, 8)
        val uri = UriComponents.of(Path.of("source.txt").toAbsolutePath()).toJson()
        val links =
            LanguageFeatures.definitionLinks("""[{"uri":$uri,"range":${range.toJson()},"originSelectionRange":${range.toJson()}}]""")
        assertEquals(range, links.single().origin)
    }

    @Test
    fun `reads a hover registration`() {
        val registration = LanguageFeatures.hoverRegistration("""[3,[{"language":"codebridgebundled"}],"me.probe"]""")
        assertEquals(3, registration.handle)
        assertTrue(registration.selector.toString().contains("codebridgebundled"))
    }

    @Test
    fun `reads a completion registration with its trigger characters`() {
        val (registration, triggers, resolves) =
            LanguageFeatures.completionRegistration("""[4,[{"language":"x"}],[".","-"],true,"me.probe"]""")
        assertEquals(4, registration.handle)
        assertEquals(listOf(".", "-"), triggers)
        assertTrue(resolves)
    }

    @Test
    fun `a provider that offers no trigger characters is still registered`() {
        val (registration, triggers, resolves) = LanguageFeatures.completionRegistration("""[4,[],null,false,"me.probe"]""")
        assertEquals(4, registration.handle)
        assertEquals(emptyList(), triggers)
        assertTrue(!resolves)
    }

    @Test
    fun `reads a definition registration`() {
        // Two arguments, not the hover's three: this one carries no extension id.
        val registration = LanguageFeatures.definitionRegistration("""[5,[{"language":"codebridgebundled"}]]""")
        assertEquals(5, registration.handle)
        assertTrue(registration.selector.toString().contains("codebridgebundled"))
    }

    @Test
    fun `reads type definition and implementation registrations alike`() {
        for (method in listOf(
            LanguageFeatures.REGISTER_TYPE_DEFINITION,
            LanguageFeatures.REGISTER_IMPLEMENTATION,
        )) {
            val registration = LanguageFeatures.locationRegistration(method, """[6,[{"language":"rust"}]]""")
            assertEquals(6, registration.handle, method)
            assertTrue(registration.selector.toString().contains("rust"), method)
        }
    }

    @Test
    fun `every unregister names the handle first`() {
        assertEquals(7, LanguageFeatures.unregisteredHandle($$"$unregisterHoverProvider", "[7]"))
        assertNull(LanguageFeatures.unregisteredHandle($$"$unregisterHoverProvider", "[]"))
    }

    @Test
    fun `builds the hover request without a cancellation token`() {
        // The host appends its own for a cancellable call; sending one would shift every argument.
        val resource = UriComponents.of(Path.of("C:\\Projects\\example.codebridgebundled"))
        val args = LanguageFeatures.provideHover(2, resource, Position(4, 9))
        assertTrue(args.startsWith("""[2,{"scheme":"file","""), args)
        assertTrue(args.contains(""""lineNumber":4,"column":9"""), args)
        assertTrue(args.endsWith("""{"verbosityRequest":null}]"""), args)
    }

    @Test
    fun `builds the completion request as an explicit invocation`() {
        val resource = UriComponents.of(Path.of("C:\\Projects\\example.codebridgebundled"))
        val args = LanguageFeatures.provideCompletions(2, resource, Position(1, 1))
        assertTrue(args.endsWith("""{"triggerKind":0}]"""), args)
    }

    @Test
    fun `builds the definition request without a context argument`() {
        val resource = UriComponents.of(Path.of("C:\\Projects\\example.codebridgebundled"))
        val args = LanguageFeatures.provideDefinition(2, resource, Position(4, 9))
        assertTrue(args.startsWith("""[2,{"scheme":"file","""), args)
        assertTrue(args.endsWith("""{"lineNumber":4,"column":9}]"""), args)
    }

    @Test
    fun `reads the places a definition reply names`() {
        // Spelled out, unlike a completion reply: this shape survived the runtime's minifier.
        val reply = """[{"uri":{"scheme":"file","authority":"","path":"/C:/x/target.py","query":"","fragment":""},""" +
                """"range":{"startLineNumber":7,"startColumn":1,"endLineNumber":9,"endColumn":2},""" +
                """"targetSelectionRange":{"startLineNumber":7,"startColumn":5,"endLineNumber":7,"endColumn":9}}]"""
        val link = LanguageFeatures.definitionLinks(reply).single()
        assertEquals("/C:/x/target.py", link.resource.path)
        assertEquals(Range(7, 1, 9, 2), link.target)
        // The name inside the definition is where the caret goes, not the first line of the body.
        assertEquals(Position(7, 5), link.caret)
    }

    @Test
    fun `falls back to the definition itself when no name was picked out`() {
        // What a `Location` becomes, which is all LSP's own `textDocument/definition` reply carries.
        val reply = """[{"uri":{"scheme":"file","path":"/C:/x/target.py"},""" +
                """"range":{"startLineNumber":3,"startColumn":2,"endLineNumber":3,"endColumn":6}}]"""
        assertEquals(Position(3, 2), LanguageFeatures.definitionLinks(reply).single().caret)
    }

    @Test
    fun `treats a provider that knows nothing as no definition`() {
        assertEquals(emptyList(), LanguageFeatures.definitionLinks("[]"))
        // A link with no resource or no range names nowhere, and is dropped rather than guessed at.
        assertEquals(emptyList(), LanguageFeatures.definitionLinks("""[{"range":{"startLineNumber":1}}]"""))
        assertEquals(emptyList(), LanguageFeatures.definitionLinks("""[{"uri":{"path":"/C:/x"}}]"""))
        assertEquals(emptyList(), LanguageFeatures.definitionLinks("null"))
        assertEquals(emptyList(), LanguageFeatures.definitionLinks("not json at all"))
    }

    @Test
    fun `builds the reference request asking for the declaration too`() {
        val resource = UriComponents.of(Path.of("C:\\Projects\\example.codebridgebundled"))
        val args = LanguageFeatures.provideReferences(5, resource, Position(4, 9))
        assertTrue(args.startsWith("""[5,{"scheme":"file","""), args)
        assertTrue(args.endsWith("""{"lineNumber":4,"column":9},{"includeDeclaration":true}]"""), args)
        assertEquals(5, LanguageFeatures.referenceRegistration("""[5,[{"language":"codebridgebundled"}]]""").handle)
    }

    @Test
    fun `reads the places a reference reply names and drops the rest`() {
        val range = Range(7, 5, 7, 9)
        val reply = """[{"uri":{"scheme":"file","path":"/C:/x/target.py"},"range":${range.toJson()}},""" +
                """{"uri":{"path":"/C:/x"}},{"range":${range.toJson()}},null]"""
        val location = LanguageFeatures.locations(reply).single()
        assertEquals("/C:/x/target.py", location.resource.path)
        assertEquals(range, location.range)
        // A provider with nothing to say answers `undefined`, which arrives as null or nothing.
        assertEquals(emptyList(), LanguageFeatures.locations("null"))
        assertEquals(emptyList(), LanguageFeatures.locations(""))
    }

    @Test
    fun `reads a rename registration and whether it can say what is renamed`() {
        val registration = LanguageFeatures.renameRegistration("""[9,[{"language":"rust"}],true]""")
        assertEquals(9, registration.handle)
        assertTrue(registration.resolvesLocation)
        assertEquals(false, LanguageFeatures.renameRegistration("""[9,[]]""").resolvesLocation)
    }

    @Test
    fun `builds the rename request with the new name last`() {
        val resource = UriComponents.of(Path.of("source.rs").toAbsolutePath())
        val args = LanguageFeatures.provideRenameEdits(4, resource, Position(2, 6), "total")
        assertTrue(args.startsWith("[4,"), args)
        assertTrue(args.endsWith(""","total"]"""), args)
    }

    @Test
    fun `reads a rename location, a refusal, and nothing`() {
        val range = Range(2, 5, 2, 11)
        assertEquals(
            LanguageFeatures.RenameLocation(range, "symbol", null),
            LanguageFeatures.renameLocation("""{"range":${range.toJson()},"text":"symbol"}"""),
        )
        assertEquals(
            LanguageFeatures.RenameLocation(null, null, "Cannot rename a keyword"),
            LanguageFeatures.renameLocation("""{"rejectReason":"Cannot rename a keyword"}"""),
        )
        assertNull(LanguageFeatures.renameLocation("null"))
        assertNull(LanguageFeatures.renameLocation(""))
        assertNull(LanguageFeatures.renameLocation("""{"range":${range.toJson()}}"""))
    }

    @Test
    fun `reads the edit a rename answers with, or why there is none`() {
        val range = Range(1, 5, 1, 11)
        val uri = UriComponents.of(Path.of("source.rs").toAbsolutePath()).toJson()
        val edits = LanguageFeatures.renameEdits(
            """{"edits":[{"resource":$uri,"textEdit":{"range":${range.toJson()},"text":"total"},"versionId":3}]}""",
        )
        val edit = edits?.edit?.textEdits?.single()
        assertEquals(TextEdit(range, "total"), edit?.edit)
        assertEquals(3, edit?.versionId)
        assertNull(edits?.rejectReason)
        assertEquals("Not a symbol", LanguageFeatures.renameEdits("""{"rejectReason":"Not a symbol"}""")?.rejectReason)
        assertNull(LanguageFeatures.renameEdits("null"))
        assertNull(LanguageFeatures.renameEdits(""))
    }

    @Test
    fun `reads the markdown vs code returns`() {
        // Not LSP's `MarkupContent`: a MarkdownString whose plaintext spaces are `&nbsp;`-escaped.
        // Getting this wrong shows the user escape sequences.
        val reply =
            """{"contents":[{"value":"Shared&nbsp;server&nbsp;PID&nbsp;42;&nbsp;version&nbsp;1","isTrusted":false}],"id":0}"""
        assertEquals("Shared server PID 42; version 1", LanguageFeatures.hoverText(reply))
    }

    @Test
    fun `joins the contributions vs code merges`() {
        val reply = """{"contents":[{"value":"first"},{"value":"second"}]}"""
        assertEquals("first\n\nsecond", LanguageFeatures.hoverText(reply))
    }

    @Test
    fun `treats an empty or absent hover as no documentation`() {
        assertNull(LanguageFeatures.hoverText("""{"contents":[]}"""))
        assertNull(LanguageFeatures.hoverText("""{"contents":[{"value":"   "}]}"""))
        assertNull(LanguageFeatures.hoverText("null"))
        assertNull(LanguageFeatures.hoverText("not json at all"))
    }

    @Test
    fun `reads what a debugger hover evaluates, with or without an expression of the extension's`() {
        val named = LanguageFeatures.evaluatableExpression(
            """{"range":{"startLineNumber":3,"startColumn":5,"endLineNumber":3,"endColumn":10},"expression":"self.total"}"""
        )!!
        assertEquals(Range(3, 5, 3, 10), named.range)
        assertEquals("self.total", named.expression)
        assertEquals(
            null, LanguageFeatures.evaluatableExpression(
                """{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":2}}"""
            )!!.expression
        )
        assertNull(LanguageFeatures.evaluatableExpression("null"))
    }
}
