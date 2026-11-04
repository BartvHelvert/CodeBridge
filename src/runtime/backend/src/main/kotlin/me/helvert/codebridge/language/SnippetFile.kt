package me.helvert.codebridge.language

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.workspace.Jsonc

/**
 * A file of VS Code snippets, as an extension's `contributes.snippets` names it:
 *
 * ```jsonc
 * {
 *   "Print to console": {
 *     "prefix": ["log", "cl"],
 *     "body": ["console.log('$1');", "$0"],
 *     "description": "Log output to console",
 *     "scope": "javascript,typescript"
 *   }
 * }
 * ```
 *
 * `prefix`, `body` and `description` are a string or a list of them, the body's joined into lines.
 * `scope` only counts in a file contributed without a language; one with a language applies there
 * whatever its snippets say, as VS Code reads them. `include` and `exclude` are globs over the file
 * the snippet would go into.
 */
internal object SnippetFile {

    /** One snippet; [scopes] are the language ids it applies to, every language when empty. */
    data class Snippet(
        val name: String,
        val prefixes: List<String>,
        val body: String,
        val description: String?,
        val scopes: Set<String>,
        val include: List<String> = emptyList(),
        val exclude: List<String> = emptyList(),
    )

    private val json = Json { isLenient = true }

    /**
     * The snippets in [text], a file contributed for [language] or, when null, for every language.
     * A snippet without a body is left out, and so is one without a prefix: VS Code offers that one
     * only from its Insert Snippet picker, which is not here.
     */
    fun parse(text: String, language: String?): List<Snippet> {
        val root = json.parseToJsonElement(Jsonc.stripToJson(text)) as? JsonObject ?: return emptyList()
        return root.mapNotNull { (name, value) ->
            val snippet = value as? JsonObject ?: return@mapNotNull null
            val body = strings(snippet["body"])?.joinToString("\n") ?: return@mapNotNull null
            val prefixes = strings(snippet["prefix"]).orEmpty().filter { it.isNotEmpty() }
            if (prefixes.isEmpty()) return@mapNotNull null
            val scopes = when (language) {
                null -> strings(snippet["scope"]).orEmpty()
                    .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                else -> setOf(language)
            }
            Snippet(
                name = name,
                prefixes = prefixes.distinct(),
                body = body,
                description = strings(snippet["description"])?.joinToString("\n")?.takeIf { it.isNotBlank() },
                scopes = scopes,
                include = strings(snippet["include"]).orEmpty(),
                exclude = strings(snippet["exclude"]).orEmpty(),
            )
        }
    }

    /**
     * Whether [snippet] goes into a file called [name] at [path], by its `include` and `exclude`
     * globs. A glob with no slash is matched against the name alone, as VS Code matches it.
     */
    fun applies(snippet: Snippet, name: String, path: String): Boolean {
        fun matches(pattern: String) = glob(pattern).matches(if ('/' in pattern) path else name)
        if (snippet.exclude.any(::matches)) return false
        return snippet.include.isEmpty() || snippet.include.any(::matches)
    }

    private fun strings(element: JsonElement?): List<String>? = when (element) {
        is JsonPrimitive -> element.takeIf { it.isString }?.contentOrNull?.let(::listOf)
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        else -> null
    }
}
