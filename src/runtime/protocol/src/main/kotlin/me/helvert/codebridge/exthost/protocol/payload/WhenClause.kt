package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A VS Code `when` clause: the condition a menu entry, a view or a keybinding is offered under,
 * such as `view == 'lldb.excludedCallers' && viewItem =~ /^frame/`.
 *
 * The workbench evaluates these against its context keys: some it sets itself (`view`, `viewItem`),
 * the rest an extension sets with the `setContext` command. A key nobody set is undefined, which
 * is false, as it is in VS Code.
 *
 * A clause this cannot read counts as false, so an entry is left out rather than offered where its
 * extension did not mean it to be.
 */
object WhenClause {

    /** Whether [clause] holds for the context keys [lookup] gives; a blank clause always does. */
    fun evaluate(clause: String?, lookup: (String) -> JsonElement?): Boolean {
        if (clause.isNullOrBlank()) return true
        return runCatching { Parser(clause, lookup).parse() }.getOrDefault(false)
    }

    private class Parser(private val text: String, private val lookup: (String) -> JsonElement?) {
        private var at = 0

        fun parse(): Boolean {
            val value = or()
            skipSpace()
            require(at == text.length) { "Unexpected '${text.substring(at)}' in '$text'" }
            return value
        }

        private fun or(): Boolean {
            var value = and()
            while (take("||")) value = and() or value
            return value
        }

        private fun and(): Boolean {
            var value = unary()
            while (take("&&")) value = unary() and value
            return value
        }

        private fun unary(): Boolean {
            if (peek("!") && !peek("!=")) { at++; return !unary() }
            if (take("(")) {
                val value = or()
                require(take(")")) { "Missing ')' in '$text'" }
                return value
            }
            return comparison()
        }

        private fun comparison(): Boolean {
            val value = when (val key = word()) {
                "true" -> return true
                "false" -> return false
                else -> lookup(key)
            }
            return when {
                take("===") || take("==") -> equal(value, operand())
                take("!==") || take("!=") -> !equal(value, operand())
                take("=~") -> regex().containsMatchIn(text(value) ?: return false)
                take(">=") -> compare(value, operand()) { it >= 0 }
                take("<=") -> compare(value, operand()) { it <= 0 }
                take(">") -> compare(value, operand()) { it > 0 }
                take("<") -> compare(value, operand()) { it < 0 }
                takeWord("not") -> { require(takeWord("in")) { "Expected 'in' after 'not' in '$text'" }; !contains(lookup(word()), value) }
                takeWord("in") -> contains(lookup(word()), value)
                else -> truthy(value)
            }
        }

        /** The right-hand side: quoted text, or a bare word read as text. */
        private fun operand(): String {
            skipSpace()
            val quote = text.getOrNull(at)
            if (quote == '\'' || quote == '"') {
                val end = text.indexOf(quote, at + 1)
                require(end > at) { "Unterminated string in '$text'" }
                return text.substring(at + 1, end).also { at = end + 1 }
            }
            return word()
        }

        private fun regex(): Regex {
            skipSpace()
            require(text.getOrNull(at) == '/') { "Expected a regular expression in '$text'" }
            var end = at + 1
            while (end < text.length && text[end] != '/') end += if (text[end] == '\\') 2 else 1
            require(end < text.length) { "Unterminated regular expression in '$text'" }
            val pattern = text.substring(at + 1, end)
            at = end + 1
            val options = mutableSetOf<RegexOption>()
            while (at < text.length && text[at].isLetter()) {
                when (text[at]) {
                    'i' -> options += RegexOption.IGNORE_CASE
                    'm' -> options += RegexOption.MULTILINE
                    's' -> options += RegexOption.DOT_MATCHES_ALL
                }
                at++
            }
            return Regex(pattern, options)
        }

        /** A context key or bare value: everything up to the next space, operator or parenthesis. */
        private fun word(): String {
            skipSpace()
            val start = at
            while (at < text.length && !text[at].isWhitespace() && text[at] !in "()!=<>&|~" ) at++
            require(at > start) { "Expected a context key at ${start + 1} in '$text'" }
            return text.substring(start, at)
        }

        private fun peek(token: String): Boolean {
            skipSpace()
            return text.startsWith(token, at)
        }

        private fun take(token: String): Boolean = peek(token).also { if (it) at += token.length }

        /** [token] as a whole word, so `in` does not match the start of `inRustProject`. */
        private fun takeWord(token: String): Boolean {
            if (!peek(token)) return false
            val after = text.getOrNull(at + token.length)
            if (after != null && !after.isWhitespace()) return false
            at += token.length
            return true
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }

    /** VS Code compares a key to the text it is written against, `true` and numbers included. */
    private fun equal(value: JsonElement?, expected: String): Boolean = text(value) == expected

    private fun compare(value: JsonElement?, expected: String, test: (Int) -> Boolean): Boolean {
        val number = (value as? JsonPrimitive)?.doubleOrNull ?: return false
        return test(number.compareTo(expected.toDoubleOrNull() ?: return false))
    }

    /** `key in list`: whether [container], an array or an object's keys, holds [value]. */
    private fun contains(container: JsonElement?, value: JsonElement?): Boolean {
        val wanted = text(value) ?: return false
        return when (container) {
            is JsonArray -> container.any { text(it) == wanted }
            is JsonObject -> wanted in container.keys
            else -> false
        }
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /** JavaScript's truthiness, which is what VS Code applies to a key on its own. */
    private fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull!!
            else -> value.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
        }
        else -> true
    }
}
