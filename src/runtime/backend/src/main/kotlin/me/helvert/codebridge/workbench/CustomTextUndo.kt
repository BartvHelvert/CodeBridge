package me.helvert.codebridge.workbench

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener

/**
 * The undo and redo of a text document a custom editor shows, as VS Code undoes the text model
 * under a custom text editor.
 *
 * Not the IDE's undo manager, which undoes what was done in a text editor: the custom editor is
 * none, and in a split-mode client is on the other side. Each command that changed the text -- an
 * extension's workspace edit is one -- is a step, whoever made it, as one model's history in VS Code
 * holds every change to it. Recording is from the change itself, on the editor thread; [undo] and
 * [redo] say what to change, for the caller to replay as a command of its own while [replaying].
 */
internal class CustomTextUndo(document: Document, parent: Disposable, private val changed: () -> Unit) {
    class Change(val offset: Int, val old: String, val new: String)

    private class Step(val command: Any?, val changes: MutableList<Change>)

    private val done = ArrayDeque<Step>()
    private val undone = ArrayDeque<List<Change>>()
    /** Set while a step is put back or taken back, which is no step of its own. */
    @Volatile var replaying = false

    init {
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!replaying) record(Change(event.offset, event.oldFragment.toString(), event.newFragment.toString()))
            }
        }, parent)
    }

    val canUndo: Boolean @Synchronized get() = done.isNotEmpty()
    val canRedo: Boolean @Synchronized get() = undone.isNotEmpty()

    /** The changes taking back the last step, last change first, which the step then is undone; null when there is none. */
    @Synchronized fun undo(): List<Change>? {
        val step = done.removeLastOrNull() ?: return null
        undone.addLast(step.changes)
        return step.changes.reversed().map { Change(it.offset, it.new, it.old) }
    }

    /** The changes making again the last step taken back; null when there is none. */
    @Synchronized fun redo(): List<Change>? {
        val changes = undone.removeLastOrNull() ?: return null
        done.addLast(Step(null, changes.toMutableList()))
        return changes
    }

    private fun record(change: Change) {
        synchronized(this) {
            undone.clear()
            // One step per command: an extension's edit changes the text in several places at once.
            val command = CommandProcessor.getInstance().currentCommand
            val last = done.lastOrNull()
            if (command != null && last != null && last.command === command) last.changes.add(change)
            else done.addLast(Step(command, mutableListOf(change)))
            if (done.size > LIMIT) done.removeFirst()
        }
        changed()
    }

    private companion object {
        const val LIMIT = 200
    }
}
