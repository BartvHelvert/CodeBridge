package me.helvert.codebridge.task

import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.workspace.SimpleSubstitutionContext
import me.helvert.codebridge.workspace.VariableSubstitutor
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** Where a task's problems are filed, by matcher owner. */
interface TaskProblemSink {
    /** The files [owner] has problems filed for now, from this run or an earlier one. */
    fun files(owner: String): Set<Path>

    /** Replaces what [owner] says about [file]; an empty list withdraws it. */
    fun publish(owner: String, file: Path, markers: List<Marker>)

    /** Whether [file] is open in an editor, for a matcher's `applyTo`. */
    fun isOpen(file: Path): Boolean
}

/**
 * Turns a task's output into problems, as VS Code's problem collectors do.
 *
 * Lines are matched as `AbstractProblemCollector` matches them: the last few are kept, as many as
 * the longest multi-line pattern, and the first matcher to match ending at the newest line wins.
 * A looping pattern then goes on matching line after line, each match a problem of its own.
 *
 * Problems replace an owner's earlier ones file by file as they are found, so a rerun's output does
 * not first clear everything and then fill it in again. What the owner said before about a file the
 * run never mentions is withdrawn when the run ends ([done]); for a [background] task, when each
 * compile ends -- the matcher's `endsPattern` -- since a watcher never does.
 */
