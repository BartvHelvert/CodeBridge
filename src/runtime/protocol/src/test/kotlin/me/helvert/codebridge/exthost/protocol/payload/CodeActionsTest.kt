package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.test.*

/** Code actions, formatting and the edits both produce, in the shapes the 1.135 host sends. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class CodeActionsTest {

    private val resource = UriComponents.of(Path.of("C:\\Projects\\main.rs"))
    private val uri = resource.toJson()

    @Test
    fun `reads a code action registration and whether it resolves`() {
        val registration = CodeActions.registration(
            $$"""[9,[{"$serialized":true,"language":"rust","scheme":"file"}],{"providedKinds":["quickfix"]},"rust-analyzer","rust-lang.rust-analyzer",true]"""
        )
        assertEquals(9, registration.handle)
        assertTrue(registration.supportsResolve)
        assertTrue(registration.selector.toString().contains("rust"))
        assertEquals(false, CodeActions.registration("""[9,[],{},"x","y",false]""").supportsResolve)
    }

    @Test
    fun `builds the request as an explicit invocation over a range`() {
        val args = CodeActions.provide(4, resource, Range(2, 3, 2, 3))
        assertTrue(args.startsWith("""[4,{"scheme":"file","""), args)
        assertTrue(
            args.endsWith(""""startLineNumber":2,"startColumn":3,"endLineNumber":2,"endColumn":3},{"trigger":1}]"""),
            args
        )
        assertTrue(
            CodeActions.provide(4, resource, Range(1, 1, 1, 1), only = "quickfix")
                .endsWith("""{"trigger":1,"only":"quickfix"}]""")
        )
        assertEquals("[4,[12,3]]", CodeActions.resolve(4, 12 to 3))
        assertEquals("[4,12]", CodeActions.release(4, 12))
    }

    @Test
    fun `reads the actions offered with their edits commands and cache ids`() {
        val reply = $$"""{"cacheId":12,"actions":[
            {"cacheId":[12,0],"title":"Add missing import","kind":"quickfix","isPreferred":true,
             "edit":{"edits":[{"resource":$$uri,"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1},"text":"use x;\n"},"versionId":4}]}},
            {"cacheId":[12,1],"title":"Extract function","kind":"refactor.extract",
             "command":{"$ident":"rust-analyzer.applyFix /1","id":"__vsc123","title":"Extract","arguments":["rust-analyzer.applyFix /1"]}},
            {"cacheId":[12,2],"title":"Inline","disabled":"Nothing to inline"},
            {"_isSynthetic":true,"title":"Run","command":{"id":"ext.run","title":"Run"}},
            {"cacheId":[12,4]}
        ]}"""
        val offered = CodeActions.offered(reply)
        assertEquals(12, offered.cacheId)
        assertEquals(
            listOf("Add missing import", "Extract function", "Inline", "Run"),
            offered.actions.map { it.title })
        val import = offered.actions[0]
        assertEquals(12 to 0, import.cacheId)
        assertTrue(import.preferred)
        assertEquals("quickfix", import.kind)
        val edit = import.edit!!.textEdits.single()
        assertEquals(4, edit.versionId)
        assertEquals(TextEdit(Range(1, 1, 1, 1), "use x;\n"), edit.edit)
        val extract = offered.actions[1].command!!
        assertEquals("__vsc123", extract.id)
        assertEquals(listOf(JsonPrimitive("rust-analyzer.applyFix /1")), extract.arguments.toList())
        assertEquals("Nothing to inline", offered.actions[2].disabled)
        // A bare command an extension returned in place of an action cannot be resolved.
        assertNull(offered.actions[3].cacheId)
        assertEquals("""["ext.run"]""", CodeActions.execute(offered.actions[3].command!!))
        assertEquals("""["__vsc123","rust-analyzer.applyFix /1"]""", CodeActions.execute(extract))
    }

    @Test
    fun `treats a provider with nothing to offer as no actions`() {
        for (reply in listOf(null, "", "null", "not json", "[]")) {
            assertEquals(CodeActions.Offered(null, emptyList()), CodeActions.offered(reply))
        }
    }

    @Test
    fun `reads what resolving adds`() {
        val resolved = CodeActions.resolved(
            """{"edit":{"edits":[{"resource":$uri,"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":4},"text":"let"}}]}}"""
        )
        assertEquals("let", resolved.edit!!.textEdits.single().edit.text)
        assertNull(resolved.command)
        assertEquals(CodeActions.Resolved(null, null), CodeActions.resolved("{}"))
        assertEquals(CodeActions.Resolved(null, null), CodeActions.resolved(null))
    }

    @Test
    fun `keeps file operations and text edits in the order given`() {
        val created = UriComponents.of(Path.of("C:\\Projects\\new.rs")).toJson()
        val edit = WorkspaceEdit.applied(
            $$"""[{"edits":[
            {"newResource":$$created,"options":{"ignoreIfExists":true}},
            {"resource":$$created,"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1},"text":"${1:fn} main() {}","insertAsSnippet":true,"keepWhitespace":true}},
            {"oldResource":$$uri,"newResource":$$created,"options":{"overwrite":true}},
            {"resource":$$uri,"cellEdit":{}},
            {"oldResource":$$uri}
        ]},null,false]"""
        )
        assertEquals(4, edit.entries.size)
        val create = edit.entries[0] as WorkspaceEdit.FileEdit
        assertNull(create.oldResource)
        assertTrue(create.ignoreIfExists)
        val text = edit.entries[1] as WorkspaceEdit.ResourceEdit
        assertTrue(text.edit.snippet)
        assertTrue(text.edit.keepWhitespace)
        assertNull(text.versionId)
        assertTrue((edit.entries[2] as WorkspaceEdit.FileEdit).overwrite)
        // The notebook cell edit has nothing here to apply to and is skipped; the delete is kept.
        assertNull((edit.entries[3] as WorkspaceEdit.FileEdit).newResource)
        assertFailsWith<PayloadException> { WorkspaceEdit.applied("[null]") }
    }

    @Test
    fun `reads formatting registrations requests and replies`() {
        val registration = Formatting.registration(
            Formatting.REGISTER_RANGE,
            """[3,[{"language":"rust"}],{"value":"rust-lang.rust-analyzer"},"rust-analyzer",true]"""
        )
        assertEquals(3, registration.handle)
        assertEquals("rust-analyzer", registration.extensionName)
        val options = Formatting.Options(4, insertSpaces = true)
        assertTrue(Formatting.provideDocument(3, resource, options).endsWith("""{"tabSize":4,"insertSpaces":true}]"""))
        val ranged = Formatting.provideRange(3, resource, Range(1, 1, 2, 1), options)
        assertTrue(ranged.endsWith(""""endColumn":1},{"tabSize":4,"insertSpaces":true}]"""), ranged)
        assertEquals(
            listOf(TextEdit(Range(1, 1, 1, 3), "  ")),
            TextEdit.list("""[{"text":"  ","range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":3}},{"text":"x"}]"""),
        )
        // Declining and finding nothing to change read differently.
        assertNull(TextEdit.list(null))
        assertNull(TextEdit.list("null"))
        assertEquals(emptyList(), TextEdit.list("[]"))
    }
}
