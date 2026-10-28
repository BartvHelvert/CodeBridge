package me.helvert.codebridge.launch

import com.intellij.execution.*
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.helvert.codebridge.debug.HostedDebugSession
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.DebugService
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.testing.HostedTesting
import me.helvert.codebridge.testing.TestSessionConsoles
import me.helvert.codebridge.workbench.HostedMessages
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * `vscode.debug.startDebugging`, which is what most code lenses' Run and Debug end in.
 *
 * The configuration runs as an IntelliJ run configuration. What can be started again becomes a
 * temporary one, as running from a gutter icon makes one, so it shows in the run widget: see
 * [kept]. It is started with the Run executor for `noDebug` -- VS Code's Run Without Debugging,
 * still a debug adapter session -- and with the Debug executor otherwise. The extension hears
 * whether the run started.
 *
 * A child session -- one started with a parent -- is stopped with its parent; see [CodeBridgeChildSessions].
 */
internal object CodeBridgeStartDebugging {

    /** The id of the session an environment's session is a child of. */
    val PARENT: Key<String> = Key.create("codebridge.debug.parent")

    /** The session an environment's configuration was resolved in before its run started: for the run to take. */
    val PREPARED: Key<AtomicReference<HostedDebugSession?>> = Key.create("codebridge.debug.prepared")

    suspend fun start(project: Project, argsJson: String): MainThreadResult {
        val result = CompletableDeferred<MainThreadResult>()
        try {
            val request = DebugService.startDebugging(argsJson)
            val workspace = project.basePath?.let(Path::of) ?: error("The project has no workspace directory")
            val folder = request.folder?.let { uri -> runCatching { uri.toPath() }.getOrNull() }
                ?.let { path -> DebugLaunch.layout(workspace).takeIf { it.isMultiRoot }?.folderOf(path)?.name }
            // Read now, while the command that started this is still running.
            val origin = request.configuration?.let { CodeBridgeDebugOrigins.current(project) }
            val testing = request.configuration != null && HostedTesting.getInstance(project).isRunning()
            // As VS Code reads it: the options' or the configuration's own; a command run again with
            // Run or Debug overrides both.
            val noDebug = origin?.noDebug
                ?: (request.noDebug || (request.configuration?.get("noDebug") as? JsonPrimitive)?.booleanOrNull == true)
            val configuration = request.configuration?.let { config ->
                if (origin?.noDebug == null) config else JsonObject(config + ("noDebug" to JsonPrimitive(noDebug)))
            }
            val source =
                configuration?.let(DebugSource::Extension) ?: named(workspace, checkNotNull(request.name), folder)
            val parent = request.parentSessionId
            val kept = kept(source, parent, origin, testing)
            val executor =
                if (noDebug) DefaultRunExecutor.getRunExecutorInstance() else DefaultDebugExecutor.getDebugExecutorInstance()
            val editors = CodeBridgeTextEditors.getInstance(project)
            val environment = editors.asClient {
                val settings = settings(project, source, register = kept == source)
                // Not run here: the configuration that does is the extension's, for this run only.
                if (kept != null && kept != source) settings(project, kept, register = true)
                ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build()
            } ?: return MainThreadResult("false")
            environment.assignNewExecutionId()
            parent?.let { environment.putUserData(PARENT, it) }
            // As VS Code starts it: resolved first, by the debugger's providers and the user's inputs. A
            // launch canceled there starts nothing -- no run to end, which the IDE would log as failed --
            // and the extension hears that it did not start. Oracle's Java extension starts a few such
            // launches as it activates, only to have its settings' variables substituted.
            if (resolvesFirst(environment)) {
                val prepared = try {
                    LaunchPreparation(project).prepare(environment)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: DebugLaunch.LaunchCanceledException) {
                    return MainThreadResult("false")
                } catch (error: Exception) {
                    LOG.warn("An extension's debug configuration could not be resolved", error)
                    HostedMessages.getInstance(project).notify(Messages.Severity.ERROR,
                        error.message ?: "Unable to start VS Code debugger", source = source.name)
                    return MainThreadResult("false")
                }
                val holder = AtomicReference<HostedDebugSession?>(prepared)
                environment.putUserData(PREPARED, holder)
                // The run takes it as it starts; one that never does leaves it to be closed here.
                result.invokeOnCompletion { holder.getAndSet(null)?.close() }
            }
            editors.asClient {
                // A debugged test run's session is the run's tab, its results the console.
                if (testing && !noDebug && parent == null) TestSessionConsoles.claim(project)
                    ?.let { environment.putUserData(TestSessionConsoles.CONSOLE, it) }
                // A compound has no process of its own to wait for: it has started once its members are on their way.
                if (source !is DebugSource.Compound) whenStarted(project, environment, result, parent)
                // A test run's session, without debugging, gets no tab of its own: the run's tab shows
                // the tests, and what the program writes -- the extension passes it on, as java-test
                // does. Debugged, it keeps its tab, which is where it is debugged.
                if (testing && noDebug) {
                    environment.setHeadless()
                    ProgramRunnerUtil.executeConfigurationAsync(environment, false, false, disposedWhenEnded())
                } else ProgramRunnerUtil.executeConfiguration(environment, false, false)
                if (source is DebugSource.Compound) result.complete(MainThreadResult("true"))
            }
        } catch (cancelled: CancellationException) {
            result.cancel(cancelled)
            throw cancelled
        } catch (error: Exception) {
            // The extension's call fails with the reason, which is what VS Code's rejected promise carries.
            LOG.warn("An extension's debug session could not be started", error)
            result.cancel()
            throw error
        }
        return withTimeoutOrNull(START_MINUTES.minutes) { result.await() } ?: MainThreadResult("false")
    }

