package me.helvert.codebridge.launch

import me.helvert.codebridge.workspace.VariableSubstitutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Parsed contents of a `.vscode/launch.json`.
 */
data class LaunchFile(
    val version: String? = null,
    val configurations: List<LaunchConfiguration> = emptyList(),
    val compounds: List<CompoundConfiguration> = emptyList(),
    val inputs: List<InputDefinition> = emptyList(),
) {
    fun configuration(name: String): LaunchConfiguration? = configurations.firstOrNull { it.name == name }
    fun compound(name: String): CompoundConfiguration? = compounds.firstOrNull { it.name == name }
    fun input(id: String): InputDefinition? = inputs.firstOrNull { it.id == id }
}

/**
 * A single entry of `configurations`.
 *
 * Only the handful of keys the *client* has to understand are modelled. Everything else stays in
 * [raw] on purpose: the Debug Adapter Protocol does not standardise the body of a `launch` request,
 * so keys like `program`, `cwd` or `sourceMaps` are adapter-specific and get forwarded verbatim.
 * Interpreting them here would mean re-implementing every adapter's schema for no benefit.
 */
data class LaunchConfiguration(
    val name: String,
    val type: String,
    val request: String,
    val raw: JsonObject,
) {
    val preLaunchTask: String? get() = string("preLaunchTask")
    val postDebugTask: String? get() = string("postDebugTask")

    /** When set, the adapter is already listening on this port instead of being spawned by us. */
    val debugServer: Int? get() = int("debugServer")

    fun string(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(key: String): Int? = (raw[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    fun boolean(key: String): Boolean? = (raw[key] as? JsonPrimitive)?.booleanOrNull

    /** VS Code accepts either a list or a single string for list-shaped keys such as `args`. */
    fun stringList(key: String): List<String> = when (val value = raw[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }

    /** A `null` value means "remove this variable from the inherited environment". */
    fun env(): Map<String, String?> {
        val obj = raw["env"] as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull }
    }
}

/** An entry of `compounds`: starts several configurations together. */
data class CompoundConfiguration(
    val name: String,
    val members: List<CompoundMember> = emptyList(),
    val preLaunchTask: String? = null,
    val stopAll: Boolean = false,
)

/** A compound member, either `"Name"` or `{ "folder": "...", "name": "..." }`. */
data class CompoundMember(
    val name: String,
    val folder: String? = null,
)

/** An entry of `inputs`, referenced from a configuration as `${input:id}`. */
sealed interface InputDefinition {
    val id: String

    data class PromptString(
        override val id: String,
        val description: String? = null,
        val default: String? = null,
        val password: Boolean = false,
    ) : InputDefinition

    data class PickString(
        override val id: String,
        val description: String? = null,
        val options: List<PickOption> = emptyList(),
        val default: String? = null,
    ) : InputDefinition

    /** Delegates to a command contributed by an extension - unresolvable without an extension host. */
    data class Command(
        override val id: String,
        val command: String,
        val args: JsonObject? = null,
    ) : InputDefinition
}

/** An option of a `pickString` input, either `"value"` or `{ "label": "...", "value": "..." }`. */
data class PickOption(
    val label: String,
    val value: String,
)

/**
 * Expands `${...}` variables throughout a configuration.
 *
 * Deliberately an extension rather than a member of [VariableSubstitutor]: the substitution engine
 * serves `tasks.json` and `settings.json` too, so it must not depend on the launch.json model.
 */
fun VariableSubstitutor.substitute(configuration: LaunchConfiguration): LaunchConfiguration =
    configuration.copy(raw = substitute(configuration.raw) as JsonObject)
