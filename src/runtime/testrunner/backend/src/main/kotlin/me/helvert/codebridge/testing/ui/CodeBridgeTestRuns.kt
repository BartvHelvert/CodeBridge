package me.helvert.codebridge.testing.ui

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.testing.HostedTesting
import kotlin.coroutines.cancellation.CancellationException

/** Running tests as a gutter icon's run is run: a temporary configuration, selected in the run widget. */
object CodeBridgeTestRuns {

    /** Runs [testIds] with [executor], through the configuration a previous run of them made, if any. */
    fun start(project: Project, testIds: List<String>, name: String, executor: Executor) = asClient(project, "start the tests") {
        val settings = settings(project, testIds, name)
        RunManager.getInstance(project).selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    /**
     * Shows a run nobody in the IDE started -- one the extension began itself, or asked for with
     * `vscode.tests.runTests` -- in a tab of its own. The configuration is not kept: Rerun on the tab
     * runs the same controllers' tests again. A debugged one is shown as debugged runs are, in the
     * tab of the debug session it starts; see [CodeBridgeTestDebugRunner].
     */
    fun show(project: Project, run: HostedTesting.Run) = asClient(project, "show test run ${run.id}") {
        val type = ConfigurationTypeUtil.findConfigurationType(CodeBridgeTestConfigurationType::class.java)
        val labels = HostedTesting.getInstance(project).controllers()
        val name = run.controllerIds.joinToString(", ") { labels[it] ?: it }.ifEmpty { "VS Code tests" }
        val settings =
            RunManager.getInstance(project).createConfiguration(name, type.configurationFactories.single())
        (settings.configuration as CodeBridgeTestRunConfiguration).testIds = run.controllerIds
        val executor = if (run.group == Testing.Group.DEBUG) DefaultDebugExecutor.getDebugExecutorInstance()
        else DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()
        environment.putUserData(CodeBridgeTestRunState.ATTACH, run)
        ProgramRunnerUtil.executeConfiguration(environment, false, false)
    }

    /** Runs [action] as the user's client, not waited for; a failure is logged, as failing to [what]. */
    private fun asClient(project: Project, what: String, action: () -> Unit) {
        Scope.getInstance(project).scope.launch {
            try {
                CodeBridgeTextEditors.getInstance(project).asClient(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not $what", failure)
            }
        }
    }

    /** Where a run is started from: after the gesture that asked, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    private val LOG = Logger.getInstance(CodeBridgeTestRuns::class.java)

    private fun settings(project: Project, testIds: List<String>, name: String): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(CodeBridgeTestConfigurationType::class.java)
        return runManager.getConfigurationSettingsList(type)
            .firstOrNull { (it.configuration as? CodeBridgeTestRunConfiguration)?.testIds == testIds }
            ?: runManager.createConfiguration(name, type.configurationFactories.single()).also { created ->
                (created.configuration as CodeBridgeTestRunConfiguration).testIds = testIds
                created.isTemporary = true
                runManager.addConfiguration(created)
            }
    }
}
