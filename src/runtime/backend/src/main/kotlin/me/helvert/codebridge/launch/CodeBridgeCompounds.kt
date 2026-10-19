package me.helvert.codebridge.launch

import com.intellij.execution.*
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.task.TaskSource
import me.helvert.codebridge.task.CodeBridgeTaskRuns
import me.helvert.codebridge.workbench.HostedMessages
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a compound: the DAP runner starts one session per run, so a compound is a runner of its own
 * that starts each member's configuration as a run of its own, with the executor it was given.
 */
class CodeBridgeCompoundRunner : ProgramRunner<RunnerSettings> {
    override fun getRunnerId(): String = "CodeBridge.LaunchJsonCompound"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID) &&
            (profile as? CodeBridgeDebugRunConfiguration)?.source is DebugSource.Compound

    override fun execute(environment: ExecutionEnvironment) {
        val profile = environment.runProfile as CodeBridgeDebugRunConfiguration
        val workspace = profile.workspace() ?: throw ExecutionException("The project has no workspace directory")
        val source = profile.source as DebugSource.Compound
        // Read now, so a broken compound is reported as this run's failure.
        try { DebugLaunch.compound(workspace, source.name, source.folder) }
        catch (error: IllegalArgumentException) { throw ExecutionException(error.message, error) }
        // Off the editor thread: the preLaunchTask is waited for.
        Scope.getInstance(environment.project).scope.launch(Dispatchers.IO) {
            CodeBridgeCompounds.start(environment.project, workspace, source, environment.executor)
        }
    }

    /** Where a compound starts: past the run that asked, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }
}

internal object CodeBridgeCompounds {

    /**
     * As VS Code runs a compound: its `preLaunchTask` until exit or readiness, then every member at once, each
     * with its own `preLaunchTask`. With `stopAll`, the first member to stop stops the rest.
     */
    suspend fun start(project: Project, workspace: Path, source: DebugSource.Compound, executor: Executor) {
        val name = source.name
        try {
            val compound = DebugLaunch.compound(workspace, name, source.folder)
            compound.preLaunchTask?.let { label ->
                val exit = CodeBridgeTaskRuns.runAndWait(project, TaskSource.TasksJson(label, source.folder))
                if (exit != 0) error("The preLaunchTask '$label' exited with code $exit")
            }
            CodeBridgeTextEditors.getInstance(project).asClient {
                val environments = compound.members.mapNotNull { member ->
                    val settings = CodeBridgeStartDebugging.settings(project, DebugSource.LaunchJson(member.name, member.folder ?: source.folder), register = false)
                    ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build()?.also { it.assignNewExecutionId() }
                }
                if (compound.stopAll) stopTogether(project, environments)
                environments.forEach { ProgramRunnerUtil.executeConfiguration(it, false, false) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HostedMessages.getInstance(project).notify(Messages.Severity.ERROR,
                "The compound '$name' could not start: ${error.message}", source = "VS Code launch")
        }
    }

    /** Once any of [environments]' runs ends, stops the others; done listening when all have ended. */
    private fun stopTogether(project: Project, environments: List<ExecutionEnvironment>) {
        val ids = environments.map { it.executionId }.toSet()
        val handlers = CopyOnWriteArrayList<ProcessHandler>()
        val remaining = AtomicInteger(ids.size)
        val listening = Disposer.newDisposable("VS Code compound")
        Disposer.register(project, listening)
        fun ended() { if (remaining.decrementAndGet() == 0) Disposer.dispose(listening) }
        project.messageBus.connect(listening).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (env.executionId !in ids) return
                handlers += handler
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        handlers.forEach { if (!it.isProcessTerminated && !it.isProcessTerminating) it.destroyProcess() }
                        ended()
                    }
                })
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (env.executionId !in ids) return
                handlers.forEach { if (!it.isProcessTerminated && !it.isProcessTerminating) it.destroyProcess() }
                ended()
            }
        })
    }
}
