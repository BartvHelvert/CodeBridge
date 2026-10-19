package me.helvert.codebridge.workbench

/**
 * VS Code glob separators are '/' on every platform; a globstar can match zero directories.
 *
 * [gitignore] reads the pattern as an ignore file writes it: braces are themselves, not alternatives,
 * and a backslash makes the character after it literal.
 */
class WorkspaceGlob(pattern: String, ignoreCase: Boolean = false, private val gitignore: Boolean = false) {
    private val regex = Regex(compile(pattern), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
    fun matches(path: String): Boolean = regex.matches(path.replace('\\', '/'))

    private fun compile(pattern: String): String = buildString {
        append('^')
        var i = 0
        var braces = 0
        while (i < pattern.length) {
            val c = pattern[i++]
            if (gitignore && (c == '{' || c == '}' || c == ',')) {
                append(Regex.escape(c.toString())); continue
            }
            if (gitignore && c == '\\' && i < pattern.length) {
                append(Regex.escape(pattern[i++].toString())); continue
            }
            when (c) {
                '*' -> if (i < pattern.length && pattern[i] == '*') {
                    i++
                    if (i < pattern.length && pattern[i] == '/') {
                        i++; append("(?:.*/)?")
                    } else append(".*")
                } else append("[^/]*")

                '?' -> append("[^/]")
                '{' -> {
                    braces++; append("(?:")
                }

                '}' -> if (braces > 0) {
                    braces--; append(')')
                } else append("\\}")

                ',' -> append(if (braces > 0) "|" else ",")
                '[' -> {
                    val end = pattern.indexOf(']', i + if (pattern.getOrNull(i) == ']') 1 else 0)
                    if (end < 0) append("\\[") else {
                        var chars = pattern.substring(i, end)
                        if (chars.startsWith('!')) chars = "^" + chars.drop(1)
                        append('[').append(chars.replace("\\", "\\\\").replace("&&", "\\&\\&")).append(']')
                        i = end + 1
                    }
                }

                else -> append(Regex.escape(c.toString()))
            }
        }
        require(braces == 0) { "Unclosed glob alternative: $pattern" }
        append('$')
    }
}
