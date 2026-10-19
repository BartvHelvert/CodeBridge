package me.helvert.codebridge.json

import kotlinx.serialization.json.*
import me.helvert.codebridge.manifest.VsixManifest

/**
 * The JSON schema VS Code gives `tasks.json`, built from what the enabled extensions declare.
 *
 * Every task has the fields VS Code's task system reads whatever runs it (`label`, `group`,
 * `dependsOn`, `presentation`, ...). Its `type` is `shell`, `process`, or one an extension declares
 * in `contributes.taskDefinitions` -- rust-analyzer's `cargo` -- and selects, as `type` and
 * `request` do in `launch.json`, the shape the rest is checked against: a command line for the
 * first two, the definition's own properties for the others. `problemMatcher` offers VS Code's
 * built-in matchers and those in `contributes.problemMatchers`.
 */
internal object TaskSchemas {

    fun tasks(manifests: List<VsixManifest>): JsonObject {
        val definitions = manifests.flatMap(::taskDefinitions).distinctBy { it.first }
        val matchers = BUILT_IN_MATCHERS + manifests.flatMap(::problemMatchers)
        val types = listOf("shell" to "The task runs as a shell command line.", "process" to "The task runs a program directly.") +
            definitions.map { (type, _) -> type to "A task the extension that declares '$type' runs." }
        val common = common(types, matchers)
        val entry = buildJsonObject {
            put("type", "object")
            put("properties", common)
        }
        val shapes = listOf("shell", "process").map { it to commandLine(common) } +
            definitions.map { (type, definition) -> type to contributed(definition, common) }
        val chain = shapes.foldRight<_, JsonObject?>(null) { (type, shape), otherwise ->
            buildJsonObject {
                putJsonObject("if") {
                    put("required", buildJsonArray { add("type") })
                    putJsonObject("properties") { putJsonObject("type") { put("const", type) } }
                }
                put("then", shape)
                otherwise?.let { put("else", it) }
            }
        }
        return buildJsonObject {
            put($$"$schema", DRAFT_07)
            put("type", "object")
            put("title", "Tasks")
            putJsonObject("default") {
                put("version", "2.0.0")
                putJsonArray("tasks") {}
            }
            putJsonObject("properties") {
                putJsonObject("version") {
                    put("enum", buildJsonArray { add("2.0.0") })
                    put("description", "The configuration's version number.")
                }
                putJsonObject("tasks") {
                    put("type", "array")
                    put("description", "The task configurations. Usually these are enrichments of a task already defined in the external task runner.")
                    put("items", JsonObject(entry + chain.orEmpty()))
                }
                put("inputs", WorkspaceSchemas.INPUTS)
                put("options", OPTIONS)
                put("presentation", PRESENTATION)
                put("problemMatcher", problemMatcher(matchers))
                for (platform in PLATFORMS) putJsonObject(platform) {
                    put("type", "object")
                    put("description", "Settings for ${PLATFORM_NAMES.getValue(platform)} only.")
                    putJsonObject("properties") {
                        put("options", OPTIONS)
                        put("presentation", PRESENTATION)
                    }
                }
            }
        }
    }

    /** `contributes.taskDefinitions`: each `type`, with the schema of the rest of its tasks. */
    private fun taskDefinitions(manifest: VsixManifest): List<Pair<String, JsonObject>> {
        val definitions = (manifest.raw["contributes"] as? JsonObject)?.get("taskDefinitions") as? JsonArray ?: return emptyList()
        return definitions.mapNotNull { definition ->
            val obj = definition as? JsonObject ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            type to obj
        }
    }

    /** `contributes.problemMatchers`, by the `$name` a task refers to each with. */
    private fun problemMatchers(manifest: VsixManifest): List<Pair<String, String>> {
        val matchers = (manifest.raw["contributes"] as? JsonObject)?.get("problemMatchers") as? JsonArray ?: return emptyList()
        return matchers.mapNotNull { matcher ->
            val obj = matcher as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val label = (obj["label"] as? JsonPrimitive)?.contentOrNullString() ?: "From ${manifest.displayName ?: manifest.name}"
            $$"$$$name" to label
        }
    }

    private fun JsonPrimitive.contentOrNullString(): String? = takeIf { it.isString }?.content

