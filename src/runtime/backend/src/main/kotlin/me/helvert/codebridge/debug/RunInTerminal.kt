package me.helvert.codebridge.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.platform.eel.isWindows
import com.intellij.platform.eel.provider.localEel
import com.intellij.util.io.BaseOutputReader
import com.jetbrains.dap.protocol.RunInTerminalKind
import com.jetbrains.dap.protocol.RunInTerminalRequestArguments
import com.jetbrains.dap.protocol.RunInTerminalResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import me.helvert.codebridge.debug.RunInTerminal.Companion.GRACE_MILLIS
import me.helvert.codebridge.debug.RunInTerminal.Companion.QUIET_MILLIS
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DAP's `runInTerminal` reverse request: the adapter asks the client to start the program.
 *
 * Many adapters launch this way by default -- debugpy's `integratedTerminal` console, CodeLLDB's
 * and js-debug's terminal modes -- and without an answer the program never starts. What is asked
 * for is a terminal, and the program gets one. An external one is a window outside the IDE
 * ([ExternalTerminal]). An integrated one, or an external one no window could be opened for, is
 * with [show] one the user sees and types into, as in VS Code: its own tab, named for the
 * request's title or [name]. Without [show], it is a pseudo-terminal no one sees, whose
 * text is handed to [output] as the adapter's own output would be, so it lands in the same
 * console, and whose input is not wired.
 *
 * A terminal, not pipes, because an adapter may start a helper there and then attach the program
 * to the helper's terminal rather than its standard streams: CodeLLDB runs `codelldb-launch` and
 * launches the program on that console (Windows) or tty (Unix). With pipes the program ran and
 * printed into nothing. A terminal merges standard error into its one stream, so everything reads
 * as standard output; where no pseudo-terminal can be made, pipes keep the two apart.
 */