class TaskProblemCollector(
    matchers: List<TaskProblemMatcher>,
    private val background: Boolean,
    private val files: (String, TaskProblemMatcher) -> Path?,
    private val sink: TaskProblemSink,
) {
    private val lineMatchers = matchers.map(::LineMatcher)
    private val bySize = lineMatchers.groupBy { it.patterns.size }
    private val bufferSize = matchers.maxOfOrNull { it.patterns.size } ?: 0
    private val buffer = ArrayDeque<String>()
    private var active: LineMatcher? = null

    /** A watcher's matchers that are between their begin and end lines. */
    private val compiling = mutableSetOf<TaskProblemMatcher>()

    /** By owner: what this run or compile found, file by file. */
    private val found = mutableMapOf<String, MutableMap<Path, LinkedHashSet<Marker>>>()

    /** By owner: files it had problems in when the run or compile began, and has not mentioned since. */
    private val stale = mutableMapOf<String, MutableSet<Path>>()

    private val streams = mutableMapOf<Any, StringBuilder>()

    /** Streams whose last character was a carriage return, so that a newline after it ends no second line. */
    private val returned = mutableSetOf<Any>()
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

    init {
        if (background) matchers.filter { it.background?.activeOnStart == true }
            .forEach { compiling += it; begin(it.owner) }
        else matchers.map { it.owner }.distinct().forEach(::begin)
    }

    /** Output as it arrives: in fragments, on either stream, with terminal colours. */
    @Synchronized
    fun output(stream: Any, text: String) {
        val pending = streams.getOrPut(stream) { StringBuilder() }
        for (char in text) {
            val crlf = char == '\n' && returned.remove(stream)
            if (char == '\r') returned += stream else returned -= stream
            if (crlf) continue
            // Blank lines count: one is what ends a looping pattern.
            if (char == '\n' || char == '\r') {
                line(pending.toString())
                pending.setLength(0)
            } else pending.append(char)
        }
    }

    /** The task has ended. */
    @Synchronized
    fun done() {
        streams.values.forEach { pending -> if (pending.isNotEmpty()) line(pending.toString()); pending.setLength(0) }
        if (!background) stale.keys.toList().forEach(::end)
    }

    private fun line(raw: String) {
        val line = raw.replace(ansi, "")
        if (background && (began(line) || ended(line))) return
        val match = find(line) ?: return
        val file = runCatching { files(match.file, match.matcher) }.getOrNull() ?: return
        record(match.matcher, file, match.marker)
    }

    private fun began(line: String): Boolean {
        var result = false
        for (matcher in lineMatchers.map { it.matcher }) {
            val pattern = matcher.background ?: continue
            if (matcher in compiling || !pattern.beginsPattern.containsMatchIn(line)) continue
            compiling += matcher
            begin(matcher.owner)
            result = true
        }
        return result
    }

    private fun ended(line: String): Boolean {
        var result = false
        for (matcher in lineMatchers.map { it.matcher }) {
            val pattern = matcher.background ?: continue
            if (matcher !in compiling || !pattern.endsPattern.containsMatchIn(line)) continue
            compiling -= matcher
            end(matcher.owner)
            result = true
        }
        return result
    }

    private fun begin(owner: String) {
        found[owner] = mutableMapOf()
        stale[owner] = sink.files(owner).toMutableSet()
    }

    private fun end(owner: String) {
        stale.remove(owner)?.forEach { sink.publish(owner, it, emptyList()) }
        found.remove(owner)
    }

    private fun record(matcher: TaskProblemMatcher, file: Path, marker: Marker) {
        stale[matcher.owner]?.remove(file)
        val applies = when (matcher.applyTo) {
            TaskProblemMatcher.ApplyTo.ALL_DOCUMENTS -> true
            TaskProblemMatcher.ApplyTo.OPEN_DOCUMENTS -> sink.isOpen(file)
            TaskProblemMatcher.ApplyTo.CLOSED_DOCUMENTS -> !sink.isOpen(file)
        }
        if (!applies) return
        val markers = found.getOrPut(matcher.owner) { mutableMapOf() }.getOrPut(file) { LinkedHashSet() }
        if (markers.add(marker)) sink.publish(matcher.owner, file, markers.toList())
    }

    /** `AbstractProblemCollector.tryFindMarker`. */
    private fun find(line: String): Match? {
        active?.let { matcher ->
            matcher.next(line)?.let { return it }
            buffer.clear()
            active = null
        }
        if (bufferSize == 0) return null
        buffer.addLast(line)
        if (buffer.size > bufferSize) buffer.removeFirst()
        for (start in buffer.indices) {
            for (matcher in bySize[buffer.size - start].orEmpty()) {
                val (match, loops) = matcher.handle(buffer, start)
                if (match != null) {
                    if (loops) active = matcher
                    buffer.clear()
                    return match
                }
            }
        }
        return null
    }

    private class Match(val matcher: TaskProblemMatcher, val file: String, val marker: Marker)

    /** What the patterns have read so far; the first value a group gives wins, the message accumulates. */
    private data class Data(
        var kind: TaskProblemPattern.Kind? = null,
        var file: String? = null,
        var message: String? = null,
        var code: String? = null,
        var severity: String? = null,
        var location: String? = null,
        var line: String? = null,
        var column: String? = null,
        var endLine: String? = null,
        var endColumn: String? = null,
    )

    /** `SingleLineMatcher` and `MultiLineMatcher` in one. */
    private class LineMatcher(val matcher: TaskProblemMatcher) {
        val patterns = matcher.patterns
        private var data: Data? = null

        /** Whether the patterns match the lines from [start]; the second value says the last one loops. */
        fun handle(lines: List<String>, start: Int): Pair<Match?, Boolean> {
            if (patterns.size == 1) {
                val result = patterns[0].regexp.find(lines[start]) ?: return null to false
                val data = Data(kind = patterns[0].kind)
                fill(data, patterns[0], result)
                return marker(data) to false
            }
            var data = Data(kind = patterns[0].kind)
            this.data = data
            for ((index, pattern) in patterns.withIndex()) {
                val result = pattern.regexp.find(lines[start + index]) ?: run { this.data = null; return null to false }
                if (pattern.loop && index == patterns.lastIndex) data = data.copy()
                fill(data, pattern, result)
            }
            val loops = patterns.last().loop
            if (!loops) this.data = null
            return marker(data) to loops
        }

        /** The looping last pattern against the next line, with what the earlier ones read. */
        fun next(line: String): Match? {
            val held = data ?: return null
            val result = patterns.last().regexp.find(line) ?: run { data = null; return null }
            val data = held.copy()
            fill(data, patterns.last(), result)
            return marker(data)
        }

        private fun fill(data: Data, pattern: TaskProblemPattern, result: MatchResult) {
            fun group(index: Int?): String? =
                index?.takeIf { it < result.groups.size }?.let { result.groups[it]?.value }
            if (data.file == null) data.file = group(pattern.file)?.trim()
            group(pattern.message)?.trim()?.let { data.message = data.message?.let { held -> "$held\n$it" } ?: it }
            if (data.code == null) data.code = group(pattern.code)?.trim()
            if (data.severity == null) data.severity = group(pattern.severity)?.trim()
            if (data.location == null) data.location = group(pattern.location)?.trim()
            if (data.line == null) data.line = group(pattern.line)
            if (data.column == null) data.column = group(pattern.column)
            if (data.endLine == null) data.endLine = group(pattern.endLine)
            if (data.endColumn == null) data.endColumn = group(pattern.endColumn)
        }

        private fun marker(data: Data): Match? {
            val file = data.file?.takeIf { it.isNotEmpty() } ?: return null
            val message = data.message?.takeIf { it.isNotEmpty() } ?: return null
            val range = range(data) ?: return null
            val severity = data.severity?.takeIf { it.isNotEmpty() }?.let(TaskProblemMatcherParser::severity)
                ?: matcher.severity ?: TaskProblemMatcherParser.ERROR
            return Match(
                matcher,
                file,
                Marker(
                    range,
                    severity,
                    message,
                    matcher.source,
                    code = data.code?.takeIf { it.isNotEmpty() }?.let { Marker.Code(it) })
            )
        }

        private fun range(data: Data): Range? {
            if (data.kind == TaskProblemPattern.Kind.FILE) return Range(1, 1, 1, 1)
            data.location?.let { location ->
                val parts = location.split(',').map { number(it) ?: return null }
                return when {
                    parts.size > 3 -> range(parts[0], parts[1], parts[2], parts[3])
                    else -> range(parts[0], parts.getOrNull(1), null, null)
                }
            }
            val line = data.line?.let(::number) ?: return null
            return range(line, data.column?.let(::number), data.endLine?.let(::number), data.endColumn?.let(::number))
        }

        /** `createLocation`: a column alone is a position, and no column is the whole line. */
        private fun range(line: Int, column: Int?, endLine: Int?, endColumn: Int?): Range = when {
            column != null && endColumn != null -> Range(line, column, endLine ?: line, endColumn)
            column != null -> Range(line, column, line, column)
            else -> Range(line, 1, line, Int.MAX_VALUE)
        }

        /** As JavaScript's `parseInt` reads a group: its leading digits. A 0 is the first line or column, as the workbench takes it. */
        private fun number(text: String): Int? =
            LEADING_DIGITS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1)

        private companion object {
            val LEADING_DIGITS = Regex("""^\s*(\d+)""")
        }
    }
}

