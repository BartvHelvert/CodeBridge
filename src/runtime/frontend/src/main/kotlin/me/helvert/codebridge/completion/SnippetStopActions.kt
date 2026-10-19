package me.helvert.codebridge.completion

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

/**
 * Tab, Shift+Tab and Esc while a snippet's stops are walked; see [SnippetStops]. Enabled only then,
 * and not while a lookup is open, which keeps those keys for itself.
 */
private class StopHandler(private val run: (SnippetStops) -> Unit, private val enabled: (SnippetStops) -> Boolean = { true }) :
    EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) { SnippetStops.of(editor)?.let(run) }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean = active(editor)?.let(enabled) == true
}

private fun active(editor: Editor): SnippetStops? =
    SnippetStops.of(editor)?.takeIf { LookupManager.getActiveLookup(editor) == null }

class NextSnippetStopAction : EditorAction(StopHandler(SnippetStops::next))

class PreviousSnippetStopAction : EditorAction(StopHandler(SnippetStops::previous, SnippetStops::canGoBack))

class ExitSnippetAction : EditorAction(StopHandler(SnippetStops::finish))

/**
 * Puts the actions above first on their keys while they are enabled. The platform gives a key to the
 * first enabled action registered for it, and a plugin's come after the editor's own: Tab would
 * indent the selected placeholder instead.
 */
class SnippetStopActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return emptyList()
        if (active(editor) == null) return emptyList()
        return actions.filter { it is NextSnippetStopAction || it is PreviousSnippetStopAction || it is ExitSnippetAction }
    }
}