class RunInTerminal(
    /** The session's name, for a terminal whose request has no title. */
    private val name: String = "Debug terminal",
    /** Shows a program, not yet started, in a terminal tab; see [DebugTerminalTabs.show]. */
    private val show: (suspend (name: String, process: KillableProcessHandler) -> Unit)? = null,
    private val output: (text: String, stderr: Boolean) -> Unit,
) : Closeable {
    private val processes = CopyOnWriteArrayList<KillableProcessHandler>()

    /** Completes once its process has ended and what it printed has been read, which the platform reports together. */
    private val ended = java.util.concurrent.ConcurrentHashMap<KillableProcessHandler, CompletableDeferred<Unit>>()

    /** Where [finished] watches the terminal; ends with [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun run(request: RunInTerminalRequestArguments): RunInTerminalResponse {
        val args = request.args
        require(args.isNotEmpty()) { "runInTerminal named no program" }
        val command = if (request.argsCanBeInterpretedByShell == true) shell(args.joinToString(" ")) else args
        val environment = HashMap(System.getenv())
        request.env?.forEach { (key, value) ->
            if (value == null) environment.remove(key) else environment[key] = value
        }
        val title = request.title?.takeIf { it.isNotBlank() } ?: name
        if (request.kind == RunInTerminalKind.External) {
            // A window of its own, as asked; failing that, the IDE's own terminal still runs the program.
            try {
                val pid = ExternalTerminal.start(
                    args, request.argsCanBeInterpretedByShell == true, request.cwd, environment,
                    request.env.orEmpty(), title
                )
                return RunInTerminalResponse(processId = pid?.toInt())
            } catch (error: Exception) {
                LOG.warn(
                    "runInTerminal: could not open an external terminal for ${args.first()}; running it in the IDE",
                    error
                )
            }
        }
        if (show != null) environment.putIfAbsent("TERM", "xterm-256color")
        val plain = GeneralCommandLine(command)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(environment)
        request.cwd?.takeIf { it.isNotBlank() }?.let { plain.withWorkingDirectory(Path.of(it)) }
        show?.let { return shown(plain, title, it) }
        // Whatever the IDE's own "run processes with PTY" preference says: a terminal was asked for.
        val handler = try {
            terminal(plain)
        } catch (_: Exception) {
            KillableProcessHandler(plain)
        }
        LOG.info("runInTerminal: started ${command.first()} with ${command.size - 1} argument(s)${if (handler is KillableColoredProcessHandler) " on a pseudo-terminal" else ""}")
        watch(handler, command.first())
        return RunInTerminalResponse(processId = handler.process.pid().toInt())
    }

    /**
     * Hands what [handler] prints to [output] and keeps it until it has ended and been read.
     *
     * Internal so that a test can watch a program whose text arrives after it has exited.
     */
    internal fun watch(handler: KillableProcessHandler, program: String) {
        val started = System.currentTimeMillis()
        val read = java.util.concurrent.atomic.AtomicInteger()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                read.addAndGet(event.text.length)
                lastOutput = System.currentTimeMillis()
                output(event.text, outputType == ProcessOutputTypes.STDERR)
            }

            override fun processTerminated(event: ProcessEvent) {
                LOG.info(
                    "runInTerminal: $program ended with exit code ${event.exitCode} after ${System.currentTimeMillis() - started} ms, " +
                            "${read.get()} character(s) read from ${handler.process.javaClass.simpleName}"
                )
                processes.remove(handler)
                ended.remove(handler)?.complete(Unit)
            }
        })
        processes.add(handler)
        ended[handler] = CompletableDeferred()
        handler.startNotify()
    }

    /**
     * [commandLine] in a terminal the user sees, answered once it is shown. Its text goes there
     * only, so none of it is waited for or ordered against the adapter's output.
     */
    private suspend fun shown(
        commandLine: GeneralCommandLine,
        title: String,
        show: suspend (String, KillableProcessHandler) -> Unit
    ): RunInTerminalResponse {
        val handler = try {
            visibleTerminal(commandLine)
        } catch (_: Exception) {
            KillableProcessHandler(commandLine)
        }
        val program = commandLine.exePath
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                LOG.info("runInTerminal: $program ended with exit code ${event.exitCode} in a terminal tab")
                processes.remove(handler)
            }
        })
        processes.add(handler)
        try {
            withTimeout(SHOW_SECONDS.seconds) { show(title, handler) }
        } catch (error: Exception) {
            processes.remove(handler)
            // Never started notifying, so the handler would only queue a stop; the process goes at once.
            handler.process.destroyForcibly()
            throw IllegalStateException("Could not show a terminal for $program", error)
        }
        LOG.info("runInTerminal: started $program in terminal tab '$title'")
        return RunInTerminalResponse(processId = handler.process.pid().toInt())
    }

    /** Whether a program started here, out of sight, is still running. */
    val inUse: Boolean get() = ended.isNotEmpty()

    /** When the terminal last had something to say. */
    @Volatile
    private var lastOutput = 0L

    /**
     * Completes once the terminal is done: everything started here has ended and been read, or it
     * has said nothing for [QUIET_MILLIS]; at the latest after [GRACE_MILLIS].
     *
     * What the session's end waits for. An adapter says the program has ended as soon as it has,
     * and the client stops reading its output at that -- while what the program printed is still
     * on its way out of the terminal, a few milliseconds behind. Quiet counts as done because what
     * runs in the terminal need not end with the program: CodeLLDB's `codelldb-launch` stays until
     * the session is disconnected, which waiting for would hold every run back the full grace.
     *
     * Once every program has exited, though, neither rule applies, and what they printed is waited
     * for up to [DRAIN_MILLIS]: see [exited].
     */
    fun finished(): Deferred<Unit> {
        val since = System.currentTimeMillis()
        val running = ended.entries.toList()
        return scope.async {
            val allEnded = launch { running.forEach { it.value.join() } }
            while (allEnded.isActive) {
                val now = System.currentTimeMillis()
                val waited = now - since
                val givenUp = if (running.all { exited(it.key) }) waited >= DRAIN_MILLIS
                else now - maxOf(since, lastOutput) >= QUIET_MILLIS || waited >= GRACE_MILLIS
                if (givenUp) {
                    allEnded.cancel()
                    break
                }
                @OptIn(ExperimentalCoroutinesApi::class)
                select {
                    allEnded.onJoin {}
                    onTimeout(CHECK_MILLIS.milliseconds) {}
                }
            }
        }
    }

    /**
     * Stops whatever is still running, when the session it was started for ends.
     *
     * Not at once: a short program has often finished by then with its last lines still in the
     * terminal -- the session ends as soon as the adapter sees the program exit, tens of
     * milliseconds after it started -- and stopping the terminal there lost everything it printed.
     * What finishes within [GRACE_MILLIS] is read to the end, for up to [DRAIN_MILLIS] (see
     * [exited]); what is still running after the grace is stopped.
     */
    override fun close() {
        scope.cancel()
        val start = System.currentTimeMillis()
        for (process in processes) {
            val left = start + GRACE_MILLIS - System.currentTimeMillis()
            if (left > 0) process.waitFor(left)
        }
        for (process in processes) {
            if (!exited(process)) continue
            val left = start + DRAIN_MILLIS - System.currentTimeMillis()
            if (left > 0) process.waitFor(left)
        }
        processes.forEach { if (!it.isProcessTerminated) it.destroyProcess() }
        processes.clear()
    }

    /**
     * Whether [handler]'s program has exited, though what it printed may not have been read yet.
     *
     * The platform reports a program ended only once its output is read to the end, and on Windows
     * that can be well after it exited: the pseudo-terminal delivered a CI runner's `echo` half a
     * second after the 2 s grace, with the program long gone, and the text was lost. What is left of
     * an exited program's output is waited for instead -- within [DRAIN_MILLIS], in case something it
     * started keeps the terminal open.
     */
    private fun exited(handler: KillableProcessHandler): Boolean = !handler.process.isAlive

    /**
     * [commandLine] on a pseudo-terminal wide enough not to wrap a line, its escape sequences turned
     * back into text; a terminal is read blocking, as the platform requires.
     *
     * Not in console mode on Unix: there it does not make the terminal the program's controlling one,
     * so a program that writes to `/dev/tty` -- or prompts there, as a debuggee asking for a password
     * does -- found no terminal at all. Windows' console mode is what gives the program its `CON`.
     */
    private fun terminal(commandLine: GeneralCommandLine): KillableProcessHandler =
        object : KillableColoredProcessHandler(
            PtyCommandLine(commandLine).withInitialColumns(PtyCommandLine.MAX_COLUMNS)
                .withConsoleMode(localEel.platform.isWindows)
        ) {
            override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forTerminalPtyProcess()
        }

    /** [commandLine] on a pseudo-terminal for a terminal emulator: raw, escape sequences and all. */
    private fun visibleTerminal(commandLine: GeneralCommandLine): KillableProcessHandler =
        object : KillableProcessHandler(PtyCommandLine(commandLine).withConsoleMode(false)) {
            override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forTerminalPtyProcess()
        }

    companion object {
        private val LOG = Logger.getInstance(RunInTerminal::class.java)
        private const val GRACE_MILLIS = 2_000L
        /** How long the output of a program that has exited is waited for: generous, but still bounded. */
        private const val DRAIN_MILLIS = 10_000L
        private const val QUIET_MILLIS = 300L
        private const val CHECK_MILLIS = 50L
        private const val SHOW_SECONDS = 30L

        /** The platform's shell, given one command line to interpret. */
        fun shell(commandLine: String): List<String> =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) listOf(
                "cmd.exe",
                "/d",
                "/c",
                commandLine
            )
            else listOf("/bin/sh", "-c", commandLine)
    }
}
