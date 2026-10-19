package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkbenchCommandsTest {

    @Test
    fun `reads the settings query as a string or an options object, without its filters`() {
        assertEquals(
            listOf("gitlens.launchpad"),
            WorkbenchCommands.settingsQuery(arguments("""["@ext:eamodio.gitlens gitlens.launchpad"]"""))
        )
        assertEquals(
            listOf("editor.fontSize"),
            WorkbenchCommands.settingsQuery(arguments("""[{"query":"editor.fontSize"}]"""))
        )
        assertEquals(emptyList(), WorkbenchCommands.settingsQuery(arguments("[]")))
    }

    @Test
    fun `reads a diff of a GitLens revision against the working file`() {
        val diff = assertNotNull(
            WorkbenchCommands.diff(
                arguments(
                    $$"""
            [{"$mid":1,"scheme":"gitlens","authority":"abc","path":"/c:/p/src/Main.java","query":"{\"ref\":\"1234\"}"},
             {"$mid":1,"scheme":"file","path":"/c:/p/src/Main.java"},
             "Main.java (1234) ↔ Main.java",
             [null,{"preview":true}]]
        """
                )
            )
        )
        assertEquals("gitlens", diff.left.scheme)
        assertEquals("file", diff.right.scheme)
        assertEquals("Main.java (1234) ↔ Main.java", diff.title)
        assertNull(WorkbenchCommands.diff(arguments($$"""[{"$mid":1,"scheme":"file","path":"/a"}]""")))
    }

    @Test
    fun `reads changes, a missing side being an added or deleted file`() {
        val changes = assertNotNull(
            WorkbenchCommands.changes(
                arguments(
                    """
            ["Changes in 1234",
             [[{"scheme":"file","path":"/p/a.txt"},{"scheme":"gitlens","path":"/p/a.txt"},{"scheme":"file","path":"/p/a.txt"}],
              [{"scheme":"file","path":"/p/new.txt"},null,{"scheme":"file","path":"/p/new.txt"}]]]
        """
                )
            )
        )
        assertEquals("Changes in 1234", changes.title)
        assertEquals(2, changes.changes.size)
        assertEquals("gitlens", changes.changes[0].original?.scheme)
        assertNull(changes.changes[1].original)
        assertEquals("/p/new.txt", changes.changes[1].modified?.path)
    }

    @Test
    fun `answers execute-provider commands in the workbench's shapes, with revivable URIs`() {
        val (resource, position) = assertNotNull(
            WorkbenchCommands.resourceAt(arguments($$"""[{"$mid":1,"scheme":"file","path":"/p/a.java"},{"lineNumber":3,"column":7}]"""))
        )
        assertEquals("/p/a.java", resource.path)
        assertEquals(Position(3, 7), position)
        assertNull(WorkbenchCommands.resourceAt(arguments($$"""[{"$mid":1,"scheme":"file","path":"/p/a.java"}]""")))

        val range = Range(1, 2, 1, 5)
        val links = Json.parseToJsonElement(
            WorkbenchCommands.locationLinks(listOf(LanguageFeatures.DefinitionLink(resource, range, range, range)))
        ).jsonArray.single().jsonObject
        // Revived as a URI by the host only with the marshalling id; then a LocationLink, not a Location.
        assertEquals("1", links["uri"]!!.jsonObject[$$"$mid"].toString())
        assertNotNull(links["targetSelectionRange"])
        assertNotNull(links["originSelectionRange"])

        assertEquals("[]", WorkbenchCommands.hovers(null))
        assertEquals("""[{"contents":[{"value":"**x**"}]}]""", WorkbenchCommands.hovers("**x**"))
        assertEquals("q", WorkbenchCommands.query(arguments("[\"q\"]")))
    }

    @Test
    fun `reads showReferences as the host converts it`() {
        // rust-analyzer's "N implementations": vscode types, converted to the editor model on the way.
        val shown = assertNotNull(
            WorkbenchCommands.showReferences(
                arguments(
                    $$"""
            [{"$mid":1,"scheme":"file","path":"/c:/p/src/main.rs"},
             {"lineNumber":3,"column":5},
             [{"uri":{"$mid":1,"scheme":"file","path":"/c:/p/src/a.rs"},
               "range":{"startLineNumber":10,"startColumn":1,"endLineNumber":10,"endColumn":7}}]]
        """
                )
            )
        )
        assertEquals("/c:/p/src/main.rs", shown.resource.path)
        assertEquals(Position(3, 5), shown.position)
        assertEquals(Range(10, 1, 10, 7), shown.locations.single().range)
        assertEquals("/c:/p/src/a.rs", shown.locations.single().resource.path)
    }

    @Test
    fun `reads LSP-shaped positions and ranges`() {
        val shown = assertNotNull(
            WorkbenchCommands.showReferences(
                arguments(
                    """
            [{"scheme":"file","path":"/p/a.rs"},
             {"line":2,"character":4},
             [{"uri":{"scheme":"file","path":"/p/b.rs"},"range":{"start":{"line":9,"character":0},"end":{"line":9,"character":6}}},
              {"uri":{"scheme":"file","path":"/p/c.rs"},"range":[{"line":0,"character":1},{"line":0,"character":2}]}]]
        """
                )
            )
        )
        assertEquals(Position(3, 5), shown.position)
        assertEquals(listOf(Range(10, 1, 10, 7), Range(1, 2, 1, 3)), shown.locations.map { it.range })
    }

    @Test
    fun `refuses arguments of another shape`() {
        assertNull(WorkbenchCommands.showReferences(arguments("[]")))
        assertNull(WorkbenchCommands.showReferences(arguments("""[{"scheme":"file","path":"/p"},{"lineNumber":1,"column":1}]""")))
    }

    @Test
    fun `reads _workbench open as vscode open sends it`() {
        val open = assertNotNull(
            WorkbenchCommands.open(
                arguments(
                    $$"""
            [{"$mid":1,"scheme":"file","path":"/c:/p/Cargo.toml"},
             [-1,{"preserveFocus":true,"selection":{"startLineNumber":4,"startColumn":2,"endLineNumber":4,"endColumn":2}}],
             "Cargo.toml"]
        """
                )
            )
        )
        assertEquals("/c:/p/Cargo.toml", open.resource.path)
        assertEquals(Range(4, 2, 4, 2), open.selection)
        assertEquals(true, open.preserveFocus)
    }

    @Test
    fun `reads _workbench open with only a resource, or a URI string`() {
        val bare = assertNotNull(WorkbenchCommands.open(arguments("""[{"scheme":"file","path":"/p/a.rs"}]""")))
        assertNull(bare.selection)
        assertEquals(false, bare.preserveFocus)
        assertEquals(
            "https",
            assertNotNull(WorkbenchCommands.open(arguments("""["https://example.com/x"]"""))).resource.scheme
        )
        assertNull(WorkbenchCommands.open(arguments("[]")))
    }

    private fun arguments(json: String) = Json.parseToJsonElement(json) as JsonArray
}
