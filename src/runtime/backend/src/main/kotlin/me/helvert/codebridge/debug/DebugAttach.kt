package me.helvert.codebridge.debug

import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.workspace.Jsonc

/**
 * Attaching to a process by its id, from what a debugger extension declares: an attach
 * configuration -- a `configurationSnippets` body or an `initialConfigurations` entry -- whose
 * process comes from one of its own process-picking variables (`${command:pickProcess}`, CodeLLDB's
 * `${command:pickMyProcess}`, js-debug's `${command:PickProcess}`). The id chosen elsewhere takes
 * the variable's place, which is what the extension's own picker would have answered with.
 */
object DebugAttach {

    /** One debugger's attach configuration; [field] is where the process id goes. */
    data class Template(
        val extensionId: String,
        val type: String,
        val label: String,
        val body: JsonObject,
        val field: String,
    )

    /** One template per debugger that has one, in the order the extensions are given. */
    fun templates(extensions: List<InstalledExtension>): List<Template> =
        extensions.filter { it.enabled }.flatMap { extension ->
            extension.manifest.contributes.debuggers.mapNotNull { debugger ->
                val pickers = debugger.variables.keys.filter { PROCESS_VARIABLE.matches(it) }.toSet()
                if (pickers.isEmpty()) return@mapNotNull null
                val candidates =
                    debugger.configurationSnippets.orEmpty().mapNotNull { (it as? JsonObject)?.get("body") } +
                            (debugger.initialConfigurations as? JsonArray).orEmpty()
                candidates.firstNotNullOfOrNull { candidate ->
                    val body = runCatching { snippet(candidate) }.getOrNull() as? JsonObject
                        ?: return@firstNotNullOfOrNull null
                    if ((body["request"] as? JsonPrimitive)?.contentOrNull != "attach") return@firstNotNullOfOrNull null
                    if ((body["type"] as? JsonPrimitive)?.contentOrNull != debugger.type) return@firstNotNullOfOrNull null
                    val field = body.entries.firstOrNull { (_, value) ->
                        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        text != null && COMMAND.matchEntire(text)?.groupValues?.get(1) in pickers
                    }?.key ?: return@firstNotNullOfOrNull null
                    Template(
                        extension.id,
                        debugger.type,
                        debugger.label ?: extension.manifest.displayName ?: debugger.type,
                        body,
                        field
                    )
                }
            }
        }

    /** [template] attached to process [pid], named for the process as the run widget shows it. */
    fun configuration(template: Template, pid: Long, process: String): JsonObject = buildJsonObject {
        template.body.forEach { (key, value) -> put(key, value) }
        // A string, as the extension's picker answers and VS Code substitutes it.
        put(template.field, pid.toString())
        put("name", "Attach to $process ($pid)")
    }

    /**
     * A snippet body as the configuration it inserts. A string is snippet text: `${1:Attach}` is its
     * default, `$1` nothing, `\$` a dollar. One that starts with `^` is inserted unquoted -- CodeLLDB
     * writes its `pid` that way, with a comment after it -- so it is read as JSON.
     */
    fun snippet(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { snippet(it.value) })
        is JsonArray -> JsonArray(element.map(::snippet))
        is JsonPrimitive -> if (!element.isString) element else {
            val text = element.content
            if (text.startsWith("^")) Json.parseToJsonElement(Jsonc.stripToJson(placeholders(text.substring(1))).trim())
            else JsonPrimitive(placeholders(text))
        }
    }

    private fun placeholders(text: String): String = text
        .replace(PLACEHOLDER_DEFAULT, "$1")
        .replace(PLACEHOLDER, "")
        .replace("\\$", "$")

    /** The name every debugger gives the variable that asks which process; CodeLLDB adds `My`. */
    private val PROCESS_VARIABLE = Regex("pick(my)?process", RegexOption.IGNORE_CASE)
    private val COMMAND = Regex("""\$\{command:([^}]+)}""")
    private val PLACEHOLDER_DEFAULT = Regex("""(?<!\\)\$\{\d+:([^}]*)}""")
    private val PLACEHOLDER = Regex("""(?<!\\)\$\{\d+}|(?<!\\)\$\d+""")
}
