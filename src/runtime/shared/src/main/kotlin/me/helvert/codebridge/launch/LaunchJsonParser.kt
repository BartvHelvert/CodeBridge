package me.helvert.codebridge.launch

import kotlinx.serialization.json.*
import me.helvert.codebridge.workspace.Jsonc

/**
 * Reads a `.vscode/launch.json` into a [LaunchFile].
 *
 * Deliberately tolerant: a single malformed configuration must not cost the user every other
 * configuration in the file, because launch.json is hand-edited and is very often mid-edit when
 * the IDE reads it. Problems are collected in [Result.problems] rather than thrown.
 */
object LaunchJsonParser {

    data class Result(
        val file: LaunchFile,
        val problems: List<String> = emptyList(),
    )

    private val json = Json { isLenient = true }

    fun parse(text: String): Result {
        val problems = mutableListOf<String>()

        val root = try {
            json.parseToJsonElement(Jsonc.stripToJson(text))
        } catch (e: Exception) {
            return Result(LaunchFile(), listOf("launch.json is not valid JSON: ${e.message}"))
        }

        if (root !is JsonObject) {
            return Result(LaunchFile(), listOf("launch.json must contain a JSON object at the top level"))
        }

        return Result(
            LaunchFile(
                version = (root["version"] as? JsonPrimitive)?.contentOrNull,
                configurations = parseConfigurations(root, problems),
                compounds = parseCompounds(root, problems),
                inputs = parseInputs(root, problems),
            ),
            problems,
        )
    }

    private fun parseConfigurations(root: JsonObject, problems: MutableList<String>): List<LaunchConfiguration> {
        val array = root["configurations"] as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: run {
                problems += "configurations[$index] is not an object"
                return@mapIndexedNotNull null
            }
            val name = obj.stringOrNull("name")
            val type = obj.stringOrNull("type")
            val request = obj.stringOrNull("request")
            problems += when {
                name == null -> "configurations[$index] has no \"name\""
                type == null -> "configuration \"$name\" has no \"type\""
                request == null -> "configuration \"$name\" has no \"request\""
                else -> return@mapIndexedNotNull LaunchConfiguration(name, type, request, obj)
            }
            null
        }
    }

    private fun parseCompounds(root: JsonObject, problems: MutableList<String>): List<CompoundConfiguration> {
        val array = root["compounds"] as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: run {
                problems += "compounds[$index] is not an object"
                return@mapIndexedNotNull null
            }
            val name = obj.stringOrNull("name") ?: run {
                problems += "compounds[$index] has no \"name\""
                return@mapIndexedNotNull null
            }
            val members = (obj["configurations"] as? JsonArray).orEmpty().mapNotNull { member ->
                when (member) {
                    is JsonPrimitive -> member.contentOrNull?.let { CompoundMember(it) }
                    is JsonObject -> member.stringOrNull("name")
                        ?.let { CompoundMember(it, member.stringOrNull("folder")) }

                    else -> null
                }
            }
            CompoundConfiguration(
                name = name,
                members = members,
                preLaunchTask = obj.stringOrNull("preLaunchTask"),
                stopAll = (obj["stopAll"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    private fun parseInputs(root: JsonObject, problems: MutableList<String>): List<InputDefinition> {
        val array = root["inputs"] as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: run {
                problems += "inputs[$index] is not an object"
                return@mapIndexedNotNull null
            }
            val id = obj.stringOrNull("id") ?: run {
                problems += "inputs[$index] has no \"id\""
                return@mapIndexedNotNull null
            }
            val description = obj.stringOrNull("description")
            when (val type = obj.stringOrNull("type")) {
                "promptString" -> InputDefinition.PromptString(
                    id = id,
                    description = description,
                    default = obj.stringOrNull("default"),
                    password = (obj["password"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )

                "pickString" -> InputDefinition.PickString(
                    id = id,
                    description = description,
                    options = parsePickOptions(obj["options"]),
                    default = obj.stringOrNull("default"),
                )

                "command" -> obj.stringOrNull("command")
                    ?.let { InputDefinition.Command(id, it, obj["args"] as? JsonObject) }
                    ?: run {
                        problems += "input \"$id\" is of type \"command\" but has no \"command\""
                        null
                    }

                else -> {
                    problems += "input \"$id\" has unsupported type \"$type\""
                    null
                }
            }
        }
    }

    private fun parsePickOptions(element: JsonElement?): List<PickOption> =
        (element as? JsonArray).orEmpty().mapNotNull { option ->
            when (option) {
                is JsonPrimitive -> option.contentOrNull?.let { PickOption(it, it) }
                is JsonObject -> {
                    val value = option.stringOrNull("value")
                    value?.let { PickOption(option.stringOrNull("label") ?: it, it) }
                }

                else -> null
            }
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
