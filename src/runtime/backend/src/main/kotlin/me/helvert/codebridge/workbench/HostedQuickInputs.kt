package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickInputEventDto
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Quick picks and input boxes waiting for the user, kept on the backend and drawn by the frontend,
 * as [HostedMessages] are: in split mode a popup the backend opens has no screen to land on, so
 * the question waits in [pending] until a client answers it.
 *
 * A [QuickInputDto.live] question is [open]ed instead: it stays in [pending], changed in place as
 * its asker changes it, and what the user does in it goes to its asker until it is closed.
 */
@Service(Service.Level.PROJECT)
class HostedQuickInputs {

    /** What the user did: the positions chosen in a pick, or the value typed. */
    data class Answer(val chosen: List<Int>?, val value: String?)

    private val ids = AtomicLong()
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<Answer?>>()
    private val shown = MutableStateFlow<List<QuickInputDto>>(emptyList())

    /** Questions not yet answered, oldest first. */
    val pending: StateFlow<List<QuickInputDto>> = shown.asStateFlow()

    /** Asks [question], whose id is assigned here, until it is answered or [Asked.withdraw]n. */
    fun ask(question: QuickInputDto): Asked {
        val asked = Asked(ids.incrementAndGet())
        waiting[asked.id] = asked.reply
        shown.update { it + question.copy(id = asked.id) }
        return asked
    }

    /** A question on screen, until the user answers it or its asker takes it back. */
    inner class Asked internal constructor(internal val id: Long) {
        internal val reply = CompletableDeferred<Answer?>()

        /**
         * What the user did; null when they dismissed it, or it was withdrawn. However the wait
         * ends -- the extension's token, a launch cancelled -- the question goes off screen with it.
         */
        suspend fun answer(): Answer? = try {
            reply.await()
        } finally {
            this@HostedQuickInputs.withdraw(id)
        }

        /** Takes it off screen, answered as dismissed. */
        fun withdraw() {
            reply.complete(null)
            this@HostedQuickInputs.withdraw(id)
        }
    }

    /** A [QuickInputDto.live] question on screen, as its asker changes and finally closes it. */
    inner class Live internal constructor(val id: Long) {
        /** Shows [question] in place of what was shown. */
        fun update(question: QuickInputDto) {
            val changed = question.copy(id = id, live = true)
            shown.update { questions -> questions.map { if (it.id == id) changed else it } }
        }

        /** Takes it off screen; nothing more the user does reaches its asker. */
        fun close() {
            listeners.remove(id)
            withdraw(id)
        }
    }

    private val listeners = ConcurrentHashMap<Long, (QuickInputEventDto) -> Unit>()

    /** Shows [question] until it is [Live.close]d, telling [events] what the user does in it. */
    fun open(question: QuickInputDto, events: (QuickInputEventDto) -> Unit): Live {
        val id = ids.incrementAndGet()
        listeners[id] = events
        shown.update { it + question.copy(id = id, live = true) }
        return Live(id)
    }

    /** What the user did in live question [id]; closing it closes it here too. */
    fun changed(id: Long, event: QuickInputEventDto) {
        val listener =
            if (event.kind == QuickInputEventDto.HIDE) listeners.remove(id)?.also { withdraw(id) } else listeners[id]
        listener?.invoke(event)
    }

    /** The frontend's answer; the first one wins. */
    fun answer(id: Long, chosen: List<Int>?, value: String?) {
        val reply = waiting.remove(id) ?: return withdraw(id)
        reply.complete(if (chosen == null && value == null) null else Answer(chosen, value))
    }

    private fun withdraw(id: Long) {
        waiting.remove(id)
        shown.update { questions -> questions.filterNot { it.id == id } }
    }

    companion object {
        fun getInstance(project: Project): HostedQuickInputs = project.service()
    }
}
