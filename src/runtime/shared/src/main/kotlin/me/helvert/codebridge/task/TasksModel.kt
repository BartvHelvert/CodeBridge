package me.helvert.codebridge.task

import kotlinx.serialization.json.*
import me.helvert.codebridge.workspace.Jsonc

/** The executable subset of a VS Code tasks.json task. */
data class TaskFile(val version: String? = null, val tasks: List<TaskDefinition> = emptyList())
data class TaskDefinition(
    val label: String,
    val type: String = "shell",
    val command: String,
    val args: List<String> = emptyList(),
    val options: TaskOptions = TaskOptions(),
    val dependsOn: List<String> = emptyList(),
    val raw: JsonObject,
    val isBackground: Boolean = false,
    val problemMatcher: JsonElement? = null,
)

data class TaskOptions(val cwd: String? = null, val env: Map<String, String?> = emptyMap())

object TasksJsonParser {
    /** [parsed] is false when the text is not a JSON object at all, and [file] then knows nothing of it. */
    data class Result(val file: TaskFile, val problems: List<String> = emptyList(), val parsed: Boolean = true)

    private val json = Json { isLenient = true }

    fun parse(text: String): Result {
        val root = try {
            json.parseToJsonElement(Jsonc.stripToJson(text))
        } catch (e: Exception) {
            return Result(TaskFile(), listOf("tasks.json is not valid JSON: ${e.message}"), parsed = false)
        }
        val obj = root as? JsonObject ?: return Result(
            TaskFile(),
            listOf("tasks.json must contain a JSON object at the top level"),
            parsed = false
        )
        val problems = mutableListOf<String>()
        val tasks = (obj["tasks"] as? JsonArray).orEmpty().mapIndexedNotNull { index, value ->
            val task = value as? JsonObject
                ?: run { problems += "tasks[$index] is not an object"; return@mapIndexedNotNull null }
            val label = task.string("label") ?: task.string("taskName")
            ?: run { problems += "tasks[$index] has no \"label\""; return@mapIndexedNotNull null }
            val command = task.string("command")
                ?: run { problems += "task \"$label\" has no \"command\""; return@mapIndexedNotNull null }
            val type = task.string("type") ?: "shell"
            if (type !in setOf("shell", "process")) problems += "task \"$label\" uses unsupported type \"$type\""
            TaskDefinition(
                label,
                type,
                command,
                (task["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                parseOptions(task["options"]),
                parseDepends(task["dependsOn"]),
                task,
                (task["isBackground"] as? JsonPrimitive)?.booleanOrNull
                    ?: (obj["isBackground"] as? JsonPrimitive)?.booleanOrNull ?: false,
                task["problemMatcher"] ?: obj["problemMatcher"]
            )
        }
        return Result(TaskFile((obj["version"] as? JsonPrimitive)?.contentOrNull, tasks), problems)
    }

    private fun parseOptions(value: JsonElement?): TaskOptions {
        val obj = value as? JsonObject ?: return TaskOptions()
        val env = (obj["env"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
        return TaskOptions(obj.string("cwd"), env)
    }

    private fun parseDepends(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    private fun JsonObject?.orEmpty(): JsonObject = this ?: buildJsonObject { }
}
