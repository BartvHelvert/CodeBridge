package me.helvert.codebridge.task

import kotlinx.serialization.json.*

/**
 * A problem matcher's diagnostic part: which lines of a task's output are problems, and where.
 *
 * Read from the matcher's configuration as VS Code's `ProblemMatcherParser` reads it, after its
 * `base` has been merged in. [TaskProblemCollector] runs it over the output.
 */
data class TaskProblemMatcher(
    /** Whose problems these are: a rerun replaces what the same owner reported before. */
    val owner: String,
    val source: String?,
    /** `MarkerSeverity` for a match that names no severity, or one VS Code does not know. */
    val severity: Int?,
    val applyTo: ApplyTo,
    val fileLocation: FileLocation,
    val patterns: List<TaskProblemPattern>,
    /** Where a watcher's compile starts and ends; its problems are replaced cycle by cycle. */
    val background: TaskBackground?,
) {
    enum class ApplyTo { ALL_DOCUMENTS, OPEN_DOCUMENTS, CLOSED_DOCUMENTS }

    /** How a matched file name becomes a path. Prefixes keep their variables until the task runs. */
    sealed interface FileLocation {
        data object Absolute : FileLocation
        data class Relative(val prefix: String) : FileLocation

        /** Relative to [prefix] if that file exists, otherwise absolute. */
        data class AutoDetect(val prefix: String) : FileLocation

        /** The first file under [include] whose path ends with the name. */
        data class Search(val include: List<String>, val exclude: List<String>) : FileLocation
    }
}

/** One line's regular expression, and which of its groups say what. Groups are 0 for the whole match. */
data class TaskProblemPattern(
    val regexp: Regex,
    val kind: Kind? = null,
    val file: Int? = null,
    val message: Int? = null,
    /** `line`, `line,column` or `line,column,endLine,endColumn` in one group. */
    val location: Int? = null,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val severity: Int? = null,
    val code: Int? = null,
    /** Only on the last of several: it matches again, line after line, each a problem of its own. */
    val loop: Boolean = false,
) {
    /** Whether a problem is about the whole file or a place in it. */
    enum class Kind { FILE, LOCATION }
}

/** Reads matcher configurations into [TaskProblemMatcher]s. */
internal object TaskProblemMatcherParser {

    fun parse(
        value: JsonElement?,
        named: Map<String, JsonObject>,
        patterns: Map<String, JsonObject>,
    ): TaskProblemMatchers.Diagnostics {
        val entries = if (value is JsonArray) value.toList() else listOfNotNull(value).filterNot { it == JsonNull }
        val matchers = mutableListOf<TaskProblemMatcher>()
        val errors = mutableListOf<String>()
        for (entry in entries) {
            try {
                matcher(merged(entry, named), patterns)?.let(matchers::add)
            } catch (error: IllegalArgumentException) {
                errors += error.message ?: "A problem matcher could not be read"
            }
        }
        return TaskProblemMatchers.Diagnostics(matchers, errors)
    }

    /** [value] with every `base` it names merged under it, the matcher's own keys winning. */
    private fun merged(value: JsonElement, named: Map<String, JsonObject>, seen: Set<String> = emptySet()): JsonObject {
        if (value is JsonPrimitive) {
            val name = value.content
            require(name !in seen) { "Circular problem matcher reference: $name" }
            val found =
                named[name] ?: named["$$name"] ?: throw IllegalArgumentException("Unknown problem matcher '$name'")
            return merged(found, named, seen + name)
        }
        val matcher =
            value as? JsonObject ?: throw IllegalArgumentException("A problem matcher must be a name or an object")
        val base = matcher["base"] ?: return matcher
        return JsonObject(merged(base, named, seen) + matcher - "base")
    }

    /** Null for a matcher that only says when a watcher is ready, and has no pattern to match problems with. */
    private fun matcher(matcher: JsonObject, patterns: Map<String, JsonObject>): TaskProblemMatcher? {
        val name = matcher.string("name")?.let { "'$it'" } ?: "A problem matcher"
        val pattern = matcher["pattern"]
        if (pattern == null || pattern == JsonNull) {
            require(matcher["background"] != null || matcher["watching"] != null) { "$name has no pattern" }
            return null
        }
        return TaskProblemMatcher(
            owner = matcher.string("owner") ?: "external",
            source = matcher.string("source"),
            severity = matcher.string("severity")?.let(::severity),
            applyTo = when (matcher.string("applyTo")) {
                "openDocuments" -> TaskProblemMatcher.ApplyTo.OPEN_DOCUMENTS
                "closedDocuments" -> TaskProblemMatcher.ApplyTo.CLOSED_DOCUMENTS
                else -> TaskProblemMatcher.ApplyTo.ALL_DOCUMENTS
            },
            fileLocation = fileLocation(matcher["fileLocation"]),
            patterns = patterns(pattern, patterns, name),
            background = TaskProblemMatchers.backgroundOf(matcher),
        )
    }

