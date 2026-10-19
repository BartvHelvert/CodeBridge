package me.helvert.codebridge.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.manifest.ManifestStrings
import me.helvert.codebridge.manifest.VsixManifest
import java.nio.file.Path

/**
 * The entries VS Code offers to add to `launch.json`'s `configurations`: every debugger's
 * `configurationSnippets`, such as CodeLLDB's "CodeLLDB: Launch".
 *
 * [body] is the entry as the extension wrote it; [text] is it as VS Code snippet syntax, in which
 * `${2:Launch}` is a tab stop and `\$` a dollar sign.
 */
internal data class LaunchSnippet(val label: String, val description: String?, val sortText: String?, val body: JsonElement) {
    /** Laid out from [indent], a level deeper per [unit], as the file it goes into lays out JSON. */
    fun text(indent: String = "", unit: String = "\t"): String = LaunchSnippets.stringify(body, indent, unit)
}

internal object LaunchSnippets {

    /** [nls] is the extension's `package.nls.json`, for labels written as `%key%`. */
    fun of(manifest: VsixManifest, nls: Map<String, String> = emptyMap()): List<LaunchSnippet> =
        manifest.contributes.debuggers.flatMap { debugger ->
            debugger.configurationSnippets.orEmpty().mapNotNull { element ->
                val snippet = element as? JsonObject ?: return@mapNotNull null
                val label = (snippet["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val body = snippet["body"] ?: return@mapNotNull null
                LaunchSnippet(
                    label = localized(label, nls),
                    description = (snippet["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { localized(it, nls) },
                    sortText = (snippet["sortText"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    body = body,
                )
            }
        }

    /** The strings of `package.nls.json` next to an extension's manifest, or none. */
    fun nls(extensionDirectory: Path): Map<String, String> = ManifestStrings.nls(extensionDirectory)

    private fun localized(text: String, nls: Map<String, String>): String = ManifestStrings.localized(text, nls)

    /**
     * [body] as the snippet VS Code's JSON support inserts for it, a level deeper per [unit] from
     * [indent]: a string starting with `^` inserted as it stands, without quotes -- which is how a
     * body writes a value the snippet syntax would otherwise read, such as `"\${workspaceFolder}"`.
     * Every other string is quoted, and its content is still snippet syntax.
     */
    internal fun stringify(body: JsonElement, indent: String, unit: String = "\t"): String {
        val inner = indent + unit
        return when (body) {
            is JsonObject -> if (body.isEmpty()) "{}" else body.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, value) ->
                "$inner${JsonPrimitive(key)}: ${stringify(value, inner, unit)}"
            }
            is JsonArray -> if (body.isEmpty()) "[]" else body.joinToString(",\n", "[\n", "\n$indent]") { "$inner${stringify(it, inner, unit)}" }
            is JsonPrimitive -> if (body.isString && body.content.startsWith('^')) body.content.substring(1) else body.toString()
        }
    }
}
