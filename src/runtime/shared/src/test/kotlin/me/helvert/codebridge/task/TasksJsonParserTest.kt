package me.helvert.codebridge.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TasksJsonParserTest {
    @Test
    fun `parses shell process options and dependencies with comments`() {
        val result = TasksJsonParser.parse("""
            { "version": "2.0.0", "tasks": [
              { "label": "compile", "type": "process", "command": "cargo", "args": ["build"], "options": { "cwd": "src", "env": { "RUST_LOG": "debug" } } },
              { "label": "check", "command": "echo check", "dependsOn": ["compile"] },
            ] }
        """.trimIndent())
        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertEquals("2.0.0", result.file.version)
        assertEquals("src", result.file.tasks.first().options.cwd)
        assertEquals(listOf("compile"), result.file.tasks.last().dependsOn)
    }
}
