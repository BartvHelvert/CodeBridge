package me.helvert.codebridge.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.dap.*
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.platform.dap.xdebugger.DapXDebuggerPresentationFactory
import com.intellij.ui.content.Content
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.ui.XDebugTabLayouter
import com.jetbrains.dap.protocol.OutputCategory
import com.jetbrains.dap.protocol.OutputEventArguments
import com.jetbrains.dap.protocol.RunInTerminalRequestArguments
import com.jetbrains.dap.protocol.RunInTerminalResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.debug.DebugTerminalTabs
import me.helvert.codebridge.debug.HostedDebugSession
import me.helvert.codebridge.debug.RunInTerminal
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickItemDto
import me.helvert.codebridge.task.TaskSource
import me.helvert.codebridge.task.CodeBridgeTaskRuns
import me.helvert.codebridge.testing.TestSessionConsoles
import me.helvert.codebridge.workbench.HostedMessages
import me.helvert.codebridge.workbench.HostedQuickInputs
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import me.helvert.codebridge.exthost.protocol.payload.Messages as HostMessages

class LaunchJsonAdapterId : DebugAdapterId("codebridge-launch-json", "VS Code launch.json")

class LaunchJsonDapArgumentsProvider : DapLaunchArgumentsProvider {
    override fun isApplicable(executorId: String, profile: RunProfile): Boolean =
        // A missing source too, so running one says to choose a configuration.
        profile is CodeBridgeDebugRunConfiguration && profile.source.let { it == null || it is DebugSource.Single } &&
            (executorId == "Debug" || executorId == "Run" && !profile.attaches())

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments {
        val run = profile as? CodeBridgeDebugRunConfiguration ?: error("Unsupported launch profile")
        run.checkConfiguration()
        val raw = run.rawConfiguration()
        val request = when (raw["request"]?.toString()?.trim('"')?.lowercase(Locale.ROOT)) {
            "attach" -> DapStartRequest.Attach
            else -> DapStartRequest.Launch
        }
        return LaunchRequestArguments(ADAPTER_ID, request, DebugLaunch.arguments(raw))
    }

    companion object {
        val ADAPTER_ID = LaunchJsonAdapterId()
    }
}

class LaunchJsonDebugAdapterSupportProvider : DebugAdapterSupportProvider<LaunchJsonAdapterId> {
    override val adapterId: LaunchJsonAdapterId = LaunchJsonDapArgumentsProvider.ADAPTER_ID
    override fun createDebugAdapterDescriptor(project: Project): DebugAdapterDescriptor<LaunchJsonAdapterId> = Descriptor(project)

    /** One per run: the platform asks the provider again for every session it starts. */
    private class Descriptor(private val project: Project) : DebugAdapterDescriptor<LaunchJsonAdapterId>() {
        override val id = LaunchJsonDapArgumentsProvider.ADAPTER_ID

        /** What the adapter's `runInTerminal` requests start, stopped with the session; see [customization]. */
        @Volatile private var terminal: RunInTerminal? = null

        /** Where a terminal's text goes: the session's output, known once the adapter first asks for one. */
        @Volatile
        private var terminalOutput: DapReverseRequestContext? = null

        /** Answers `runInTerminal`, which the platform then advertises in `initialize`. */
        override val customization: DapCustomization = object : DapCustomization() {
            override val runInTerminalSupport = object : DapRunInTerminalSupport() {
                override suspend fun runInTerminal(
                    context: DapReverseRequestContext,
                    args: RunInTerminalRequestArguments,
                ): RunInTerminalResponse {
                    terminalOutput = context
                    val terminal = terminal ?: throw ExecutionException("The debug session has no terminal")
                    return withContext(Dispatchers.IO) { terminal.run(args) }
                }
            }
        }

        override fun createXDebugProcess(
            session: XDebugSession,
            dapDebugSession: DapDebugSession,
            xDebugProcessScope: CoroutineScope,
            globalScope: CoroutineScope,
            debugAdapterDescriptor: DebugAdapterDescriptor<*>,
            executionEnvironment: ExecutionEnvironment,
            executionResult: ExecutionResult?,
            startRequestType: DapStartRequest,
            startRequestArguments: Map<String, Any?>,
        ): DapXDebugProcess {
            val debugger = (startRequestArguments["type"] as? String)
            return CodeBridgeDebugProcess(session, dapDebugSession, xDebugProcessScope, globalScope, debugAdapterDescriptor,
                executionEnvironment, executionResult, startRequestType, startRequestArguments, debugger)
        }

