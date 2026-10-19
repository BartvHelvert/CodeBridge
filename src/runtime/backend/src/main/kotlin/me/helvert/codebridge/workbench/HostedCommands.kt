package me.helvert.codebridge.workbench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostInitData
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Args
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Commands are asynchronous: activation/execution can issue more workbench requests. */
internal class HostedCommands(
    /** Where an activation nobody waits for runs; it ends with the workbench. */
    private val scope: CoroutineScope,
    private val manifests: List<JsonObject>,
    private val callHost: suspend (String, String, String) -> String,
    private val report: (String) -> Unit,
    /** Told of each `setContext(key, value)`, for the `when` clauses of menus and views. */
    private val contextChanged: (key: String, value: JsonElement?) -> Unit = { _, _ -> },
    /**
     * Runs a workbench command no extension registered, or answers null if there is none by that id.
     *
     * The renderer's own commands -- `editor.action.showReferences` and the like -- need the IDE,
     * which this side of the workbench does not know about.
     */
    private val builtIn: suspend (command: String, arguments: JsonArray) -> MainThreadResult? = { _, _ -> null },
) {
    private val registered = ConcurrentHashMap.newKeySet<String>()

    suspend fun handle(call: MainThreadCall): MainThreadResult {
        val args = Args.of(call.method, call.argsJson)
        return when (call.method) {
            $$"$registerCommand" -> {
                registered.add(args.str(0)); MainThreadResult()
            }

            $$"$unregisterCommand" -> {
                registered.remove(args.str(0)); MainThreadResult()
            }

            $$"$getCommands" -> MainThreadResult(JsonArray(registered.sorted().map(::JsonPrimitive)).toString())

            $$"$fireCommandActivationEvent" -> {
                val command = args.str(0)
                // Asked for now, in order with what the host sends next; only the answer is not waited for.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        activate(command)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        report("Command activation failed: ${error.message}")
                    }
                }
                MainThreadResult()
            }

            $$"$executeCommand" -> {
                val command = args.str(0)
                val arguments = args.array(1)
                if (command !in registered) activate(command)
                // When-clause context keys, which the menus of extensions' views are offered by.
                when (command) {
                    in CONTEXT_COMMANDS -> {
                        (arguments.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.let {
                            contextChanged(
                                it,
                                arguments.getOrNull(1)
                            )
                        }
                        MainThreadResult()
                    }
                    // Preserve the existing fallback for unsupported built-in UI commands. Do
                    // not advertise these in getCommands or mistake them for implemented ones.
                    !in registered -> builtIn(command, arguments) ?: run {
                        report("Workbench command is not implemented: $command")
                        MainThreadResult()
                    }

                    else -> MainThreadResult(callHost("ExtHostCommands", $$"$executeContributedCommand", buildJsonArray {
                        add(command); arguments.forEach(::add)
                    }.toString()).takeIf(String::isNotEmpty))
                }
            }

            else -> throw IllegalArgumentException("Unsupported commands method ${call.method}")
        }
    }

    private companion object {
        val CONTEXT_COMMANDS = setOf("setContext", "_setContext")
    }

    /** Every extension that contributes [command], each asked at once; the first refusal fails it, once all have answered. */
    private suspend fun activate(command: String) {
        val event = "onCommand:$command"
        val owners = manifests.filter { manifest ->
            val contributes = (manifest["contributes"] as? JsonObject)?.get("commands")
            val commands = contributes as? JsonArray ?: listOfNotNull(contributes)
            commands.any { (it as? JsonObject)?.get("command")?.jsonPrimitive?.contentOrNull == command } ||
                    (manifest["activationEvents"] as? JsonArray).orEmpty()
                        .any { it.jsonPrimitive.contentOrNull == event }
        }
        val failures = coroutineScope {
            owners.map { manifest ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val id = "${manifest["publisher"]!!.jsonPrimitive.content}.${manifest["name"]!!.jsonPrimitive.content}"
                    val identifier = ExtensionHostInitData.identifier(id)
                    runCatching {
                        val activated = callHost("ExtHostExtensionService", $$"$activate", buildJsonArray {
                            add(identifier)
                            add(buildJsonObject {
                                put("startup", false); put("activationEvent", event); put(
                                "extensionId",
                                identifier
                            )
                            })
                        }.toString())
                        check(activated == "true") { "Could not activate $id for $event" }
                    }.exceptionOrNull()
                }
            }.awaitAll()
        }
        failures.firstOrNull { it != null }?.let { throw it }
    }
}
