package me.helvert.codebridge.exthost.protocol.payload

import kotlin.math.abs

/**
 * How an editor indents: what `TextEditor.options` reports as `tabSize` and `insertSpaces`.
 *
 * Extensions format what they insert with it. CodeLLDB writes a launch configuration through
 * jsonc-parser's `modify` with the active editor's options, and that formatter works out the
 * indentation level of the line it starts on by dividing by `tabSize` -- told 4 for a file indented
 * by 2, it moved the line it inserted under to column 0 and indented the new configuration by 4.
 */
data class Indentation(val tabSize: Int, val insertSpaces: Boolean) {

    companion object {
        /** VS Code's own `editor.tabSize` and `editor.insertSpaces` defaults. */
        val DEFAULT: Indentation = Indentation(4, true)

        private const val MAX_LINES = 10_000
        private const val MAX_ALLOWED_TAB_SIZE_GUESS = 8
        private val ALLOWED_TAB_SIZE_GUESSES = intArrayOf(2, 4, 6, 8, 3, 5, 7)

        /**
         * What VS Code's `guessIndentation` makes of [text], as `editor.detectIndentation` -- on by
         * default -- has it do for every file it opens, with [default] where the text says nothing.
         *
         * A port rather than a lookalike: an extension sees the same options for a file here as it
         * would in VS Code, and formats what it inserts the same way.
         */
        fun guess(text: String, default: Indentation = DEFAULT): Indentation {
            val lines = text.split('\n').take(MAX_LINES).map { it.removeSuffix("\r") }
            var tabbed = 0
            var spaced = 0
            var previousText = ""
            var previousIndentation = 0
            val spacesDiffCount = IntArray(MAX_ALLOWED_TAB_SIZE_GUESS + 1)
            for (line in lines) {
                val indentation = line.indexOfFirst { it != ' ' && it != '\t' }
                if (indentation < 0) continue
                val leading = line.substring(0, indentation)
                if ('\t' in leading) tabbed++ else if (leading.length > 1) spaced++
                val diff = spacesDiff(previousText, previousIndentation, line, indentation)
                if (diff.looksLikeAlignment && !(default.insertSpaces && default.tabSize == diff.spaces)) continue
                if (diff.spaces <= MAX_ALLOWED_TAB_SIZE_GUESS) spacesDiffCount[diff.spaces]++
                previousText = line
                previousIndentation = indentation
            }
            val insertSpaces = if (tabbed != spaced) tabbed < spaced else default.insertSpaces
            var tabSize = default.tabSize
            if (insertSpaces) {
                var score = 0
                for (candidate in ALLOWED_TAB_SIZE_GUESSES) {
                    if (spacesDiffCount[candidate] > score) {
                        score = spacesDiffCount[candidate]
                        tabSize = candidate
                    }
                }
                // A file indented by 4 with some lines by 2 is one indented by 2 with some deeper.
                if (tabSize == 4 && spacesDiffCount[4] > 0 && spacesDiffCount[2] > 0 && spacesDiffCount[2] >= spacesDiffCount[4] / 2) {
                    tabSize = 2
                }
            }
            return Indentation(tabSize, insertSpaces)
        }

        private class Diff(val spaces: Int, val looksLikeAlignment: Boolean)

        /** How much deeper or shallower [b] is indented than [a], in spaces; VS Code's `spacesDiff`. */
        private fun spacesDiff(a: String, aLength: Int, b: String, bLength: Int): Diff {
            var common = 0
            while (common < aLength && common < bLength && a[common] == b[common]) common++
            val aSpaces = (common until aLength).count { a[it] == ' ' }
            val aTabs = aLength - common - aSpaces
            val bSpaces = (common until bLength).count { b[it] == ' ' }
            val bTabs = bLength - common - bSpaces
            if ((aSpaces > 0 && aTabs > 0) || (bSpaces > 0 && bTabs > 0)) return Diff(0, false)
            val tabsDiff = abs(aTabs - bTabs)
            val spacesDiff = abs(aSpaces - bSpaces)
            if (tabsDiff == 0) {
                // Aligned under something on the line above, after a comma, rather than indented.
                val alignment = spacesDiff > 0 && bSpaces - 1 in a.indices && bSpaces < b.length &&
                        b[bSpaces] != ' ' && a[bSpaces - 1] == ' ' && a.last() == ','
                return Diff(spacesDiff, alignment)
            }
            return Diff(if (spacesDiff % tabsDiff == 0) spacesDiff / tabsDiff else 0, false)
        }
    }
}
