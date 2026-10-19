package me.helvert.codebridge.language

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/**
 * The code actions offered at the caret, asked for once however many slots want them.
 *
 * Every slot below asks whether it has an action, and the platform asks each of them in turn: one
 * request per slot would put twenty round trips in front of the intention menu. So the last answer
 * is kept, keyed by the file, the document's modification stamp and the selection, and a new one
 * is asked for only when one of those moves. Replacing an answer releases it in the host, which
 * otherwise holds every list it was ever asked for.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeCodeActionCache(private val project: Project) : Disposable {

    private data class Key(val file: VirtualFile, val stamp: Long, val range: TextRange)

    private var key: Key? = null
    private var offered = HostedLanguageFeatures.OfferedActions.NONE

    /**
     * The actions an extension offers for [editor]'s selection, or at its caret.
     *
     * Never asks from the editor thread, where a slow language server would freeze the IDE; there
     * it answers from the cache, which a background availability pass has normally just filled.
     */
    fun actions(editor: Editor, file: VirtualFile): List<HostedLanguageFeatures.OfferedAction> {
        val features = HostedLanguageFeatures.getInstance(project)
        val document = editor.document
        val selection = editor.selectionModel
        val range = if (selection.hasSelection()) TextRange(selection.selectionStart, selection.selectionEnd)
            else TextRange.from(editor.caretModel.offset, 0)
        val wanted = Key(file, document.modificationStamp, range)
        synchronized(this) {
            if (key == wanted) return usable()
        }
        if (ApplicationManager.getApplication().isDispatchThread) return emptyList()
        val fresh = features.codeActions(file, CodeBridgeTextEdits.rangeOf(document.immutableCharSequence, range))
        val stale = synchronized(this) {
            val previous = offered
            key = wanted
            offered = fresh
            previous
        }
        features.releaseCodeActions(stale)
        return synchronized(this) { usable() }
    }

    /**
     * Hands over the current answer, which [invalidate] and a later [actions] will then not release.
     *
     * For an action about to run. The host deletes an action's command along with the list it came
     * in, and a language server's fix is usually exactly such a command -- rust-analyzer's
     * `rust-analyzer.resolveCodeAction` wraps every assist -- so releasing the list before the
     * command has run leaves the host nothing to execute. Whoever takes it releases it afterwards.
     */
    fun take(): HostedLanguageFeatures.OfferedActions = synchronized(this) {
        val taken = offered
        key = null
        offered = HostedLanguageFeatures.OfferedActions.NONE
        taken
    }

    /** Forgets and releases the current answer. */
    fun invalidate() = HostedLanguageFeatures.getInstance(project).releaseCodeActions(take())

    /** What can be chosen: an extension may offer an action only to say why it will not run it. */
    private fun usable() = offered.actions.filter { it.action.disabled == null }

    override fun dispose() = invalidate()

    companion object {
        fun getInstance(project: Project): CodeBridgeCodeActionCache = project.service()
    }
}

/**
 * One slot in the intention menu, filled by whichever code action is at its index.
 *
 * IntelliJ's intentions are registered up front, one class each; an extension's code actions exist
 * only once it is asked. So a fixed number of slots is registered and each takes the action at its
 * position in the current answer -- the arrangement IntelliJ's own LSP client uses. Actions beyond
 * the last slot are not shown.
 *
 * Invoked outside a write action, because choosing an action may first ask the extension for its
 * edit, and the edit is then applied as one undoable command. A command the action carries is run
 * afterwards and not waited on: it usually calls straight back to apply an edit of its own.
 */
internal abstract class CodeBridgeCodeActionIntention(private val index: Int) : IntentionAction, PriorityAction, Iconable {

    @Volatile private var current: HostedLanguageFeatures.OfferedAction? = null

    override fun getFamilyName(): String = "VS Code extension action"

    override fun getText(): String = current?.action?.title ?: familyName