    /** VS Code's default is relative to the workspace folder. */
    private fun fileLocation(value: JsonElement?): TaskProblemMatcher.FileLocation {
        val default = TaskProblemMatcher.FileLocation.Relative(WORKSPACE_FOLDER)
        fun kind(name: String?, prefix: JsonElement?): TaskProblemMatcher.FileLocation? = when (name) {
            "absolute" -> TaskProblemMatcher.FileLocation.Absolute
            "relative" -> TaskProblemMatcher.FileLocation.Relative(
                (prefix as? JsonPrimitive)?.contentOrNull ?: WORKSPACE_FOLDER
            )

            "autoDetect" -> TaskProblemMatcher.FileLocation.AutoDetect(
                (prefix as? JsonPrimitive)?.contentOrNull ?: WORKSPACE_FOLDER
            )

            "search" -> {
                val options = prefix as? JsonObject
                fun list(key: String) = when (val entry = options?.get(key)) {
                    is JsonPrimitive -> listOfNotNull(entry.contentOrNull)
                    is JsonArray -> entry.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    else -> emptyList()
                }
                TaskProblemMatcher.FileLocation.Search(
                    list("include").ifEmpty { listOf(WORKSPACE_FOLDER) },
                    list("exclude")
                )
            }

            else -> null
        }
        return when (value) {
            is JsonPrimitive -> kind(value.contentOrNull, null)
            is JsonArray -> kind((value.firstOrNull() as? JsonPrimitive)?.contentOrNull, value.getOrNull(1))
            else -> null
        } ?: default
    }

    private fun patterns(
        value: JsonElement,
        named: Map<String, JsonObject>,
        matcher: String
    ): List<TaskProblemPattern> = when (value) {
        is JsonPrimitive -> {
            val name = value.content
            val found = named[name.removePrefix("$")]
                ?: throw IllegalArgumentException("Unknown problem pattern '$name' in $matcher")
            patterns(found["patterns"] ?: found, named, matcher)
        }

        is JsonObject -> listOf(pattern(value, defaults = true, matcher))
        is JsonArray -> {
            require(value.isNotEmpty()) { "$matcher has an empty pattern list" }
            val read = value.map {
                pattern(
                    it as? JsonObject ?: throw IllegalArgumentException("$matcher has a pattern that is not an object"),
                    defaults = false,
                    matcher
                )
            }
            // Only the last may loop; the first says, by default, that the problem is at a location.
            read.mapIndexed { index, pattern ->
                pattern.copy(
                    loop = pattern.loop && index == read.lastIndex,
                    kind = if (index == 0) pattern.kind ?: TaskProblemPattern.Kind.LOCATION else pattern.kind,
                )
            }
        }
    }

