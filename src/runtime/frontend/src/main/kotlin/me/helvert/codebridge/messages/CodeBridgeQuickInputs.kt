package me.helvert.codebridge.messages

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.project.projectId
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickInputEventDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.awt.Component
import java.awt.Point
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPasswordField
import javax.swing.text.JTextComponent
import kotlin.time.Duration.Companion.milliseconds

/** Starts answering a project's extension quick picks and input boxes as soon as it opens. */
internal class CodeBridgeQuickInputsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeQuickInputs>().start()
}

/**
 * `window.showQuickPick` and `window.showInputBox`, and the pickers an extension builds itself,
 * drawn where the user is. A single choice is a list popup at the caret, like the chooser an
 * intention offers, with speed search; by the status bar item or view button whose click asked for
 * it (see [anchorNextPick]); with no editor in front of the user it is centred in the window instead.
 * A picker the extension drives itself is a [CodeBridgeLiveQuickPick], placed the same way. A choice of several is a dialog ([CodeBridgeQuickPickDialog]), since a popup has no
 * good way to tick more than one, and an input box is an input dialog.
 *
 * In the frontend for the reason [CodeBridgeMessages] is: a popup the backend opens has no screen in split
 * mode. Whatever the user does goes back as the answer, dismissing it included, since the extension
 * is waiting on it. A question the backend withdraws -- its host stopped -- is closed unanswered.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeQuickInputs(private val project: Project, private val scope: CoroutineScope) {

    private val started = java.util.concurrent.atomic.AtomicBoolean()
    private val shown = ConcurrentHashMap.newKeySet<Long>()
    private val answered = ConcurrentHashMap.newKeySet<Long>()
    /** How to close each open picker, unanswered, if the backend withdraws its question. */
    private val closers = ConcurrentHashMap<Long, () -> Unit>()

    /** The live pickers on screen, by question; touched on the EDT only. */
    private val livePicks = HashMap<Long, CodeBridgeLiveQuickPick>()

    /** What the user did in live pickers, sent one at a time so that the extension hears it in order. */
    private val liveEvents = Channel<Pair<Long, QuickInputEventDto>>(Channel.UNLIMITED)

    /** See [anchorNextPick]; touched on the EDT only. */
    private var anchor: Anchor? = null

    private class Anchor(val component: () -> JComponent?, val above: Boolean, val since: Long)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            for ((id, event) in liveEvents) {
                runCatching { CodeBridgeApi.getInstance().changeQuickInput(project.projectId(), id, event) }
                    .onFailure {
                        if (it is CancellationException) throw it else LOG.warn(
                            "Could not report quick input $id",
                            it
                        )
                    }
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().quickInputs(project.projectId()).collect { questions ->
                        val live = questions.mapTo(HashSet()) { it.id }
                        closers.keys.filter { it !in live }.forEach { id ->
                            answered.add(id)
                            // Under any modality: the question being closed is itself a modal dialog.
                            closers.remove(id)?.let { close ->
                                scope.launch(
                                    Dispatchers.EDT + ModalityState.any().asContextElement()
                                ) { close() }
                            }
                        }
                        // Each on its own: an input dialog is modal, and the stream must keep flowing past it.
                        questions.forEach { question ->
                            if (shown.add(question.id)) scope.launch(Dispatchers.EDT) { show(question) }
                            else if (question.live) scope.launch(Dispatchers.EDT) {
                                livePicks[question.id]?.update(
                                    question
                                )
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension quick input stream; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private fun show(question: QuickInputDto) {
        if (project.isDisposed || question.id in answered) return
        when {
            question.kind == QuickInputDto.INPUT -> input(question)
            question.live -> livePick(question)
            else -> pick(question)
        }
    }

    /** A picker the extension drives while it is open; see [CodeBridgeLiveQuickPick]. */
    private fun livePick(question: QuickInputDto) {
        val id = question.id
        val pick = CodeBridgeLiveQuickPick(question) { event ->
            if (event.kind == QuickInputEventDto.HIDE) {
                livePicks.remove(id)
                closers.remove(id)
                answered.add(id)
            }
            liveEvents.trySend(id to event)
        }
        livePicks[id] = pick
        closers[id] = {
            livePicks.remove(id)
            pick.close()
        }
        place(pick.popup)
    }

    private fun pick(question: QuickInputDto) {
        if (question.items.none { !it.separator }) {
            Messages.showInfoMessage(project, "There is nothing to choose from.", question.title ?: question.placeholder ?: "Select")
            return answer(question.id, null, null)
        }
        if (!question.canPickMany) return popup(question)
        val dialog = CodeBridgeQuickPickDialog(project, question)
        closers[question.id] = { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        val accepted = dialog.showAndGet()
        closers.remove(question.id)
        answer(question.id, dialog.chosen.takeIf { accepted && it.isNotEmpty() }, null)
    }

    /**
     * One choice, as a list popup. Not a [com.intellij.modcommand.ModChooseAction], which draws the
     * same list: that runs its actions on the backend, picks a lone action without asking, and says
     * nothing when it is dismissed -- and the extension is waiting for `undefined` then.
     */
    private fun popup(question: QuickInputDto) {
        val choices = question.items.withIndex().filter { !it.value.separator }
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle(question.title ?: question.placeholder ?: question.prompt ?: "Select")
            .setRenderer(QuickItemRenderer())
            // No filter field, but typing still narrows the list, as in any IntelliJ list popup.
            .setNamerForFiltering { (_, item) -> listOfNotNull(item.label, item.description, item.detail).joinToString(" ") }
            .setVisibleRowCount(VISIBLE_ROWS)
            .setRequestFocus(true)
            .setMovable(true)
            .apply { choices.firstOrNull { it.value.picked }?.let { setSelectedValue(it, true) } }
            .setItemChosenCallback { answer(question.id, listOf(it.index), null) }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    closers.remove(question.id)
                    // A chosen item is answered by the callback, which may run after this.
                    if (!event.isOk) answer(question.id, null, null)
                }
            })
            .createPopup()
        closers[question.id] = { popup.cancel() }
        place(popup)
    }

    /** By what the user clicked to ask for it, else at the caret, else in the middle of the window. */
    private fun place(popup: JBPopup) {
        val anchor = takeAnchor()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (anchor != null) {
            val (component, above) = anchor
            if (above) popup.show(RelativePoint(component, Point(0, -popup.content.preferredSize.height)))
            else popup.showUnderneathOf(component)
        }
        else if (editor != null && editor.component.isShowing) popup.showInBestPositionFor(editor)
        else popup.showCenteredInCurrentWindow(project)
    }

    /**
     * Where the user just clicked to ask for a picker: a status bar item, whose command opens one --
     * the Java extension's status menu, say -- or a button in a view's welcome content. The picker
     * then opens [above] or under it, as VS Code's menu-like pickers stay near what opened them,
     * rather than at a caret the user is not looking at.
     */
    fun anchorNextPick(above: Boolean = true, component: () -> JComponent?) {
        anchor = Anchor(component, above, System.currentTimeMillis())
    }

    /** The anchor a click left, and whether the picker goes above it, if it is recent and still on screen; used once. */
    private fun takeAnchor(): Pair<JComponent, Boolean>? {
        val anchor = anchor ?: return null
        this.anchor = null
        if (System.currentTimeMillis() - anchor.since > ANCHOR_MILLIS) return null
        return anchor.component()?.takeIf { it.isShowing }?.let { it to anchor.above }
    }

    /**
     * A value to type. A dialog the extension can close, as it closes VS Code's input box: a sign-in
     * offers one for pasting the code by hand and closes it once the browser's redirect brings it.
     */
    private fun input(question: QuickInputDto) {
        val message = listOfNotNull(question.problem, question.prompt ?: question.placeholder).joinToString("\n").ifEmpty { "Value:" }
        val title = question.title ?: "Input"
        val icon = if (question.problem != null) Messages.getErrorIcon() else null
        val frame = WindowManager.getInstance().getFrame(project)?.rootPane
        val dialog = when {
            question.password && frame != null -> PasswordDialog(frame, message, title, icon)
            question.password -> PasswordDialog(project, message, title, icon)
            frame != null -> Messages.InputDialog(frame, message, title, icon, question.value, null)
            else -> Messages.InputDialog(project, message, title, icon, question.value, null)
        }
        closers[question.id] = { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
        dialog.show()
        closers.remove(question.id)
        answer(question.id, null, dialog.inputString)
    }

    /** [Messages.showPasswordDialog]'s dialog, which the platform keeps to itself. */
    private class PasswordDialog : Messages.InputDialog {
        constructor(parent: Component, message: String, title: String, icon: Icon?) : super(
            parent,
            message,
            title,
            icon,
            null,
            null
        )

        constructor(project: Project, message: String, title: String, icon: Icon?) : super(
            project,
            message,
            title,
            icon,
            null,
            null
        )

        override fun createTextFieldComponent(): JTextComponent = JPasswordField(INPUT_DIALOG_COLUMNS)
    }

    /** The first answer per question wins. */
    private fun answer(id: Long, chosen: List<Int>?, value: String?) {
        if (!answered.add(id)) return
        scope.launch {
            runCatching { CodeBridgeApi.getInstance().answerQuickInput(project.projectId(), id, chosen, value) }
                .onFailure { if (it is CancellationException) throw it else LOG.warn("Could not answer quick input $id", it) }
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeQuickInputs::class.java)
        private const val RETRY_MILLIS = 2_000L
        private const val VISIBLE_ROWS = 12

        /** How long after a click a picker still counts as its answer; an extension may first ask its server. */
        private const val ANCHOR_MILLIS = 5_000L
    }
}
