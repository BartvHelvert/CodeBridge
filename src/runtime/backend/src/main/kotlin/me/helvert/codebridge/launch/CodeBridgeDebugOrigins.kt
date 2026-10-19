package me.helvert.codebridge.launch

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.CodeActions
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.workbench.HostedMessages
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the user did that an extension's `vscode.debug.startDebugging` comes of: the command they
 * ran -- a code lens's Run, a code action -- for as long as it runs.
 *
 * The configuration an extension starts is its own, and may hold what only that run can use:
 * java-debug's progress handle, java-test's port to report results on. So it is not what is kept to
 * run again; the command is, and the extension makes a fresh configuration each time. VS Code names
 * no such origin, so it is the command running when the configuration came.
 */
internal object CodeBridgeDebugOrigins {

    /** [command] as it was run; [noDebug] overrides what it starts, when run again with Run or Debug. */
    data class Origin(val command: CodeActions.Command, val noDebug: Boolean? = null)

    private val running = CopyOnWriteArrayList<Pair<Project, Origin>>()

    /** Runs [run], the command of [origin], as the origin of what it starts until it is over. */
    suspend fun <T> during(project: Project, origin: Origin, run: suspend () -> T): T {
        val entry = project to origin
        running += entry
        try {
            return run()
        } finally {
            running -= entry
        }
    }

    /** The command [project]'s debug session now starting comes of: the latest still running. */
    fun current(project: Project): Origin? = running.lastOrNull { it.first == project }?.second
}

/**
 * Runs a [DebugSource.Command]: its command again, which starts the session. Run starts it without
 * debugging and Debug with, whatever the command chose the first time.
 */
class CodeBridgeCommandRunner : ProgramRunner<RunnerSettings> {
    override fun getRunnerId(): String = "CodeBridge.LaunchCommand"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID) &&
                (profile as? CodeBridgeDebugRunConfiguration)?.source is DebugSource.Command

    override fun execute(environment: ExecutionEnvironment) {
        val project = environment.project
        val source = (environment.runProfile as CodeBridgeDebugRunConfiguration).source as DebugSource.Command
        val noDebug = environment.executor.id == DefaultRunExecutor.EXECUTOR_ID
        val features = HostedLanguageFeatures.getInstance(project)
        features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                features.executeCommand(source.command, noDebug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                HostedMessages.getInstance(project).notify(
                    Messages.Severity.ERROR,
                    "'${source.name}' could not start: ${(failure.cause ?: failure).message}", source = "VS Code launch"
                )
            }
        }
    }
}