    private fun pattern(value: JsonObject, defaults: Boolean, matcher: String): TaskProblemPattern {
        val source = value.string("regexp") ?: throw IllegalArgumentException("$matcher has a pattern with no regexp")
        val regexp = try {
            Regex(source)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$matcher has an invalid regexp: ${error.message}", error)
        }

        fun group(key: String) = (value[key] as? JsonPrimitive)?.intOrNull
        val kind = when (value.string("kind")) {
            "file" -> TaskProblemPattern.Kind.FILE
            "location" -> TaskProblemPattern.Kind.LOCATION
            else -> null
        }
        val read = TaskProblemPattern(
            regexp, kind,
            file = group("file"), message = group("message"), location = group("location"),
            line = group("line"), column = group("column"), endLine = group("endLine"), endColumn = group("endColumn"),
            severity = group("severity"), code = group("code"),
            loop = (value["loop"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        if (!defaults) return read
        // VS Code's defaults for a single pattern, filled only where the pattern names no group.
        return if (read.location != null || kind == TaskProblemPattern.Kind.FILE)
            read.copy(file = read.file ?: 1, message = read.message ?: 0)
        else read.copy(
            file = read.file ?: 1,
            line = read.line ?: 2,
            column = read.column ?: 3,
            message = read.message ?: 0
        )
    }

    /** VS Code's `Severity.fromValue`, and the letters and words matchers fall back to. */
    fun severity(value: String): Int? = when (value.lowercase()) {
        "error", "e" -> ERROR
        "warning", "warn", "w" -> WARNING
        "info", "information", "i", "hint", "note" -> INFO
        else -> null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    const val WORKSPACE_FOLDER = $$"${workspaceFolder}"

    /** `MarkerSeverity`. */
    const val ERROR = 8
    const val WARNING = 4
    const val INFO = 2
}

/**
 * The matchers and patterns VS Code itself defines, rather than an extension: `$msCompile`, `$go`
 * and the rest of `problemMatcher.ts`'s defaults. The schema already offers them in `tasks.json`.
 */
internal object CoreProblemMatchers {

    val patterns: Map<String, JsonObject> = mapOf(
        "msCompile" to pattern(
            """^(?:\s*\d+>)?(\S.*)\((\d+|\d+,\d+|\d+,\d+,\d+,\d+)\)\s*:\s+((?:fatal +)?error|warning|info)\s+(\w+\d+)\s*:\s*(.*)$""",
            "location", "file" to 1, "location" to 2, "severity" to 3, "code" to 4, "message" to 5
        ),
        "gulp-tsc" to pattern(
            """^([^\s].*)\((\d+|\d+,\d+|\d+,\d+,\d+,\d+)\):\s+(\d+)\s+(.*)$""",
            "location", "file" to 1, "location" to 2, "code" to 3, "message" to 4
        ),
        "cpp" to pattern(
            """^(\S.*)\((\d+|\d+,\d+|\d+,\d+,\d+,\d+)\):\s+(error|warning|info)\s+(C\d+)\s*:\s*(.*)$""",
            "location", "file" to 1, "location" to 2, "severity" to 3, "code" to 4, "message" to 5
        ),
        "csc" to pattern(
            """^(\S.*)\((\d+|\d+,\d+|\d+,\d+,\d+,\d+)\):\s+(error|warning|info)\s+(CS\d+)\s*:\s*(.*)$""",
            "location", "file" to 1, "location" to 2, "severity" to 3, "code" to 4, "message" to 5
        ),
        "vb" to pattern(
            """^(\S.*)\((\d+|\d+,\d+|\d+,\d+,\d+,\d+)\):\s+(error|warning|info)\s+(BC\d+)\s*:\s*(.*)$""",
            "location", "file" to 1, "location" to 2, "severity" to 3, "code" to 4, "message" to 5
        ),
        "lessCompile" to pattern(
            """^\s*(.*) in file (.*) line no. (\d+)$""",
            "location", "message" to 1, "file" to 2, "line" to 3
        ),
        "jshint" to pattern(
            """^(.*):\s+line\s+(\d+),\s+col\s+(\d+),\s(.+?)(?:\s+\((\w)(\d+)\))?$""",
            "location", "file" to 1, "line" to 2, "column" to 3, "message" to 4, "severity" to 5, "code" to 6
        ),
        "jshint-stylish" to patterns(
            pattern("""^(.+)$""", "location", "file" to 1),
            pattern(
                """^\s+line\s+(\d+)\s+col\s+(\d+)\s+(.+?)(?:\s+\((\w)(\d+)\))?$""", null,
                "line" to 1, "column" to 2, "message" to 3, "severity" to 4, "code" to 5, loop = true
            ),
        ),
        "eslint-compact" to pattern(
            """^(.+):\sline\s(\d+),\scol\s(\d+),\s(Error|Warning|Info)\s-\s(.+)\s\((.+)\)$""",
            "location", "file" to 1, "line" to 2, "column" to 3, "severity" to 4, "message" to 5, "code" to 6
        ),
        "eslint-stylish" to patterns(
            pattern("""^((?:[a-zA-Z]:)*[./\\]+.*?)$""", "location", "file" to 1),
            pattern(
                """^\s+(\d+):(\d+)\s+(error|warning|info)\s+(.+?)(?:\s\s+(.*))?$""", null,
                "line" to 1, "column" to 2, "severity" to 3, "message" to 4, "code" to 5, loop = true
            ),
        ),
        "go" to pattern(
            """^([^:]*: )?((.:)?[^:]*):(\d+)(:(\d+))?: (.*)$""",
            "location", "file" to 2, "line" to 4, "column" to 6, "message" to 7
        ),
    )

    val matchers: Map<String, JsonObject> = listOf(
        matcher("msCompile", "Microsoft compiler problems", "msCompile", "cpp", "absolute"),
        matcher("lessCompile", "Less problems", "lessCompile", "less", "absolute", severity = "error"),
        matcher("gulp-tsc", "Gulp TSC Problems", "typescript", "ts", "relative", applyTo = "closedDocuments"),
        matcher("jshint", "JSHint problems", "jshint", "jshint", "absolute"),
        matcher("jshint-stylish", "JSHint stylish problems", "jshint", "jshint", "absolute"),
        matcher("eslint-compact", "ESLint compact problems", "eslint", "eslint", "absolute"),
        matcher("eslint-stylish", "ESLint stylish problems", "eslint", "eslint", "absolute"),
        matcher("go", "Go problems", "go", "go", "relative"),
    ).associateBy { "$" + it.getValue("name").jsonPrimitive.content }

    private fun matcher(
        name: String, label: String, owner: String, source: String, fileLocation: String,
        severity: String? = null, applyTo: String = "allDocuments",
    ) = buildJsonObject {
        put("name", name)
        put("label", label)
        put("owner", owner)
        put("source", source)
        put("applyTo", applyTo)
        put("fileLocation", fileLocation)
        severity?.let { put("severity", it) }
        put("pattern", "$$name")
    }

    private fun pattern(regexp: String, kind: String?, vararg groups: Pair<String, Int>, loop: Boolean = false) =
        buildJsonObject {
            put("regexp", regexp)
            kind?.let { put("kind", it) }
            groups.forEach { (key, index) -> put(key, index) }
            if (loop) put("loop", true)
        }

    private fun patterns(vararg patterns: JsonObject) =
        buildJsonObject { put("patterns", JsonArray(patterns.toList())) }
}
