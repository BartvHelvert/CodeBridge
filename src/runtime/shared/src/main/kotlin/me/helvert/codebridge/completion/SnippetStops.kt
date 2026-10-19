package me.helvert.codebridge.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

/**
 * A snippet's tab stops, walked by Tab and Shift+Tab as VS Code walks them, in an editor of this
 * process.
 *
 * A live template does this too, but only the backend can start one, and in split mode it redraws
 * the inserted text a round trip after the insertion. This runs where the insertion ran, on the
 * client. It ends, as in VS Code, on Esc, on Tab from the last stop, or once the caret leaves the
 * snippet. A stop written more than once is visited where it is first written; the other copies do
 * not follow what is typed there.
 */
class SnippetStops private constructor(
    private val editor: Editor,
    /** In the order Tab visits them; the last may be the empty `$0`, where the snippet ends. */
    private val stops: List<RangeMarker>,
    private val whole: RangeMarker,
) : Disposable {

    private var index = 0
    private val highlighters = mutableListOf<RangeHighlighter>()

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            val offset = editor.caretModel.offset
            if (!whole.isValid || offset < whole.startOffset || offset > whole.endOffset) finish()
        }
    }

    val canGoBack: Boolean get() = index > 0

    fun next() {
        if (index + 1 >= stops.size) {
            // From the last stop: the snippet is done, and the caret goes to its end.
            val end = stops.lastOrNull()?.takeIf { it.isValid && it.startOffset == it.endOffset }?.startOffset
                ?: whole.endOffset.takeIf { whole.isValid }
            finish()
            end?.let { editor.caretModel.moveToOffset(it); editor.selectionModel.removeSelection() }
            return
        }
        select(index + 1)
    }

    fun previous() { if (canGoBack) select(index - 1) }

    fun finish() { Disposer.dispose(this) }

    private fun select(target: Int) {
        val stop = stops[target]
        if (!stop.isValid) return finish()
        index = target
        // Moving the caret within the snippet must not end it.
        editor.caretModel.removeCaretListener(caretListener)
        try {
            editor.caretModel.moveToOffset(stop.endOffset)
            editor.selectionModel.setSelection(stop.startOffset, stop.endOffset)
        } finally {
            editor.caretModel.addCaretListener(caretListener)
        }
        highlight()
    }

    /** Boxes the stops still ahead, as live templates do. */
    private fun highlight() {
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        val attributes = (editor as? EditorEx)?.colorsScheme?.getAttributes(EditorColors.LIVE_TEMPLATE_INACTIVE_SEGMENT) ?: return
        for ((position, stop) in stops.withIndex()) {
            if (position == index || !stop.isValid || stop.startOffset == stop.endOffset) continue
            highlighters += editor.markupModel.addRangeHighlighter(stop.startOffset, stop.endOffset,
                HighlighterLayer.LAST + 1, attributes, HighlighterTargetArea.EXACT_RANGE)
        }
    }

    override fun dispose() {
        editor.caretModel.removeCaretListener(caretListener)
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        stops.forEach(RangeMarker::dispose)
        whole.dispose()
        if (editor.getUserData(KEY) === this) editor.putUserData(KEY, null)
    }

    companion object {
        private val KEY = Key.create<SnippetStops>("CodeBridge.SnippetStops")

        /** The stops being walked in [editor], if any. */
        fun of(editor: Editor): SnippetStops? = editor.getUserData(KEY)

        /**
         * Starts walking [stops], `start until end` document ranges in visiting order, within [start]
         * to [end], and selects the first. A snippet with fewer than two places to go has nothing to walk; its one
         * stop is only selected.
         */
        fun start(editor: Editor, start: Int, end: Int, stops: List<IntRange>) {
            of(editor)?.finish()
            val document = editor.document
            val markers = stops.map { range ->
                document.createRangeMarker(range.first, range.last + 1).apply {
                    // Typing into a stop, even an empty one, grows it.
                    isGreedyToLeft = true
                    isGreedyToRight = true
                }
            }
            val whole = document.createRangeMarker(start, end).apply { isGreedyToLeft = true; isGreedyToRight = true }
            val walk = SnippetStops(editor, markers, whole)
            if (markers.size < 2) {
                markers.firstOrNull()?.let { editor.caretModel.moveToOffset(it.endOffset); editor.selectionModel.setSelection(it.startOffset, it.endOffset) }
                return walk.finish()
            }
            editor.putUserData(KEY, walk)
            // Ends with the editor, if nothing else ends it first.
            EditorUtil.disposeWithEditor(editor, walk)
            walk.select(0)
        }
    }
}
