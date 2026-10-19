package me.helvert.codebridge.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.launch.InputDefinition
import me.helvert.codebridge.launch.PickOption
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Everything a debug session is decided by before anything is launched.
 *
 * This is where a `launch.json` becomes a set of adapter arguments and a list of extensions to
 * start, and every failure here is one the user has to be told about clearly -- a missing
 * configuration, an unsupported key, a debugger no installed extension provides. Requires no IDE:
 * it is a workspace directory in and a decision out.
 */
class DebugLaunchTest {

    @Test
    fun `reads a named configuration out of launch json`() {
        val workspace = workspace("""
            { "version": "0.2.0", "configurations": [
              { "name": "Launch", "type": "node", "request": "launch", "program": "app.js" },
              { "name": "Attach", "type": "node", "request": "attach", "port": 9229 }
            ] }
        """)
        assertEquals("app.js", DebugLaunch.configuration(workspace, "Launch")["program"]!!.jsonPrimitive())
        assertEquals(9229L, DebugLaunch.arguments(DebugLaunch.configuration(workspace, "Attach"))["port"])
    }

    @Test
    fun `says so when the configuration is missing or ambiguous`() {
        val workspace = workspace("""
            { "configurations": [
              { "name": "Same", "type": "node", "request": "launch" },
              { "name": "Same", "type": "node", "request": "launch" }
            ] }
        """)
        assertTrue(assertFailsWith<IllegalArgumentException> { DebugLaunch.configuration(workspace, "Same") }
            .message!!.contains("found 2"))
        assertTrue(assertFailsWith<IllegalArgumentException> { DebugLaunch.configuration(workspace, "Absent") }
            .message!!.contains("found 0"))
    }

    @Test
    fun `reads jsonc, which is what vs code writes`() {
        // Comments and trailing commas are normal in a .vscode folder and must not be an error.
        val workspace = workspace("""
            {
              // the usual launch entry
              "configurations": [
                { "name": "Launch", "type": "node", "request": "launch", },
              ],
            }
        """)
        assertEquals("node", DebugLaunch.configuration(workspace, "Launch")["type"]!!.jsonPrimitive())
    }

    @Test
    fun `refuses a workspace with no launch json rather than guessing`() {
        val empty = Files.createTempDirectory("codebridge-debug-empty")
        assertTrue(assertFailsWith<IllegalArgumentException> { DebugLaunch.read(empty) }
            .message!!.contains("No .vscode/launch.json"))
    }

    @Test
    fun `settings are optional and default to empty`() {
        val workspace = workspace("""{ "configurations": [] }""")
        assertEquals(buildJsonObject { }, DebugLaunch.settings(workspace))

        workspace.resolve(".vscode/settings.json").writeText("""{ /* c */ "java.home": "/opt/jdk", }""")
        assertEquals("/opt/jdk", DebugLaunch.settings(workspace)["java.home"]!!.jsonPrimitive())

        // An empty file, or one with only a comment, is no settings rather than a broken launch.
        workspace.resolve(".vscode/settings.json").writeText("")
        assertEquals(buildJsonObject { }, DebugLaunch.settings(workspace))
        workspace.resolve(".vscode/settings.json").writeText("// nothing yet\n")
        assertEquals(buildJsonObject { }, DebugLaunch.settings(workspace))
    }

    @Test
    fun `substitutes the variables a launch entry may use`() {
        val workspace = workspace("""{ "configurations": [] }""")
        val config = json($$"""{ "program": "${workspaceFolder}/app.js", "cwd": "${config:java.home}" }""")
        val resolved = DebugLaunch.substitute(config, workspace, json("""{ "java.home": "/opt/jdk" }"""))
        assertEquals(workspace.toString().replace('\\', '/') + "/app.js", resolved["program"]!!.jsonPrimitive().replace('\\', '/'))
        assertEquals("/opt/jdk", resolved["cwd"]!!.jsonPrimitive())
    }

    @Test
    fun `refuses to launch with a variable it could not resolve`() {
        // Passing `${command:...}` through unresolved would hand the adapter a literal placeholder
        // and fail somewhere much less obvious.
        val workspace = workspace("""{ "configurations": [] }""")
        val config = json($$"""{ "program": "${command:pickFile}" }""")
        assertTrue(assertFailsWith<IllegalArgumentException> {
            DebugLaunch.substitute(config, workspace, buildJsonObject { })
        }.message!!.contains("Unresolved launch variables"))
    }

