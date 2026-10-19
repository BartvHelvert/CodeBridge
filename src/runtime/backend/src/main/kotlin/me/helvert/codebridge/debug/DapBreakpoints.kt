package me.helvert.codebridge.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The breakpoints each debug adapter reported, by the source line they were set on.
 *
 * IntelliJ's DAP client sets breakpoints itself, so the only record of what the adapter made of one
 * -- its DAP `id`, whether it is verified -- is in the replies to `setBreakpoints`. They are read off
 * the messages as they pass: a request names a file and its lines, and the reply lists the adapter's
 * breakpoints for those lines in the same order. `breakpoint` events change them later.
 *
 * An extension asks for one with `DebugSession.getDebugProtocolBreakpoint`, which CodeLLDB needs to
 * tell which breakpoint a caller exclusion belongs to.
 */
class DapBreakpoints {

    /** One session's adapter: whether its lines count from 1, its breakpoints, and requests awaiting replies. */
    private class Adapter {
        @Volatile var linesStartAt1 = true
        /** By file key and line as the adapter counts them. */
        val breakpoints = ConcurrentHashMap<Pair<String, Int>, JsonObject>()
        /** `setBreakpoints` requests by `seq`: the file key and the lines asked for, in order. */
        val pending = ConcurrentHashMap<Int, Pair<String, List<Int>>>()
    }

    private val adapters = ConcurrentHashMap<String, Adapter>()

    /** A message the client sent to the adapter of host session [sessionId]. */
    fun sent(sessionId: String, json: String) {
        val message = parse(json) ?: return
        if (message.string("type") != "request") return
        val arguments = message["arguments"] as? JsonObject ?: return
        val adapter = adapters.computeIfAbsent(sessionId) { Adapter() }
        when (message.string("command")) {
            "initialize" -> adapter.linesStartAt1 = (arguments["linesStartAt1"] as? JsonPrimitive)?.booleanOrNull ?: true
            "setBreakpoints" -> {
                val seq = (message["seq"] as? JsonPrimitive)?.intOrNull ?: return
                val file = (arguments["source"] as? JsonObject)?.string("path")?.let(::key) ?: return
                val lines = (arguments["breakpoints"] as? JsonArray).orEmpty()
                    .mapNotNull { ((it as? JsonObject)?.get("line") as? JsonPrimitive)?.intOrNull }
                adapter.pending[seq] = file to lines
            }
        }
    }

    /** A message the adapter of host session [sessionId] sent the client. */
    fun received(sessionId: String, json: String) {
        val message = parse(json) ?: return
        val adapter = adapters[sessionId] ?: return
        when (message.string("type")) {
            "response" -> {
                val seq = (message["request_seq"] as? JsonPrimitive)?.intOrNull ?: return
                val (file, lines) = adapter.pending.remove(seq) ?: return
                // Replaced, not merged: `setBreakpoints` sets every breakpoint the file has.
                adapter.breakpoints.keys.removeIf { it.first == file }
                if ((message["success"] as? JsonPrimitive)?.booleanOrNull == false) return
                val reported = ((message["body"] as? JsonObject)?.get("breakpoints") as? JsonArray).orEmpty()
                lines.zip(reported).forEach { (line, breakpoint) ->
                    (breakpoint as? JsonObject)?.let { adapter.breakpoints[file to line] = it }
                }
            }
            "event" -> if (message.string("event") == "breakpoint") {
                val body = message["body"] as? JsonObject ?: return
                val changed = body["breakpoint"] as? JsonObject ?: return
                val id = (changed["id"] as? JsonPrimitive)?.intOrNull ?: return
                val entry = adapter.breakpoints.entries.firstOrNull { (it.value["id"] as? JsonPrimitive)?.intOrNull == id } ?: return
                if (body.string("reason") == "removed") adapter.breakpoints.remove(entry.key)
                else adapter.breakpoints[entry.key] = JsonObject(entry.value + changed)
            }
        }
    }

    /**
     * The breakpoint the adapter of [sessionId] reported for [file] at [line], counted from 0 as
     * the IDE counts lines; null if it reported none.
     */
    fun find(sessionId: String, file: Path, line: Int): JsonObject? {
        val adapter = adapters[sessionId] ?: return null
        return adapter.breakpoints[key(file.toString()) to line + if (adapter.linesStartAt1) 1 else 0]
    }

    /** Everything about [sessionId], once its session has ended. */
    fun ended(sessionId: String) {
        adapters.remove(sessionId)
    }

    fun clear() = adapters.clear()

    private fun parse(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** The same file however its path is spelled: case and separators differ between IDE and adapter on Windows. */
    private fun key(path: String): String = runCatching { UriComponents.key(Path.of(path)) }.getOrDefault(path)
}
