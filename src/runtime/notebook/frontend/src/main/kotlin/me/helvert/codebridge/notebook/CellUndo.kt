package me.helvert.codebridge.notebook

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener

/**
 * A cell's undo and redo of what was typed in it, as VS Code keeps one per cell. Not the IDE's undo
 * manager: in a JetBrains Client it asks the backend to undo along with it, and the backend, which
 * has never seen the cell's editor, fails on it. Typing goes in a step at a time, as the IDE groups
 * it: characters typed one after another, or deleted one after another, are one step. Used on the EDT.
 */
internal class CellUndo(private val editor: Editor, parent: Disposable) {
    private class Change(val offset: Int, val old: String, val new: String)

    private val done = ArrayDeque<MutableList<Change>>()
    private val undone = ArrayDeque<List<Change>>()
    /** Set while a step is put back or taken back, which is no step of its own. */
    private var replaying = false

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!replaying) record(Change(event.offset, event.oldFragment.toString(), event.newFragment.toString()))
            }
        }, parent)
    }

    /** Forgets every step: the text was replaced from elsewhere, which the steps no longer fit. */
    fun clear() {
        done.clear()
        undone.clear()
    }

    /** Takes back the last step; false when there is none. */
    fun undo(): Boolean {
        val step = done.removeLastOrNull() ?: return false
        replay(step.reversed().map { Change(it.offset, it.new, it.old) })
        undone.addLast(step)
        return true
    }

    /** Puts back the last step taken back; false when there is none. */
    fun redo(): Boolean {
        val step = undone.removeLastOrNull() ?: return false
        replay(step)
        done.addLast(step.toMutableList())
        return true
    }

    private fun record(change: Change) {
        undone.clear()
        val last = done.lastOrNull()?.last()
        if (last != null && continues(last, change)) done.last().add(change) else done.addLast(mutableListOf(change))
        // A cell's typing, not a document's history.
        if (done.size > LIMIT) done.removeFirst()
    }

    /** Whether [next] is more of the typing, or the deleting, that [last] was. */
    private fun continues(last: Change, next: Change): Boolean {
        if ('\n' in next.new || '\n' in next.old) return false
        val typing = last.old.isEmpty() && next.old.isEmpty() && next.offset == last.offset + last.new.length
        val deleting = last.new.isEmpty() && next.new.isEmpty() &&
            (next.offset + next.old.length == last.offset || next.offset == last.offset)
        return typing || deleting
    }

    private fun replay(changes: List<Change>) {
        replaying = true
        try {
            WriteCommandAction.runWriteCommandAction(editor.project) {
                for (change in changes) editor.document.replaceString(change.offset, change.offset + change.old.length, change.new)
            }
        } finally {
            replaying = false
        }
        changes.lastOrNull()?.let { editor.caretModel.moveToOffset(it.offset + it.new.length) }
    }

    private companion object {
        const val LIMIT = 100
    }
}
