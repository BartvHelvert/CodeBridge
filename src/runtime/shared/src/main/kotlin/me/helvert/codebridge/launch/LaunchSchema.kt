package me.helvert.codebridge.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.helvert.codebridge.manifest.VsixManifest

/**
 * The shape of a `launch.json` entry as VS Code defines it: the fields every configuration has, and
 * those each debugger declares in `contributes.debuggers[].configurationAttributes`.
 */
object LaunchSchema {

    /** A debugger type: what it is shown as, and its attributes by request, merged across the extensions that contribute to it. */
    class Debugger(val label: String?, val attributes: JsonObject)

    /** Every debugger type the [manifests] declare attributes for, merged as VS Code merges them. */
    fun debuggers(manifests: List<VsixManifest>): Map<String, Debugger> {
        val debuggers = linkedMapOf<String, Debugger>()
        for (debugger in manifests.flatMap { it.contributes.debuggers }) {
            val attributes = debugger.configurationAttributes ?: continue
            val known = debuggers[debugger.type]
            debuggers[debugger.type] = Debugger(known?.label ?: debugger.label,
                merge(known?.attributes ?: JsonObject(emptyMap()), attributes) as JsonObject)
        }
        return debuggers
    }

    /** What a configuration of one type and request may have, and must. */
    class Shape(val properties: Map<String, JsonObject>, val required: Set<String>)

    /**
     * The shape of a configuration of [type] and [request]: the debugger's own properties, then
     * those every configuration has. Null when no debugger declares that pair.
     */
    fun configuration(manifests: List<VsixManifest>, type: String, request: String): Shape? {
        val attributes = debuggers(manifests)[type]?.attributes?.get(request) as? JsonObject ?: return null
        val declared = (attributes["properties"] as? JsonObject).orEmpty()
        val properties = linkedMapOf<String, JsonObject>()
        declared.forEach { (key, schema) -> (schema as? JsonObject)?.let { properties[key] = it } }
        COMMON.forEach { (key, schema) -> properties.putIfAbsent(key, schema as JsonObject) }
        val required = (attributes["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        return Shape(properties, (listOf("name", "type", "request") + required).toSet())
    }

    /** The shape of a `compounds` entry, which is VS Code's own. */
    val compound: Shape
        get() = Shape(
            (COMPOUND["properties"] as JsonObject).mapValues { it.value as JsonObject },
            setOf("name", "configurations"),
        )

    /** [top] laid over [base]: objects merge key by key, anything else is replaced. */
    fun merge(base: JsonElement, top: JsonElement): JsonElement =
        if (base is JsonObject && top is JsonObject) {
            JsonObject(base + top.mapValues { (key, value) -> base[key]?.let { merge(it, value) } ?: value })
        } else top

    /** The fields every configuration has, whichever debugger it is for. */
    val COMMON: JsonObject = buildJsonObject {
        putJsonObject("name") {
            put("type", "string")
            put("description", "Name of configuration; appears in the launch configuration dropdown menu.")
            put("default", "Launch")
        }
        putJsonObject("debugServer") {
            put("type", "number")
            put("description", "For debug extension development only: if a port is specified VS Code tries to connect to a debug adapter running in server mode")
            put("default", 4711)
        }
        putJsonObject("preLaunchTask") {
            put("type", "string")
            put("description", "Task to run before debug session starts.")
            put("default", "")
        }
        putJsonObject("postDebugTask") {
            put("type", "string")
            put("description", "Task to run after debug session ends.")
            put("default", "")
        }
        put("presentation", PRESENTATION)
        putJsonObject("internalConsoleOptions") {
            put("enum", buildJsonArray { add("neverOpen"); add("openOnSessionStart"); add("openOnFirstSessionStart") })
            put("default", "openOnFirstSessionStart")
            put("description", "Controls when the internal Debug Console should open.")
        }
        putJsonObject("suppressMultipleSessionWarning") {
            put("type", "boolean")
            put("description", "Disable the warning when trying to start the same debug configuration more than once.")
            put("default", true)
        }
    }

    val PRESENTATION: JsonObject
        get() = buildJsonObject {
            put("type", "object")
            put("description", "Presentation options on how to show this configuration in the debug configuration dropdown and the command palette.")
            putJsonObject("properties") {
                putJsonObject("hidden") {
                    put("type", "boolean")
                    put("default", false)
                    put("description", "Controls if this configuration should be shown in the configuration dropdown and the command palette.")
                }
                putJsonObject("group") {
                    put("type", "string")
                    put("default", "")
                    put("description", "Group that this configuration belongs to. Used for grouping and sorting in the configuration dropdown and the command palette.")
                }
                putJsonObject("order") {
                    put("type", "number")
                    put("default", 1)
                    put("description", "Order of this configuration within a group. Used for grouping and sorting in the configuration dropdown and the command palette.")
                }
            }
        }

    /** One `compounds` entry. */
    val COMPOUND: JsonObject = buildJsonObject {
        put("type", "object")
        put("required", buildJsonArray { add("name"); add("configurations") })
        putJsonObject("default") { put("name", ""); putJsonArray("configurations") {} }
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Name of compound. Appears in the launch configuration drop down menu.")
            }
            put("presentation", PRESENTATION)
            putJsonObject("configurations") {
                put("type", "array")
                put("description", "Names of configurations that will be started as part of this compound.")
                putJsonArray("default") {}
                putJsonObject("items") {
                    putJsonArray("oneOf") {
                        addJsonObject {
                            put("type", "string")
                            put("description", "Please use unique configuration names.")
                        }
                        addJsonObject {
                            put("type", "object")
                            put("required", buildJsonArray { add("name") })
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string"); put("description", "Name of configuration.") }
                                putJsonObject("folder") { put("type", "string"); put("description", "Name of folder in which the compound is located.") }
                            }
                        }
                    }
                }
            }
            putJsonObject("stopAll") {
                put("type", "boolean")
                put("default", false)
                put("description", "Controls whether manually terminating one session will stop all of the compound sessions.")
            }
            putJsonObject("preLaunchTask") {
                put("type", "string")
                put("default", "")
                put("description", "Task to run before any of the compound configurations start.")
            }
        }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