/**
 * Where a matched file name is, by the matcher's `fileLocation`, for a task that runs in
 * [workspaceFolder] with [cwd] as its working directory. A prefix's `${cwd}` is the task's, and
 * its other variables are a task's own.
 */
class TaskProblemFiles(
    private val workspaceFolder: Path,
    private val cwd: Path,
    private val folders: Map<String, Path> = emptyMap(),
) {
    private val searched = ConcurrentHashMap<Pair<String, TaskProblemMatcher.FileLocation>, Path>()

    fun resolve(name: String, location: TaskProblemMatcher.FileLocation): Path? = try {
        when (location) {
            TaskProblemMatcher.FileLocation.Absolute -> absolute(name)
            is TaskProblemMatcher.FileLocation.Relative -> relative(location.prefix, name)
            is TaskProblemMatcher.FileLocation.AutoDetect -> relative(
                location.prefix,
                name
            ).takeIf { it.isRegularFile() } ?: absolute(name)

            is TaskProblemMatcher.FileLocation.Search -> searched[name to location] ?: search(
                name,
                location
            )?.also { searched[name to location] = it }
        }
    } catch (_: InvalidPathException) {
        null
    }

    /** A name that is not absolute after all is taken as the task's own tools would: from its directory. */
    private fun absolute(name: String): Path = cwd.resolve(name).normalize()

    private fun relative(prefix: String, name: String): Path = Path.of(substitute(prefix)).resolve(name).normalize()

    private fun search(name: String, location: TaskProblemMatcher.FileLocation.Search): Path? {
        val target = Path.of(name)
        if (target.isAbsolute) return target.takeIf { it.isRegularFile() }
        val excluded = location.exclude.map { Path.of(substitute(it)).normalize() }
        for (include in location.include.map { Path.of(substitute(it)).normalize() }) {
            if (!include.isDirectory()) continue
            runCatching {
                Files.walk(include).use { paths ->
                    paths.filter { path -> excluded.none { path.startsWith(it) } && path.endsWith(target) && path.isRegularFile() }
                        .findFirst().orElse(null)
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun substitute(text: String): String =
        VariableSubstitutor(
            SimpleSubstitutionContext(
                workspaceFolder = workspaceFolder,
                namedWorkspaceFolders = folders
            )
        )
            .substitute(text.replace($$"${cwd}", cwd.toString()))
}
