package me.helvert.codebridge.workbench

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.*
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.terminal.TerminalExecutionConsoleBuilder
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.io.BaseOutputReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Terminals
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.rpc.TerminalEventDto
import me.helvert.codebridge.rpc.TerminalRequestDto
import java.io.Closeable
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * `MainThreadTerminalService`: an extension's terminals, where IntelliJ shows the same kind of thing.
 *
 * A shell the user is meant to see -- the usual `window.createTerminal` -- is a tab of the Terminal
 * tool window, beside the user's own shells. Those tabs are made by the frontend, which runs the
 * shell itself, so it is asked to through [CodeBridgeToolWindowTerminals] ([ToolWindowTerminal]).
 *
 * Everything else is a Run tool window tab whose console is a terminal emulator
 * (`TerminalExecutionConsole`) ([RunTabTerminal]). That console takes any process handler, which is
 * what lets it serve an extension's own `Pseudoterminal` -- usually a task or a run, which belongs
 * there anyway: the handler's output is what the extension writes, and its input goes back to the
 * extension. A hidden shell, one whose environment the Terminal tool window cannot express, and any
 * shell in an IDE without the Terminal plugin run there too, on a real pseudo-terminal.
 *
 * As `window.createTerminal` does not reveal a terminal until `show()`, a new tab is added without
 * bringing its tool window forward. `hideFromUser` leaves it out until shown.
 */
