package me.helvert.codebridge.notebook

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.actionSystem.ActionPlan
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.actionSystem.TypedActionHandlerEx

/**
 * Types into a notebook cell's editor itself, as the IDE's default typing does, but without the
 * IDE's typing handler. That handler, finding no PSI file for the cell's document, tells the backend
 * the editor has none, so completion can start there -- for an editor the backend has never seen,
 * which it reports as an error on every key. The handler has nothing else for a cell: no language of
 * the IDE's is behind its text, and its completions are [CellLanguage]'s. Anywhere else, typing does
 * what it did.
 */
internal class CellTypedHandler private constructor(private val original: TypedActionHandler) : TypedActionHandlerEx {
    override fun beforeExecute(editor: Editor, c: Char, context: DataContext, plan: ActionPlan) {
        if (editor.getUserData(CellView.KEY) == null) (original as? TypedActionHandlerEx)?.beforeExecute(editor, c, context, plan)
    }

    override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
        if (editor.getUserData(CellView.KEY) == null) return original.execute(editor, charTyped, dataContext)
        if (!EditorModificationUtil.checkModificationAllowed(editor)) return
        // Grouped by the document, as the IDE's typing is, so that undo takes back a word at a time.
        CommandProcessor.getInstance().executeCommand(CommonDataKeys.PROJECT.getData(dataContext), {
            ApplicationManager.getApplication().runWriteAction {
                EditorModificationUtil.typeInStringAtCaretHonorMultipleCarets(editor, charTyped.toString(), true)
            }
        }, null, editor.document)
    }

    companion object {
        private var installed = false

        /** Puts the handler in front of the IDE's typing, once. Used on the EDT. */
        fun install() {
            if (installed) return
            installed = true
            val typing = TypedAction.getInstance()
            typing.setupRawHandler(CellTypedHandler(typing.rawHandler))
        }
    }
}
