package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.isLinux
import com.intellij.platform.eel.isMac
import com.intellij.platform.eel.isWindows
import com.intellij.platform.eel.provider.localEel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The context keys `when` clauses read: the ones extensions set with `setContext` -- rust-analyzer
 * sets `inRustProject` -- and the few the workbench always has, such as `config.*` and `isWindows`.
 *
 * Keys that belong to one place, such as `view` or `viewItem`, are supplied where a clause about
 * that place is evaluated; see [lookup].
 */
@Service(Service.Level.PROJECT)
class HostedContextKeys {
    private val keys = MutableStateFlow<Map<String, JsonElement>>(emptyMap())

    /** The settings `config.*` keys read: the running host's, as extensions see them; none without one. */
    @Volatile
    var configuration: () -> JsonObject? = { null }

    val values: StateFlow<Map<String, JsonElement>> = keys.asStateFlow()

    /** `setContext(key, value)`; an undefined or null value removes the key, as in VS Code. */
    fun set(key: String, value: JsonElement?) {
        keys.update { if (value == null || value == JsonNull) it - key else it + (key to value) }
    }

    /** Every key belonged to the host that has gone, and so did its settings. */
    fun hostStopped() {
        keys.value = emptyMap()
        configuration = { null }
    }

    /**
     * The value of a key: what [local] gives first -- keys of the place being evaluated -- then the
     * workbench's own, then what extensions set.
     */
    fun lookup(local: (String) -> JsonElement? = { null }): (String) -> JsonElement? {
        val set = keys.value
        return { key -> local(key) ?: workbench(key) ?: set[key] }
    }

    // The extension host runs on this machine, whatever the project lives on.
    private fun workbench(key: String): JsonElement? = when {
        key.startsWith(CONFIG) -> configuration()?.let { setting(it, key.removePrefix(CONFIG)) }
        key == "isWindows" -> JsonPrimitive(localEel.platform.isWindows)
        key == "isMac" -> JsonPrimitive(localEel.platform.isMac)
        key == "isLinux" -> JsonPrimitive(localEel.platform.isLinux)
        key == "isWeb" -> JsonPrimitive(false)
        else -> null
    }

    companion object {
        private const val CONFIG = "config."

        /**
         * Setting [name] in [settings], held either flat (`"rust-analyzer.showSyntaxTree"`) or as
         * the nested objects its dots spell; both appear, since a section can itself contain dots.
         */
        internal fun setting(settings: JsonObject, name: String): JsonElement? {
            settings[name]?.let { return it }
            val dot = name.indexOf('.')
            if (dot < 0) return null
            var at = dot
            while (at >= 0) {
                val section = settings[name.substring(0, at)] as? JsonObject
                if (section != null) setting(section, name.substring(at + 1))?.let { return it }
                at = name.indexOf('.', at + 1)
            }
            return null
        }

        fun getInstance(project: Project): HostedContextKeys = project.service()
    }
}