        override suspend fun launchDebugAdapter(
            environment: ExecutionEnvironment,
            executionResult: ExecutionResult?,
            sessionId: String,
        ): DebugAdapterHandle {
            val tabs = DebugTerminalTabs(project)
            val terminal = RunInTerminal(environment.runProfile.name, tabs::show) { text, stderr ->
                terminalOutput?.publishOutput(
                    OutputEventArguments(if (stderr) OutputCategory.Stderr else OutputCategory.Stdout, text)
                )
            }
            this.terminal = terminal
            val profile = environment.runProfile as? CodeBridgeDebugRunConfiguration
                ?: throw ExecutionException("Unsupported launch profile")
            val workspace = profile.workspace()
                ?: throw ExecutionException("The project has no workspace directory")
            // The workspace folder the entry is in, in a multi-root workspace; see [DebugLaunch].
            val folder = (profile.source as? DebugSource.LaunchJson)?.folder
            // Run is VS Code's Run Without Debugging, which the extension's providers get to see.
            val raw = profile.rawConfiguration().let { raw ->
                if (environment.executor.id == DefaultRunExecutor.EXECUTOR_ID) JsonObject(raw + ("noDebug" to JsonPrimitive(true)))
                else raw
            }
            // Before anything is started for the session, as VS Code runs it: the task's own configuration,
            // in a Run tool window tab of its own, waited for until it exits or reports ready.
            raw["preLaunchTask"]?.let { task ->
                @Suppress("DialogTitleCapitalization") // starts with the key's name
                val label = (task as? JsonPrimitive)?.contentOrNull ?: throw ExecutionException("preLaunchTask must name a task")
                val exit = try {
                    CodeBridgeTaskRuns.runAndWait(project, TaskSource.TasksJson(label, folder))
                }
                catch (canceled: CancellationException) { throw canceled }
                catch (error: Exception) { throw ExecutionException("The preLaunchTask '$label' could not run: ${error.message}", error) }
                if (exit != 0) throw ExecutionException("The preLaunchTask '$label' exited with code $exit")
            }
            val editor = editorContext()
            // As in VS Code, launch.json's inputs serve whatever configuration is started, an extension's too.
            val inputs = runCatching { DebugLaunch.read(workspace, folder).inputs }.getOrDefault(emptyList())
            val settings = DebugLaunch.settings(workspace, folder)
            // The session's folder: the entry's, or the first for one the workspace file holds.
            val sessionFolder = DebugLaunch.folderPath(workspace, folder) ?: DebugLaunch.layout(workspace).folders.first().path
            val extensions = ExtensionInstallationService.getInstance().list()
            val selected = DebugLaunch.selectExtensions(raw["type"]?.jsonPrimitive?.contentOrNull ?: "", extensions)
            val session = project.service<HostedLanguageServers>().debugSession(selected, sessionFolder, settings,
                install = { path -> ExtensionInstallationService.getInstance().install(path) { }.extension })
            try {
                val input = DebugLaunch.inputs(inputs, Prompts(session))
                // Blocking, as the variable substitution it calls back into is.
                withContext(Dispatchers.IO) {
                    session.prepare(raw, { config, command -> DebugLaunch.substitute(config, workspace, settings, command, input, editor, folder) },
                        parent = environment.getUserData(CodeBridgeStartDebugging.PARENT))
                }
                session.started()
                CodeBridgeDebugSessions.started(session.id, environment)
                val adapter = session.adapter(
                    startFailed = { reason ->
                        HostedMessages.getInstance(project).notify(HostMessages.Severity.ERROR, reason, source = session.configuration.name())
                    },
                    // A program run in a terminal is read to the end, and shown, before its end is.
                    terminalInUse = { terminal.inUse },
                    beforeEnd = { terminal.finished() },
                )
                return ClosingHandle(session, adapter, terminal) {
                    CodeBridgeDebugSessions.ended(session.id)
                    CodeBridgeChildSessions.stop(session.id)
                    DebugLaunch.postDebugTask(session.configuration)?.let { postDebugTask(it, folder) }
                }
            } catch (canceled: DebugLaunch.InputCanceledException) {
                session.close()
                terminal.close()
                // Not an error, as in VS Code: the run just does not start. A cancellation is what
                // IntelliJ ends a run on without reporting it.
                throw CancellationException(canceled.message)
            } catch (error: Throwable) {
                session.close()
                terminal.close()
                throw ExecutionException(error.message ?: "Unable to start VS Code debugger", error)
            }
        }

