package me.helvert.codebridge.extensions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import me.helvert.codebridge.rpc.awaitOperation
import me.helvert.codebridge.rpc.backendModal
import me.helvert.codebridge.rpc.marketplace
import javax.swing.JComponent

class InstallVsixAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) { event.project?.let(::chooseAndInstall) }
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }

    companion object {
        fun chooseAndInstall(project: Project?, parent: JComponent? = null) {
            val chooser = FileChooserDescriptorFactory.singleFile()
                .withFileFilter { it.extension.equals("vsix", ignoreCase = true) }
                .withTitle("Install VS Code Extension from VSIX")
            val file = FileChooser.chooseFile(chooser, parent, project, null) ?: return
            try {
                val installed = backendModal(parent, "Installing ${file.name}") {
                    // Read through VirtualFile: never reinterpret a frontend path on backend.
                    val contents = flow {
                        file.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                emit(buffer.copyOf(size))
                            }
                        }
                    }.flowOn(Dispatchers.IO)
                    marketplace { installVsix(contents) }.awaitOperation()
                } ?: return
                val extension = installed.extension
                val message = if (installed.previousVersion == null) {
                    "Installed ${extension.id} ${extension.version}."
                } else "Replaced ${extension.id} ${installed.previousVersion} with ${extension.version}."
                notify(project, message, NotificationType.INFORMATION)
            } catch (_: ProcessCanceledException) {
                // Backend staging is cleaned when the transfer is canceled.
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                notify(project, "Extension installation failed: ${error.message ?: error.javaClass.simpleName}", NotificationType.ERROR)
            }
        }

        private fun notify(project: Project?, text: String, type: NotificationType) {
            if (project?.isDisposed == true) return
            val escaped = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text)
            NotificationGroupManager.getInstance().getNotificationGroup("CodeBridge")
                .createNotification(escaped, type).notify(project)
        }
    }
}
