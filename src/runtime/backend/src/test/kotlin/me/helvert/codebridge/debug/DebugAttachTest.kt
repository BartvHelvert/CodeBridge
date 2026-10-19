package me.helvert.codebridge.debug

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Attach to Process, from the attach configurations debuggers declare. */
class DebugAttachTest {

    @Test
    fun `reads CodeLLDB's attach snippet, whose pid is inserted unquoted with a comment after it`() {
        // As CodeLLDB's package.json writes it.
        val lldb = extension("vadimcn", "vscode-lldb", $$"""[{
            "type": "lldb", "label": "CodeLLDB",
            "variables": { "pickProcess": "lldb.pickProcess", "pickMyProcess": "lldb.pickMyProcess" },
            "configurationSnippets": [
              { "label": "CodeLLDB: Launch", "body": { "type": "lldb", "request": "launch", "name": "${2:Launch}", "program": "^\"\\${workspaceFolder}/${1:<executable file>}\"" } },
              { "label": "CodeLLDB: Attach to Process", "body": { "type": "lldb", "request": "attach", "name": "${2:Attach}", "pid": "^\"\\${command:pickMyProcess}\" // use \\${command:pickProcess} to pick other users' processes" } }
            ] }]""")
        val template = DebugAttach.templates(listOf(lldb)).single()
        assertEquals("CodeLLDB", template.label)
        assertEquals("pid", template.field)
        val configuration = DebugAttach.configuration(template, 4242, "sleeper.exe")
        assertEquals("lldb", configuration["type"]!!.jsonPrimitive.content)
        assertEquals("attach", configuration["request"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("4242"), configuration["pid"])
        assertEquals("Attach to sleeper.exe (4242)", configuration["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `finds js-debug's capitalized picker in an initial configuration`() {
        val node = extension("ms-vscode", "js-debug", $$"""[{
            "type": "pwa-node", "label": "Node.js",
            "variables": { "PickProcess": "extension.js-debug.pickNodeProcess" },
            "initialConfigurations": [ { "type": "pwa-node", "request": "attach", "name": "Attach", "processId": "${command:PickProcess}" } ] }]""")
        assertEquals("processId", DebugAttach.templates(listOf(node)).single().field)
    }

    @Test
    fun `a debugger that cannot be told a process is not offered`() {
        val noPicker = extension("a", "launch-only", """[{
            "type": "x", "configurationSnippets": [ { "body": { "type": "x", "request": "attach", "port": 9229 } } ] }]""")
        val pickerUnused = extension("b", "picker-unused", """[{
            "type": "y", "variables": { "pickProcess": "y.pick" },
            "configurationSnippets": [ { "body": { "type": "y", "request": "launch", "program": "a" } } ] }]""")
        assertTrue(DebugAttach.templates(listOf(noPicker, pickerUnused)).isEmpty())
    }

    @Test
    fun `snippet text keeps its defaults and loses its stops`() {
        assertEquals(JsonPrimitive("Attach"), DebugAttach.snippet(JsonPrimitive($$"${2:Attach}")))
        assertEquals(JsonPrimitive("a  b"), DebugAttach.snippet(JsonPrimitive($$"a $1 b${0}")))
        assertEquals(JsonPrimitive($$"${workspaceFolder}/x"), DebugAttach.snippet(JsonPrimitive($$"\\${workspaceFolder}/x")))
    }

    private fun extension(publisher: String, name: String, debuggers: String): InstalledExtension {
        val manifest = """{ "publisher": "$publisher", "name": "$name", "version": "1.0.0", "contributes": { "debuggers": $debuggers } }"""
        val parsed = requireNotNull(VsixManifestParser.parse(manifest).manifest) { "fixture manifest did not parse" }
        return InstalledExtension(parsed, Path.of("."), null, "2026-01-01T00:00:00Z")
    }
}
