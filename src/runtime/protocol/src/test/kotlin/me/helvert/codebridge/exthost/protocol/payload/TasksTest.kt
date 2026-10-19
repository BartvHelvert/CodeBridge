package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The tasks an extension hands `vscode.tasks.executeTask`, as `TaskDTO.from` writes them. */
class TasksTest {

    @Test
    fun `reads a process task`() {
        // The shape rust-analyzer's Run lens sends: a cargo task with a process execution.
        val task = Tasks.task(
            Tasks.EXECUTE, """[{"definition":{"type":"cargo","command":"run"},"name":"run main",
            "execution":{"process":"cargo","args":["run","--bin","main"],"options":{"cwd":"/w","env":{"RUST_LOG":"info","GONE":null}}},
            "isBackground":false,"source":{"extensionId":"rust-lang.rust-analyzer","label":"rust","scope":1}}]"""
        )!!
        assertEquals("run main", task.name)
        assertEquals("cargo", task.type)
        val process = assertIs<Tasks.Process>(task.execution)
        assertEquals("cargo", process.process)
        assertEquals(listOf("run", "--bin", "main"), process.args)
        assertEquals("/w", process.options.cwd)
        assertEquals(mapOf("RUST_LOG" to "info", "GONE" to null), process.options.env)
    }

    @Test
    fun `reads a shell task's quoting, as a number or a name`() {
        val shell = assertIs<Tasks.Shell>(
            Tasks.execution(
                json(
                    """{"command":{"value":"echo","quoting":1},
            "args":["plain",{"value":"two words","quoting":"strong"}],"options":{"executable":"/bin/bash","shellArgs":["-lc"]}}"""
                )
            )
        )
        assertEquals(Tasks.ShellArgument("echo", Tasks.Quoting.ESCAPE), shell.command)
        assertEquals(
            listOf(Tasks.ShellArgument("plain", null), Tasks.ShellArgument("two words", Tasks.Quoting.STRONG)),
            shell.args
        )
        assertEquals("/bin/bash", shell.executable)
        assertEquals(listOf("-lc"), shell.shellArgs)
        assertNull(shell.commandLine)
    }

    @Test
    fun `reads a shell command line and a custom execution`() {
        assertEquals(
            "npm test",
            assertIs<Tasks.Shell>(Tasks.execution(json("""{"commandLine":"npm test"}"""))).commandLine
        )
        assertIs<Tasks.Custom>(Tasks.execution(json("""{"customExecution":"customExecution"}""")))
        assertNull(Tasks.execution(json("{}")))
    }

    @Test
    fun `a task handle is not a task`() {
        // `{id, workspaceFolder}`: a task listed earlier by fetchTasks, named rather than carried.
        assertNull(
            Tasks.task(
                Tasks.GET_EXECUTION,
                """[{"id":"shell|build","workspaceFolder":{"scheme":"file","path":"/w"}}]"""
            )
        )
    }

    @Test
    fun `writes the events the host waits on`() {
        val execution = Tasks.executionDto("t1", json("""{"name":"n","definition":{"type":"cargo"}}"""))
        assertEquals(
            """[{"id":"t1","task":{"name":"n","definition":{"type":"cargo"}}},-1,{"type":"cargo"}]""",
            Tasks.started(execution)
        )
        assertEquals("""[{"id":"t1","processId":42}]""", Tasks.processStarted("t1", 42))
        assertEquals("""[{"id":"t1","exitCode":0}]""", Tasks.processEnded("t1", 0))
        assertEquals("""[{"id":"t1","exitCode":null}]""", Tasks.processEnded("t1", null))
        assertEquals("""[{"id":"t1","task":{"name":"n","definition":{"type":"cargo"}}}]""", Tasks.ended(execution))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
