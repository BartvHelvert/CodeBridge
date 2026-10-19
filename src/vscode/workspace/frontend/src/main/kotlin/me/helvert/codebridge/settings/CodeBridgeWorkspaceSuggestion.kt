package me.helvert.codebridge.settings

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.helvert.codebridge.rpc.WorkspaceApi
import me.helvert.codebridge.rpc.WorkspaceChoiceDto
import me.helvert.codebridge.rpc.backendModal

/**
 * Suggests a `.code-workspace` file the project directory holds, as VS Code does on opening a folder
 * with one: it is used only once the user says so, and the answer is kept. Asked again on the next
 * opening while it is not answered, as VS Code asks again.
 */
internal class CodeBridgeWorkspaceSuggestion : ProjectActivity {
    override suspend fun execute(project: Project) {
        val choice = try {
            WorkspaceApi.getInstance().workspaceChoice(project.projectId())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LOG.debug("Could not ask which VS Code workspace file to use", error)
            return
        } ?: return
        if (choice.decided || choice.candidates.isEmpty()) return
        withContext(Dispatchers.EDT) { suggest(project, choice) }
    }

    private fun suggest(project: Project, choice: WorkspaceChoiceDto) {
        val single = choice.candidates.singleOrNull()
        val text =
            if (single != null) "This project contains a VS Code workspace file, '$single'. Open it as a multi-root workspace?"
            else "This project contains ${choice.candidates.size} VS Code workspace files. Open one as a multi-root workspace?"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("CodeBridge")
            .createNotification("VS Code workspace", text, NotificationType.INFORMATION)
        if (single != null) notification.addAction(NotificationAction.createSimpleExpiring("Open workspace") {
            choose(
                project,
                single
            )
        })
        else notification.addAction(NotificationAction.createSimpleExpiring("Select workspace…") {
            WorkspacePicker.pick(
                project,
                choice
            )
        })
        notification.addAction(NotificationAction.createSimpleExpiring("Don't use") { choose(project, null) })
        notification.notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeWorkspaceSuggestion::class.java)
    }
}

/**
 * The list behind the suggestion's "Select Workspace…": the folder alone, or one of the project
 * directory's workspace files. The menus offer the same as a submenu; see the backend's
 * `CodeBridgeWorkspaceMenu`.
 */
internal object WorkspacePicker {
    /** The project's directory alone, at the top of the list. */
    private const val FOLDER_ONLY = ""

    fun pick(project: Project, choice: WorkspaceChoiceDto) {
        val options = listOf(FOLDER_ONLY) + choice.candidates
        fun label(option: String): String {
            val text = if (option == FOLDER_ONLY) "This folder only, without a workspace file" else option
            val current =
                if (option == FOLDER_ONLY) choice.decided && choice.chosen == null else option == choice.chosen
            return if (current) "$text (current)" else text
        }
        val step = object : BaseListPopupStep<String>("Choose VS Code Workspace", options) {
            override fun getTextFor(value: String) = label(value)
            override fun isSpeedSearchEnabled() = true
            override fun onChosen(selectedValue: String, finalChoice: Boolean) =
                doFinalStep { choose(project, selectedValue.takeUnless { it == FOLDER_ONLY }) }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }
}

/** Keeps [name] -- or the folder alone, for null -- as the project's workspace; the extension host restarts in it. */
private fun choose(project: Project, name: String?) {
    try {
        backendModal(null, "Opening the VS Code workspace") { WorkspaceApi.getInstance().chooseWorkspace(project.projectId(), name) }
    } catch (_: ProcessCanceledException) {
    } catch (_: CancellationException) {
    } catch (error: Exception) {
        NotificationGroupManager.getInstance().getNotificationGroup("CodeBridge")
            .createNotification(
                "Could not change the VS Code workspace: ${error.message ?: error.javaClass.simpleName}",
                NotificationType.ERROR
            )
            .notify(project)
    }
}
