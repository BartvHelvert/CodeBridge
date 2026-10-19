package me.helvert.codebridge.language

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionSkipTextElement
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.UserDataHolderEx
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import me.helvert.codebridge.exthost.protocol.payload.CodeActions
import me.helvert.codebridge.exthost.protocol.payload.InlineCompletions
import me.helvert.codebridge.exthost.protocol.payload.Position
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Inline completions -- what an extension proposes to type next -- drawn as the IDE's own ghost
 * text, accepted with Tab and cycled between as its own suggestions are.
 *
 * Registered on the backend only: a split-mode client asks the backend's providers itself and draws
 * their answers. Each item is a variant, in provider and then item order, as VS Code orders them.
 * The lists the answers came in are held by [CodeBridgeInlineCompletionLists] until the suggestion is gone.
 */
internal class CodeBridgeInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id: InlineCompletionProviderID = ID

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val editor = when (event) {
            is InlineCompletionEvent.DocumentChange -> {
                if (event.typing is TypingEvent.PairedEnclosureInsertion) {
                    if (completesInline(event.editor)) askAgain(event.editor)
                    return false
                }
                event.editor
            }

            is InlineCompletionEvent.DirectCall -> event.editor
            is InlineCompletionEvent.Backspace -> event.editor
            is InlineCompletionEvent.TemplateInserted -> event.editor
            is InlineCompletionEvent.SuggestionInserted -> event.editor
            is InlineCompletionEvent.ManualCall -> event.editor
            else -> return false
        }
        return completesInline(editor)
    }

    private fun completesInline(editor: Editor): Boolean {
        val project = editor.project ?: return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return HostedLanguageFeatures.getInstance(project).completesInline(file)
    }

    /**
     * Asks again once the bracket the editor closed after a typed one is in, as this provider's own
     * call: the closing edit ends the session the typed bracket started before its answer arrives,
     * and on a split-mode backend only a call of a named provider reaches the client, which starts
     * every session there. Typing the bracket starts no other.
     *
     * Until the call comes back as a request. The closing edit and the call reach a split-mode client
     * by separate routes, and when the call arrives first, the client takes it as an update of the
     * typed bracket's session, which is still alive, and ends that session without starting one --
     * on CI the ghost text never came. A call after the edit has landed starts the session.
     */
    private fun askAgain(editor: Editor) {
        val project = editor.project ?: return
        val holder = editor as? UserDataHolderEx ?: return
        val requested = holder.putUserDataIfAbsent(ASKED, AtomicInteger())
        val modality = ModalityState.stateForComponent(editor.component)
        Scope.getInstance(project).scope.launch(Dispatchers.EDT + modality.asContextElement()) {
            repeat(ASK_AGAIN_ATTEMPTS) {
                if (editor.isDisposed) return@launch
                val before = requested.get()
                val handler = InlineCompletion.getHandlerOrNull(editor) ?: return@launch
                handler.invokeEvent(InlineCompletionEvent.ManualCall(editor, ID, UserDataHolderBase()))
                delay(ASK_AGAIN_WAIT_MILLIS.milliseconds)
                if (requested.get() != before) return@launch
            }
        }
    }

    /** What the providers asked for, or VS Code's own wait where none did. */
    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        val project = request.editor.project ?: return DEFAULT_DEBOUNCE_MILLIS.milliseconds
        val file =
            FileDocumentManager.getInstance().getFile(request.document) ?: return DEFAULT_DEBOUNCE_MILLIS.milliseconds
        return (HostedLanguageFeatures.getInstance(project).inlineCompletionDebounceMillis(file)
            ?: DEFAULT_DEBOUNCE_MILLIS)
            .milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        // Heard by [askAgain]: its call reached whoever starts the sessions.
        if (request.event is InlineCompletionEvent.ManualCall) request.editor.getUserData(ASKED)?.incrementAndGet()
        val project = request.editor.project ?: return InlineCompletionSuggestion.Empty
        val file =
            FileDocumentManager.getInstance().getFile(request.document) ?: return InlineCompletionSuggestion.Empty
        val (text, caret) = readAction { request.document.immutableCharSequence.toString() to request.endOffset }
        val position =
            CodeBridgeTextEdits.rangeOf(text, TextRange(caret, caret)).let { Position(it.startLineNumber, it.startColumn) }
        val features = HostedLanguageFeatures.getInstance(project)
        val answers =
            features.inlineCompletions(file, text, position, request.event is InlineCompletionEvent.DirectCall)
        if (answers.isEmpty()) return InlineCompletionSuggestion.Empty
        val lists = CodeBridgeInlineCompletionLists.getInstance(project)
        val variants = try {
            variants(answers, text, caret).also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) {
            lists.free(answers)
            throw cancelled
        }
        if (variants.isEmpty()) {
            lists.free(answers)
            return InlineCompletionSuggestion.Empty
        }
        lists.hold(request.editor, answers)
        return object : InlineCompletionSuggestion {
            override suspend fun getVariants(): List<InlineCompletionVariant> = variants
        }
    }

    private fun variants(
        answers: List<HostedLanguageFeatures.InlineAnswer>,
        text: String,
        caret: Int
    ): List<InlineCompletionVariant> =
        answers.asSequence()
            .flatMap { answer -> answer.answer.items.map { Offered(answer.handle, it) } }
            .mapNotNull { offered ->
                val proposed = if (offered.item.snippet) Snippets.plain(offered.item.text) else offered.item.text
                val range = offered.item.range?.let { CodeBridgeTextEdits.offsets(text, it) ?: return@mapNotNull null }
                val pieces = InlineCompletionPlacement.place(text, caret, range, proposed) ?: return@mapNotNull null
                val data = UserDataHolderBase().apply { putUserData(OFFERED, offered) }
                InlineCompletionVariant.build(data, pieces.map(::element).asFlow())
            }
            .take(InlineCompletionSuggestion.MAX_VARIANTS_NUMBER)
            .toList()

    private fun element(piece: InlineCompletionPlacement.Piece): InlineCompletionElement =
        if (piece.kept) InlineCompletionSkipTextElement(piece.text) else InlineCompletionGrayTextElement(piece.text)

    /** Where asking again runs: once the closing bracket is in, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        val ID: InlineCompletionProviderID = InlineCompletionProviderID(CodeBridgeInlineCompletionProvider::class.java.name)

        /** VS Code's: its inline completions debounce is pinned to 50ms either way. */
        private const val DEFAULT_DEBOUNCE_MILLIS = 50L

        /** How many calls [askAgain] makes at most, and how long each waits to come back as a request. */
        private const val ASK_AGAIN_ATTEMPTS = 3
        private const val ASK_AGAIN_WAIT_MILLIS = 1_000L

        /** The requests made by a [InlineCompletionEvent.ManualCall] in an editor, counted for [askAgain]. */
        private val ASKED: Key<AtomicInteger> = Key.create("codebridge.inlineCompletion.asked")

        /** The item a variant shows, which the context of the session showing it carries too. */
        internal val OFFERED: Key<Offered> = Key.create("codebridge.inlineCompletion.offered")
    }

    /** One item of provider [handle]'s answer. */
    internal data class Offered(val handle: Int, val item: InlineCompletions.Item)
}

