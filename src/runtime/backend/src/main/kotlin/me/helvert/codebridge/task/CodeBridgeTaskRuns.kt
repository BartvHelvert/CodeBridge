package me.helvert.codebridge.task

import com.intellij.execution.*
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentDescriptorReusePolicy
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.language.CodeBridgeTextEditors
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Running a [TaskSource] as IntelliJ runs a gutter icon's: through its run configuration -- the one
 * [CodeBridgeTaskConfigurations] keeps for a `tasks.json` task, else a temporary one, the one an earlier run
 * of the same task made if there is one -- with the Run executor, so it gets
 * the ordinary Run tool window tab and can be run again from the run widget.
 */
object CodeBridgeTaskRuns {

    /**
     * The run an extension asked for and is waiting on: the execution id it holds, the task DTO it
     * sent, and the answer to `$executeTask`.
     */
    class Pending(val id: String, val dto: JsonObject, val started: CompletableDeferred<Unit>)

    val PENDING: Key<Pending> = Key.create("codebridge.task.pending")
    val LAUNCH_READY: Key<CompletableDeferred<Int>> = Key.create("codebridge.task.launch.ready")

    /** Starts [source]'s configuration, selected in the run widget, returning once it is under way. */
    suspend fun start(project: Project, source: TaskSource, pending: Pending? = null) {
        CodeBridgeTextEditors.getInstance(project).asClient {
            val environment = environment(settings(project, source, select = true))
            pending?.let { environment.putUserData(PENDING, it) }
            ProgramRunnerUtil.executeConfiguration(environment, false, false)
        }
    }

    /**
     * The exit code once a foreground task exits, or 0 once a background task reports ready. The
     * Run tab keeps the watcher alive, and the run widget keeps the configuration the user chose.
     * Giving up on the wait stops the task.
     */
    suspend fun runAndWait(project: Project, source: TaskSource): Int {
        val exit = CompletableDeferred<Int>()
        try {
            CodeBridgeTextEditors.getInstance(project).asClient {
                val settings = settings(project, source, select = false)
                // Fail the launch directly for malformed tasks. The runner's configuration
                // validation can stop before processNotStarted, leaving a waiting launch unanswered.
                TaskProcesses.validate((settings.configuration as CodeBridgeTaskRunConfiguration).resolve(), preLaunch = true)
                val environment = environment(settings)
                environment.putUserData(LAUNCH_READY, exit)
                whenEnded(project, environment, exit)
                ProgramRunnerUtil.executeConfigurationAsync(environment, false, false, keepTab())
            }
            return exit.await()
        } catch (cancelled: CancellationException) {
            exit.cancel()
            throw cancelled
        }
    }

    /** Where a run nobody waits for is followed: a `postDebugTask`. It ends with the project. */
    internal fun scope(project: Project): CoroutineScope = Scope.getInstance(project).scope

    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    /**
     * Keeps a `preLaunchTask`'s tab for its own next run only. IntelliJ hands a finished tab to the
     * next run that starts -- the debug session the task was run for, a moment later -- and the
     * build's output went with it. VS Code keeps a task's terminal; here the tab stays beside the
     * session, and the task's next run takes it over rather than adding another.
     */
    private fun keepTab(): ProgramRunner.Callback = ProgramRunner.Callback { descriptor ->
        descriptor?.reusePolicy = object : RunContentDescriptorReusePolicy() {
            override fun canBeReusedBy(newDescriptor: RunContentDescriptor): Boolean = newDescriptor.displayName == descriptor.displayName
        }
    }

    /**
     * [task]'s configuration, found by its source so a changed task gets a new one: the one
     * [CodeBridgeTaskConfigurations] keeps for a `tasks.json` task, else a temporary one. Editor thread.
     */
    fun settings(project: Project, task: TaskSource, select: Boolean): RunnerAndConfigurationSettings {
        // A `preLaunchTask` names its entry's folder; the configuration is of the file that has the task.
        val source = project.basePath?.let { TaskSources.served(Path.of(it), task) } ?: task
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(CodeBridgeTaskConfigurationType::class.java)
        val settings = runManager.getConfigurationSettingsList(type)
            .firstOrNull { (it.configuration as? CodeBridgeTaskRunConfiguration)?.source == source }
            ?: runManager.createConfiguration(source.name, type.configurationFactories.single()).also { created ->
                (created.configuration as CodeBridgeTaskRunConfiguration).source = source
                created.isTemporary = true
                runManager.addConfiguration(created)
            }
        if (select) runManager.selectedConfiguration = settings
        return settings
    }

    private fun environment(settings: RunnerAndConfigurationSettings): ExecutionEnvironment =
        ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
            .also { it.assignNewExecutionId() }

    private fun whenEnded(project: Project, environment: ExecutionEnvironment, exit: CompletableDeferred<Int>) {
        val listening = Disposer.newDisposable("VS Code task run")
        Disposer.register(project, listening)
        Disposer.register(listening) { exit.cancel() }
        exit.invokeOnCompletion { Disposer.dispose(listening) }
        fun ours(other: ExecutionEnvironment) = other.executionId == environment.executionId
        project.messageBus.connect(listening).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (ours(env)) exit.completeExceptionally(IllegalStateException("The task did not start"))
            }
        })
    }
}
