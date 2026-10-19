package me.helvert.codebridge.launch

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.DebugService
import java.util.concurrent.ConcurrentHashMap

/**
 * The runs of the sessions the extension host knows, by the id it knows each by, for
 * `vscode.debug.stopDebugging`: js-debug stops a session of its own when its target goes away.
 */
internal object CodeBridgeDebugSessions {
    private val runs = ConcurrentHashMap<String, ExecutionEnvironment>()

    fun started(id: String, environment: ExecutionEnvironment) { runs[id] = environment }

    fun ended(id: String) { runs.remove(id) }

    /** `$stopDebugging`: one session, or -- with no id -- every one of this project's. */
    suspend fun stop(project: Project, argsJson: String): MainThreadResult {
        val id = DebugService.stoppedSession(argsJson)
        val environments = if (id == null) runs.values.filter { it.project == project } else listOfNotNull(runs[id])
        withContext(Dispatchers.EDT) { environments.forEach(::stop) }
        return MainThreadResult()
    }

    /** As the Stop button does: the debug session if there is one, else the run's process. */
    private fun stop(environment: ExecutionEnvironment) {
        val session = XDebuggerManager.getInstance(environment.project).debugSessions
            .firstOrNull { (it.debugProcess as? CodeBridgeDebugProcess)?.runEnvironment === environment }
        if (session != null) return session.stop()
        RunContentManager.getInstance(environment.project).allDescriptors
            .firstOrNull { environment.executionId != 0L && it.executionId == environment.executionId }
            ?.processHandler?.takeUnless { it.isProcessTerminated || it.isProcessTerminating }?.destroyProcess()
    }
}
