package me.helvert.codebridge.language

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * The `/regex/format/flags` of a snippet variable, as `${TM_FILENAME/(.*)\\..+$/$1/}` takes the
 * extension off a file name -- the class-named-after-its-file that most snippet collections write.
 *
 * The regex is JavaScript's, which Java's reads the same for everything snippets use. The format is
 * VS Code's:
 *
 * | Form | Gives |
 * |---|---|
 * | `$1`, `${1}` | the group |
 * | `${1:/upcase}`, `/downcase`, `/capitalize`, `/camelcase`, `/pascalcase` | the group, so changed |
 * | `${1:+if}` | `if` when the group matched something |
 * | `${1:?if:else}` | `if` when it did, else `else` |
 * | `${1:-else}`, `${1:else}` | the group, or `else` when it matched nothing |
 *
 * Without `g`, only the first match is replaced, as JavaScript's `replace` does.
 */
internal class SnippetTransform private constructor(
    private val pattern: Pattern,
    private val format: List<Part>,
    private val global: Boolean,
) {

    fun apply(value: String): String {
        val matcher = pattern.matcher(value)
        val result = StringBuilder()
        var last = 0
        while (matcher.find()) {
            result.append(value, last, matcher.start())
            format.forEach { result.append(it.resolve(matcher)) }
            last = matcher.end()
            if (!global) break
        }
        return result.append(value, last, value.length).toString()
    }

    private sealed interface Part {
        fun resolve(matcher: Matcher): String
    }

    private class Literal(val text: String) : Part {
        override fun resolve(matcher: Matcher) = text
    }

    private class Group(
        val number: Int,
        val shorthand: String? = null,
        val ifValue: String? = null,
        val elseValue: String? = null,
    ) : Part {
        override fun resolve(matcher: Matcher): String {
            val value = if (number <= matcher.groupCount()) matcher.group(number).orEmpty() else ""
            return when {
                shorthand != null -> case(value, shorthand)
                value.isNotEmpty() && ifValue != null -> ifValue
                value.isEmpty() && elseValue != null -> elseValue
                else -> value
            }
        }
    }

    companion object {
        /** `regex/format/flags`, as written after the variable's name; null when it is not one. */
        fun parse(transform: String): SnippetTransform? {
            val (regex, format, flags) = split(transform) ?: return null
            var options = 0
            if ('i' in flags) options = options or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if ('m' in flags) options = options or Pattern.MULTILINE
            if ('s' in flags) options = options or Pattern.DOTALL
            val pattern = runCatching { Pattern.compile(regex, options) }.getOrNull() ?: return null
            return SnippetTransform(pattern, parts(format), 'g' in flags)
        }

        /**
         * The three parts, split on the slashes that are not escaped, nor inside one of the format's
         * groups -- `${1:/upcase}`. The regex keeps its escapes.
         */
        private fun split(transform: String): Triple<String, String, String>? {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var groups = 0
            var index = 0
            while (index < transform.length) {
                val character = transform[index]
                val inFormat = parts.size == 1
                when {
                    character == '\\' && index + 1 < transform.length -> {
                        current.append(character).append(transform[index + 1]); index++
                    }
                    inFormat && character == '$' && transform.getOrNull(index + 1) == '{' -> {
                        groups++; current.append("\${"); index++
                    }
                    inFormat && character == '}' && groups > 0 -> { groups--; current.append(character) }
                    character == '/' && parts.size < 2 && groups == 0 -> { parts += current.toString(); current.clear() }
                    else -> current.append(character)
                }
                index++
            }
            parts += current.toString()
            return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
        }

        private fun parts(format: String): List<Part> {
            val parts = mutableListOf<Part>()
            val literal = StringBuilder()
            fun flush() {
                if (literal.isNotEmpty()) parts += Literal(literal.toString())
                literal.clear()
            }
            var index = 0
            while (index < format.length) {
                val character = format[index]
                when {
                    character == '\\' && index + 1 < format.length -> {
                        literal.append(unescaped(format[index + 1])); index += 2
                    }
                    character == '$' && format.getOrNull(index + 1)?.isDigit() == true -> {
                        val start = index + 1
                        index = start
                        while (index < format.length && format[index].isDigit()) index++
                        flush(); parts += Group(format.substring(start, index).toInt())
                    }
                    character == '$' && format.getOrNull(index + 1) == '{' -> {
                        val end = closing(format, index + 2)
                        val group = if (end < 0) null else group(format.substring(index + 2, end))
                        if (group == null) { literal.append(character); index++ }
                        else { flush(); parts += group; index = end + 1 }
                    }
                    else -> { literal.append(character); index++ }
                }
            }
            flush()
            return parts
        }

        /** The inside of `${...}` in a format; null when it is not a group. */
        private fun group(inner: String): Group? {
            val digits = inner.takeWhile(Char::isDigit)
            val number = digits.toIntOrNull() ?: return null
            if (digits.length == inner.length) return Group(number)
            if (inner[digits.length] != ':') return null
            val rest = inner.substring(digits.length + 1)
            return when (rest.firstOrNull()) {
                '/' -> Group(number, shorthand = rest.substring(1))
                '+' -> Group(number, ifValue = text(rest.substring(1)))
                '?' -> {
                    val colon = unescapedIndex(rest, ':', 1)
                    if (colon < 0) Group(number, ifValue = text(rest.substring(1)), elseValue = "")
                    else Group(number, ifValue = text(rest.substring(1, colon)), elseValue = text(rest.substring(colon + 1)))
                }
                '-' -> Group(number, elseValue = text(rest.substring(1)))
                else -> Group(number, elseValue = text(rest))
            }
        }

        /** The `}` closing a group whose inside starts at [from], past escaped characters; -1 if none. */
        private fun closing(format: String, from: Int): Int {
            var index = from
            while (index < format.length) {
                when (format[index]) {
                    '\\' -> index++
                    '}' -> return index
                }
                index++
            }
            return -1
        }

        private fun unescapedIndex(text: String, character: Char, from: Int): Int {
            var index = from
            while (index < text.length) {
                if (text[index] == '\\') index++
                else if (text[index] == character) return index
                index++
            }
            return -1
        }

        private fun text(escaped: String): String = buildString {
            var index = 0
            while (index < escaped.length) {
                if (escaped[index] == '\\' && index + 1 < escaped.length) append(unescaped(escaped[++index]))
                else append(escaped[index])
                index++
            }
        }

        /** What `\x` in a format stands for: the characters the grammar uses stand for themselves. */
        private fun unescaped(character: Char): String = when (character) {
            '$', '}', '\\', '/', ':' -> character.toString()
            else -> "\\$character"
        }

        /** VS Code's case changes; a name it does not have leaves the value as it is. */
        private fun case(value: String, shorthand: String): String = when (shorthand) {
            "upcase" -> value.uppercase()
            "downcase" -> value.lowercase()
            "capitalize" -> value.replaceFirstChar(Char::uppercaseChar)
            "pascalcase" -> WORD.findAll(value).joinToString("") { it.value.replaceFirstChar(Char::uppercaseChar) }
                .ifEmpty { value }
            "camelcase" -> WORD.findAll(value).mapIndexed { index, word ->
                if (index == 0) word.value.replaceFirstChar(Char::lowercaseChar)
                else word.value.replaceFirstChar(Char::uppercaseChar)
            }.joinToString("").ifEmpty { value }
            else -> value
        }

        private val WORD = Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)
    }
}