    /** Whether [environment] runs as a `launch.json`-style DAP session, which is resolved before it starts. */
    private fun resolvesFirst(environment: ExecutionEnvironment): Boolean =
        LaunchJsonDapArgumentsProvider().isApplicable(environment.executor.id, environment.runProfile) &&
            (environment.runProfile as CodeBridgeDebugRunConfiguration).source != null

    /**
     * What of a start is kept for the user to run again: a `launch.json` entry, by name; and for a
     * configuration an extension made, the command that started it, which makes a fresh one. Never
     * that configuration itself, which may hold what only this run can use -- java-debug's progress
     * handle, java-test's port to report results on.
     *
     * Nothing for a child, the parent's business: js-debug starts one per worker it finds, and the
     * run widget would switch to each. Nothing for a test run's session, which the test's own
     * configuration runs again, nor for one whose command is unknown.
     */
    internal fun kept(
        source: DebugSource,
        parent: String?,
        origin: CodeBridgeDebugOrigins.Origin?,
        testing: Boolean
    ): DebugSource? = when {
        parent != null -> null
        source !is DebugSource.Extension -> source
        testing || origin == null -> null
        else -> DebugSource.Command(source.name, origin.command)
    }

    /**
     * A name, as VS Code reads one: a configuration, else a compound -- of [folder]'s `launch.json`
     * when the extension named a folder that has one, else the workspace's own. A missing one fails
     * the extension's call now rather than the run later.
     */
    private fun named(workspace: Path, name: String, folder: String?): DebugSource {
        val where = folder?.takeIf { DebugLaunch.exists(workspace, it) }
        val file = DebugLaunch.read(workspace, where)
        if (file.configurations.none { it.name == name } && file.compounds.any { it.name == name }) {
            DebugLaunch.compound(workspace, name, where)
            return DebugSource.Compound(name, where)
        }
        DebugLaunch.configuration(workspace, name, where)
        return DebugSource.LaunchJson(name, where)
    }

    /**
     * The configuration for [source]: one the user or an earlier start made, else a new one. A
     * [register]ed new one is temporary and selected, as running from a gutter icon makes one;
     * otherwise it is used for this run only. Editor thread.
     */
    fun settings(project: Project, source: DebugSource, register: Boolean): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(CodeBridgeDebugConfigurationType::class.java)
        // The one listed for launch.json first, then any other made for it.
        val existing = runManager.getConfigurationSettingsList(type)
            .filter { settings -> (settings.configuration as? CodeBridgeDebugRunConfiguration)?.source == source }
            .maxByOrNull { settings -> (settings.configuration as CodeBridgeDebugRunConfiguration).managed }
        if (existing != null) {
            if (register) runManager.selectedConfiguration = existing
            return existing
        }
        val settings = runManager.createConfiguration(source.name, type.factory(DebugSource.attaches(source, project.basePath?.let(Path::of))))
        (settings.configuration as CodeBridgeDebugRunConfiguration).source = source
        if (register) runManager.setTemporaryConfiguration(settings)
        return settings
    }

    /** Completes [result] once [environment]'s run has started, or has failed to. */
    private fun whenStarted(project: Project, environment: ExecutionEnvironment, result: CompletableDeferred<MainThreadResult>, parent: String?) {
        val listening = Disposer.newDisposable("VS Code startDebugging")
        Disposer.register(project, listening)
        result.invokeOnCompletion { Disposer.dispose(listening) }
        fun ours(other: ExecutionEnvironment) = other.executionId == environment.executionId
        project.messageBus.connect(listening).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (!ours(env)) return
                parent?.let { CodeBridgeChildSessions.add(it, handler) }
                result.complete(MainThreadResult("true"))
            }
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (ours(env)) result.complete(MainThreadResult("false"))
            }
        })
    }

    /** A headless run's descriptor, shown nowhere and so closed by nobody: done with once its process has ended. */
    private fun disposedWhenEnded() = ProgramRunner.Callback { descriptor ->
        val handler = descriptor?.processHandler ?: return@Callback
        fun dispose() {
            Scope.getInstance().scope.launch(Dispatchers.EDT) { Disposer.dispose(descriptor) }
        }
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = dispose()
        })
        if (handler.isProcessTerminated) dispose()
    }

    private val LOG = Logger.getInstance(CodeBridgeStartDebugging::class.java)

    /** How long the extension waits to hear whether its session started, before it is told it did not. */
    private const val START_MINUTES = 2L

    /** Where a headless run is done with: after its process, which knows no project. */
    @Service(Service.Level.APP)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(): Scope = service()
        }
    }
}
