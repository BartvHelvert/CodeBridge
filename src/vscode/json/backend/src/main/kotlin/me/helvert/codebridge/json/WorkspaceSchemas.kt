package me.helvert.codebridge.json

import kotlinx.serialization.json.*
import me.helvert.codebridge.launch.LaunchSchema
import me.helvert.codebridge.manifest.ContributedSettings
import me.helvert.codebridge.manifest.VsixManifest

/**
 * The JSON schemas VS Code gives `launch.json` and `settings.json`, built from what the enabled
 * extensions declare.
 *
 * Both are assembled the way the workbench assembles them. `launch.json` takes one entry per debugger
 * type and request from `contributes.debuggers[].configurationAttributes`, with the fields every
 * configuration shares; `settings.json` takes every `contributes.configuration` property. The
 * extensions' own schema text is kept as written, apart from VS Code's `enumDescriptions`, which the
 * IDE reads from `x-intellij-enum-metadata` instead.
 */
internal object WorkspaceSchemas {

    fun launch(manifests: List<VsixManifest>): JsonObject = buildJsonObject {
        put($$"$schema", DRAFT_07)
        put("type", "object")
        put("title", "Launch")
        putJsonObject("default") {
            put("version", "0.2.0")
            putJsonArray("configurations") {}
            putJsonArray("compounds") {}
        }
        putJsonObject("properties") {
            putJsonObject("version") {
                put("type", "string")
                put("description", "Version of this file format.")
                put("default", "0.2.0")
            }
            putJsonObject("configurations") {
                put("type", "array")
                put("description", "List of configurations. Add new configurations or edit existing ones by using IntelliSense.")
                put("items", configuration(manifests))
                putJsonArray("default") {}
            }
            put("compounds", COMPOUNDS)
            put("inputs", INPUTS)
        }
    }

    /**
     * [workspace] is a project's `.vscode/settings.json`, which, as in VS Code, does not offer the
     * settings an extension scopes to the application or the machine: those belong in user settings.
     */
    fun settings(manifests: List<VsixManifest>, workspace: Boolean): JsonObject {
        val properties = linkedMapOf<String, JsonElement>()
        for (manifest in manifests) {
            for ((key, schema) in ContributedSettings.of(manifest)) {
                val scope = ((schema as? JsonObject)?.get("scope") as? JsonPrimitive)?.content
                if (workspace && scope in USER_ONLY_SCOPES) continue
                properties[key] = withEnumMetadata(schema)
            }
        }
        val overridable = properties.filterValues { schema ->
            ((schema as? JsonObject)?.get("scope") as? JsonPrimitive)?.content == "language-overridable"
        }
        return buildJsonObject {
            put($$"$schema", DRAFT_07)
            put("type", "object")
            put("properties", JsonObject(properties))
            putJsonObject("patternProperties") {
                putJsonObject(LANGUAGE_OVERRIDE) {
                    put("type", "object")
                    put("description", "Configure settings to be overridden for a language.")
                    put("properties", JsonObject(overridable))
                }
            }
        }
    }

