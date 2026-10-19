package me.helvert.codebridge.task

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import kotlinx.coroutines.*
import me.helvert.codebridge.task.TaskProcesses.STOP_GRACE_MILLIS
import me.helvert.codebridge.workspace.SimpleSubstitutionContext
import me.helvert.codebridge.workspace.VariableSubstitutor
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/** A [TaskCommand] as a process: the command line, and the handler a run's console follows. */
object TaskProcesses {

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** The process for [task]: its one command, or each of its steps in turn when it has dependencies. */
    fun start(workspace: Path, task: ResolvedTask): ProcessHandler = start(workspace, task, null)

    /** [problems], when given, reads each step's output with that step's problem matchers. */
    fun start(
        workspace: Path,
        task: ResolvedTask,
        launch: CompletableDeferred<Int>?,
        problems: TaskProblems? = null
    ): ProcessHandler {
        val steps = task.steps()
        validate(task, launch != null)
        fun process(step: ResolvedTask): ProcessHandler {
            val commandLine = commandLine(workspace, step.command, step.folder)
            return StepProcessHandler(commandLine).also { handler ->
                problems?.attach(handler, workspace, step, commandLine.workingDirectory ?: workspace)
            }
        }

        val handler = if (steps.size == 1 && !task.isBackground) process(steps.single())
        else TaskProcessSequence(steps, ::process, sequences)
        launch?.let { waitUntilReady(handler, it) }
        return handler
    }

    internal fun validate(task: ResolvedTask, preLaunch: Boolean) {
        val steps = task.steps()
        steps.forEachIndexed { index, step ->
            require(!step.isBackground || step.background.isNotEmpty() || (!preLaunch && index == steps.lastIndex)) {
                "Background task '${step.name}' needs a problemMatcher with beginsPattern and endsPattern before it can be used as a preLaunchTask or dependency"
            }
        }
    }

    /** Attached before startNotify, so even the first output line can signal readiness. */
    internal fun waitUntilReady(handler: ProcessHandler, result: CompletableDeferred<Int>) {
        val listener = object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) { result.complete(event.exitCode) }
        }
        if (handler is TaskProcessSequence) {
            sequences.launch {
                try {
                    result.complete(handler.ready.await())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } else {
            handler.addProcessListener(listener)
            if (handler.isProcessTerminated) result.complete(handler.exitCode ?: -1)
        }
        // Whoever waits giving up -- a launch cancelled -- stops the task.
        result.invokeOnCompletion { cause ->
            handler.removeProcessListener(listener)
            if (cause is CancellationException) handler.destroyProcess()
        }
    }

    /** Where task groups run their steps; each ends itself, with its last step or a Stop. */
    private val sequences = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long a step asked to stop has before it is killed; see [StepProcessHandler]. */
    private const val STOP_GRACE_MILLIS = 5_000L

    /**
     * A step's process, which a Stop ends however it takes it. The first Stop asks, as IntelliJ's
     * does -- on Windows by Ctrl+C -- and a process still running [STOP_GRACE_MILLIS] later is
     * killed, as VS Code ends a task it is told to: on the Windows CI runner a task's Node process
     * ignored the Ctrl+C, and the extension never heard its task end.
     */
    private class StepProcessHandler(commandLine: GeneralCommandLine) : KillableColoredProcessHandler(commandLine) {
        override fun destroyProcessGracefully(): Boolean {
            val asked = super.destroyProcessGracefully()
            if (asked) sequences.launch {
                delay(STOP_GRACE_MILLIS.milliseconds)
                if (!isProcessTerminated) killProcess()
            }
            return asked
        }
    }

    /**
     * The program, arguments, directory and environment [command] asks for, its variables
     * substituted. [folder] is the workspace folder it runs in; by default the project's directory,
     * as a single-folder workspace is. In a multi-root workspace each folder is also
     * `${workspaceFolder:name}`.
     */
    fun commandLine(workspace: Path, command: TaskCommand, folder: Path? = workspace): GeneralCommandLine {
        val layout = CodeBridgeWorkspace.of(workspace)
        val substitutor = VariableSubstitutor(SimpleSubstitutionContext(
            workspaceFolder = folder, namedWorkspaceFolders = layout.folders.associate { it.name to it.path },
        ))
        val arguments = when (command) {
            is TaskCommand.Process -> listOf(command.program) + command.args
            is TaskCommand.Shell -> shell(command)
        }.map(substitutor::substitute)
        val environment = HashMap(System.getenv())
        command.env.forEach { (key, value) -> if (value == null) environment.remove(key) else environment[key] = substitutor.substitute(value) }
        val base = folder ?: workspace
        val cwd = command.cwd?.let { base.resolve(substitutor.substitute(it)).normalize() } ?: base
        return GeneralCommandLine(arguments)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(environment)
            .withWorkingDirectory(cwd)
    }

    /**
     * A shell command as the program to start: the command's shell or the platform's, given one
     * command line. A [TaskCommand.Shell.commandLine] goes to the shell as it is; a command and
     * arguments are quoted first, roughly as the workbench quotes them.
     */
    fun shell(command: TaskCommand.Shell): List<String> {
        val line = command.commandLine
            ?: (listOfNotNull(command.command) + command.args).joinToString(" ") { quote(it, cmd = command.executable?.let(::isCmd) ?: windows) }
        val executable = command.executable ?: return if (windows) listOf("cmd.exe", "/d", "/c", line) else listOf("/bin/sh", "-c", line)
        val name = Path.of(executable).fileName.toString().lowercase()
        val arguments = command.shellArgs ?: when {
            isCmd(executable) -> listOf("/d", "/c")
            name.startsWith("powershell") || name.startsWith("pwsh") -> listOf("-Command")
            else -> listOf("-c")
        }
        return buildList { add(executable); addAll(arguments); add(line) }
    }

    private fun isCmd(executable: String) = Path.of(executable).fileName.toString().lowercase().let { it == "cmd" || it == "cmd.exe" }

    private fun quote(argument: TaskCommand.Argument, cmd: Boolean): String {
        val value = argument.value
        return when (argument.quoting) {
            TaskCommand.Quoting.ESCAPE -> if (cmd) value.replace(Regex("([ ^&|<>()\"])"), "^$1") else value.replace(Regex($$"([ \\\\'\"$`])"), "\\\\$1")
            TaskCommand.Quoting.STRONG -> if (cmd) "\"$value\"" else "'" + value.replace("'", "'\\''") + "'"
            TaskCommand.Quoting.WEAK -> "\"$value\""
            // Unquoted unless it would otherwise split, which is what the workbench does by default.
            null -> if (value.isEmpty() || value.any { it.isWhitespace() }) "\"$value\"" else value
        }
    }

}
