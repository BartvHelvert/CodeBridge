package me.helvert.codebridge.language

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateEditingListener
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * The stops of snippets an extension's edit has just put into an editor, walked as a live template.
 *
 * VS Code inserts the snippets of an edit to the active editor -- `TextEditor.insertSnippet`, or a
 * `SnippetTextEdit` in `workspace.applyEdit` -- as one snippet session: its stops numbered across
 * all of them, so `$1` written in two places, or at two ranges, is one stop typed into both. An
 * inline template is that session here. It is laid over text already in the document, so the
 * snippets go in as text with the rest of the edit, and the template then spans the first snippet to
 * the last, whatever lies between them its own literal text.
 *
 * Only the backend can start a live template; a split-mode client draws the one started in its
 * editor here, as it does completion's.
 */
internal object CodeBridgeSnippetTemplates {

    /** A snippet in a document: [marker] spans the text it was expanded to. */
    class Placed(val marker: RangeMarker, val snippet: Snippets.Expansion)

    /** How [document] indents, for [CodeBridgeTextEdits.locate] to indent snippets as VS Code would. */
    fun indentation(project: Project, document: Document): Snippets.Indentation {
        val options = CodeStyle.getIndentOptions(project, document)
        return Snippets.Indentation(options.INDENT_SIZE, !options.USE_TAB_CHARACTER)
    }

    /**
     * The snippets of [located] -- as [CodeBridgeTextEdits.locate] returns them -- once applied to
     * [document], marked so that later edits in the same command move them along.
     */
    fun place(document: Document, located: List<CodeBridgeTextEdits.Located>): List<Placed> =
        CodeBridgeTextEdits.placed(located).map { (start, snippet) ->
            Placed(document.createRangeMarker(start, start + snippet.text.length), snippet)
        }

    /**
     * Walks the stops of [placed] in [editor], or, with none to walk, leaves a caret where the
     * snippets end: on each final stop, its value selected as VS Code selects it, or else after the
     * last snippet. Snippets whose text has since been edited are left as text.
     *
     * Runs in a write action, in the command that inserted them, so that one undo takes back the
     * insertion and the template together.
     */
    fun start(project: Project, editor: Editor, placed: List<Placed>) {
        val intact =
            placed.filter { it.marker.isValid && it.marker.endOffset - it.marker.startOffset == it.snippet.text.length }
                .sortedBy { it.marker.startOffset }
        try {
            if (intact.isEmpty()) return
            val marks = intact.flatMap { placed ->
                val start = placed.marker.startOffset
                placed.snippet.marks.map { it.copy(start = start + it.start, end = start + it.end) }
            }
            val last = intact.last().marker.endOffset
            // VS Code ends with a cursor on every `$0`, or after the last snippet when none has one.
            val finals = marks.filter { it.number == 0 }.ifEmpty { listOf(Snippets.Mark(0, last, last)) }
            val stops = marks.filter { it.number > 0 }
            // A template already running would be broken off by another; VS Code would merge the two.
            if (stops.isEmpty() || TemplateManagerImpl.getTemplateState(editor) != null) {
                val carets = editor.caretModel
                carets.removeSecondaryCarets()
                for ((index, final) in finals.withIndex()) {
                    val caret = if (index == 0) carets.primaryCaret
                    else carets.addCaret(editor.offsetToVisualPosition(final.end), false) ?: continue
                    caret.moveToOffset(final.end)
                    if (final.start < final.end) caret.setSelection(final.start, final.end) else caret.removeSelection()
                }
                return
            }
            startTemplate(project, editor, intact, stops, finals)
        } finally {
            placed.forEach { it.marker.dispose() }
        }
    }