    /**
     * One `configurations` entry: the fields every entry has, then the shape of the debugger and
     * request it names. Debuggers several extensions contribute to under one type are merged first,
     * as VS Code merges them.
     *
     * VS Code lists the shapes under `oneOf`. Here `type` and `request` select one with `if`/`then`
     * instead: when an entry matches no shape, the IDE reports the shape that came closest, which for
     * a launch entry missing its `program` is the attach shape, telling the user `request` should be
     * "attach". Selected, the entry is checked against its own shape only.
     */
    private fun configuration(manifests: List<VsixManifest>): JsonObject {
        val debuggers = LaunchSchema.debuggers(manifests)
        val requests = debuggers.values.flatMap { it.attributes.keys }.ifEmpty { listOf("launch", "attach") }.distinct()
        // The whole choice, in every shape as well as here: while an entry's `type` is still being
        // typed the IDE merges every shape, and the last one's `type` would otherwise be all it offers.
        val selectors = buildJsonObject {
            putJsonObject("type") {
                if (debuggers.isEmpty()) {
                    put("type", "string")
                } else {
                    put("enum", buildJsonArray { debuggers.keys.forEach { add(it) } })
                    put("x-intellij-enum-metadata", buildJsonObject {
                        debuggers.forEach { (type, debugger) -> debugger.label?.let { put(type, it) } }
                    })
                }
                put("description", TYPE_DESCRIPTION)
            }
            putJsonObject("request") {
                put("enum", buildJsonArray { requests.forEach { add(it) } })
                put("description", REQUEST_DESCRIPTION)
            }
        }
        val shapes = debuggers.flatMap { (type, contribution) ->
            contribution.attributes.mapNotNull { (request, shape) -> (shape as? JsonObject)?.let { Triple(type, request, request(it, selectors)) } }
        }
        val entry = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add("name"); add("type"); add("request") })
            putJsonObject("properties") {
                LaunchSchema.COMMON.forEach { (key, schema) -> put(key, schema) }
                selectors.forEach { (key, schema) -> put(key, schema) }
            }
        }
        // One chain rather than an `allOf` of conditions: the IDE's completion merges an `allOf`
        // before it looks at any `if`, and would offer every shape's properties in every entry.
        val chain = shapes.foldRight<_, JsonObject?>(null) { (type, request, shape), otherwise ->
            buildJsonObject {
                putJsonObject("if") {
                    put("required", buildJsonArray { add("type"); add("request") })
                    putJsonObject("properties") {
                        putJsonObject("type") { put("const", type) }
                        putJsonObject("request") { put("const", request) }
                    }
                }
                put("then", shape)
                otherwise?.let { put("else", it) }
            }
        }
        return JsonObject(entry + chain.orEmpty())
    }

    /** One debugger's request shape, completed as `Debugger.getSchemaAttributes` completes it. */
    private fun request(attributes: JsonObject, selectors: JsonObject): JsonObject {
        val declared = (attributes["properties"] as? JsonObject).orEmpty()
        val required = (attributes["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        return buildJsonObject {
            attributes.forEach { (key, value) -> if (key !in setOf("properties", "required")) put(key, withEnumMetadata(value)) }
            put("type", "object")
            put("additionalProperties", false)
            put("required", buildJsonArray { (listOf("name", "type", "request") + required).distinct().forEach { add(it) } })
            putJsonObject("properties") {
                declared.forEach { (key, schema) -> put(key, withEnumMetadata(schema)) }
                selectors.forEach { (key, schema) -> put(key, schema) }
                LaunchSchema.COMMON.forEach { (key, schema) -> put(key, schema) }
            }
        }
    }

    /**
     * The properties a `configurations` entry of [type] and [request] may have, read back from a
     * [launch] schema; null when no installed debugger declares that pair.
     */
    fun configurationProperties(launch: JsonObject, type: String, request: String): Set<String>? {
        val entry = ((launch["properties"] as? JsonObject)?.get("configurations") as? JsonObject)?.get("items") as? JsonObject
        return generateSequence(entry) { it["else"] as? JsonObject }.firstNotNullOfOrNull { condition ->
            val selects = ((condition["if"] as? JsonObject)?.get("properties") as? JsonObject).orEmpty()
            fun constant(key: String) = ((selects[key] as? JsonObject)?.get("const") as? JsonPrimitive)?.content
            if (constant("type") == type && constant("request") == request) {
                ((condition["then"] as? JsonObject)?.get("properties") as? JsonObject)?.keys
            } else null
        }
    }

    /**
     * Every `enumDescriptions` in [schema] also as the `x-intellij-enum-metadata` the IDE reads, and
     * every `markdownDeprecationMessage` as the `deprecationMessage` it reports a deprecated setting by.
     */
    internal fun withEnumMetadata(schema: JsonElement): JsonElement = when (schema) {
        is JsonArray -> JsonArray(schema.map(::withEnumMetadata))
        is JsonObject -> {
            val deprecation = (schema["markdownDeprecationMessage"] as? JsonPrimitive)?.takeIf { it.isString && "deprecationMessage" !in schema }
            val walked = schema.mapValues { (_, value) -> withEnumMetadata(value) } +
                listOfNotNull(deprecation?.let { "deprecationMessage" to it })
            val values = schema["enum"] as? JsonArray
            val descriptions = (schema["markdownEnumDescriptions"] ?: schema["enumDescriptions"]) as? JsonArray
            if (values == null || descriptions == null || "x-intellij-enum-metadata" in schema) JsonObject(walked)
            else JsonObject(walked + ("x-intellij-enum-metadata" to buildJsonObject {
                values.zip(descriptions).forEach { (value, description) ->
                    val text = (description as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (value is JsonPrimitive && !text.isNullOrEmpty()) put(value.content, text)
                }
            }))
        }
        else -> schema
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    /**
     * A `.code-workspace` file, VS Code's multi-root workspace: its `folders`, and the workspace's own
     * `settings`, `launch` and `tasks`, typed as those files are.
     */
    fun codeWorkspace(launch: JsonObject, tasks: JsonObject, settings: JsonObject): JsonObject = buildJsonObject {
        put($$"$schema", DRAFT_07)
        put("type", "object")
        put("title", "VS Code workspace")
        putJsonObject("default") { putJsonArray("folders") { addJsonObject { put("path", ".") } } }
        putJsonObject("properties") {
            putJsonObject("folders") {
                put("type", "array")
                put("description", "Root folders in the workspace.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("default") { put("path", "") }
                    putJsonObject("properties") {
                        putJsonObject("path") {
                            put("type", "string")
                            put("description", "A file path, absolute or relative to this workspace file. e.g. `/root/folderA` or `./folderA`.")
                        }
                        putJsonObject("uri") { put("type", "string"); put("description", "URI of the folder.") }
                        putJsonObject("name") { put("type", "string"); put("description", "An optional name for the folder.") }
                    }
                }
            }
            put("settings", without(settings, $$"$schema"))
            put("launch", without(launch, $$"$schema"))
            put("tasks", without(tasks, $$"$schema"))
            putJsonObject("extensions") {
                put("type", "object")
                putJsonObject("properties") {
                    for (key in listOf("recommendations", "unwantedRecommendations")) putJsonObject(key) {
                        put("type", "array"); putJsonObject("items") { put("type", "string") }
                    }
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun without(schema: JsonObject, key: String) = JsonObject(schema - key)

    private const val DRAFT_07 = "http://json-schema.org/draft-07/schema#"
    private const val TYPE_DESCRIPTION = "Type of configuration."
    private const val REQUEST_DESCRIPTION = "Request type of configuration. Can be \"launch\" or \"attach\"."

    /** `[rust]`, or `[rust][toml]` for several languages at once. */
    private const val LANGUAGE_OVERRIDE = "^(\\[[^\\]]+\\])+$"
    private val USER_ONLY_SCOPES = setOf("application", "machine")

    private val COMPOUNDS: JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "List of compounds. Each compound references multiple configurations which will get launched together.")
        putJsonArray("default") {}
        put("items", LaunchSchema.COMPOUND)
    }

    @Suppress("DuplicatedCode") // each input type lists its own properties, as VS Code's schema does
    internal val INPUTS: JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "User inputs. Used for defining user input prompts, such as free string input or a choice from several options.")
        putJsonObject("items") {
            putJsonArray("oneOf") {
                add(input("promptString", "The 'promptString' type opens an input box to ask the user for input.", listOf("description")) {
                    putJsonObject("description") { put("type", "string"); put("description", "The description is shown when the user is prompted for input.") }
                    putJsonObject("default") { put("type", "string"); put("description", "The default value for the input.") }
                    putJsonObject("password") { put("type", "boolean"); put("description", "Set to true to input with a password prompt that will not show the typed value.") }
                })
                add(input("pickString", "The 'pickString' type shows a selection list.", listOf("description", "options")) {
                    putJsonObject("description") { put("type", "string"); put("description", "The description is shown when the user is prompted for input.") }
                    putJsonObject("default") { put("type", "string"); put("description", "The default value for the input.") }
                    putJsonObject("options") {
                        put("type", "array")
                        put("description", "An array of strings that defines the options for a quick pick.")
                        putJsonObject("items") {
                            putJsonArray("oneOf") {
                                add(buildJsonObject { put("type", "string") })
                                add(buildJsonObject {
                                    put("type", "object")
                                    put("required", buildJsonArray { add("value") })
                                    put("additionalProperties", false)
                                    putJsonObject("properties") {
                                        putJsonObject("label") { put("type", "string"); put("description", "Label for the option.") }
                                        putJsonObject("value") { put("type", "string"); put("description", "Value for the option.") }
                                    }
                                })
                            }
                        }
                    }
                })
                add(input("command", "The 'command' type executes a command.", listOf("command")) {
                    putJsonObject("command") { put("type", "string"); put("description", "The command to execute for this input variable.") }
                    putJsonObject("args") { put("description", "Optional arguments passed to the command.") }
                })
            }
        }
    }

    private fun input(
        type: String,
        description: String,
        required: List<String>,
        properties: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", buildJsonArray { (listOf("id", "type") + required).forEach { add(it) } })
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string"); put("description", $$"The input's id is used to associate an input with a variable of the form ${input:id}.") }
            putJsonObject("type") {
                put("description", "The type of user input prompt to use.")
                put("enum", buildJsonArray { add(type) })
                put("x-intellij-enum-metadata", buildJsonObject { put(type, description) })
            }
            properties()
        }
    }
}
