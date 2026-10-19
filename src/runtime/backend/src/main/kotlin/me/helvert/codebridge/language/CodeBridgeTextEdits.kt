package me.helvert.codebridge.language

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.TextEdit

/**
 * Text edits as offsets into one text, and applied to it.
 *
 * Strict where [DocumentLines] is lenient. A diagnostic that names a line since deleted is still
 * worth drawing somewhere nearby; an edit that does is about text the user has already replaced,
 * and applying it anywhere would corrupt the file. So a range that does not fit is a refusal of the
 * whole set, never a clamp.
 */
internal object CodeBridgeTextEdits {

    /**
     * One edit located in the text: what to replace, and with what. [snippet] is what [text] was
     * expanded from, for an edit inserted as a snippet into an editor.
     */
    data class Located(val range: TextRange, val text: String, val snippet: Snippets.Expansion? = null)

    /**
     * Every edit as offsets into [text], last first, or null if any does not fit or two overlap.
     *
     * Last first so that applying them in this order never moves a range still to be applied. Ordered
     * as VS Code's text model orders them, by start and then by end, so an insertion where a
     * replacement starts comes before it rather than overlapping it -- CodeLLDB's edit to
     * `launch.json` is one of each, at the same point. Edits at the same range keep the order they
     * were given in, which is how VS Code applies two insertions at one point: the first one given
     * ends up first in the text.
     *
     * A snippet is reduced to its text, as VS Code inserts one into a document no editor is showing.
     * Going into an editor -- [editor] given, how it indents -- it keeps its expansion, for its stops
     * to be walked, and is indented to the line it starts on unless it says to keep its whitespace.
     */
    fun locate(text: CharSequence, edits: List<TextEdit>, editor: Snippets.Indentation? = null): List<Located>? {
        val lines = lineStarts(text)
        val located = edits.mapIndexed { index, edit ->
            val range = offsets(text, lines, edit.range) ?: return null
            // IntelliJ documents hold `\n` only, and extensions format for the platform they run on.
            val written = StringUtil.convertLineSeparators(edit.text)
            val element = when {
                !edit.snippet -> Located(range, written)
                editor == null -> Located(range, Snippets.plain(written))
                else -> {
                    val indented =
                        if (edit.keepWhitespace) written
                        else Snippets.indented(written, indentBefore(text, range.startOffset), editor)
                    val snippet = Snippets.expand(indented)
                    Located(range, snippet.text, snippet)
                }
            }
            IndexedValue(index, element)
        }.sortedWith(compareBy<IndexedValue<Located>> { it.value.range.startOffset }
            .thenBy { it.value.range.endOffset }.thenBy { it.index })
            .map { it.value }
        for (index in 1 until located.size) {
            if (located[index].range.startOffset < located[index - 1].range.endOffset) return null
        }
        return located.asReversed()
    }

    /** The leading whitespace of the line [offset] is on, up to [offset]. */
    private fun indentBefore(text: CharSequence, offset: Int): String {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = start
        while (end < offset && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(start, end)
    }

    /**
     * Each snippet in [located] -- as [locate] returns them, last first -- with where it starts once
     * they are all applied, first to last.
     */
    fun placed(located: List<Located>): List<Pair<Int, Snippets.Expansion>> {
        var shift = 0
        return located.asReversed().mapNotNull { (range, replacement, snippet) ->
            val start = range.startOffset + shift
            shift += replacement.length - range.length
            snippet?.let { start to it }
        }
    }

    /** [text] with [edits] applied, or null if they do not fit it. */
    fun apply(text: CharSequence, edits: List<TextEdit>): String? {
        val located = locate(text, edits) ?: return null
        val out = StringBuilder(text)
        for ((range, replacement) in located) out.replace(range.startOffset, range.endOffset, replacement)
        return out.toString()
    }

    /** VS Code's 1-based range as offsets, or null when it names a line or column [text] lacks. */
    fun offsets(text: CharSequence, range: Range): TextRange? = offsets(text, lineStarts(text), range)

    /** The 1-based range covering all of [text], the shape a whole-document request is given. */
    fun whole(text: CharSequence): Range {
        val lines = lineStarts(text)
        return Range(1, 1, lines.size, text.length - lines.last() + 1)
    }

    /** The 1-based range covering [range] of [text]. */
    fun rangeOf(text: CharSequence, range: TextRange): Range {
        val lines = lineStarts(text)
        fun position(offset: Int): Pair<Int, Int> {
            val line = lines.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            return line + 1 to offset - lines[line] + 1
        }
        val (startLine, startColumn) = position(range.startOffset)
        val (endLine, endColumn) = position(range.endOffset)
        return Range(startLine, startColumn, endLine, endColumn)
    }

    private fun offsets(text: CharSequence, lines: IntArray, range: Range): TextRange? {
        fun offset(line: Int, column: Int): Int? {
            if (line !in 1..lines.size || column < 1) return null
            val start = lines[line - 1]
            val end = if (line < lines.size) lines[line] - 1 else text.length
            return (start + column - 1).takeIf { it <= end }
        }
        val start = offset(range.startLineNumber, range.startColumn) ?: return null
        val end = offset(range.endLineNumber, range.endColumn) ?: return null
        return if (end >= start) TextRange(start, end) else null
    }

    private fun lineStarts(text: CharSequence): IntArray = buildList {
        add(0)
        for (index in text.indices) if (text[index] == '\n') add(index + 1)
    }.toIntArray()
}
