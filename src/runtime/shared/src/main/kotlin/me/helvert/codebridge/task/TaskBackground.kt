package me.helvert.codebridge.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import me.helvert.codebridge.settings.VsCodeRuntime
import java.nio.file.Files

/** The begin/end part of a problem matcher: when a watcher starts and finishes a compile. */
data class TaskBackground(val activeOnStart: Boolean, val beginsPattern: Regex, val endsPattern: Regex)

object TaskProblemMatchers {
    /** What a task's problem matchers find problems with, and why any of them could not be read. */
    data class Diagnostics(val matchers: List<TaskProblemMatcher> = emptyList(), val errors: List<String> = emptyList())

    /** `contributes.problemMatchers`, by the `$name` a task refers to each with. */
    fun contributions(manifests: Iterable<JsonObject>): Map<String, JsonObject> =
        contributed(manifests, "problemMatchers").mapKeys { (name, _) -> $$"$$$name" }

    /** `contributes.problemPatterns`, by their bare name; a matcher's `"pattern": "$name"` names one. */
    fun patternContributions(manifests: Iterable<JsonObject>): Map<String, JsonObject> =
        contributed(manifests, "problemPatterns")

    private fun contributed(manifests: Iterable<JsonObject>, point: String): Map<String, JsonObject> = buildMap {
        manifests.forEach { manifest ->
            val values = (manifest["contributes"] as? JsonObject)?.get(point) as? JsonArray
            values?.forEach entry@{ value ->
                val entry = value as? JsonObject ?: return@entry
                val name = (entry["name"] as? JsonPrimitive)?.contentOrNull ?: return@entry
                put(name, entry)
            }
        }
    }

    /**
     * VS Code's own matchers, then the pinned runtime's built-in extensions' contributions, read
     * rather than copying its tsc-watch expressions.
     */
    fun builtins(): Map<String, JsonObject> = CoreProblemMatchers.matchers + contributions(builtinManifests())

    /** The problem patterns [builtins] can name. */
    fun builtinPatterns(): Map<String, JsonObject> =
        CoreProblemMatchers.patterns + patternContributions(builtinManifests())

    private fun builtinManifests(): List<JsonObject> {
        val root = VsCodeRuntime.getInstance().installationOrNull()?.builtinExtensions ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { folders ->
            folders.map { folder ->
                runCatching { Json.parseToJsonElement(Files.readString(folder.resolve("package.json"))) as? JsonObject }.getOrNull()
            }.toList().filterNotNull()
        }
    }

    /**
     * The matchers [value] names that find problems, with [named] matchers and [patterns] to look
     * names up in. Unlike [background], a matcher that cannot be read is reported rather than thrown:
     * the task still runs, as in VS Code, and only its problems go unseen.
     */
    fun diagnostics(
        value: JsonElement?,
        named: Map<String, JsonObject>,
        patterns: Map<String, JsonObject>
    ): Diagnostics =
        TaskProblemMatcherParser.parse(value, named, patterns)

    fun background(value: JsonElement?, named: Map<String, JsonObject>): List<TaskBackground> {
        fun resolve(value: JsonElement, seen: Set<String> = emptySet()): JsonObject {
            if (value is JsonPrimitive) {
                val name = value.content
                require(name !in seen) { "Circular problem matcher reference: $name" }
                return resolve(
                    named[name] ?: throw IllegalArgumentException("Unknown problem matcher '$name'"),
                    seen + name
                )
            }
            val matcher =
                value as? JsonObject ?: throw IllegalArgumentException("A problem matcher must be a name or an object")
            // An explicit background block replaces the base's. Its diagnostic pattern need not
            // be available here, because this code only uses the readiness expressions.
            if (matcher["background"] != null || matcher["watching"] != null) return matcher
            val base = matcher["base"] ?: return matcher
            return JsonObject(resolve(base, seen) + matcher)
        }

        val values = if (value is JsonArray) value.toList() else listOfNotNull(value).filterNot { it == JsonNull }
        return values.mapNotNull { entry -> backgroundOf(resolve(entry)) }
    }

    /** A resolved matcher's `background` (or older `watching`) block, if it has one. */
    internal fun backgroundOf(matcher: JsonObject): TaskBackground? {
        val background = (matcher["background"] ?: matcher["watching"]) as? JsonObject ?: return null
        fun pattern(key: String): Regex {
            val raw = background[key]
            val regexp = (if (raw is JsonObject) raw["regexp"] else raw) as? JsonPrimitive
            require(regexp?.isString == true && regexp.content.isNotEmpty()) { "Background problem matcher needs '$key'" }
            return try {
                Regex(regexp.content)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid background '$key': ${error.message}", error)
            }
        }
        return TaskBackground(
            (background["activeOnStart"] as? JsonPrimitive)?.booleanOrNull == true,
            pattern("beginsPattern"), pattern("endsPattern")
        )
    }
}

/** Lines can arrive in fragments, on either stream, and with terminal colours. */
class TaskBackgroundMonitor(patterns: List<TaskBackground>) {
    val ready = CompletableDeferred<Unit>()

    private data class State(
        val pattern: TaskBackground,
        var active: Boolean = pattern.activeOnStart,
        var ended: Boolean = false
    )

    private val states = patterns.map(::State)
    private val buffers = mutableMapOf<Any, StringBuilder>()
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

    @Synchronized
    fun output(stream: Any, text: String) {
        if (ready.isCompleted) return
        val buffer = buffers.getOrPut(stream) { StringBuilder() }
        for (char in text) {
            if (char == '\n' || char == '\r') {
                line(buffer.toString())
                buffer.setLength(0)
            } else buffer.append(char)
        }
    }

    @Synchronized
    fun flush() {
        buffers.values.forEach { buffer -> if (buffer.isNotEmpty()) line(buffer.toString()); buffer.setLength(0) }
    }

    private fun line(text: String) {
        val plain = text.replace(ansi, "")
        states.forEach { state ->
            if (state.pattern.beginsPattern.containsMatchIn(plain)) state.active = true
            else if (state.active && state.pattern.endsPattern.containsMatchIn(plain)) {
                state.active = false
                state.ended = true
            }
        }
        if (states.isNotEmpty() && states.all { it.ended && !it.active }) ready.complete(Unit)
    }
}
