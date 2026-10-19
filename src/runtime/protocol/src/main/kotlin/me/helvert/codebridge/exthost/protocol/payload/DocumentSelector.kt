package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether a provider's document selector claims a file.
 *
 * Every feature asks only the providers that claim the file, as VS Code does. Asking the rest costs a
 * round trip each -- mostly one after another -- and for formatting it would be wrong outright:
 * offering to format a file takes it away from the IDE's own formatter.
 *
 * The selector arrives as the host serialized it: a list of filters, each
 * `{language, scheme, pattern, notebookType}`, where every field given must match. A bare string is
 * a language id. `pattern` is a glob, or a relative pattern `{baseUri, pattern}`.
 */
object DocumentSelector {

    /**
     * [path] is the file's absolute path with forward slashes, as `UriComponents.path` spells it.
     *
     * A selector this cannot read is taken to match: better to ask a provider that then declines
     * than to hide one whose selector is merely shaped differently in a newer VS Code.
     */
    fun matches(selector: JsonElement?, languageId: String, path: String): Boolean {
        val filters = when (selector) {
            is JsonArray -> selector
            is JsonObject, is JsonPrimitive -> listOf(selector)
            else -> return true
        }
        return filters.any { filter ->
            when (filter) {
                is JsonPrimitive -> filter.contentOrNull.let { it == languageId || it == "*" }
                is JsonObject -> matchesFilter(filter, languageId, path)
                else -> false
            }
        }
    }

    private fun matchesFilter(filter: JsonObject, languageId: String, path: String): Boolean {
        fun string(name: String) = (filter[name] as? JsonPrimitive)?.contentOrNull
        // Notebook cells only; there are no notebooks here.
        if (string("notebookType") != null) return false
        string("language")?.let { if (it != languageId && it != "*") return false }
        string("scheme")?.let { if (it != UriComponents.FILE && it != "*") return false }
        return when (val pattern = filter["pattern"]) {
            null -> true
            is JsonPrimitive -> pattern.contentOrNull?.let { glob(it, path) } ?: true
            is JsonObject -> relative(pattern, path)
            else -> true
        }
    }

    /** `{baseUri, pattern}`: the glob applies to the path below the base. */
    private fun relative(pattern: JsonObject, path: String): Boolean {
        val glob = (pattern["pattern"] as? JsonPrimitive)?.contentOrNull ?: return true
        val base = UriComponents.from(pattern["baseUri"])?.path
            ?: (pattern["base"] as? JsonPrimitive)?.contentOrNull?.replace('\\', '/')
            ?: return glob(glob, path)
        val prefix = base.trimEnd('/') + "/"
        if (!path.startsWith(prefix, ignoreCase = true)) return false
        return glob(glob, path.substring(prefix.length))
    }

    /**
     * VS Code's glob dialect, as far as selectors use it: `**`, `*`, `?`, `{a,b}` and `[...]`.
     *
     * A pattern with no `/` matches the file name anywhere, the way `*.rs` is meant in a selector.
     * Case-insensitive, since a selector written for one platform is routinely used on another.
     */
    internal fun glob(pattern: String, path: String): Boolean {
        val target = if ('/' in pattern) path else path.substringAfterLast('/')
        return compiled.getOrPut(pattern) { Regex(regexOf(pattern), RegexOption.IGNORE_CASE) }.matches(target) ||
            // `**/x` also matches `x` at the root of what it is applied to.
            (pattern.startsWith("**/") && glob(pattern.removePrefix("**/"), path.substringAfterLast('/')))
    }

    /** Compiled once per pattern: selectors are consulted on every completion and colouring request. */
    private val compiled = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun regexOf(glob: String): String {
        val out = StringBuilder()
        var index = 0
        var braces = 0
        while (index < glob.length) {
            when (val character = glob[index]) {
                '*' if glob.startsWith("**", index) -> { out.append(".*"); index++ }
                '*' -> out.append("[^/]*")
                '?' -> out.append("[^/]")
                '{' -> { out.append("(?:"); braces++ }
                '}' if braces > 0 -> { out.append(')'); braces-- }
                ',' if braces > 0 -> out.append('|')
                '[' -> {
                    val end = glob.indexOf(']', index + 1)
                    if (end < 0) out.append("\\[")
                    else {
                        val set = glob.substring(index + 1, end).replace("\\", "\\\\")
                        out.append('[').append(if (set.startsWith('!')) "^" + set.substring(1) else set).append(']')
                        index = end
                    }
                }
                else -> out.append(Regex.escape(character.toString()))
            }
            index++
        }
        return out.toString()
    }
}
