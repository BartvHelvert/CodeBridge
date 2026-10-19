package me.helvert.codebridge.messages

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.project.projectId
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.MessageDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.time.Duration.Companion.milliseconds

/** Starts showing a project's extension messages as soon as it opens. */
internal class CodeBridgeMessagesActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeMessages>().start()
}

/**
 * `window.showInformationMessage` and friends, drawn where the user is.
 *
 * In the frontend because in split mode that is the window in front of the user; a notification
 * raised on the backend has no client to land in. A plain message becomes a balloon, one with items
 * gets a button per item, and a modal one a dialog -- VS Code's three shapes, in IntelliJ's terms.
 *
 * Whatever the user does is sent back as the answer, including closing it without choosing, since
 * the extension is waiting on that promise.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeMessages(private val project: Project, private val scope: CoroutineScope) {

    private val started = AtomicBoolean()
    private val shown = ConcurrentHashMap.newKeySet<Long>()
    private val answered = ConcurrentHashMap.newKeySet<Long>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Reconnects like the status bar: a backend restart ends the stream, and a question
            // still waiting must be shown again once it is back.
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().messages(project.projectId()).collect { messages ->
                        val fresh = messages.filter { shown.add(it.id) }
                        if (fresh.isNotEmpty()) withContext(Dispatchers.EDT) { fresh.forEach(::show) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension message stream; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private fun show(message: MessageDto) {
        if (project.isDisposed) return
        if (message.modal) dialog(message) else balloon(message)
    }

    /**
     * A modal message: a dialog, answered before anything else happens.
     *
     * VS Code gives a modal message a Cancel button unless one of the items is the close
     * affordance, and closing the dialog answers with that item -- or with nothing.
     */
    private fun dialog(message: MessageDto) {
        val buttons = message.items.ifEmpty { listOf("OK") }.toMutableList()
        if (message.items.isNotEmpty() && message.cancelIndex == null) buttons += "Cancel"
        val body = listOfNotNull(message.text, message.detail).joinToString("\n\n")
        // Over the project's own window. Given the project alone, the platform picks a parent from
        // focus, and when the browser has it -- a link opened there -- the dialog lands at the
        // screen's top left.
        val frame = WindowManager.getInstance().getFrame(project)
        val chosen = if (frame != null) Messages.showDialog(
            frame.rootPane, body, message.source ?: "VS Code extension",
            buttons.toTypedArray(), 0, icon(message.severity)
        ) else Messages.showDialog(
            project, body, message.source ?: "VS Code extension",
            buttons.toTypedArray(), 0, icon(message.severity)
        )
        answer(message.id, chosen.takeIf { it in message.items.indices } ?: message.cancelIndex)
    }

    /** A balloon, with a button per item; letting it expire answers "nothing chosen". */
    private fun balloon(message: MessageDto) {
        val content = listOfNotNull(message.text, message.detail)
            .joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(message.source.orEmpty(), content, type(message.severity))
        message.items.forEachIndexed { index, title ->
            // Not `createSimpleExpiring`: that expires the balloon before running the action, so the
            // dismissal below would answer first and every choice would reach the extension as none.
            notification.addAction(NotificationAction.create(title) { _, shown ->
                answer(message.id, index)
                shown.expire()
            })
        }
        notification.whenExpired { answer(message.id, null) }
        notification.notify(project)
        // Nothing to choose: the backend only needs to know it was shown, to stop offering it.
        if (message.items.isEmpty()) answer(message.id, null)
    }

    /** The first answer per message wins; a balloon's button answers before its expiry reports a dismissal. */
    private fun answer(id: Long, choice: Int?) {
        if (!answered.add(id)) return
        scope.launch {
            runCatching { CodeBridgeApi.getInstance().answerMessage(project.projectId(), id, choice) }
                .onFailure {
                    if (it is CancellationException) throw it else LOG.warn(
                        "Could not answer message $id",
                        it
                    )
                }
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeMessages::class.java)

        /** Declared in `CodeBridge.frontend.xml`. */
        private const val GROUP = "CodeBridge"

        private const val RETRY_MILLIS = 2_000L

        private fun type(severity: String) = when (severity) {
            "error" -> NotificationType.ERROR
            "warning" -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }

        private fun icon(severity: String): Icon = when (severity) {
            "error" -> AllIcons.General.ErrorDialog
            "warning" -> AllIcons.General.WarningDialog
            else -> AllIcons.General.InformationDialog
        }
    }
}