    /** A shell or process task: what to run. */
    private fun commandLine(common: JsonObject): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            common.forEach { (key, schema) -> put(key, schema) }
            putJsonObject("command") {
                put("description", "The command to be executed. Can be an external program or a shell command.")
                putJsonArray("type") { add("string"); add("array") }
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("args") {
                put("type", "array")
                put("description", "Arguments passed to the command when this task is invoked.")
                putJsonObject("items") { putJsonArray("type") { add("string"); add("object") } }
            }
            put("options", OPTIONS)
            for (platform in PLATFORMS) putJsonObject(platform) {
                put("type", "object")
                put("description", "Settings for ${PLATFORM_NAMES.getValue(platform)} only.")
                putJsonObject("properties") {
                    putJsonObject("command") { putJsonArray("type") { add("string"); add("array") } }
                    putJsonObject("args") { put("type", "array") }
                    put("options", OPTIONS)
                }
            }
        }
    }

    /** A task of an extension's type: the properties its definition declares, required as it says. */
    private fun contributed(definition: JsonObject, common: JsonObject): JsonObject = buildJsonObject {
        val declared = (definition["properties"] as? JsonObject).orEmpty()
        val required = (definition["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        put("type", "object")
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
        putJsonObject("properties") {
            common.forEach { (key, schema) -> put(key, schema) }
            declared.forEach { (key, schema) -> if (key != "type") put(key, WorkspaceSchemas.withEnumMetadata(schema)) }
            put("options", OPTIONS)
        }
    }

    /** What every task may say, whichever runs it. */
    private fun common(types: List<Pair<String, String>>, matchers: List<Pair<String, String>>): JsonObject = buildJsonObject {
        putJsonObject("label") { put("type", "string"); put("description", "The task's user interface label.") }
        putJsonObject("type") {
            put("enum", buildJsonArray { types.forEach { add(it.first) } })
            put("x-intellij-enum-metadata", buildJsonObject { types.forEach { (type, description) -> put(type, description) } })
            put("description", "Defines whether the task is run as a process or as a command inside a shell, or by the extension that declares its type.")
        }
        putJsonObject("detail") { put("type", "string"); put("description", "An optional description of a task that shows in the Run Task quick pick as a detail.") }
        putJsonObject("group") {
            put("description", "Defines to which execution group this task belongs to.")
            putJsonArray("anyOf") {
                addJsonObject {
                    put("enum", buildJsonArray { add("build"); add("test"); add("none") })
                    put("x-intellij-enum-metadata", buildJsonObject {
                        put("build", "Marks the task as a build task.")
                        put("test", "Marks the task as a test task.")
                        put("none", "Assigns the task to no group.")
                    })
                }
                addJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("kind") { put("enum", buildJsonArray { add("build"); add("test"); add("none") }); put("description", "The task's execution group.") }
                        putJsonObject("isDefault") {
                            putJsonArray("type") { add("boolean"); add("string") }
                            put("description", "Defines if this task is the default task in the group, or a glob to match the file which should trigger this task.")
                        }
                    }
                }
            }
        }
        putJsonObject("dependsOn") {
            put("description", "Either a string representing another task or an array of other tasks that this task depends on.")
            putJsonArray("anyOf") {
                addJsonObject { put("type", "string") }
                addJsonObject { put("type", "array"); putJsonObject("items") { putJsonArray("type") { add("string"); add("object") } } }
                addJsonObject { put("type", "object") }
            }
        }
        putJsonObject("dependsOrder") {
            put("enum", buildJsonArray { add("parallel"); add("sequence") })
            put("x-intellij-enum-metadata", buildJsonObject {
                put("parallel", "Run all dependsOn tasks in parallel.")
                put("sequence", "Run all dependsOn tasks in sequence.")
            })
            put("description", "Determines the order of the dependsOn tasks for this task.")
        }
        putJsonObject("isBackground") { put("type", "boolean"); put("description", "Whether the executed task is kept alive and is running in the background.") }
        putJsonObject("promptOnClose") { put("type", "boolean"); put("description", "Whether the user is prompted when VS Code closes with a running task.") }
        putJsonObject("hide") { put("type", "boolean"); put("description", "Hide this task from the run task quick pick.") }
        putJsonObject("icon") {
            put("type", "object")
            put("description", "The icon used for the task in the terminal.")
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "string"); put("description", "The id of a codicon.") }
                putJsonObject("color") { put("type", "string"); put("description", "A terminal color theme key.") }
            }
        }
        put("presentation", PRESENTATION)
        put("problemMatcher", problemMatcher(matchers))
        putJsonObject("runOptions") {
            put("type", "object")
            put("description", "The task's run related options.")
            putJsonObject("properties") {
                putJsonObject("reevaluateOnRerun") { put("type", "boolean"); put("description", "Whether to reevaluate task variables on rerun.") }
                putJsonObject("runOn") {
                    put("enum", buildJsonArray { add("default"); add("folderOpen") })
                    put("description", "Specifies when the task should be run.")
                }
                putJsonObject("instanceLimit") { put("type", "number"); put("description", "The number of instances of the task that are allowed to run simultaneously.") }
                putJsonObject("instancePolicy") {
                    put("enum", buildJsonArray { add("terminateNewest"); add("terminateOldest"); add("prompt"); add("warn"); add("silent") })
                    put("description", "Policy to apply when instance limit is reached.")
                }
            }
        }
    }

    private fun problemMatcher(matchers: List<Pair<String, String>>): JsonObject = buildJsonObject {
        val named = buildJsonObject {
            put("enum", buildJsonArray { matchers.forEach { add(it.first) } })
            put("x-intellij-enum-metadata", buildJsonObject { matchers.forEach { (name, label) -> put(name, label) } })
        }
        put("description", "The problem matcher(s) to use. Can either be a string or a problem matcher definition or an array of strings and problem matchers.")
        putJsonArray("anyOf") {
            add(named)
            addJsonObject { put("type", "string") }
            addJsonObject { put("type", "object") }
            addJsonObject {
                put("type", "array")
                putJsonObject("items") {
                    putJsonArray("anyOf") {
                        add(named)
                        addJsonObject { put("type", "string") }
                        addJsonObject { put("type", "object") }
                    }
                }
            }
        }
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private const val DRAFT_07 = "http://json-schema.org/draft-07/schema#"
    private val PLATFORMS = listOf("windows", "osx", "linux")
    private val PLATFORM_NAMES = mapOf("windows" to "Windows", "osx" to "macOS", "linux" to "Linux")

    /** The matchers VS Code's task system has without any extension. */
    private val BUILT_IN_MATCHERS = listOf(
        $$"$msCompile" to "Microsoft compiler problems",
        $$"$lessCompile" to "Less problems",
        $$"$gulp-tsc" to "Gulp TSC Problems",
        $$"$jshint" to "JSHint problems",
        $$"$jshint-stylish" to "JSHint stylish problems",
        $$"$eslint-compact" to "ESLint compact problems",
        $$"$eslint-stylish" to "ESLint stylish problems",
        $$"$go" to "Go problems",
    )

    private val OPTIONS: JsonObject = buildJsonObject {
        put("type", "object")
        put("description", "Additional command options.")
        putJsonObject("properties") {
            putJsonObject("cwd") { put("type", "string"); put("description", "The current working directory of the executed program or script. If omitted Code's current workspace root is used.") }
            putJsonObject("env") {
                put("type", "object")
                put("description", "The environment of the executed program or shell. If omitted the parent process' environment is used.")
                putJsonObject("additionalProperties") { putJsonArray("type") { add("string"); add("null") } }
            }
            putJsonObject("shell") {
                put("type", "object")
                put("description", "Configures the shell to be used.")
                putJsonObject("properties") {
                    putJsonObject("executable") { put("type", "string"); put("description", "The shell to be used.") }
                    putJsonObject("args") { put("type", "array"); put("description", "The shell arguments."); putJsonObject("items") { put("type", "string") } }
                }
            }
        }
    }

    private val PRESENTATION: JsonObject = buildJsonObject {
        put("type", "object")
        put("description", "Configures the panel that is used to present the task's output and reads its input.")
        putJsonObject("properties") {
            enum("reveal", "Controls whether the terminal running the task is revealed or not.", "always", "silent", "never")
            enum("revealProblems", "Controls whether the problems panel is revealed when running this task or not.", "always", "onProblem", "never")
            putJsonObject("echo") { put("type", "boolean"); put("description", "Controls whether the executed command is echoed to the panel.") }
            putJsonObject("focus") { put("type", "boolean"); put("description", "Controls whether the panel takes focus.") }
            enum("panel", "Controls if the panel is shared between tasks, dedicated to this task or a new one is created on every run.", "shared", "dedicated", "new")
            putJsonObject("showReuseMessage") { put("type", "boolean"); put("description", "Controls whether to show the `Terminal will be reused by tasks, press any key to close it` message.") }
            putJsonObject("clear") { put("type", "boolean"); put("description", "Controls whether the terminal is cleared before executing the task.") }
            putJsonObject("group") { put("type", "string"); put("description", "Controls whether the task is executed in a specific terminal group using split panes.") }
            putJsonObject("close") { put("type", "boolean"); put("description", "Controls whether the terminal the task runs in is closed when the task exits.") }
        }
    }

    private fun JsonObjectBuilder.enum(name: String, description: String, vararg values: String) = putJsonObject(name) {
        put("enum", buildJsonArray { values.forEach { add(it) } })
        put("description", description)
    }
}
