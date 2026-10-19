package me.helvert.codebridge.manifest

import kotlinx.serialization.json.*
import me.helvert.codebridge.workspace.Jsonc

/**
 * Reads the `extension/package.json` of a VSIX into a [VsixManifest].
 *
 * Tolerant in the same way as the launch.json parser: an extension declaring one malformed
 * contribution should still give us every other contribution it declares.
 */
object VsixManifestParser {

    data class Result(
        val manifest: VsixManifest?,
        val problems: List<String> = emptyList(),
    )

    private val json = Json { isLenient = true }

    /**
     * [nls] is the extension's `package.nls.json`. Its strings replace the manifest's `%key%`
     * values everywhere before anything is read, as VS Code's scanner does, so a view name, a
     * welcome text or the `displayName` the extension host labels messages with reads as written.
     */
    fun parse(text: String, nls: Map<String, String> = emptyMap()): Result {
        val problems = mutableListOf<String>()

        val root = try {
            ManifestStrings.localized(json.parseToJsonElement(Jsonc.stripToJson(text)), nls)
        } catch (e: Exception) {
            return Result(null, listOf("package.json is not valid JSON: ${e.message}"))
        }

        if (root !is JsonObject) {
            return Result(null, listOf("package.json must contain a JSON object at the top level"))
        }

        val publisher = root.stringOrNull("publisher")
        val name = root.stringOrNull("name")
        val version = root.stringOrNull("version")
        if (publisher == null || name == null || version == null) {
            return Result(
                null,
                listOf("package.json must declare \"publisher\", \"name\" and \"version\" to be a VS Code extension"),
            )
        }

        return Result(
            VsixManifest(
                publisher = publisher,
                name = name,
                version = version,
                displayName = root.stringOrNull("displayName"),
                description = root.stringOrNull("description"),
                icon = root.stringOrNull("icon"),
                engineRange = (root["engines"] as? JsonObject)?.stringOrNull("vscode"),
                main = root.stringOrNull("main"),
                activationEvents = root.stringList("activationEvents"),
                extensionDependencies = root.stringList("extensionDependencies"),
                extensionPack = root.stringList("extensionPack"),
                categories = root.stringList("categories"),
                contributes = parseContributes(root["contributes"] as? JsonObject, problems),
                raw = root,
            ),
            problems,
        )
    }

    private fun parseContributes(node: JsonObject?, problems: MutableList<String>): Contributes {
        if (node == null) return Contributes()
        return Contributes(
            debuggers = parseDebuggers(node["debuggers"] as? JsonArray, problems),
            languages = parseLanguages(node["languages"] as? JsonArray, problems),
            languageServers = parseLanguageServers(node["languageServers"] ?: node["languageServer"], problems),
            breakpointLanguages = (node["breakpoints"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.stringOrNull("language") },
            declaredKeys = node.keys.toSet(),
        )
    }

    private fun parseLanguageServers(
        element: JsonElement?,
        problems: MutableList<String>
    ): List<LanguageServerContribution> {
        val entries = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        return entries.mapIndexedNotNull { index, obj ->
            val command = obj.stringOrNull("command") ?: run {
                problems += "contributes.languageServer[$index] has no \"command\""
                return@mapIndexedNotNull null
            }
            LanguageServerContribution(command, obj.stringList("args"), obj.stringList("languages"), obj)
        }
    }

    private fun parseDebuggers(array: JsonArray?, problems: MutableList<String>): List<DebuggerContribution> =
        array.orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: run {
                problems += "contributes.debuggers[$index] is not an object"
                return@mapIndexedNotNull null
            }
            val type = obj.stringOrNull("type") ?: run {
                problems += "contributes.debuggers[$index] has no \"type\""
                return@mapIndexedNotNull null
            }
            DebuggerContribution(
                type = type,
                label = obj.stringOrNull("label"),
                program = obj.stringOrNull("program"),
                runtime = obj.stringOrNull("runtime"),
                args = obj.stringList("args"),
                languages = obj.stringList("languages"),
                variables = (obj["variables"] as? JsonObject)
                    ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
                    ?.toMap()
                    .orEmpty(),
                configurationAttributes = obj["configurationAttributes"] as? JsonObject,
                initialConfigurations = obj["initialConfigurations"],
                configurationSnippets = obj["configurationSnippets"] as? JsonArray,
                raw = obj,
            )
        }

    private fun parseLanguages(array: JsonArray?, problems: MutableList<String>): List<LanguageContribution> =
        array.orEmpty().mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: run {
                problems += "contributes.languages[$index] is not an object"
                return@mapIndexedNotNull null
            }
            val id = obj.stringOrNull("id") ?: run {
                problems += "contributes.languages[$index] has no \"id\""
                return@mapIndexedNotNull null
            }
            LanguageContribution(
                id = id,
                aliases = obj.stringList("aliases"),
                extensions = obj.stringList("extensions"),
                filenames = obj.stringList("filenames"),
                filenamePatterns = obj.stringList("filenamePatterns"),
                configuration = obj.stringOrNull("configuration"),
                icon = parseLanguageIcon(obj["icon"]),
            )
        }

    /** `{ "light": "...", "dark": "..." }`, and nothing else; a half-declared pair is not one. */
    private fun parseLanguageIcon(element: JsonElement?): LanguageIcon? {
        val obj = element as? JsonObject ?: return null
        val light = obj.stringOrNull("light")?.takeIf { it.isNotBlank() } ?: return null
        val dark = obj.stringOrNull("dark")?.takeIf { it.isNotBlank() } ?: return null
        return LanguageIcon(light, dark)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
