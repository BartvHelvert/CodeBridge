package me.helvert.codebridge.settings

import com.intellij.ide.vfs.virtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import me.helvert.codebridge.rpc.LaunchStartDto
import me.helvert.codebridge.rpc.WorkspaceApi
import me.helvert.codebridge.rpc.backendModal

/**
 * Opens the workspace's [fileName], making it first as VS Code does when there is none: "Debug: Open
 * launch.json", "Tasks: Open tasks.json" and "Preferences: Open Workspace Settings (JSON)".
 *
 * In a multi-root workspace VS Code asks which: a folder's own, or the workspace's, which is the
 * `.code-workspace` file's section. A new `launch.json` starts from the `initialConfigurations` of
 * a debugger the user picks, as VS Code's "create a launch.json file" does, or empty when no
 * debugger declares any.
 */
abstract class OpenWorkspaceFileAction(private val fileName: String) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val targets = request(project) { workspaceTargets(project.projectId()) } ?: return
        val folders = targets.value ?: return start(project, null)
        // The workspace's own first, as VS Code lists it; then each folder.
        choose(project, "Select Folder", listOf<String?>(null) + folders, { it ?: "Workspace" }) { start(project, it) }
    }

    /** Opens [target]'s file, asking for a debugger first for a `launch.json` that is to be made. */
    private fun start(project: Project, target: String?) {
        if (fileName != LAUNCH) return open(project, null, target)
        val starts = request(project) { launchStarts(project.projectId(), target) } ?: return
        // Null: there are launch configurations already, so there is nothing to start from.
        if (starts.value.isNullOrEmpty()) return open(project, null, target)
        choose(project, "Select Debugger", starts.value + EMPTY, { it.label }) { open(project, it.type.takeIf { _ -> it !== EMPTY }, target) }
    }

    private fun <T> choose(project: Project, title: String, choices: List<T>, label: (T) -> String, chosen: (T) -> Unit) {
        val step = object : BaseListPopupStep<T>(title, choices) {
            override fun getTextFor(value: T) = label(value)
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: T, finalChoice: Boolean) = doFinalStep { chosen(selectedValue) }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }

    private fun open(project: Project, debugger: String?, target: String?) {
        val result = request(project) { workspaceFile(project.projectId(), fileName, debugger, target) } ?: return
        val opened = result.value ?: return notify(project, "Could not open $fileName: the workspace has no such file")
        val file = opened.file.virtualFile()
            ?: return notify(project, "Could not open $fileName: this client cannot find the file")
        OpenFileDescriptor(project, file, opened.offset ?: 0).navigate(true)
    }

    /** [work]'s result, boxed so a null result is told apart from a failure, which is reported. */
    private fun <T> request(project: Project, work: suspend WorkspaceApi.() -> T): Result<T>? = try {
        Result(backendModal(null, "Opening .vscode/$fileName") { work(WorkspaceApi.getInstance()) })
    } catch (_: ProcessCanceledException) { null
    } catch (_: CancellationException) { null
    } catch (error: Exception) {
        notify(project, "Could not open .vscode/$fileName: ${error.message ?: error.javaClass.simpleName}")
        null
    }

    private class Result<T>(val value: T)

    private fun notify(project: Project, text: String) =
        NotificationGroupManager.getInstance().getNotificationGroup("CodeBridge")
            .createNotification(text, NotificationType.ERROR).notify(project)

    private companion object {
        const val LAUNCH = "launch.json"
        val EMPTY = LaunchStartDto("", "Empty launch.json")
    }
}

class OpenLaunchJsonAction : OpenWorkspaceFileAction("launch.json")
class OpenTasksJsonAction : OpenWorkspaceFileAction("tasks.json")
class OpenWorkspaceSettingsAction : OpenWorkspaceFileAction("settings.json")
