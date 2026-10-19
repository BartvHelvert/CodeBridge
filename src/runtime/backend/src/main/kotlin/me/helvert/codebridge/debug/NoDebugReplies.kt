package me.helvert.codebridge.debug

import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Run Without Debugging, as VS Code lets it run.
 *
 * With `noDebug` an adapter starts the program without a debugger, and may refuse what only a
 * debugger can do -- breakpoints, threads, stack traces; CodeLLDB answers "Not supported in noDebug
 * mode". VS Code shrugs those refusals off. IntelliJ's client takes one as fatal and disconnects at
 * once, before the program has written a line, so its output never reaches the console. A refusal
 * of such a request is answered here as an empty success instead, which is what VS Code makes of
 * it: nothing to show.
 *
 * Starting, stopping and the handshake keep their real answers: a refused `launch` must still end
 * the session, and so on. Every client request passes through [sent] and every adapter message
 * through [received].
 */
class NoDebugReplies {
    private val requests = ConcurrentHashMap<Int, JsonObject>()

    fun sent(json: String) {
        val message = parse(json) ?: return
        if (message.string("type") != "request") return
        val seq = (message["seq"] as? JsonPrimitive)?.intOrNull ?: return
        requests[seq] = message
    }

    /** [json], or an empty success in place of a refused request a run without debugging can do without. */
    fun received(json: String): String {
        val message = parse(json) ?: return json
        if (message.string("type") != "response") return json
        val seq = (message["request_seq"] as? JsonPrimitive)?.intOrNull ?: return json
        val request = requests.remove(seq) ?: return json
        if ((message["success"] as? JsonPrimitive)?.booleanOrNull != false) return json
        val command = request.string("command") ?: return json
        if (command in KEPT) return json
        return buildJsonObject {
            message.forEach { (key, value) -> if (key != "message" && key != "body") put(key, value) }
            put("success", true)
            put("command", command)
            put("body", emptyBody(command, request["arguments"] as? JsonObject, message.string("message")))
        }.toString()
    }

    /** What an empty answer to [command] holds: the arrays its response requires, empty or unverified. */
    private fun emptyBody(command: String, arguments: JsonObject?, reason: String?): JsonObject = buildJsonObject {
        when (command) {
            // One entry per breakpoint asked for, none verified, saying why.
            "setBreakpoints", "setFunctionBreakpoints", "setInstructionBreakpoints", "setDataBreakpoints", "setExceptionBreakpoints" -> {
                val asked = (arguments?.get("breakpoints") ?: arguments?.get("filters")) as? JsonArray
                put("breakpoints", buildJsonArray {
                    repeat(asked?.size ?: 0) {
                        add(buildJsonObject {
                            put(
                                "verified",
                                false
                            ); reason?.let { put("message", it) }
                        })
                    }
                })
            }

            "threads" -> put("threads", JsonArray(emptyList()))
            "stackTrace" -> {
                put("stackFrames", JsonArray(emptyList())); put("totalFrames", 0)
            }

            "scopes" -> put("scopes", JsonArray(emptyList()))
            "variables" -> put("variables", JsonArray(emptyList()))
            "loadedSources" -> put("sources", JsonArray(emptyList()))
            "modules" -> put("modules", JsonArray(emptyList()))
        }
    }

    private fun parse(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** Requests whose refusal means what it says, in a run as in a debug session. */
        val KEPT = setOf(
            "initialize",
            "launch",
            "attach",
            "disconnect",
            "terminate",
            "restart",
            "runInTerminal",
            "startDebugging"
        )
    }
}
