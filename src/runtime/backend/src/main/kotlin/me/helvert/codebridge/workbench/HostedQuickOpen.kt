package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.QuickOpen
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickInputEventDto
import me.helvert.codebridge.rpc.QuickItemDto
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * `MainThreadQuickOpen`: an extension's quick picks and input boxes, asked through
 * [HostedQuickInputs] and drawn by the frontend as IntelliJ's chooser popup and input dialog.
 *
 * A single-choice picker the extension created is shown live, as VS Code shows it: with a box to
 * type in, changed in place as the extension changes it, and open until the extension hides it or
 * the user dismisses it -- an accept does not close it, so that an extension can take the choice as
 * a step, as GitLens's commit search does. Item and title buttons are not drawn. A multiple choice
 * or an input box is asked once instead, when it is visible and has something to choose from, and
 * closes on the first answer.
 */
class HostedQuickOpen(
    private val project: Project,
    private val callHost: suspend (service: String, method: String, args: String) -> String,
) {
    /** `$show` calls waiting for their items; null items is an error, or a host gone. */
    private val waiting = ConcurrentHashMap<Int, Pending>()
    private val sessions = ConcurrentHashMap<Int, Session>()

    /** Questions on screen for this host, withdrawn when it stops. */
    private val asked = ConcurrentHashMap.newKeySet<HostedQuickInputs.Asked>()

    /** Pickers shown for the extension's own sessions, and what is sent about them; ends with [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Pending {
        val items = CompletableDeferred<List<QuickOpen.Item>?>()
    }

    /** A created picker or input box, as the extension last described it. Used under its own lock. */
    private class Session(val id: Int) {
        var state: JsonObject = JsonObject(emptyMap())

        /** On screen: asked, or [live]. */
        var open = false

        /** Reported hidden since it was last shown. */
        var hidden = false

        /** A single-choice picker on screen. */
        var live: HostedQuickInputs.Live? = null

        /**
         * An input box or a many-choice picker on screen: a question asked once, withdrawn -- and so
         * taken off the client's screen -- when the extension hides or disposes it. GitLens's box for
         * pasting a sign-in code is disposed so once the browser's redirect has brought the code.
         */
        var question: HostedQuickInputs.Asked? = null
        var items: List<QuickOpen.Item> = emptyList()

        /** How often the extension has set its value and its items; see [QuickInputDto.valueRevision]. */
        var valueRevision = 0
        var itemsRevision = 0
    }

    suspend fun handle(call: MainThreadCall): MainThreadResult = try {
        when (call.method) {
            // Answered once its items have come and the user has chosen from them.
            QuickOpen.SHOW -> {
                val (instance, options) = QuickOpen.show(call.argsJson)
                val pending = Pending()
                waiting[instance] = pending
                val items = pending.items.await()
                if (items == null) MainThreadResult()
                else MainThreadResult(QuickOpen.chosen(pick(options, items), options.canPickMany))
            }

            QuickOpen.SET_ITEMS -> {
                val (instance, items) = QuickOpen.setItems(call.argsJson)
                waiting.remove(instance)?.items?.complete(items)
                MainThreadResult()
            }

            QuickOpen.SET_ERROR -> {
                waiting.remove(QuickOpen.failedInstance(call.argsJson))?.items?.complete(null)
                MainThreadResult()
            }

            QuickOpen.INPUT -> {
                val (options, validate) = QuickOpen.input(call.argsJson)
                MainThreadResult(input(options, validate)?.let { JsonPrimitive(it).toString() })
            }

            QuickOpen.CREATE_OR_UPDATE -> {
                update(call.argsJson); MainThreadResult()
            }

            QuickOpen.DISPOSE -> {
                sessions.remove(QuickOpen.disposed(call.argsJson))?.let { session ->
                    synchronized(session) {
                        // Gone: nothing to tell the extension when its question is withdrawn.
                        session.hidden = true
                        session.live?.close()
                        session.question?.withdraw()
                    }
                }
                MainThreadResult()
            }

            else -> MainThreadResult()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LOG.warn("Could not handle ${call.service}.${call.method}", error)
        throw error
    }

    /** Nobody is left to answer: every open question is answered as dismissed. */
    fun close() {
        waiting.values.forEach { it.items.complete(null) }
        waiting.clear()
        sessions.values.forEach { session -> synchronized(session) { session.live?.close() } }
        sessions.clear()
        asked.toList().forEach { it.withdraw() }
        scope.cancel()
    }

    private fun update(argsJson: String) {
        val (id, changed) = QuickOpen.update(argsJson)
        val session = sessions.computeIfAbsent(id, ::Session)
        synchronized(session) {
            val wasVisible = session.state.visible
            var state = JsonObject(session.state + changed)
            if ("value" in changed) session.valueRevision++
            if ("items" in changed) {
                session.items = QuickOpen.items(state["items"])
                session.itemsRevision++
                // Handles name the items of one list: those of the last name nothing now.
                if ("activeItems" !in changed) state = JsonObject(state - "activeItems")
            }
            session.state = state
            val live = session.live
            when {
                // `hide()`: VS Code reports it hidden, which is what a picker's owner disposes it on.
                !state.visible && wasVisible -> {
                    live?.close()
                    session.live = null
                    session.open = false
                    hidden(session)
                    session.question?.withdraw()
                }

                state.visible && !session.open -> show(session)
                live != null -> live.update(liveQuestion(session))
            }
        }
    }

    private fun show(session: Session) {
        val state = session.state
        if (state.type == "inputBox") {
            opened(session)
            // Asked now, under the session's lock as the caller holds it; answered when the user does.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val value = input(QuickOpen.inputOptions(state), validate = false, session)
                synchronized(session) {
                    session.open = false
                    if (value != null) {
                        notify(QuickOpen.ON_DID_CHANGE_VALUE, QuickOpen.changedValue(session.id, value))
                        notify(QuickOpen.ON_DID_ACCEPT, QuickOpen.session(session.id))
                    } else dismissed(session)
                }
            }
            return
        }
        val options = QuickOpen.pickOptions(state)
        if (!options.canPickMany) {
            LOG.debug("Quick pick ${session.id} shown live with ${session.items.size} item(s)")
            opened(session)
            session.live =
                HostedQuickInputs.getInstance(project).open(liveQuestion(session)) { event -> userDid(session, event) }
            return
        }
        val items = session.items
        // Shown before its items are in, busy: shown once they are, since the dialog cannot take
        // items later. One left empty is shown empty, as VS Code shows it.
        if (items.none { !it.separator } && options.busy) {
            return LOG.debug("Quick pick ${session.id} is visible; waiting for its items")
        }
        LOG.debug("Quick pick ${session.id} shown with ${items.size} item(s)")
        opened(session)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val handles = pick(options, items, session)
            synchronized(session) {
                session.open = false
                if (!handles.isNullOrEmpty()) {
                    notify(QuickOpen.ON_DID_CHANGE_ACTIVE, QuickOpen.changedItems(session.id, handles.take(1)))
                    notify(QuickOpen.ON_DID_CHANGE_SELECTION, QuickOpen.changedItems(session.id, handles))
                    notify(QuickOpen.ON_DID_ACCEPT, QuickOpen.session(session.id))
                } else dismissed(session)
            }
        }
    }

    private fun opened(session: Session) {
        session.open = true
        session.hidden = false
    }

    /**
     * The user closed it: hidden now, as the extension host takes it to be once told, so that only
     * the extension's next `show()` brings it back -- not any other change it makes meanwhile.
     */
    private fun dismissed(session: Session) {
        session.live = null
        session.open = false
        session.state = JsonObject(session.state + ("visible" to JsonPrimitive(false)))
        hidden(session)
    }

    /** A live picker as it stands, for the frontend. */
    private fun liveQuestion(session: Session): QuickInputDto {
        val options = QuickOpen.pickOptions(session.state)
        val active = QuickOpen.activeHandles(session.state).firstOrNull()
        return QuickInputDto(
            id = 0,
            kind = QuickInputDto.PICK,
            title = options.title,
            placeholder = options.placeholder,
            prompt = options.prompt,
            items = session.items.map(::itemDto),
            value = options.value,
            busy = options.busy,
            matchOnDescription = options.matchOnDescription,
            matchOnDetail = options.matchOnDetail,
            ignoreFocusOut = options.ignoreFocusOut,
            active = active?.let { handle -> session.items.indexOfFirst { it.handle == handle }.takeIf { it >= 0 } },
            valueRevision = session.valueRevision,
            itemsRevision = session.itemsRevision,
        )
    }

    /** What the user did in [session]'s live picker, told to the extension as VS Code tells it. */
    private fun userDid(session: Session, event: QuickInputEventDto) = synchronized(session) {
        if (session.live == null) return@synchronized
        // Positions in the items the user saw; null if the extension has replaced those since.
        val handles = event.chosen.mapNotNull { session.items.getOrNull(it)?.handle }
            .takeIf { event.itemsRevision == session.itemsRevision }
        when (event.kind) {
            QuickInputEventDto.VALUE -> {
                val value = event.value ?: return@synchronized
                // The extension host keeps what was typed as its value, without setting it back.
                session.state = JsonObject(session.state + ("value" to JsonPrimitive(value)))
                notify(QuickOpen.ON_DID_CHANGE_VALUE, QuickOpen.changedValue(session.id, value))
            }

            QuickInputEventDto.ACTIVE -> if (!handles.isNullOrEmpty()) {
                notify(QuickOpen.ON_DID_CHANGE_ACTIVE, QuickOpen.changedItems(session.id, handles))
            }

            QuickInputEventDto.ACCEPT -> {
                // Chosen from a list the extension has replaced since: not what the user meant.
                if (handles == null) return@synchronized
                if (handles.isNotEmpty()) {
                    notify(QuickOpen.ON_DID_CHANGE_ACTIVE, QuickOpen.changedItems(session.id, handles))
                    notify(QuickOpen.ON_DID_CHANGE_SELECTION, QuickOpen.changedItems(session.id, handles))
                }
                notify(QuickOpen.ON_DID_ACCEPT, QuickOpen.session(session.id))
            }

            QuickInputEventDto.HIDE -> dismissed(session)
        }
    }

    private fun hidden(session: Session) {
        if (session.hidden) return
        session.hidden = true
        notify(QuickOpen.ON_DID_HIDE, QuickOpen.session(session.id))
    }

    private fun itemDto(item: QuickOpen.Item) =
        QuickItemDto(item.label, item.description, item.detail, item.picked, item.separator, item.alwaysShow)

    /** Sent now, in order with what follows; the answer is only looked at for a failure. */
    private fun notify(method: String, args: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                callHost(QuickOpen.EXT_HOST, method, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not send $method", failure)
            }
        }
    }

    /** The handles chosen, in list order; null when the user dismissed the picker. */
    private suspend fun pick(
        options: QuickOpen.PickOptions,
        items: List<QuickOpen.Item>,
        session: Session? = null
    ): List<Int>? {
        val question = QuickInputDto(
            id = 0,
            kind = QuickInputDto.PICK,
            title = options.title,
            placeholder = options.placeholder,
            prompt = options.prompt,
            items = items.map(::itemDto),
            canPickMany = options.canPickMany,
        )
        return ask(question, session)?.chosen?.mapNotNull { items.getOrNull(it)?.handle }?.takeIf { it.isNotEmpty() }
    }

    /**
     * The value typed, or null when the box was dismissed. A [validate]d value is checked by the
     * extension once accepted, and the box asked again with its message while it refuses.
     */
    private suspend fun input(
        options: QuickOpen.InputOptions,
        validate: Boolean,
        session: Session? = null
    ): String? {
        var value = options.value.orEmpty()
        var problem: String? = null
        while (true) {
            val question = QuickInputDto(
                id = 0, kind = QuickInputDto.INPUT, title = options.title, placeholder = options.placeholder,
                prompt = options.prompt, value = value, password = options.password, problem = problem,
            )
            val typed = ask(question, session)?.value ?: return null
            if (!validate) return typed
            // An extension that does not answer, or fails to, is taken to have nothing against it.
            val reply = try {
                withTimeoutOrNull(VALIDATE_SECONDS.seconds) {
                    callHost(QuickOpen.EXT_HOST, QuickOpen.VALIDATE_INPUT, QuickOpen.validateInput(typed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not validate an input", failure)
                null
            }
            problem = QuickOpen.validationError(reply) ?: return typed
            value = typed
        }
    }

    /**
     * Asked on the client's screen, through the frontend; withdrawn if the host goes away first, or
     * when the extension hides or disposes [session].
     */
    private suspend fun ask(question: QuickInputDto, session: Session? = null): HostedQuickInputs.Answer? {
        val asking = HostedQuickInputs.getInstance(project).ask(question)
        asked += asking
        session?.question = asking
        try {
            return asking.answer()
        } finally {
            asked -= asking
            if (session != null && session.question === asking) session.question = null
        }
    }

    private val JsonObject.visible: Boolean get() = (this["visible"] as? JsonPrimitive)?.booleanOrNull == true
    private val JsonObject.type: String? get() = (this["type"]?.takeUnless { it is JsonNull } as? JsonPrimitive)?.content

    private companion object {
        val LOG = Logger.getInstance(HostedQuickOpen::class.java)

        /** How long an extension has to say what is wrong with an input before it is taken as is. */
        const val VALIDATE_SECONDS = 10L
    }
}