        private fun JsonObject.name(): String = (this["name"] as? JsonPrimitive)?.contentOrNull ?: "VS Code launch"

        /** Run as VS Code runs it, once the session has ended; nothing waits on it. */
        private fun postDebugTask(label: String, folder: String?) {
            CodeBridgeTaskRuns.scope(project).launch {
                val problem = try {
                    CodeBridgeTaskRuns.runAndWait(project, TaskSource.TasksJson(label, folder)).takeIf { it != 0 }?.let { "exited with code $it" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    "could not run: ${(error.cause ?: error).message}"
                }
                if (problem != null) HostedMessages.getInstance(project).notify(HostMessages.Severity.WARNING,
                    "The postDebugTask '$label' $problem", source = "VS Code launch")
            }
        }

        /**
         * `launch.json`'s `inputs`, asked in front of the user: drawn by the frontend, on the
         * client's screen, and waited for. Canceling one cancels the launch, as it does in VS Code.
         */
        private inner class Prompts(private val session: HostedDebugSession) : DebugLaunch.InputPrompts {
            override fun text(input: InputDefinition.PromptString): String? = ask(QuickInputDto(
                id = 0, kind = QuickInputDto.INPUT, title = TITLE, prompt = input.description ?: input.id,
                value = input.default.orEmpty(), password = input.password,
            ))?.value

            override fun pick(input: InputDefinition.PickString): String? {
                if (input.options.isEmpty()) return null
                val chosen = ask(QuickInputDto(
                    id = 0, kind = QuickInputDto.PICK, title = TITLE, placeholder = input.description ?: input.id,
                    items = input.options.map { QuickItemDto(it.label, picked = it.value == input.default) },
                ))?.chosen?.firstOrNull() ?: return null
                return input.options.getOrNull(chosen)?.value
            }

            override fun command(input: InputDefinition.Command): String? = session.executeCommand(input.command, input.args)

            private fun ask(question: QuickInputDto): HostedQuickInputs.Answer? =
                runBlockingMaybeCancellable { HostedQuickInputs.getInstance(project).ask(question).answer() }
        }

        /**
         * The file and line the user was in, for `${file}` and its kin. Read as the split-mode
         * client, whose editor is the one in front of the user; empty when there is none.
         */
        private fun editorContext(): DebugLaunch.EditorContext {
            var context = DebugLaunch.EditorContext()
            runCatching {
                runBlockingMaybeCancellable {
                    withTimeoutOrNull(5.seconds) {
                        CodeBridgeTextEditors.getInstance(project).asClient {
                            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@asClient
                            val file = FileDocumentManager.getInstance().getFile(editor.document)
                            context = DebugLaunch.EditorContext(
                                file = file?.takeIf { it.isInLocalFileSystem }?.toNioPath(),
                                lineNumber = editor.caretModel.logicalPosition.line + 1,
                                selectedText = editor.selectionModel.selectedText,
                            )
                        }
                    }
                }
            }
            return context
        }

        private companion object { const val TITLE = "Launch Input" }

        override val breakpointsDescription = DapBreakpointsDescription(CodeBridgeLineBreakpointType::class.java, CodeBridgeExceptionBreakpointType::class.java)
    }
}

/**
 * The platform's DAP process, with exception breakpoints: the adapter's filters become breakpoints
 * the user switches on and off, and the enabled ones are sent to it.
 */
internal class CodeBridgeDebugProcess(
    session: XDebugSession,
    dapDebugSession: DapDebugSession,
    xDebugProcessScope: CoroutineScope,
    globalScope: CoroutineScope,
    descriptor: DebugAdapterDescriptor<*>,
    /** The run this is the debugger of. */
    val runEnvironment: ExecutionEnvironment,
    executionResult: ExecutionResult?,
    startRequest: DapStartRequest,
    arguments: Map<String, Any?>,
    /** The session's debug `type`, such as `lldb`: which extension's debugger it runs. */
    val debugger: String?,
) : DapXDebugProcess(session, dapDebugSession, xDebugProcessScope, globalScope, descriptor, runEnvironment, executionResult,
    startRequest, arguments) {
    private val handlers: Array<XBreakpointHandler<*>> by lazy {
        super.getBreakpointHandlers() + CodeBridgeExceptionBreakpointHandler(dapDebugSession, debugger)
    }

    init {
        // The filters an adapter offers can change once it has started: CodeLLDB swaps its C++ ones
        // for Rust's when the program turns out to be Rust.
        if (debugger != null) xDebugProcessScope.launch {
            dapDebugSession.capabilities.collect { capabilities ->
                CodeBridgeExceptionBreakpointType.learned(session.project, debugger, capabilities.exceptionBreakpointFilters.orEmpty().toList())
            }
        }
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = handlers

    /**
     * A test run's session shows the run's results as its console, as a debugged JUnit run does;
     * see [TestSessionConsoles]. What the adapter prints then has a console of its own beside them,
     * as VS Code keeps its Debug Console apart from the Test Results -- an extension may also pass
     * the program's output to the run, as java-test does, and it would be there twice.
     */
    private val tests: ExecutionConsole? get() = runEnvironment.getUserData(TestSessionConsoles.CONSOLE)

    private val adapterOutput: ConsoleView by lazy {
        TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console
            .also { console -> tests?.let { Disposer.register(it, console) } }
    }

    override fun createConsole(): ExecutionConsole = tests ?: super.createConsole()

    override fun createTabLayouter(): XDebugTabLayouter {
        val layouter = super.createTabLayouter()
        if (tests == null) return layouter
        return object : XDebugTabLayouter() {
            override fun registerConsoleContent(ui: RunnerLayoutUi, console: ExecutionConsole): Content =
                layouter.registerConsoleContent(ui, console)

            override fun registerAdditionalContent(ui: RunnerLayoutUi) {
                layouter.registerAdditionalContent(ui)
                val content = ui.createContent(
                    ADAPTER_OUTPUT, adapterOutput.component, "Debug Console",
                    AllIcons.Debugger.Console, adapterOutput.preferredFocusableComponent
                )
                content.isCloseable = false
                ui.addContent(content, 0, PlaceInGrid.center, false)
            }
        }
    }

    override fun formatAndPrintOutput(outEvent: OutputEventArguments) {
        if (tests == null) return super.formatAndPrintOutput(outEvent)
        // As the platform's own: `console` is the debugger's, `stderr` the program's errors.
        val type = when (outEvent.category) {
            OutputCategory.Console -> ConsoleViewContentType.SYSTEM_OUTPUT
            OutputCategory.Stderr -> ConsoleViewContentType.ERROR_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        adapterOutput.print(outEvent.output, type)
    }

    private companion object {
        const val ADAPTER_OUTPUT = "CodeBridgeAdapterOutput"
    }

    /**
     * Read by the platform from its own constructor, before a field of this class is set; a
     * `lateinit` field has no initializer to reset it once this class's turn comes.
     */
    private lateinit var frames: CodeBridgePresentationFactory
    override val presentationFactory: DapXDebuggerPresentationFactory
        get() {
            if (!::frames.isInitialized) frames = CodeBridgePresentationFactory(DefaultDapInlineValueLocator())
            return frames
        }
}

private class ClosingHandle(
    private val session: HostedDebugSession,
    private val delegate: DebugAdapterHandle,
    private val terminal: RunInTerminal?,
    /** What follows the session's end: its children stopping, its `postDebugTask`. */
    private val ended: () -> Unit,
) : DebugAdapterHandle {
    private val disconnected = AtomicBoolean()
    override val input get() = delegate.input
    override val output get() = delegate.output
    override suspend fun disconnect() {
        try { delegate.disconnect() } finally {
            session.close()
            // Waits a moment for a program that has just finished to be read to the end; see [RunInTerminal.close].
            terminal?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }
            if (disconnected.compareAndSet(false, true)) ended()
        }
    }
}
