package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.rpc.MessageDto
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `MainThreadMessageService`, and the plugin's own messages about extensions, for the user to see.
 *
 * Until this existed every `window.showInformationMessage` was dropped: rust-analyzer's "Extension
 * Info" ran and said nothing, and an extension asking a question waited for an answer nobody was
 * shown. The backend only keeps the messages; the frontend draws them, because in split mode that
 * is where the user is, and sends the choice back through [answer].
 *
 * A message stays in [pending] until the frontend answers it, so a client that connects after it
 * was sent -- or reconnects -- still shows it. One with no items is answered for the extension at
 * once, as VS Code does; it waits in [pending] only to be displayed.
 */
@Service(Service.Level.PROJECT)
class HostedMessages {

    private val ids = AtomicLong()
    private val waiting = ConcurrentHashMap<Long, Waiting>()
    private val shown = MutableStateFlow<List<MessageDto>>(emptyList())

    /** Item handles in the order the frontend numbers them, and the extension's pending reply. */
    private class Waiting(val handles: List<Int>, val reply: CompletableDeferred<Int?>)

    /** Messages not yet answered, oldest first. */
    val pending: StateFlow<List<MessageDto>> = shown.asStateFlow()

    /** Handles one `MainThreadMessageService` call; its reply is the chosen item's handle. */
    suspend fun handle(call: MainThreadCall): MainThreadResult {
        if (call.method != Messages.SHOW) return MainThreadResult()
        val message = try {
            Messages.message(call.argsJson)
        } catch (failure: Exception) {
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
            return MainThreadResult()
        }
        return MainThreadResult(Messages.chosen(show(message)))
    }

    /**
     * Shows [message], answering with the handle of the item chosen, or null for none.
     *
     * Also how the plugin reports something about an extension -- a command that failed, a setting
     * it changed -- so those read like the extension's own messages and sit in the same place.
     */
    suspend fun show(message: Messages.Message): Int? = post(message).await()

    /** Puts [message] up for the frontend, with the answer still to come. */
    private fun post(message: Messages.Message): CompletableDeferred<Int?> {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Int?>()
        val handles = message.items.map { it.handle }
        waiting[id] = Waiting(handles, reply)
        shown.update {
            it + MessageDto(
                id = id,
                severity = message.severity.name.lowercase(),
                text = message.text,
                detail = message.detail,
                modal = message.modal,
                source = message.source,
                items = message.items.map { item -> item.title },
                cancelIndex = message.items.indexOfFirst { item -> item.closeAffordance }
                    .takeIf { index -> index >= 0 },
            )
        }
        // Nothing to choose, so nothing to wait for: VS Code resolves these straight away too.
        if (handles.isEmpty()) reply.complete(null)
        return reply
    }

    /** Shows a plain message on the plugin's behalf, attributed to [source] when there is one. */
    fun notify(severity: Messages.Severity, text: String, source: String? = null, detail: String? = null) {
        post(Messages.Message(severity, text, detail, modal = false, source = source, items = emptyList()))
    }

    /**
     * The frontend's answer to message [id]: an index into its items, or null for a dismissal.
     *
     * The first answer wins; a second client answering the same message, or one answering after
     * the host went away, changes nothing.
     */
    fun answer(id: Long, choice: Int?) {
        shown.update { messages -> messages.filterNot { it.id == id } }
        val entry = waiting.remove(id) ?: return
        entry.reply.complete(choice?.let(entry.handles::getOrNull))
    }

    /** Nobody is left to answer these; a question the host was waiting on is answered "dismissed". */
    fun hostStopped() {
        waiting.keys.toList().forEach { id -> waiting.remove(id)?.reply?.complete(null) }
        shown.value = emptyList()
    }

    companion object {
        private val LOG = Logger.getInstance(HostedMessages::class.java)

        fun getInstance(project: Project): HostedMessages = project.service()
    }
}