    @Test
    fun `accepts launch and attach, and nothing else`() {
        DebugLaunch.validate(json("""{ "type": "node", "request": "launch" }"""))
        DebugLaunch.validate(json("""{ "type": "node", "request": "attach" }"""))
        assertFailsWith<IllegalArgumentException> { DebugLaunch.validate(json("""{ "request": "launch" }""")) }
        assertFailsWith<IllegalArgumentException> { DebugLaunch.validate(json("""{ "type": "node" }""")) }
        assertFailsWith<IllegalArgumentException> { DebugLaunch.validate(json("""{ "type": "node", "request": "test" }""")) }
    }

    @Test
    fun `accepts post debug tasks and debug servers, of the right shape`() {
        DebugLaunch.validate(json("""{ "type": "node", "request": "launch", "postDebugTask": "clean", "debugServer": 4711 }"""), resolved = true)
        assertEquals("clean", DebugLaunch.postDebugTask(json("""{ "postDebugTask": "clean" }""")))
        assertFailsWith<IllegalArgumentException> {
            DebugLaunch.validate(json("""{ "type": "node", "request": "launch", "postDebugTask": ["a"] }"""))
        }
        // Before substitution it may still be `${input:port}`; after, the host would ignore a string.
        DebugLaunch.validate(json($$"""{ "type": "node", "request": "launch", "debugServer": "${input:port}" }"""))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            DebugLaunch.validate(json("""{ "type": "node", "request": "launch", "debugServer": "4711" }"""), resolved = true)
        }.message!!.contains("debugServer"))
    }

    @Test
    fun `recognises a refused launch, which ends the session`() {
        val launch = """{"type":"request","seq":2,"command":"launch","arguments":{}}"""
        assertEquals(2, DebugLaunch.startRequestSeq(launch))
        assertEquals(null, DebugLaunch.startRequestSeq("""{"type":"request","seq":3,"command":"threads"}"""))
        // CodeLLDB's own answer, before any `initialized`: an empty command.
        val refused = """{"seq":3,"type":"response","request_seq":2,"success":false,"command":"",""" +
            """"message":"'/tmp/x' does not exist","show_user":true}"""
        assertEquals("'/tmp/x' does not exist", DebugLaunch.startFailure(refused, 2))
        assertEquals(null, DebugLaunch.startFailure(refused, 7))
        assertEquals(null, DebugLaunch.startFailure("""{"type":"response","request_seq":2,"success":true,"command":"launch"}""", 2))
        val events = DebugLaunch.startFailedEvents("gone").map(::json)
        assertEquals(listOf("output", "terminated"), events.map { it["event"]!!.jsonPrimitive() })
    }

    @Test
    fun `a terminated event that asks for no restart reaches the client without one`() {
        // java-debug's, at the end of every session: IntelliJ's client took it as a restart, and never stopped.
        val ended =
            DebugLaunch.withoutFalseRestart("""{"seq":9,"type":"event","event":"terminated","body":{"restart":false}}""")
        assertEquals(json("""{"seq":9,"type":"event","event":"terminated","body":{}}"""), json(ended))
        for (none in listOf("null", "0", "\"\"")) {
            val stripped =
                json(DebugLaunch.withoutFalseRestart("""{"type":"event","event":"terminated","body":{"restart":$none}}"""))
            assertEquals(json("""{"type":"event","event":"terminated","body":{}}"""), stripped)
        }
        // A real request to restart, or any other message, passes untouched.
        for (kept in listOf(
            """{"type":"event","event":"terminated","body":{"restart":true}}""",
            """{"type":"event","event":"terminated","body":{"restart":{"port":1}}}""",
            """{"type":"event","event":"terminated"}""",
            """{"type":"event","event":"output","body":{"restart":false}}""",
        )) assertEquals(kept, DebugLaunch.withoutFalseRestart(kept))
    }

    @Test
    fun `a request's source path reaches a Windows adapter as a native path`() {
        // java-debug read `C:/...` as a URI with the scheme `C`, and so verified no breakpoint in the file.
        val sent = DebugLaunch.withNativeSourcePath(
            """{"seq":3,"type":"request","command":"setBreakpoints","arguments":{"source":{"name":"A.java","path":"C:/p/src/A.java"},"lines":[8]}}""",
            '\\'
        )
        assertEquals(
            json("""{"seq":3,"type":"request","command":"setBreakpoints","arguments":{"source":{"name":"A.java","path":"C:\\p\\src\\A.java"},"lines":[8]}}"""),
            json(sent)
        )
        val unc = DebugLaunch.withNativeSourcePath(
            """{"type":"request","command":"source","arguments":{"source":{"path":"//host/share/A.java"}}}""",
            '\\'
        )
        assertEquals(
            """\\host\share\A.java""",
            json(unc)["arguments"]!!.jsonObject["source"]!!.jsonObject["path"]!!.jsonPrimitive()
        )
        // A URI, a path already native, any other system, or no source: untouched.
        for (kept in listOf(
            """{"type":"request","command":"setBreakpoints","arguments":{"source":{"path":"file:///C:/p/A.java"}}}""",
            """{"type":"request","command":"setBreakpoints","arguments":{"source":{"path":"C:\\p\\A.java"}}}""",
            """{"type":"request","command":"setBreakpoints","arguments":{"source":{"sourceReference":4}}}""",
            """{"type":"request","command":"threads"}""",
        )) assertEquals(kept, DebugLaunch.withNativeSourcePath(kept, '\\'))
        val unix = """{"type":"request","command":"setBreakpoints","arguments":{"source":{"path":"/p/A.java"}}}"""
        assertEquals(unix, DebugLaunch.withNativeSourcePath(unix, '/'))
    }

    @Test
    fun `reads a compound whose members all exist`() {
        val workspace = workspace("""
            { "configurations": [
                { "name": "Server", "type": "node", "request": "launch" },
                { "name": "Client", "type": "node", "request": "launch" }
              ],
              "compounds": [
                { "name": "Both", "configurations": ["Server", "Client"], "stopAll": true, "preLaunchTask": "build" },
                { "name": "Broken", "configurations": ["Server", "Absent"] }
              ] }
        """)
        val both = DebugLaunch.compound(workspace, "Both")
        assertEquals(listOf("Server", "Client"), both.members.map { it.name })
        assertTrue(both.stopAll)
        assertEquals("build", both.preLaunchTask)
        assertTrue(assertFailsWith<IllegalArgumentException> { DebugLaunch.compound(workspace, "Broken") }
            .message!!.contains("'Absent'"))
    }

    @Test
    fun `asks each input once, and cancels the launch when one is dismissed`() {
        val definitions = listOf(
            InputDefinition.PromptString("name", default = "world"),
            InputDefinition.PickString("mode", options = listOf(PickOption("Fast", "fast"))),
            InputDefinition.Command("pid", "pickProcess"),
        )
        val asked = mutableListOf<String>()
        val prompts = object : DebugLaunch.InputPrompts {
            override fun text(input: InputDefinition.PromptString) = input.default.also { asked += input.id }
            override fun pick(input: InputDefinition.PickString) = input.options.first().value.also { asked += input.id }
            override fun command(input: InputDefinition.Command): String? = null.also { asked += input.id }
        }
        val workspace = Path.of("/w")
        val config = json($$"""{ "a": "${input:name} ${input:mode}", "b": "${input:name}" }""")
        val resolved = DebugLaunch.substitute(config, workspace, buildJsonObject { }, input = DebugLaunch.inputs(definitions, prompts))
        assertEquals("world fast", resolved["a"]!!.jsonPrimitive())
        assertEquals(listOf("name", "mode"), asked)

        assertFailsWith<DebugLaunch.InputCanceledException> {
            DebugLaunch.substitute(json($$"""{ "p": "${input:pid}" }"""), workspace, buildJsonObject { },
                input = DebugLaunch.inputs(definitions, prompts))
        }
        // An input launch.json does not define is reported as any unresolved variable is.
        assertTrue(assertFailsWith<IllegalArgumentException> {
            DebugLaunch.substitute(json($$"""{ "p": "${input:absent}" }"""), workspace, buildJsonObject { },
                input = DebugLaunch.inputs(definitions, prompts))
        }.message!!.contains("input:absent"))
    }

    @Test
    fun `picks the extension that provides the debugger, with its dependencies`() {
        val installed = listOf(
            extension("acme", "debug", debuggers = """[{"type":"node","label":"Node"}]""", dependsOn = listOf("acme.shared")),
            extension("acme", "shared"),
            extension("other", "unrelated"),
        )
        val selected = DebugLaunch.selectExtensions("node", installed)
        assertEquals(listOf("acme.debug", "acme.shared"), selected.map { it.id })
    }

    @Test
    fun `explains which extension is missing`() {
        val none = assertFailsWith<IllegalArgumentException> { DebugLaunch.selectExtensions("node", emptyList()) }
        assertTrue(none.message!!.contains("No installed extension provides debugger 'node'"))

        val two = listOf(
            extension("a", "one", debuggers = """[{"type":"node","label":"Node"}]"""),
            extension("b", "two", debuggers = """[{"type":"node","label":"Node"}]"""),
        )
        assertTrue(assertFailsWith<IllegalArgumentException> { DebugLaunch.selectExtensions("node", two) }
            .message!!.contains("Multiple installed extensions"))
    }

    @Test
    fun `reports a dependency that is not installed`() {
        val installed = listOf(extension("acme", "debug",
            debuggers = """[{"type":"node","label":"Node"}]""", dependsOn = listOf("acme.absent")))
        assertTrue(assertFailsWith<IllegalStateException> { DebugLaunch.selectExtensions("node", installed) }
            .message!!.contains("Missing dependency 'acme.absent'"))
    }

    @Test
    fun `hands the adapter its own value shapes`() {
        // Adapters read these directly, so a number must not arrive as a string and an explicit
        // null must survive as one -- it can mean something different from an absent key.
        val arguments = DebugLaunch.arguments(json("""
            { "port": 9229, "stopOnEntry": true, "ratio": 1.5, "name": "x",
              "args": ["a", 2], "env": { "NESTED": null } }
        """))
        assertEquals(9229L, arguments["port"])
        assertEquals(true, arguments["stopOnEntry"])
        assertEquals(1.5, arguments["ratio"])
        assertEquals("x", arguments["name"])
        assertEquals(listOf<Any?>("a", 2L), arguments["args"])
        assertEquals(mapOf("NESTED" to null), arguments["env"])
    }

    private fun workspace(launchJson: String): Path {
        val root = Files.createTempDirectory("codebridge-debug")
        root.resolve(".vscode").createDirectories().resolve("launch.json").writeText(launchJson.trimIndent())
        return root
    }

    private fun json(text: String) = Json.parseToJsonElement(text.trimIndent()).jsonObject

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive() =
        (this as kotlinx.serialization.json.JsonPrimitive).content

    private fun extension(
        publisher: String, name: String,
        debuggers: String = "[]", dependsOn: List<String> = emptyList(),
    ): InstalledExtension {
        val manifest = """
            { "publisher": "$publisher", "name": "$name", "version": "1.0.0",
              "extensionDependencies": [${dependsOn.joinToString { "\"$it\"" }}],
              "contributes": { "debuggers": $debuggers } }
        """.trimIndent()
        val parsed = requireNotNull(VsixManifestParser.parse(manifest).manifest) { "fixture manifest did not parse" }
        return InstalledExtension(parsed, Path.of("."), null, "2026-01-01T00:00:00Z")
    }

    @Test
    fun `the adapter is launched with the configuration as the extension resolved it`() {
        // The client sends what it was handed before any provider ran; the adapter must see the result.
        val sent = $$"""{"seq":2,"type":"request","command":"launch","arguments":{"request":"launch","program":"${file}","noDebug":true}}"""
        val resolved = Json.parseToJsonElement("""{"type":"lldb","request":"launch","program":"/w/main"}""").jsonObject
        val rewritten = Json.parseToJsonElement(DebugLaunch.launchRequest(sent, resolved, "s1")).jsonObject
        assertEquals("2", rewritten["seq"].toString())
        val arguments = rewritten["arguments"]!!.jsonObject
        assertEquals("\"/w/main\"", arguments["program"].toString())
        assertEquals("true", arguments["noDebug"].toString())
        assertEquals("\"s1\"", arguments["__sessionId"].toString())
    }

    @Test
    fun `other messages pass to the adapter untouched`() {
        val resolved = buildJsonObject { }
        val request = """{"seq":3,"type":"request","command":"threads"}"""
        assertEquals(request, DebugLaunch.launchRequest(request, resolved, "s1"))
        val response = """{"seq":4,"type":"response","command":"launch","success":true}"""
        assertEquals(response, DebugLaunch.launchRequest(response, resolved, "s1"))
        assertEquals("not json", DebugLaunch.launchRequest("not json", resolved, "s1"))
    }
}
