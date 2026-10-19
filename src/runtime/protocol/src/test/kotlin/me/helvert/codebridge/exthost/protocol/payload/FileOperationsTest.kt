package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Renames and moves, in the shapes the 1.135 host's `ExtHostFileSystemEventService` takes and answers. */
class FileOperationsTest {

    private val moves = listOf(
        FileOperations.Change(UriComponents.of("file:///c%3A/src/a.ts"), UriComponents.of("file:///c%3A/src/lib/a.ts"))
    )

    @Test
    fun `asks as a move, with the source and target of each`() {
        val args =
            Json.parseToJsonElement(FileOperations.willRun(FileOperations.Operation.MOVE, moves, 60_000)).jsonArray

        assertEquals(3, args.size)
        assertEquals(2, args[0].jsonPrimitive.content.toInt())
        val pair = args[1].jsonArray.single().jsonObject
        assertEquals("/c:/src/a.ts", pair["source"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("/c:/src/lib/a.ts", pair["target"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("60000", args[2].jsonPrimitive.content)
    }

    @Test
    fun `tells of it the same way, without a timeout`() {
        val args = Json.parseToJsonElement(FileOperations.didRun(FileOperations.Operation.MOVE, moves)).jsonArray

        assertEquals(2, args.size)
        assertEquals(1, args[1].jsonArray.size)
    }

    @Test
    fun `a deletion names only what it deletes`() {
        val deleted = FileOperations.Change(null, UriComponents.of("file:///c%3A/src/a.ts"))
        val args =
            Json.parseToJsonElement(FileOperations.didRun(FileOperations.Operation.DELETE, listOf(deleted))).jsonArray

        assertEquals(1, args[0].jsonPrimitive.content.toInt())
        val pair = args[1].jsonArray.single().jsonObject
        assertEquals(setOf("target"), pair.keys)
    }

    @Test
    fun `operations are numbered as VS Code numbers them`() {
        assertEquals(listOf(0, 1, 2, 3), FileOperations.Operation.entries.map { it.code })
    }

    @Test
    fun `reads the edit the listeners made, and who made it`() {
        val reply = """{"edit":{"edits":[
            {"resource":{"scheme":"file","path":"/c:/src/main.ts"},"versionId":4,
             "textEdit":{"range":{"startLineNumber":1,"startColumn":20,"endLineNumber":1,"endColumn":25},"text":"./lib/a"}}
        ]},"extensionNames":["TypeScript and JavaScript Language Features"]}"""

        val participation = FileOperations.participation(reply)!!

        assertEquals(listOf("TypeScript and JavaScript Language Features"), participation.extensionNames)
        val edit = participation.edit.textEdits.single()
        assertEquals("/c:/src/main.ts", edit.resource.path)
        assertEquals(4, edit.versionId)
        assertEquals("./lib/a", edit.edit.text)
    }

    @Test
    fun `no listener with an edit answers nothing`() {
        assertNull(FileOperations.participation(null))
        assertNull(FileOperations.participation(""))
        assertNull(FileOperations.participation("""{"extensionNames":[]}"""))
    }

    @Test
    fun `reads what an extension said of each change`() {
        val edit = WorkspaceEdit.from(
            Json.parseToJsonElement(
                """{"edits":[
            {"resource":{"scheme":"file","path":"/c:/src/main.ts"},
             "textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1},"text":"x"},
             "metadata":{"needsConfirmation":true,"label":"Update imports","description":"for a.ts"}},
            {"oldResource":{"scheme":"file","path":"/c:/src/a.ts"},"newResource":{"scheme":"file","path":"/c:/src/b.ts"},
             "metadata":{"needsConfirmation":false,"label":"Rename"}},
            {"resource":{"scheme":"file","path":"/c:/src/main.ts"},
             "textEdit":{"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":1},"text":"y"}}
        ]}"""
            )
        )!!

        assertEquals(WorkspaceEdit.Metadata(true, "Update imports", "for a.ts"), edit.entries[0].metadata)
        assertEquals(WorkspaceEdit.Metadata(false, "Rename", null), edit.entries[1].metadata)
        assertNull(edit.entries[2].metadata)
        assertTrue(edit.needsConfirmation)
        assertFalse(WorkspaceEdit(edit.entries.drop(1)).needsConfirmation)
    }
}
