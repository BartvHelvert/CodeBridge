package me.helvert.codebridge.exthost

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.protocol.payload.Args
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** The native host reports failures separately: `$activate` returns true even after a failure. */
class ExtensionActivations {
    private val failures = ConcurrentHashMap<String, Failure>()

    data class Failure(val extensionId: String, val message: String, val missingDependency: String?) {
        /**
         * Whether the user is told, as VS Code's release build decides: only a missing dependency is
         * something they can act on. Any other failure is the extension's to explain -- golang.go
         * without Go installed shows its own "Failed to find the go binary" message and then rejects
         * `activate` with no reason, which would otherwise read "failed: undefined." Every failure is
         * still marked on the VS Code Extensions page, as VS Code's runtime status.
         */
        val notifiesUser: Boolean get() = missingDependency != null
    }

    fun observe(call: MainThreadCall) {
        failure(call)?.let { failures[it.extensionId.lowercase(Locale.ROOT)] = it }
    }

    fun checkResult(argsJson: String, result: String): String {
        val args = Args.of($$"$activate", argsJson)
        val id = args.obj(0).getValue("value").jsonPrimitive.content
        failures[id.lowercase(Locale.ROOT)]?.let { throw IllegalStateException(it.message) }
        check(result == "true") { "Extension '$id' is not available in the extension host" }
        return result
    }

    companion object {
        fun failure(call: MainThreadCall): Failure? {
            if (call.service != "MainThreadExtensionService" || call.method != $$"$onExtensionActivationError") return null
            val args = Args.of(call.method, call.argsJson)
            val id = args.obj(0).getValue("value").jsonPrimitive.content
            val message = args.obj(1)["message"]?.jsonPrimitive?.contentOrNull
                ?: "Extension '$id' failed to activate"
            val missing = args.objOrNull(2)?.get("dependency")?.jsonPrimitive?.contentOrNull
            return Failure(id, message, missing)
        }
    }
}
