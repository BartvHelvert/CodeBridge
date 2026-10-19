package me.helvert.codebridge.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.exthost.protocol.payload.Tasks
import me.helvert.codebridge.workspace.WorkspaceChoices
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What a task source runs: a `tasks.json` entry and its dependencies, or an extension's task. */
class TaskSourcesTest {

    @Test
    fun `a tasks json entry resolves with its dependencies first, each once`() {
        val workspace = workspace("""
            { "version": "2.0.0", "tasks": [
              { "label": "gen", "type": "process", "command": "gen", "args": ["--all"] },
              { "label": "compile", "command": "make", "args": ["all"], "dependsOn": "gen" },
              { "label": "test", "type": "shell", "command": "make", "args": ["test"], "dependsOn": ["gen", "compile"] }
            ] }
        """)
        val task = TaskSources.resolve(workspace, TaskSource.TasksJson("test"))
        assertEquals(listOf("gen", "compile", "test"), task.steps().map { it.name })
        assertEquals(TaskCommand.Process("gen", listOf("--all")), task.steps().first().command)
        assertEquals(TaskCommand.Shell(command = TaskCommand.Argument("make"), args = listOf(TaskCommand.Argument("test"))), task.command)
    }

    @Test
    fun `cycles and missing labels are refused`() {
        val workspace = workspace("""
            { "tasks": [
              { "label": "a", "command": "x", "dependsOn": "b" },
              { "label": "b", "command": "y", "dependsOn": "a" }
            ] }
        """)
        val cycle = assertFailsWith<IllegalArgumentException> { TaskSources.resolve(workspace, TaskSource.TasksJson("a")) }
        assertTrue(cycle.message!!.contains("Circular"))
        assertFailsWith<IllegalArgumentException> { TaskSources.resolve(workspace, TaskSource.TasksJson("missing")) }
    }

    @Test
    fun `an extension's task runs as it was kept, and is described to the host as its own`() {
        val definition = Json.parseToJsonElement("""{"type":"cargo","command":"run"}""").jsonObject
        val command = TaskCommand.Process("cargo", listOf("run"), cwd = "/w")
        val task = TaskSources.resolve(Path.of("."), TaskSource.Extension("run", "rust-lang.rust-analyzer", definition, command))
        assertEquals(command, task.command)
        assertEquals(definition, task.dto["definition"])
        // The host reads the DTO back into a task, so the execution must be one it recognises.
        assertEquals(command, TaskCommand.of(Tasks.execution(task.dto["execution"])))
        assertEquals(JsonPrimitive("rust-lang.rust-analyzer"), task.dto["source"]!!.jsonObject["extensionId"])
    }

    @Test
    fun `an extension's execution becomes our command`() {
        val shell = Tasks.execution(Json.parseToJsonElement("""{"command":{"value":"echo","quoting":2},"args":["a"],"options":{"executable":"/bin/bash"}}"""))
        assertEquals(TaskCommand.Shell(command = TaskCommand.Argument("echo", TaskCommand.Quoting.STRONG), args = listOf(TaskCommand.Argument("a")),
            executable = "/bin/bash"), TaskCommand.of(shell))
    }

    @Test
    fun `a shell command without arguments is a command line, run as written`() {
        val workspace = workspace("""
            { "tasks": [
              { "label": "both", "type": "shell", "command": "cargo build && cargo test" },
              { "label": "one", "type": "shell", "command": "cargo", "args": ["build", "--bin=my app"] }
            ] }
        """)
        assertEquals(TaskCommand.Shell(commandLine = "cargo build && cargo test"), TaskSources.resolve(workspace, TaskSource.TasksJson("both")).command)
        // With arguments, the command and each argument are words of their own, quoted as needed.
        assertEquals(
            TaskCommand.Shell(command = TaskCommand.Argument("cargo"), args = listOf(TaskCommand.Argument("build"), TaskCommand.Argument("--bin=my app"))),
            TaskSources.resolve(workspace, TaskSource.TasksJson("one")).command,
        )
    }