class HostedTerminals(
    private val project: Project,
    private val workspace: Path,
    private val callHost: suspend (service: String, method: String, args: String) -> String,
) : Closeable {
    private val nextId = AtomicInteger()

    /** By the workbench's id and, for those the host created, by the host's own id as well. */
    private val terminals = ConcurrentHashMap<String, Terminal>()
    private val toolWindowEvents: (TerminalEventDto) -> Unit = { event ->
        (terminals[event.id.toString()] as? ToolWindowTerminal)?.ended(event.byUser)
    }

    init {
        CodeBridgeToolWindowTerminals.getInstance(project).events = toolWindowEvents
    }

    suspend fun handle(call: MainThreadCall): MainThreadResult {
        val args = call.argsJson
        when (call.method) {
            Terminals.CREATE -> create(Terminals.create(args))
            Terminals.SEND_TEXT -> {
                val sent = Terminals.sendText(args)
                find(sent.terminal)?.type(Terminals.typed(sent.text, sent.execute))
            }

            Terminals.SHOW -> find(Terminals.ref(call.method, args))?.show(Terminals.preserveFocus(args))
            Terminals.DISPOSE -> find(Terminals.ref(call.method, args))?.dispose()
            Terminals.PROCESS_DATA -> pty(Terminals.ref(call.method, args))?.write(Terminals.processData(args))
            Terminals.PROCESS_EXIT -> pty(Terminals.ref(call.method, args))?.exited(Terminals.exitCode(args))
            // `$hide`, `$sendProcessReady`, titles, link and profile providers, environment
            // collections: nothing here draws any of them.
            else -> {}
        }
        return MainThreadResult()
    }

    /**
     * A terminal for a task's custom execution, opened in the host before the task is said to have
     * started, so the extension can attach its pseudoterminal to it. [Terminal.ended] completes when
     * the pseudoterminal closes.
     */
    suspend fun openForTask(name: String): RunTabTerminal {
        val terminal = RunTabTerminal(nextId.incrementAndGet(), null, name)
        terminals[terminal.id.toString()] = terminal
        terminal.open(null, hidden = false)
        terminal.announce(Terminals.launchConfig(name, null, emptyList(), null))
        return terminal
    }

    override fun close() {
        CodeBridgeToolWindowTerminals.getInstance(project).let { if (it.events === toolWindowEvents) it.events = null }
        terminals.values.toSet().forEach { it.dispose() }
        terminals.clear()
    }

    /**
     * A shell the user can see goes to the Terminal tool window, where IntelliJ's shells are. A
     * pseudoterminal goes to the Run tool window, as does what the Terminal tool window cannot take
     * or is not there for: a hidden terminal, which runs before anyone shows it; an environment that
     * must replace or remove variables; an IDE without the Terminal plugin.
     */
    private suspend fun create(request: Terminals.Create) {
        val name = request.name ?: if (request.pseudoterminal) "Extension terminal" else shellName(request.shellPath)
        val inToolWindow = !request.pseudoterminal && !request.hideFromUser && !request.strictEnv &&
                request.env.values.none { it == null } && CodeBridgeToolWindowTerminals.getInstance(project).connected
        if (inToolWindow && toolWindowTerminal(request, name)) return
        val terminal = RunTabTerminal(nextId.incrementAndGet(), request.extHostId, name)
        // Registered before anything waits: `sendText` straight after `createTerminal` names this id.
        terminals[terminal.id.toString()] = terminal
        terminals[request.extHostId] = terminal
        val shell = if (request.pseudoterminal) null else shellCommandLine(request)
        terminal.open(shell, request.hideFromUser, request.initialText)
        // The host's `createTerminal` fails unless it already holds the workbench's id when this
        // call is answered, so the reply waits for the host to have taken it.
        terminal.announce(
            Terminals.launchConfig(
                name, shell?.exePath, shell?.parametersList?.list.orEmpty(),
                shell?.workingDirectory?.toString()
            )
        )
        terminal.started()
    }

    /** False if the frontend could not be asked after all, to fall back to a Run tool window tab. */
    private suspend fun toolWindowTerminal(request: Terminals.Create, name: String): Boolean {
        val terminal = ToolWindowTerminal(nextId.incrementAndGet(), request.extHostId, name)
        terminals[terminal.id.toString()] = terminal
        terminals[request.extHostId] = terminal
        val args = shellArgs(request)
        // No shell named: the user's own, as the Terminal tool window would start it.
        val command = request.shellPath?.let { listOf(it) + args }.orEmpty()
        val cwd = workingDirectory(request)
        val env = request.env.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        if (!terminal.open(command, cwd, env)) {
            terminals.values.removeIf { it === terminal }
            return false
        }
        terminal.announce(Terminals.launchConfig(name, request.shellPath, args, cwd.toString()))
        return true
    }

    private fun shellArgs(request: Terminals.Create) =
        request.shellArgs ?: request.shellArgsLine?.let(ParametersListUtil::parse) ?: emptyList()

    private fun workingDirectory(request: Terminals.Create) =
        request.cwd?.let { workspace.resolve(it).normalize() }?.takeIf { Files.isDirectory(it) } ?: workspace

    private fun find(ref: Terminals.Ref): Terminal? = terminals[ref.text]

    private fun pty(ref: Terminals.Ref): ExtensionPty? = (find(ref) as? RunTabTerminal)?.handler

    /** The shell [request] names, or the platform's, as a command line on a pseudo-terminal. */
    private fun shellCommandLine(request: Terminals.Create): GeneralCommandLine {
        val shell = request.shellPath ?: defaultShell()
        val args = shellArgs(request)
        val environment = if (request.strictEnv) HashMap() else HashMap(System.getenv())
        environment.putIfAbsent("TERM", "xterm-256color")
        request.env.forEach { (key, value) -> if (value == null) environment.remove(key) else environment[key] = value }
        val cwd = workingDirectory(request)
        return PtyCommandLine(listOf(shell) + args)
            .withConsoleMode(false)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(environment)
            .withWorkingDirectory(cwd)
    }

    private fun shellName(path: String?) =
        path?.let { Path.of(it).fileName.toString().substringBeforeLast('.') } ?: defaultShellName()

    /** Sends [method] now, in order with what follows; the answer is only looked at for a failure. */
    private fun tell(method: String, args: String) {
        Scope.getInstance(project).scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                callHost(Terminals.EXT_HOST, method, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not send $method", failure)
            }
        }
    }

    /** Runs [action] as the client, not waited for; a tab may be cleaned up after the host has gone. */
    private fun onClient(action: () -> Unit) {
        Scope.getInstance(project).scope.launch {
            try {
                editors().asClient(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not update a terminal tab", failure)
            }
        }
    }

    /** One terminal, wherever it is drawn, and what the host is told about it. */
    abstract inner class Terminal(val id: Int, private val extHostId: String?, protected val name: String) {
        /** Completes with the exit code, if there was one, when the terminal has closed. */
        val ended = CompletableDeferred<Int?>()
        private val isClosed = AtomicBoolean()

        /** Why it closed, as the host will be told; whatever ends it first decides. */
        @Volatile
        protected var closing = Terminals.ExitReason.PROCESS

        /** Tells the host the workbench has this terminal, and waits until it has taken it in. */
        suspend fun announce(config: kotlinx.serialization.json.JsonObject) {
            callHost(Terminals.EXT_HOST, Terminals.OPENED, Terminals.opened(id, extHostId, name, config))
        }

        /** What follows the host having taken the terminal in. */
        open fun started() = Unit

        /** `sendText`, as already turned into what is typed. */
        abstract fun type(text: String)

        abstract suspend fun show(preserveFocus: Boolean)

        /** `Terminal.dispose()`: the terminal goes, and its process with it. */
        abstract fun dispose()

        protected fun closed(exitCode: Int?) {
            if (!isClosed.compareAndSet(false, true)) return
            terminals.values.removeIf { it === this }
            tell(Terminals.CLOSED, Terminals.closed(id, exitCode, closing))
            ended.complete(exitCode)
        }
    }

    /**
     * A tab of the Terminal tool window, which the frontend makes and owns: asked for through
     * [CodeBridgeToolWindowTerminals], and heard back from when it ends. The shell runs where the Terminal
     * plugin runs it, so there is no process id to report.
     */
    inner class ToolWindowTerminal(id: Int, extHostId: String, name: String) : Terminal(id, extHostId, name) {
        private val toolWindow = CodeBridgeToolWindowTerminals.getInstance(project)

        fun open(command: List<String>, cwd: Path, env: Map<String, String>): Boolean =
            toolWindow.send(TerminalRequestDto(TerminalRequestDto.OPEN, id, name, command, cwd.toString(), env))

        override fun type(text: String) {
            toolWindow.send(TerminalRequestDto(TerminalRequestDto.TYPE, id, text = text))
        }

        override suspend fun show(preserveFocus: Boolean) {
            toolWindow.send(TerminalRequestDto(TerminalRequestDto.SHOW, id, preserveFocus = preserveFocus))
            callHost(Terminals.EXT_HOST, Terminals.ACTIVE_CHANGED, Terminals.active(id))
        }

        override fun dispose() {
            closing = Terminals.ExitReason.EXTENSION
            toolWindow.send(TerminalRequestDto(TerminalRequestDto.DISPOSE, id))
            closed(null)
        }

        /** The frontend's word that the tab has gone, or its shell has. */
        fun ended(byUser: Boolean) {
            if (byUser && closing == Terminals.ExitReason.PROCESS) closing = Terminals.ExitReason.USER
            closed(null)
        }
    }

    /** A Run tool window tab: its process, a terminal emulator as its console, and the tab once shown. */
    inner class RunTabTerminal(id: Int, extHostId: String?, name: String) : Terminal(id, extHostId, name) {
        @Volatile
        var handler: ExtensionPty? = null
            private set
        @Volatile
        private var process: ProcessHandler? = null
        @Volatile
        private var console: TerminalExecutionConsole? = null
        @Volatile
        private var descriptor: RunContentDescriptor? = null

        /**
         * Starts the shell, or makes the pseudoterminal's stand-in, then its console, and adds the
         * tab unless [hidden]. A shell's output is read only once the console listens.
         */
        suspend fun open(shell: GeneralCommandLine?, hidden: Boolean, initialText: String? = null) {
            val process = try {
                if (shell != null) PtyProcessHandler(shell) else ExtensionPty(id).also { handler = it }
            } catch (error: Exception) {
                closed(null)
                throw error
            }
            this.process = process
            process.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) =
                    closed(if (shell != null || event.exitCode != NO_CODE) event.exitCode else null)
            })
            editors().asClient {
                // A pseudoterminal writes CRLF itself, as the API requires; a shell is on a real pty.
                val console =
                    TerminalExecutionConsoleBuilder(project).convertLfToCrlfForProcessWithoutPty(false).build()
                console.attachToProcess(process)
                this.console = console
                initialText?.let { console.print("$it\r\n", ConsoleViewContentType.SYSTEM_OUTPUT) }
                process.startNotify()
                if (!hidden) addTab(activate = false)
            }
        }

        /**
         * What follows the opening: a shell's process id, or, for a pseudoterminal, leave to start.
         * A pseudoterminal writes nothing before it is started, and a task's is started only once the
         * extension has attached it.
         */
        override fun started() {
            val shell = process as? PtyProcessHandler
            if (shell != null) tell(Terminals.PROCESS_ID, Terminals.processId(id, shell.process.pid()))
            else startPseudoterminal()
        }

        fun startPseudoterminal() = tell(Terminals.START_EXTENSION_TERMINAL, Terminals.startExtensionTerminal(id, COLUMNS, ROWS))

        override fun type(text: String) {
            val handler = this.handler
            if (handler != null) handler.input(text)
            else process?.processInput?.let { input -> runCatching { input.write(text.toByteArray()); input.flush() } }
        }

        override suspend fun show(preserveFocus: Boolean) {
            editors().asClient {
                val descriptor = descriptor
                if (descriptor == null) addTab(activate = true)
                else RunContentManager.getInstance(project)
                    .toFrontRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
                if (!preserveFocus) console?.preferredFocusableComponent?.let {
                    IdeFocusManager.getInstance(project).requestFocus(it, true)
                }
            }
            tell(Terminals.ACTIVE_CHANGED, Terminals.active(id))
        }

        override fun dispose() {
            closing = Terminals.ExitReason.EXTENSION
            process?.let { if (!it.isProcessTerminated) it.destroyProcess() }
            val descriptor = descriptor
            if (descriptor != null) onClient {
                RunContentManager.getInstance(project)
                    .removeRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
            } else console?.let { console -> onClient { Disposer.dispose(console) } }
            closed(null)
        }

        /** Must run on the editor thread. */
        private fun addTab(activate: Boolean) {
            val process = checkNotNull(process)
            val descriptor = terminalRunContent(name, process, checkNotNull(console), activate)
            this.descriptor = descriptor
            // Closing the tab ends the terminal, as closing VS Code's does.
            Disposer.register(descriptor) {
                if (closing == Terminals.ExitReason.PROCESS) closing = Terminals.ExitReason.USER
                if (!process.isProcessTerminated) process.destroyProcess()
                closed(null)
            }
            RunContentManager.getInstance(project)
                .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        }
    }

    /**
     * An extension's `Pseudoterminal` as a process: what it writes is this handler's output, and what
     * is typed into the console is written to [getProcessInput], which hands it to the extension.
     */
    inner class ExtensionPty(private val id: Int) : ProcessHandler() {
        private val input = object : OutputStream() {
            override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) =
                input(String(bytes, offset, length, Charsets.UTF_8))
        }

        fun input(text: String) {
            if (!isProcessTerminated) tell(Terminals.PROCESS_INPUT, Terminals.input(id, text))
        }

        fun write(data: String) = notifyTextAvailable(data, ProcessOutputTypes.STDOUT)

        /** The pseudoterminal closed itself. */
        fun exited(code: Int?) {
            if (!isProcessTerminated) notifyProcessTerminated(code ?: NO_CODE)
        }

        /** Stop: the extension is asked to close it, and the tab does not wait for it to. */
        override fun destroyProcessImpl() {
            tell(Terminals.PROCESS_SHUTDOWN, Terminals.shutdown(id, immediate = true))
            notifyProcessTerminated(NO_CODE)
        }

        override fun detachProcessImpl() = destroyProcessImpl()
        override fun detachIsDefault() = false
        override fun getProcessInput(): OutputStream = input
    }

    /** A shell on a pseudo-terminal, read as a terminal reads it: raw, as it arrives. */
    private class PtyProcessHandler(commandLine: GeneralCommandLine) : KillableProcessHandler(commandLine) {
        override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forTerminalPtyProcess()
    }

    private fun editors() = CodeBridgeTextEditors.getInstance(project)

    /** Where what nobody waits on runs: it outlives the host, as the tabs do, and ends with the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        /** A pseudoterminal's exit with no code, which the host is told as none. */
        private const val NO_CODE: Int = Int.MIN_VALUE

        /** What a pseudoterminal is told it has to start with, before anything has been measured. */
        private const val COLUMNS = 120
        private const val ROWS = 30

        private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        /** VS Code's default profile: PowerShell on Windows, the login shell elsewhere. */
        internal fun defaultShell(): String =
            if (windows) "powershell.exe" else System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"

        private fun defaultShellName() = Path.of(defaultShell()).fileName.toString().substringBeforeLast('.')

        private val LOG = Logger.getInstance(HostedTerminals::class.java)
    }
}