/**
 * The inline completion lists the host holds for each editor, freed when the suggestion they were
 * offered for is hidden, and the command of an accepted item, run once its text is in.
 *
 * Both are heard from the editor's inline completion handler, which in split mode is the
 * backend's: the client tells it when it hides a suggestion, and why, which here is the only sign
 * one was accepted. A list a cancelled request was still holding is freed with the next, and any
 * still held when the editor goes are freed with it. Kept on the editor, so a closed one is not.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeInlineCompletionLists(private val project: Project, private val scope: CoroutineScope) {

    private data class Held(val handle: Int, val id: Int)

    /** Keeps [answers] in the host until the suggestion shown in [editor] is hidden. */
    fun hold(editor: Editor, answers: List<HostedLanguageFeatures.InlineAnswer>) {
        val holder = editor as? UserDataHolderEx ?: return free(answers)
        listen(editor, holder)
        holder.putUserDataIfAbsent(HELD, ConcurrentHashMap.newKeySet()) += answers.map { Held(it.handle, it.answer.id) }
    }

    /** Lets [answers] go now, for a request that showed none of them. */
    fun free(answers: List<HostedLanguageFeatures.InlineAnswer>) =
        release(answers.map { Held(it.handle, it.answer.id) })

    /** Listens to [editor]'s handler, once for each handler the editor is given. */
    private fun listen(editor: Editor, holder: UserDataHolderEx) {
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        val previous = holder.getUserData(LISTENED)
        if (previous === handler || !holder.replace(LISTENED, previous, handler)) return
        // The listener rather than `InlineCompletionEventAdapter`: implementing the adapter compiles
        // an override of its internal, non-extendable `onSuggestionInitialized` into this class.
        handler.addEventListener(object : InlineCompletionEventListener {
            override fun on(event: InlineCompletionEventType) {
                if (event !is InlineCompletionEventType.Hide) return
                // Typed as any enum, so the bytecode names `Enum`, not the internal `FinishType`; the
                // only sign of acceptance in split mode (docs/platform-limitations.md).
                @Suppress("UnstableApiUsage")
                val finishType: Enum<*> = event.finishType
                hidden(editor, finishType.name == SELECTED)
            }
        })
        if (holder.replace(RELEASED, null, true)) EditorUtil.disposeWithEditor(editor) { release(take(editor)) }
    }

    private fun take(editor: Editor): List<Held> = editor.getUserData(HELD)?.let { held ->
        held.toList().also { held.removeAll(it.toSet()) }
    }.orEmpty()

    /** On the editor thread, before the session is gone -- and, when accepted, before its text is in. */
    private fun hidden(editor: Editor, selected: Boolean) {
        val lists = take(editor)
        val session = InlineCompletionSession.getOrNull(editor)
        val accepted =
            session?.takeIf { selected && it.provider.id == CodeBridgeInlineCompletionProvider.ID }
                ?.let(::offered)
        val command = accepted?.item?.command
        if (command == null) {
            release(lists)
            return
        }
        runAfterInsertion(editor.document, command) { release(lists) }
    }

    private fun offered(session: InlineCompletionSession): CodeBridgeInlineCompletionProvider.Offered? =
        session.context.getUserData(CodeBridgeInlineCompletionProvider.OFFERED)
            ?: session.capture()?.activeVariant?.data?.getUserData(CodeBridgeInlineCompletionProvider.OFFERED)

    /**
     * Runs [command] once the document has changed -- in a single process straight after this, on a
     * split-mode client's edit once it arrives -- and the host has the change, as VS Code runs it
     * after the edit. Waits a little for the change, and runs the command anyway after that.
     */
    private fun runAfterInsertion(document: Document, command: CodeActions.Command, then: () -> Unit) {
        val stamp = document.modificationStamp
        scope.launch {
            val changed = withTimeoutOrNull(SYNC_MILLIS.milliseconds) {
                while (readAction { document.modificationStamp } == stamp) delay(POLL_MILLIS.milliseconds)
            }
            if (changed == null) LOG.info("An accepted inline completion had not arrived after ${SYNC_MILLIS}ms; running its command anyway")
            project.service<HostedLanguageServers>().afterSync(document) {
                val features = HostedLanguageFeatures.getInstance(project)
                features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        features.executeCommand(command)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LOG.warn("Could not run the command of an inline completion", failure)
                    } finally {
                        then()
                    }
                }
            }
        }
    }

    private fun release(lists: List<Held>) {
        val features = HostedLanguageFeatures.getInstance(project)
        lists.forEach { features.freeInlineCompletions(it.handle, it.id) }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeInlineCompletionLists::class.java)
        private const val SYNC_MILLIS = 2_000L
        private const val POLL_MILLIS = 20L

        /**
         * The finish type of a suggestion the user accepted, by name: the `FinishType` enum is
         * internal API, and naming its constant would reference it. `Hide.finishType` itself is
         * public, and in split mode the hide the client forwards is the only sign of acceptance.
         */
        private const val SELECTED = "SELECTED"

        private val HELD: Key<MutableSet<Held>> = Key.create("codebridge.inlineCompletion.held")
        private val LISTENED: Key<Any> = Key.create("codebridge.inlineCompletion.listened")
        private val RELEASED: Key<Boolean> = Key.create("codebridge.inlineCompletion.released")

        fun getInstance(project: Project): CodeBridgeInlineCompletionLists = project.service()
    }
}