    @Test
    fun `in a multi-root workspace a folder's task runs in that folder, and the workspace file's serve the rest`() {
        val workspace = Files.createTempDirectory("codebridge-tasks")
        val app = workspace.resolve("app").createDirectories()
        app.resolve(".vscode").createDirectories().resolve("tasks.json")
            .writeText("""{ "tasks": [{ "label": "build", "type": "process", "command": "cargo" }] }""")
        workspace.resolve("w.code-workspace").writeText("""
            { "folders": [{ "path": "app" }],
              "tasks": { "tasks": [{ "label": "lint", "type": "process", "command": "lint" }] } }
        """.trimIndent())
        WorkspaceChoices.choose(workspace, WorkspaceChoices.Choice.File("w.code-workspace"))

        val build = TaskSources.resolve(workspace, TaskSource.TasksJson("build", "app"))
        assertEquals(app.toAbsolutePath().normalize(), build.folder)
        // Not the folder's: the workspace's own, which no one folder runs.
        val lint = TaskSources.resolve(workspace, TaskSource.TasksJson("lint", "app"))
        assertEquals(TaskCommand.Process("lint", emptyList()), lint.command)
        assertEquals(null, lint.folder)
        assertFailsWith<IllegalArgumentException> { TaskSources.resolve(workspace, TaskSource.TasksJson("build")) }
    }

    @Test
    fun `the workspace's tasks are listed per file, hidden ones left out`() {
        val workspace = Files.createTempDirectory("codebridge-tasks")
        workspace.resolve("app").createDirectories().resolve(".vscode").createDirectories().resolve("tasks.json")
            .writeText("""{ "tasks": [{ "label": "build", "command": "cargo" }, { "label": "helper", "command": "x", "hide": true }] }""")
        workspace.resolve("lib").createDirectories()
        workspace.resolve("w.code-workspace").writeText("""
            { "folders": [{ "path": "app" }, { "path": "lib" }],
              "tasks": { "tasks": [{ "label": "lint", "type": "npm", "command": "lint" }] } }
        """.trimIndent())
        WorkspaceChoices.choose(workspace, WorkspaceChoices.Choice.File("w.code-workspace"))

        // One that cannot run here is listed all the same, and says why when it is run.
        assertEquals(listOf(TaskSource.TasksJson("lint"), TaskSource.TasksJson("build", "app")), TaskSources.listed(workspace))
    }

    @Test
    fun `a tasks json half-way through an edit lists nothing`() {
        assertEquals(null, TaskSources.listed(workspace("""{ "tasks": [{ "label": """)))
        assertEquals(listOf(TaskSource.TasksJson("a")), TaskSources.listed(workspace("""{ "tasks": [{ "label": "a", "command": "x" }] }""")))
    }

    @Test
    fun `a folder's source for a task only the workspace has is the workspace's`() {
        val workspace = Files.createTempDirectory("codebridge-tasks")
        workspace.resolve("app").createDirectories().resolve(".vscode").createDirectories().resolve("tasks.json")
            .writeText("""{ "tasks": [{ "label": "build", "type": "process", "command": "cargo" }] }""")
        workspace.resolve("w.code-workspace").writeText("""
            { "folders": [{ "path": "app" }],
              "tasks": { "tasks": [{ "label": "lint", "type": "process", "command": "lint" }] } }
        """.trimIndent())
        WorkspaceChoices.choose(workspace, WorkspaceChoices.Choice.File("w.code-workspace"))

        assertEquals(TaskSource.TasksJson("build", "app"), TaskSources.served(workspace, TaskSource.TasksJson("build", "app")))
        assertEquals(TaskSource.TasksJson("lint"), TaskSources.served(workspace, TaskSource.TasksJson("lint", "app")))
    }

    private fun workspace(tasks: String): Path {
        val workspace = Files.createTempDirectory("codebridge-tasks")
        workspace.resolve(".vscode").createDirectories().resolve("tasks.json").writeText(tasks.trimIndent())
        return workspace
    }
}
