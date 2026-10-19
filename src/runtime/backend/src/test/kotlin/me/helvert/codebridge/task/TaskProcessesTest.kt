package me.helvert.codebridge.task

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a task is started as. Requires no IDE: a command in, a command line out. */
class TaskProcessesTest {

    private val workspace = Path.of("work").toAbsolutePath()

    @Test
    fun `a process runs its program with its arguments, in the workspace by default`() {
        val line = TaskProcesses.commandLine(workspace, TaskCommand.Process("cargo", listOf("run", "--bin", "main")))
        assertEquals(listOf("cargo", "run", "--bin", "main"), listOf(line.exePath) + line.parametersList.list)
        assertEquals(workspace, line.workingDirectory)
    }

    @Test
    fun `variables are substituted and the directory is resolved against the workspace`() {
        val command = TaskCommand.Process(
            "tool", listOf($$"${workspaceFolderBasename}"), $$"${workspaceFolder}/sub",
            mapOf("TARGET" to $$"${workspaceFolder}/out", "PATH_GONE" to null)
        )
        val line = TaskProcesses.commandLine(workspace, command)
        assertEquals(listOf("work"), line.parametersList.list)
        assertEquals(workspace.resolve("sub"), line.workingDirectory)
        assertEquals("$workspace/out", line.environment["TARGET"])
    }

    @Test
    fun `a shell command line goes to the shell as it is`() {
        val shell =
            TaskProcesses.shell(TaskCommand.Shell(commandLine = "npm test && echo done", executable = "/bin/bash"))
        assertEquals(listOf("/bin/bash", "-c", "npm test && echo done"), shell)
    }

    @Test
    fun `shell arguments are quoted as each asked`() {
        val args = listOf(
            TaskCommand.Argument("plain"),
            TaskCommand.Argument("two words"),
            TaskCommand.Argument("it's", TaskCommand.Quoting.STRONG),
            TaskCommand.Argument("a b", TaskCommand.Quoting.ESCAPE),
        )
        val shell = TaskProcesses.shell(
            TaskCommand.Shell(
                command = TaskCommand.Argument("echo"),
                args = args,
                executable = "/bin/sh"
            )
        )
        assertEquals(listOf("/bin/sh", "-c", """echo plain "two words" 'it'\''s' a\ b"""), shell)
    }

    @Test
    fun `a shell's own arguments replace the default ones`() {
        val cmd =
            TaskProcesses.shell(TaskCommand.Shell(commandLine = "dir", executable = "C:/Windows/System32/cmd.exe"))
        assertEquals(listOf("C:/Windows/System32/cmd.exe", "/d", "/c", "dir"), cmd)
        val custom = TaskProcesses.shell(
            TaskCommand.Shell(
                commandLine = "ls",
                executable = "/bin/zsh",
                shellArgs = listOf("-lic")
            )
        )
        assertEquals(listOf("/bin/zsh", "-lic", "ls"), custom)
    }
}
