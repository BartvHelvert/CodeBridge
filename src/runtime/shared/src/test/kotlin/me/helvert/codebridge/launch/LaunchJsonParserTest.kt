package me.helvert.codebridge.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LaunchJsonParserTest {

    @Test
    fun `parses a realistic launch file with comments`() {
        val result = LaunchJsonParser.parse(
            $$"""
            {
              // See https://go.microsoft.com/fwlink/?linkid=830387
              "version": "0.2.0",
              "configurations": [
                {
                  "type": "lldb",
                  "request": "launch",
                  "name": "Debug executable",
                  "program": "${workspaceFolder}/target/debug/app",
                  "args": ["--verbose"],
                  "cwd": "${workspaceFolder}",
                  "env": { "RUST_LOG": "debug" },
                  "preLaunchTask": "cargo build",
                },
              ],
            }
            """.trimIndent()
        )

        assertEquals(emptyList(), result.problems)
        assertEquals("0.2.0", result.file.version)

        val configuration = assertNotNull(result.file.configuration("Debug executable"))
        assertEquals("lldb", configuration.type)
        assertEquals("launch", configuration.request)
        assertEquals(listOf("--verbose"), configuration.stringList("args"))
        assertEquals(mapOf("RUST_LOG" to "debug"), configuration.env())
        assertEquals("cargo build", configuration.preLaunchTask)
    }

    @Test
    fun `keeps adapter-specific keys in raw for pass-through to the launch request`() {
        val result = LaunchJsonParser.parse(
            """
            { "configurations": [ {
                "type": "node", "request": "launch", "name": "App",
                "sourceMaps": true, "skipFiles": ["<node_internals>/**"], "runtimeArgs": ["--inspect"]
            } ] }
            """.trimIndent()
        )

        val configuration = assertNotNull(result.file.configuration("App"))
        assertEquals(true, configuration.boolean("sourceMaps"))
        assertEquals(listOf("<node_internals>/**"), configuration.stringList("skipFiles"))
        assertTrue("runtimeArgs" in configuration.raw)
    }

    @Test
    fun `a broken configuration does not cost the others`() {
        val result = LaunchJsonParser.parse(
            """
            { "configurations": [
                { "type": "node", "request": "launch" },
                { "type": "node", "request": "launch", "name": "Good" }
            ] }
            """.trimIndent()
        )

        assertEquals(listOf("Good"), result.file.configurations.map { it.name })
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single().contains("name"))
    }

    @Test
    fun `invalid json yields a problem rather than an exception`() {
        val result = LaunchJsonParser.parse("{ this is not json ")
        assertTrue(result.file.configurations.isEmpty())
        assertEquals(1, result.problems.size)
    }

    @Test
    fun `parses compounds in both member forms`() {
        val result = LaunchJsonParser.parse(
            """
            {
              "configurations": [],
              "compounds": [ {
                  "name": "Full stack",
                  "configurations": [ "Server", { "folder": "web", "name": "Client" } ],
                  "stopAll": true
              } ]
            }
            """.trimIndent()
        )

        val compound = assertNotNull(result.file.compound("Full stack"))
        assertEquals(
            listOf(CompoundMember("Server"), CompoundMember("Client", "web")),
            compound.members,
        )
        assertTrue(compound.stopAll)
    }

    @Test
    fun `parses the three input kinds`() {
        val result = LaunchJsonParser.parse(
            """
            { "configurations": [], "inputs": [
                { "id": "port", "type": "promptString", "description": "Port", "default": "8080" },
                { "id": "target", "type": "pickString", "options": [ "dev", { "label": "Production", "value": "prod" } ] },
                { "id": "pid", "type": "command", "command": "extension.pickProcess" }
            ] }
            """.trimIndent()
        )

        assertEquals(emptyList(), result.problems)

        val port = assertIs<InputDefinition.PromptString>(result.file.input("port"))
        assertEquals("8080", port.default)

        val target = assertIs<InputDefinition.PickString>(result.file.input("target"))
        assertEquals(
            listOf(PickOption("dev", "dev"), PickOption("Production", "prod")),
            target.options,
        )

        val pid = assertIs<InputDefinition.Command>(result.file.input("pid"))
        assertEquals("extension.pickProcess", pid.command)
    }
}
