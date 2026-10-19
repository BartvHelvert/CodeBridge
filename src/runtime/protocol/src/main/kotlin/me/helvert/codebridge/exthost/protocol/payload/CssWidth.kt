package me.helvert.codebridge.exthost.protocol.payload

/**
 * A CSS width as decorations give one: so many `ch`, `em` and `px`, alone or summed in a `calc()`.
 *
 * GitLens sizes its blame column `calc(30ch - 6px)`, the same on every line, so the lines of a commit
 * after the first -- whose text is a single space -- still line up with it. Anything else, a
 * percentage say, is not a width this can draw, and reads as null.
 */
data class CssWidth(val ch: Double, val em: Double, val px: Double) {

    /** In pixels, given the width of `0` and the font size in the editor's pixels, and what a CSS pixel is there. */
    fun pixels(zeroWidth: Double, fontSize: Double, pixel: Double): Double = ch * zeroWidth + em * fontSize + px * pixel

    companion object {
        private val TERM = Regex("""([+-]?)\s*(\d+(?:\.\d+)?|\.\d+)(ch|em|px)""")

        val ZERO: CssWidth = CssWidth(0.0, 0.0, 0.0)

        /**
         * The left and right of a CSS `margin` shorthand, one to four lengths: Error Lens' message
         * sits `0 0 0 4ch` from the code, GitLens' blame column `0 26px -1px 0` before it. Null for
         * a margin this cannot measure.
         */
        fun margins(text: String?): Pair<CssWidth, CssWidth>? = sides(text)?.let { it[3] to it[1] }

        /**
         * A one-to-four-length shorthand -- `margin`, `border-width` -- as top, right, bottom and left,
         * as CSS spreads it. Lengths split at spaces outside parentheses, so a `calc()` stays one.
         */
        fun sides(text: String?): List<CssWidth>? {
            val lengths = text?.let(::topLevelWords)?.map { parse(it) ?: return null } ?: return null
            return when (lengths.size) {
                1 -> List(4) { lengths[0] }
                2 -> listOf(lengths[0], lengths[1], lengths[0], lengths[1])
                3 -> listOf(lengths[0], lengths[1], lengths[2], lengths[1])
                4 -> lengths
                else -> null
            }
        }

        private fun topLevelWords(text: String): List<String> {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var depth = 0
            for (char in text.trim()) {
                when {
                    char == '(' -> {
                        depth++; word.append(char)
                    }

                    char == ')' -> {
                        depth--; word.append(char)
                    }

                    char.isWhitespace() && depth == 0 -> if (word.isNotEmpty()) {
                        words += word.toString(); word.clear()
                    }

                    else -> word.append(char)
                }
            }
            if (word.isNotEmpty()) words += word.toString()
            return words
        }

        fun parse(text: String?): CssWidth? {
            val css = text?.trim()?.lowercase() ?: return null
            // The one length CSS lets go without a unit.
            if (css == "0") return ZERO
            val body = css.removeSurrounding("calc(", ")").trim()
            if (body.isEmpty()) return null
            var ch = 0.0
            var em = 0.0
            var px = 0.0
            var at = 0
            while (at < body.length) {
                if (body[at].isWhitespace()) {
                    at++; continue
                }
                val term = TERM.matchAt(body, at) ?: return null
                // Only a calc() sums terms; a bare width is one term with no sign of its own to speak of.
                if (at > 0 && term.groupValues[1].isEmpty()) return null
                val value = term.groupValues[2].toDouble() * if (term.groupValues[1] == "-") -1 else 1
                when (term.groupValues[3]) {
                    "ch" -> ch += value
                    "em" -> em += value
                    else -> px += value
                }
                at = term.range.last + 1
            }
            return CssWidth(ch, em, px)
        }
    }
}
