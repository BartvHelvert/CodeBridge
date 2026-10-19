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
import kotlinx.coroutines.CancellationException
import me.helvert.codebridge.rpc.backendModal
import java.awt.Component

/**
 * VS Code's "Preferences: Open User Settings (JSON)": the user-level `settings.json` the extensions
 * of every project read, beneath that project's own `.vscode/settings.json`.
 *
 * The file is on the backend, where the extensions run; it is opened here because this is where
 * the user's editors are.
 */
class OpenUserSettingsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) { event.project?.let { open(it) } }

    companion object {
        /** Opens the file in [project]'s editor; [parent] owns the progress shown while the backend finds it. */
        fun open(project: Project, parent: Component? = null) {
            val settings = try {
                backendModal(parent, "Opening VS Code user settings") { userSettings() }
            } catch (_: ProcessCanceledException) { return
            } catch (_: CancellationException) { return
            } catch (error: Exception) {
                return notify(project, "Could not open the VS Code user settings: ${error.message ?: error.javaClass.simpleName}", NotificationType.ERROR)
            }
            val file = settings.file.virtualFile()
                ?: return notify(project, "Could not open the VS Code user settings: this client cannot find the file", NotificationType.ERROR)
            OpenFileDescriptor(project, file).navigate(true)
        }

        @Suppress("SameParameterValue")
        private fun notify(project: Project, text: String, type: NotificationType) =
            NotificationGroupManager.getInstance().getNotificationGroup("CodeBridge").createNotification(text, type).notify(project)
    }
}
