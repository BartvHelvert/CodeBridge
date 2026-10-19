package me.helvert.codebridge.debug

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import com.intellij.xdebugger.impl.frame.XDebuggerFramesList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.rpc.TreeActionDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * What extensions add to VS Code's Call Stack right-click menu, in the right-click menu of the
 * debugger's frames list: CodeLLDB's Exclude Caller.
 *
 * The frames list selects the frame it is right-clicked on before the menu opens, so the frame is
 * the session's current one, which the backend acts on. Which entries there are depends on the session's debugger and the extensions'
 * context keys, so they are asked for each time the menu opens.
 */
internal class CodeBridgeCallStackActions : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val project = event?.project ?: return EMPTY_ARRAY
        val list = event.getData(FRAMES_LIST) ?: return EMPTY_ARRAY
        if (list.selectedFrame == null) return EMPTY_ARRAY
        val actions = try {
            runBlockingCancellable {
                withTimeoutOrNull(TIMEOUT_MILLIS.milliseconds) {
                    CodeBridgeApi.getInstance().callStackActions(project.projectId())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LOG.debug("Could not read the call stack menu", error)
            null
        }
        if (actions.isNullOrEmpty()) return EMPTY_ARRAY
        return (listOf<AnAction>(Separator.getInstance()) + actions.map { action(project, it) }).toTypedArray()
    }

    private fun action(project: Project, entry: TreeActionDto): AnAction =
        // A submenu, which opens its own entries.
        if (entry.children.isNotEmpty()) DefaultActionGroup(
            entry.title,
            entry.children.map { action(project, it) }).apply { isPopup = true }
        else object : DumbAwareAction(
            entry.title,
            null,
            entry.icon?.let(Codicons::references)?.firstOrNull()?.let(Codicons::icon)
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = entry.enabled
            }

            override fun actionPerformed(event: AnActionEvent) {
                Scope.getInstance(project).scope.launch {
                    runCatching { CodeBridgeApi.getInstance().runCallStackAction(project.projectId(), entry.id) }
                        .onFailure {
                            if (it is CancellationException) throw it else LOG.warn(
                                "Could not run ${entry.title}",
                                it
                            )
                        }
                }
            }
        }

    /** Where a chosen entry runs: it outlives the menu, and ends with the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeCallStackActions::class.java)

        /** The frames list's own key, which it keeps private; a data key is known by its name. */
        val FRAMES_LIST: DataKey<XDebuggerFramesList> = DataKey.create("FRAMES_LIST")

        /** A menu cannot wait for long; past this it opens without the extensions' entries. */
        const val TIMEOUT_MILLIS = 2_000L
    }
}