    private fun startTemplate(
        project: Project,
        editor: Editor,
        intact: List<Placed>,
        stops: List<Snippets.Mark>,
        finals: List<Snippets.Mark>,
    ) {
        val document = editor.document
        val text = document.immutableCharSequence
        val from = intact.first().marker.startOffset
        val to = maxOf(intact.last().marker.endOffset, finals.last().end)
        // Every `$0` after the first, renumbered: the snippets' own names would repeat across them.
        val others = finals.drop(1).mapIndexed { index, mark ->
            mark to Snippets.TabStop(Snippets.finalName(index + 1), text.substring(mark.start, mark.end), emptyList())
        }
        // Each mark with the variable it becomes; the first `$0`, none -- it is the template's end.
        val variables = buildList {
            stops.mapTo(this) { it to Snippets.name(it.number) }
            others.mapTo(this) { (mark, final) -> mark to final.name }
            add(finals.first() to null)
        }
        // The span as template text: every variable's value taken out, for the template to put back.
        val template = StringBuilder()
        var at = from
        fun literal(until: Int) {
            template.append(text.subSequence(at, until).toString().replace("$", "$$"))
            at = until
        }
        for ((mark, name) in variables.sortedWith(compareBy({ it.first.start }, { it.first.number == 0 }))) {
            literal(mark.start)
            if (name != null) {
                template.append('$').append(name).append('$')
                at = mark.end
            } else if (mark.start < mark.end) {
                // The final stop's value stays as text, and is selected when the template finishes.
                template.append($$"$SELECTION_START$")
                literal(mark.end)
                template.append($$"$SELECTION_END$").append($$"$END$")
            } else template.append(Snippets.END)
        }
        literal(to)

        val manager = TemplateManager.getInstance(project)
        val live = manager.createTemplate("", "", template.toString())
        live.setInline(true)
        live.isToReformat = false
        live.setToIndent(false)
        // In number order, as VS Code visits them; where snippets disagree on a value, the first wins.
        val values = intact.flatMap { it.snippet.stops }.groupBy { it.name }
        for (number in stops.map { it.number }.distinct().sorted()) {
            val name = Snippets.name(number)
            val candidates = values[name].orEmpty()
            val value = candidates.firstOrNull { it.value.isNotEmpty() }?.value.orEmpty()
            val choices = candidates.firstOrNull { it.choices.isNotEmpty() }?.choices.orEmpty()
            val node = ConstantNode(value).withLookupStrings(choices)
            live.addVariable(name, node, node, true)
        }
        val extra = others.map { it.second }
        addFinals(live, extra)
        val taken = stops + others.map { it.first }
        for ((_, start, end) in taken.sortedByDescending { it.start }) document.deleteString(start, end)
        // An inline template starts at the caret, over the text that is already there.
        editor.caretModel.removeSecondaryCarets()
        editor.caretModel.moveToOffset(from)
        manager.startTemplate(editor, live, caretsOnFinals(editor, extra))
    }

    /**
     * Adds [finals] -- a snippet's `$0`s after the first, see [Snippets.Expansion.finals] -- to
     * [template], as variables it never stops at.
     */
    fun addFinals(template: Template, finals: List<Snippets.TabStop>) {
        for ((name, text) in finals) {
            val value = ConstantNode(text)
            template.addVariable(name, value, value, false)
        }
    }

    /**
     * Adds a caret on each of [finals] once Tab leaves the template's last stop, as VS Code ends a
     * snippet with a cursor on every `$0`. Escape adds none: VS Code leaves its cursors where they are.
     */
    fun caretsOnFinals(editor: Editor, finals: List<Snippets.TabStop>): TemplateEditingListener =
        object : TemplateEditingAdapter() {
            private var ranges = emptyList<TextRange>()

            override fun beforeTemplateFinished(state: TemplateState, template: Template?) {
                ranges = finals.mapNotNull { state.getVariableRange(it.name) }
            }

            override fun templateFinished(template: Template, brokenOff: Boolean) {
                if (brokenOff) return
                for (range in ranges) {
                    val caret =
                        editor.caretModel.addCaret(editor.offsetToVisualPosition(range.endOffset), false) ?: continue
                    if (!range.isEmpty) caret.setSelection(range.startOffset, range.endOffset)
                }
            }
        }
}
