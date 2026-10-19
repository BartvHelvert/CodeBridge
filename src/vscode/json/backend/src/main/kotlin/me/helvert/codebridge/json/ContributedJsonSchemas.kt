package me.helvert.codebridge.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.manifest.VsixManifest
import java.nio.file.Path

/**
 * `contributes.jsonValidation`: the JSON schemas extensions declare for files they know, such as
 * rust-analyzer's for `rust-project.json`.
 *
 * Each names its files by `fileMatch` patterns and its schema by `url`, either on the web or a path
 * inside the extension. Schemas VS Code serves itself (`vscode://schemas/...`) are left out: there is
 * nothing here to serve them.
 */
internal object ContributedJsonSchemas {

    /** One declared schema: where it is, and which files it applies to. */
    data class Contribution(val extension: String, val schema: Schema, val files: FileMatch)

    sealed interface Schema {
        data class Remote(val url: String) : Schema
        data class Local(val path: Path) : Schema
    }

    @Suppress("HttpUrlsUsage") // recognises the schemes extensions use
    fun of(extension: String, manifest: VsixManifest, directory: Path): List<Contribution> {
        val entries = (manifest.raw["contributes"] as? JsonObject)?.get("jsonValidation") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val patterns = when (val match = obj["fileMatch"]) {
                is JsonPrimitive -> listOf(match.content)
                is JsonArray -> match.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
            if (patterns.isEmpty()) return@mapNotNull null
            val schema = when {
                url.startsWith("https://") || url.startsWith("http://") -> Schema.Remote(url)
                Regex("^[a-zA-Z][\\w+.-]*:").containsMatchIn(url) -> return@mapNotNull null
                else -> Schema.Local(directory.resolve(url.removePrefix("./")).normalize()).takeIf { it.path.startsWith(directory) }
                    ?: return@mapNotNull null
            }
            Contribution(extension, schema, FileMatch(patterns))
        }
    }

    /**
     * VS Code's `fileMatch`: globs matched against the end of a file's path, so `package.json` is any
     * file of that name and `/.vscode/foo.json` one in a `.vscode` folder. `*` stays within a folder,
     * `**` crosses them, and a pattern starting with `!` excludes what it matches.
     */
    class FileMatch(patterns: List<String>) {
        private val included = patterns.filterNot { it.startsWith('!') }.mapNotNull(::regex)
        private val excluded = patterns.filter { it.startsWith('!') }.mapNotNull { regex(it.substring(1)) }

        /** [path] with `/` separators. */
        fun matches(path: String): Boolean {
            val normalized = "/" + path.replace('\\', '/').trimStart('/')
            return included.any { it.matches(normalized) } && excluded.none { it.matches(normalized) }
        }

        private fun regex(pattern: String): Regex? {
            // VS Code's own locations, `%APP_SETTINGS_HOME%/...`, are files of its own.
            if (pattern.isEmpty() || pattern.startsWith('%')) return null
            val anchored = if (pattern.startsWith('/') || pattern.startsWith('*')) pattern else "/$pattern"
            val body = StringBuilder()
            var index = 0
            while (index < anchored.length) {
                val char = anchored[index]
                when {
                    anchored.startsWith("**/", index) -> { body.append("(?:.*/)?"); index += 3; continue }
                    anchored.startsWith("**", index) -> { body.append(".*"); index += 2; continue }
                    char == '*' -> body.append("[^/]*")
                    char == '?' -> body.append("[^/]")
                    else -> body.append(Regex.escape(char.toString()))
                }
                index++
            }
            return Regex(".*$body")
        }
    }
}