    override fun isAvailable(project: Project, editor: Editor?, psiFile: PsiFile?): Boolean {
        current = null
        if (editor == null) return false
        val file = psiFile?.virtualFile ?: return false
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.hasCodeActionProviders(file)) return false
        current = CodeBridgeCodeActionCache.getInstance(project).actions(editor, file).getOrNull(index)
        return current != null
    }

    /**
     * Preferred fixes first, then other quick fixes, then refactorings and source actions.
     *
     * VS Code puts the preferred fix at the top of its menu and marks it for the auto-fix shortcut;
     * the nearest IntelliJ has is priority within the menu.
     */
    override fun getPriority(): PriorityAction.Priority {
        val action = current?.action ?: return PriorityAction.Priority.NORMAL
        return when {
            action.preferred -> PriorityAction.Priority.HIGH
            action.kind?.startsWith(QUICK_FIX) == true -> PriorityAction.Priority.NORMAL
            else -> PriorityAction.Priority.LOW
        }
    }

    /**
     * A light bulb for a quick fix, nothing for the rest -- IntelliJ's own arrangement, where only
     * an action that fixes a problem carries a bulb and plain intentions stand bare.
     *
     * Without this every slot is drawn as a plain intention, quick fixes included, since that is
     * where the platform files them.
     */
    override fun getIcon(flags: Int): Icon? =
        if (current?.action?.kind?.startsWith(QUICK_FIX) == true) AllIcons.Actions.IntentionBulb else null

    override fun startInWriteAction(): Boolean = false

    /** No preview: producing one would mean asking the extension, and running its command. */
    override fun generatePreview(project: Project, editor: Editor, psiFile: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, psiFile: PsiFile?) {
        val offered = current ?: return
        val features = HostedLanguageFeatures.getInstance(project)
        val title = offered.action.title
        // Taken out of the cache before anything else: applying the edit changes the document, and
        // the next availability pass would otherwise release the list this action's command lives in.
        val list = CodeBridgeCodeActionCache.getInstance(project).take()
        var releasedByCommand = false
        try {
            val resolved = runWithModalProgressBlocking(project, title) { features.resolveCodeAction(offered) }
            val edit = resolved.edit
            if (edit != null && !CodeBridgeWorkspaceEdits.apply(project, edit, title, features::isCurrent)) {
                if (editor != null) HintManager.getInstance().showErrorHint(editor, "'$title' no longer applies to this code")
                return
            }
            val command = resolved.command ?: return
            releasedByCommand = true
            features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    features.executeCommand(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.warn("The command behind '$title' (${command.id}) failed", failure)
                } finally {
                    features.releaseCodeActions(list)
                }
            }
        } finally {
            if (!releasedByCommand) features.releaseCodeActions(list)
        }
    }

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeCodeActionIntention::class.java)

        /** `quickfix` and its sub-kinds, `quickfix.import` and the like. */
        const val QUICK_FIX = "quickfix"
    }
}

// One class per slot, because the platform registers intentions by class. Twenty covers what a
// language server usually offers at one position; rust-analyzer's assists are the long tail.
internal class CodeBridgeCodeAction0 : CodeBridgeCodeActionIntention(0)
internal class CodeBridgeCodeAction1 : CodeBridgeCodeActionIntention(1)
internal class CodeBridgeCodeAction2 : CodeBridgeCodeActionIntention(2)
internal class CodeBridgeCodeAction3 : CodeBridgeCodeActionIntention(3)
internal class CodeBridgeCodeAction4 : CodeBridgeCodeActionIntention(4)
internal class CodeBridgeCodeAction5 : CodeBridgeCodeActionIntention(5)
internal class CodeBridgeCodeAction6 : CodeBridgeCodeActionIntention(6)
internal class CodeBridgeCodeAction7 : CodeBridgeCodeActionIntention(7)
internal class CodeBridgeCodeAction8 : CodeBridgeCodeActionIntention(8)
internal class CodeBridgeCodeAction9 : CodeBridgeCodeActionIntention(9)
internal class CodeBridgeCodeAction10 : CodeBridgeCodeActionIntention(10)
internal class CodeBridgeCodeAction11 : CodeBridgeCodeActionIntention(11)
internal class CodeBridgeCodeAction12 : CodeBridgeCodeActionIntention(12)
internal class CodeBridgeCodeAction13 : CodeBridgeCodeActionIntention(13)
internal class CodeBridgeCodeAction14 : CodeBridgeCodeActionIntention(14)
internal class CodeBridgeCodeAction15 : CodeBridgeCodeActionIntention(15)
internal class CodeBridgeCodeAction16 : CodeBridgeCodeActionIntention(16)
internal class CodeBridgeCodeAction17 : CodeBridgeCodeActionIntention(17)
internal class CodeBridgeCodeAction18 : CodeBridgeCodeActionIntention(18)
internal class CodeBridgeCodeAction19 : CodeBridgeCodeActionIntention(19)
