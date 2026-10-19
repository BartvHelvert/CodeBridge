package me.helvert.codebridge.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * `runInTerminal` of kind `external`: the program in a terminal window of its own, outside the IDE,
 * as VS Code opens it. Windows gets a console window (`start`), macOS Terminal, and Linux the
 * desktop's terminal: GNOME's or KDE's, else `x-terminal-emulator`, else xterm.
 *
 * The command goes into a script the window runs, rather than through the quoting of `start` and
 * of each terminal's `-e`. Once the program has ended, the window waits for Enter, so what it
 * printed can still be read. Neither closing the window nor the session's end stops the other.
 */
object ExternalTerminal {
    private val LOG = Logger.getInstance(ExternalTerminal::class.java)
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val mac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    /**
     * Opens the window for [args], as one line for the shell if [shellLine]. [environment] is the
     * program's whole environment; [changes] are what the adapter asked for, null to remove.
     * Returns the id of the process that holds the window open, where there is one: on Windows,
     * as VS Code reports it.
     */
    fun start(
        args: List<String>, shellLine: Boolean, cwd: String?, environment: Map<String, String>,
        changes: Map<String, String?>, title: String,
    ): Long? {
        val launcher = if (windows) windowsLauncher(args, shellLine, title) else unixLauncher(args, shellLine, cwd, changes)
        val commandLine = GeneralCommandLine(launcher.command)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(environment)
        cwd?.takeIf { it.isNotBlank() }?.let { commandLine.withWorkingDirectory(Path.of(it)) }
        val process = try { commandLine.createProcess() } catch (error: Exception) {
            Files.deleteIfExists(launcher.script)
            throw error
        }
        LOG.info("runInTerminal: opened an external terminal with ${launcher.command.first()} for ${args.firstOrNull()}")
        // A Windows window is waited for by its launcher; a Unix script removes itself.
        if (windows) process.onExit().thenRun { runCatching { Files.deleteIfExists(launcher.script) } }
        return if (windows) process.pid() else null
    }

    private class Launcher(val command: List<String>, val script: Path)

    /**
     * `cmd` runs the script, which opens the window with `start` and runs itself again in it. The
     * window inherits `cmd`'s directory and environment; the launching `cmd` lasts as long as it.
     */
    private fun windowsLauncher(args: List<String>, shellLine: Boolean, title: String): Launcher {
        val script = Files.createTempFile("codebridge-debug-", ".cmd")
        Files.writeString(script, windowsScript(args, shellLine, title))
        return Launcher(listOf("cmd.exe", "/d", "/c", script.toString()), script)
    }

    private fun unixLauncher(args: List<String>, shellLine: Boolean, cwd: String?, changes: Map<String, String?>): Launcher {
        val script = Files.createTempFile("codebridge-debug-", if (mac) ".command" else ".sh")
        Files.writeString(script, unixScript(args, shellLine, cwd, changes))
        script.toFile().setExecutable(true, true)
        val path = script.toString()
        if (mac) return Launcher(listOf("open", "-a", "Terminal", path), script)
        val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
        val candidates = buildList {
            if (desktop.contains("GNOME", ignoreCase = true)) add(listOf("gnome-terminal", "--", "/bin/sh", path))
            if (desktop.contains("KDE", ignoreCase = true)) add(listOf("konsole", "-e", "/bin/sh", path))
            add(listOf("x-terminal-emulator", "-e", "/bin/sh", path))
            add(listOf("xterm", "-e", "/bin/sh", path))
        }
        val command = candidates.firstOrNull { PathEnvironmentVariableUtil.isOnPath(it.first()) }
        if (command == null) {
            Files.deleteIfExists(script)
            throw IllegalStateException("No terminal emulator found: none of ${candidates.joinToString { it.first() }} is on the PATH")
        }
        return Launcher(command, script)
    }

    /** A batch file that opens a window titled [title] and runs the program in it. */
    internal fun windowsScript(args: List<String>, shellLine: Boolean, title: String): String {
        val command = if (shellLine) args.joinToString(" ") else args.joinToString(" ") { windowsArgument(it).replace("%", "%%") }
        val windowTitle = title.filter { it != '"' }.replace("%", "%%")
        return listOf(
            "@echo off",
            "if \"%~1\"==\"run\" goto run",
            "start \"$windowTitle\" /wait cmd.exe /d /c \"\"%~f0\" run\"",
            "exit /b",
            ":run",
            command,
            "echo.",
            "pause",
            "",
        ).joinToString("\r\n")
    }

    /** A shell script that removes itself, then runs the program in the window it was opened in. */
    internal fun unixScript(args: List<String>, shellLine: Boolean, cwd: String?, changes: Map<String, String?>): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("rm -f \"$0\"")
        cwd?.takeIf { it.isNotBlank() }?.let { appendLine("cd ${shellQuote(it)} || exit 1") }
        // The terminal need not have the IDE's environment -- GNOME's comes from its own server -- so
        // what the adapter asked for is set here.
        changes.filterKeys { it.matches(IDENTIFIER) }.forEach { (key, value) ->
            appendLine(if (value == null) "unset $key" else "export $key=${shellQuote(value)}")
        }
        appendLine(if (shellLine) args.joinToString(" ") else args.joinToString(" ", transform = ::shellQuote))
        appendLine("echo")
        appendLine("printf '%s' 'Press Enter to close this window...'")
        appendLine("read _")
    }

    /** [value] as one argument for a Windows program, quoted as its C runtime reads it back. */
    internal fun windowsArgument(value: String): String = buildString {
        append('"')
        var backslashes = 0
        for (char in value) {
            when (char) {
                '\\' -> backslashes++
                '"' -> {
                    append("\\".repeat(backslashes * 2 + 1)).append('"')
                    backslashes = 0
                }
                else -> {
                    append("\\".repeat(backslashes)).append(char)
                    backslashes = 0
                }
            }
        }
        append("\\".repeat(backslashes * 2)).append('"')
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