/**
 * Where VS Code's proposal goes: its text replaces a range, and the ghost text is what it adds after
 * the caret.
 *
 * The text before the caret in the range must start the proposal, as VS Code requires of ghost
 * text. The text after it must be kept in the proposal, in order -- the `)` an editor closed
 * automatically, say -- and is typed over rather than drawn. Anything else would edit the text
 * around the caret, which ghost text cannot show, and is not offered.
 */
internal object InlineCompletionPlacement {

    /** Text to draw, or text already there that the proposal keeps when [kept]. */
    data class Piece(val text: String, val kept: Boolean)

    /**
     * What [proposed] shows in [text] with the caret at [caret], replacing [range] -- or, by default,
     * the word the caret ends to the end of its line, as VS Code defaults it. Null when it does not
     * fit, or adds nothing.
     */
    fun place(text: CharSequence, caret: Int, range: TextRange?, proposed: String): List<Piece>? {
        val replaced = range ?: defaultRange(text, caret)
        if (caret !in replaced.startOffset..replaced.endOffset) return null
        val typed = text.subSequence(replaced.startOffset, caret).toString()
        if (!proposed.startsWith(typed)) return null
        val added = proposed.substring(typed.length)
        val after = text.subSequence(caret, replaced.endOffset)
        // Matched from the end, so a closing bracket the editor added is the last the proposal has.
        val kept = BooleanArray(added.length)
        var remaining = after.length
        for (index in added.indices.reversed()) {
            if (remaining == 0) break
            if (added[index] == after[remaining - 1]) {
                kept[index] = true
                remaining--
            }
        }
        if (remaining > 0 || kept.all { it }) return null
        return buildList {
            var start = 0
            for (index in 1..added.length) {
                if (index == added.length || kept[index] != kept[start]) {
                    add(Piece(added.substring(start, index), kept[start]))
                    start = index
                }
            }
        }
    }

    private fun defaultRange(text: CharSequence, caret: Int): TextRange {
        var start = caret
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        var end = caret
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        return TextRange(start, end)
    }
}
