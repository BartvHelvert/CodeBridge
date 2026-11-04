package me.helvert.codebridge.textmate

/**
 * Rewrites a JavaScript regular expression, as a language configuration holds them, into one
 * `java.util.regex` reads the same way.
 *
 * VS Code compiles these with `new RegExp(pattern)`, without the `u` flag, which is lenient where
 * Java is strict. Go's `increaseIndentPattern` has a bare `{` after a group, which JavaScript reads as
 * a literal and Java rejects as an "Illegal repetition". Only the differences that turn up in
 * patterns are handled:
 *
 * - `{` that does not start a `{n}`, `{n,}` or `{n,m}` quantifier is a literal.
 * - In a class, `[` is a literal, where Java starts a nested class, and so is `&&`, which Java reads
 *   as an intersection.
 * - `[]` matches nothing and `[^]` any character, where Java reads the `]` as a literal.
 * - `\v` is the vertical tab, where Java reads any vertical whitespace, and a lone `\0` is NUL.
 * - An escaped letter with no meaning of its own is the letter, where Java rejects it.
 */
internal object JavaScriptRegex {
    /** Letters Java and JavaScript both read the same way after a backslash. */
    private const val SHARED_ESCAPES = "dDwWsSbBnrtfcxuk"

    fun toJava(pattern: String): String {
        val out = StringBuilder(pattern.length + 8)
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    when {
                        next == 'v' -> out.append("\\x0B")
                        next == '0' && pattern.getOrNull(i + 2)?.isDigit() != true -> out.append("\\x00")
                        next.isAsciiLetter() && next !in SHARED_ESCAPES -> out.append(next)
                        else -> out.append(c).append(next)
                    }
                    i += 2
                    continue
                }
                inClass -> when (c) {
                    ']' -> { inClass = false; out.append(c) }
                    '[', '&' -> out.append('\\').append(c)
                    else -> out.append(c)
                }
                c == '[' -> {
                    when {
                        pattern.startsWith("[]", i) -> { out.append("(?!)"); i += 2; continue }
                        pattern.startsWith("[^]", i) -> { out.append("[\\s\\S]"); i += 3; continue }
                    }
                    inClass = true
                    out.append(c)
                    if (pattern.startsWith("^", i + 1)) { out.append('^'); i++ }
                }
                c == '{' -> {
                    val quantifier = QUANTIFIER.matchAt(pattern, i)
                    if (quantifier == null) out.append("\\{") else {
                        out.append(quantifier.value)
                        i += quantifier.value.length
                        continue
                    }
                }
                c == '}' -> out.append("\\}")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private val QUANTIFIER = Regex("""\{\d+(,\d*)?}""")

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}
