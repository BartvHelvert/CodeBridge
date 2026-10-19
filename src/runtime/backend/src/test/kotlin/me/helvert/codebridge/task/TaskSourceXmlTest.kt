package me.helvert.codebridge.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jdom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A task configuration's source, saved and read back: in our own format, never VS Code's DTO. */
class TaskSourceXmlTest {

    @Test
    fun `a tasks json entry is saved by its label`() {
        val source = TaskSource.TasksJson("build")
        assertEquals(source, TaskSourceXml.read(TaskSourceXml.write(source)))
    }

    @Test
    fun `an extension's process task keeps its command and environment`() {
        val source = TaskSource.Extension("run main", "rust-lang.rust-analyzer",
            Json.parseToJsonElement("""{"type":"cargo","command":"run","args":["--bin","main"]}""").jsonObject,
            TaskCommand.Process("cargo", listOf("run", "--bin", "main"), "/w", mapOf("RUST_LOG" to "info", "GONE" to null)))
        assertEquals(source, TaskSourceXml.read(TaskSourceXml.write(source)))
    }

    @Test
    fun `an extension's shell task keeps its quoting and shell`() {
        val source = TaskSource.Extension("test", null, Json.parseToJsonElement("""{"type":"npm"}""").jsonObject,
            TaskCommand.Shell(command = TaskCommand.Argument("npm"), args = listOf(TaskCommand.Argument("run test", TaskCommand.Quoting.STRONG)),
                executable = "/bin/bash", shellArgs = listOf("-lc")))
        assertEquals(source, TaskSourceXml.read(TaskSourceXml.write(source)))
    }

    @Test
    fun `an extension's task keeps the problem matchers it named`() {
        val source = TaskSource.Extension(
            "check", "rust-lang.rust-analyzer", Json.parseToJsonElement("""{"type":"cargo"}""").jsonObject,
            TaskCommand.Process("cargo", listOf("check")), listOf($$"$rustc", $$"$rustc-watch")
        )
        assertEquals(source, TaskSourceXml.read(TaskSourceXml.write(source)))
    }

    @Test
    fun `what cannot be read is nothing, not a guess`() {
        assertNull(TaskSourceXml.read(Element(TaskSourceXml.ELEMENT).setAttribute("from", "somewhere")))
        assertNull(TaskSourceXml.read(Element(TaskSourceXml.ELEMENT).setAttribute("from", "extension").setAttribute("name", "x")))
    }
}
