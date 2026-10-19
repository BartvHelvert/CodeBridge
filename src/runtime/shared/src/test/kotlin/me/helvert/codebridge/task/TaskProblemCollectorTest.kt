package me.helvert.codebridge.task

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.exthost.protocol.payload.Range
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskProblemCollectorTest {
    @TempDir
    lateinit var temp: Path

    private fun json(text: String) = Json.parseToJsonElement(text)

    private class Sink(val open: Set<Path> = emptySet()) : TaskProblemSink {
        val filed = mutableMapOf<String, MutableMap<Path, List<Marker>>>()
        val published = mutableListOf<Pair<Path, Int>>()
        override fun files(owner: String) = filed[owner]?.keys?.toSet().orEmpty()
        override fun publish(owner: String, file: Path, markers: List<Marker>) {
            published += file to markers.size
            if (markers.isEmpty()) filed[owner]?.remove(file) else filed.getOrPut(owner) { mutableMapOf() }[file] =
                markers
        }

        override fun isOpen(file: Path) = file in open
    }

    private fun matchers(
        value: String,
        named: Map<String, JsonObject> = CoreProblemMatchers.matchers,
        patterns: Map<String, JsonObject> = CoreProblemMatchers.patterns
    ) =
        TaskProblemMatchers.diagnostics(json(value), named, patterns)
            .also { assertEquals(emptyList(), it.errors) }.matchers

    private fun collector(matchers: List<TaskProblemMatcher>, sink: Sink, background: Boolean = false) =
        TaskProblemCollector(
            matchers,
            background,
            { name, matcher -> TaskProblemFiles(temp, temp).resolve(name, matcher.fileLocation) },
            sink
        )

    @Test
    fun `a single pattern takes VS Code's default groups`() {
        val sink = Sink()
        val collector = collector(
            matchers("""{"owner":"cc","source":"cc","pattern":{"regexp":"^(.*):(\\d+):(\\d+): (.*)$"}}"""),
            sink
        )
        collector.output("out", "src/a.c:3:7: expected ';'\nnot a problem\n")
        collector.done()
        val marker = sink.filed.getValue("cc").getValue(temp.resolve("src/a.c")).single()
        assertEquals(Range(3, 7, 3, 7), marker.range)
        assertEquals(
            "src/a.c:3:7: expected ';'",
            marker.message,
            "The message is the whole match unless a group is named"
        )
        assertEquals("cc", marker.source)
        assertEquals(8, marker.severity, "No severity group or matcher severity is an error")
    }

    @Test
    fun `location groups severities and codes are read as the core msCompile matcher reads them`() {
        val sink = Sink()
        val collector = collector(matchers($$""""$msCompile""""), sink)
        val file = temp.resolve("Main.cs")
        collector.output("out", "  2>$file(10,5,10,9): warning CS0168: The variable 'e' is declared but never used\n")
        collector.output("out", "$file(12): fatal error C1083: Cannot open include file\n")
        collector.done()
        val (first, second) = sink.filed.getValue("msCompile").getValue(file)
        assertEquals(Range(10, 5, 10, 9), first.range)
        assertEquals(4, first.severity)
        assertEquals("CS0168", first.code?.value)
        assertEquals(Range(12, 1, 12, Int.MAX_VALUE), second.range, "A line alone is the whole line")
        assertEquals(8, second.severity, "'fatal error' is not a severity VS Code knows, so it falls back to an error")
    }

    @Test
    fun `a looping multi-line pattern makes a problem of every line after the file`() {
        val sink = Sink()
        val collector = collector(matchers($$""""$eslint-stylish""""), sink)
        collector.output("out", "./src/app.js\n  1:10  error  'x' is defined but never used  no-unused-vars\n")
        collector.output("out", "  4:2  warning  Missing semicolon  semi\n\n./src/other.js\n  2:1  error  Bad  rule\n")
        collector.done()
        val app = sink.filed.getValue("eslint").getValue(temp.resolve("src/app.js"))
        assertEquals(listOf("'x' is defined but never used", "Missing semicolon"), app.map { it.message })
        assertEquals(listOf("no-unused-vars", "semi"), app.map { it.code?.value })
        assertEquals(listOf(8, 4), app.map { it.severity })
        assertEquals(
            Range(2, 1, 2, 1),
            sink.filed.getValue("eslint").getValue(temp.resolve("src/other.js")).single().range
        )
    }

    @Test
    fun `named patterns and bases come from contributions`() {
        val manifest = json(
            $$"""{"contributes":{
            "problemPatterns":[{"name":"rustc","patterns":[
                {"regexp":"^(warning|error)(?:\\[(.*?)\\])?: (.*)$","severity":1,"code":2,"message":3},
                {"regexp":"^[\\s->=]*(.*?):(\\d*):(\\d*)\\s*$","file":1,"line":2,"column":3}]}],
            "problemMatchers":[{"name":"rustc","owner":"rustc","source":"rustc","fileLocation":["autoDetect","${workspaceFolder}"],"pattern":"$rustc"}]}}"""
        ).jsonObject
        val named = TaskProblemMatchers.contributions(listOf(manifest))
        val patterns = TaskProblemMatchers.patternContributions(listOf(manifest))
        val sink = Sink()
        val collector = collector(matchers($$"""{"base":"$rustc","owner":"mine"}""", named, patterns), sink)
        collector.output("err", "error[E0308]: mismatched types\n  --> src/main.rs:4:18\n")
        collector.done()
        val marker = sink.filed.getValue("mine").getValue(temp.resolve("src/main.rs")).single()
        assertEquals("mismatched types", marker.message)
        assertEquals("E0308", marker.code?.value)
        assertEquals("rustc", marker.source, "What the override leaves alone is the base's")
        assertEquals(Range(4, 18, 4, 18), marker.range)
    }

    @Test
    fun `a rerun replaces the owner's problems file by file and withdraws the rest when it ends`() {
        val sink = Sink()
        val matcher = matchers("""{"owner":"cc","pattern":{"regexp":"^(.*):(\\d+):(\\d+): (.*)$","message":4}}""")
        collector(matcher, sink).apply { output("out", "a.c:1:1: one\nb.c:1:1: two\n"); done() }
        assertEquals(setOf(temp.resolve("a.c"), temp.resolve("b.c")), sink.files("cc"))
        val rerun = collector(matcher, sink)
        rerun.output("out", "a.c:2:1: still\n")
        assertEquals(
            setOf(temp.resolve("a.c"), temp.resolve("b.c")),
            sink.files("cc"),
            "Nothing is withdrawn while the run goes on"
        )
        assertEquals("still", sink.filed.getValue("cc").getValue(temp.resolve("a.c")).single().message)
        rerun.done()
        assertEquals(setOf(temp.resolve("a.c")), sink.files("cc"))
    }

    @Test
    fun `a watcher's problems are replaced compile by compile`() {
        val sink = Sink()
        val matcher = matchers(
            """{"owner":"tsc","pattern":{"regexp":"^(.*):(\\d+):(\\d+) - (.*)$","message":4},
            "background":{"activeOnStart":true,"beginsPattern":"Starting","endsPattern":"Watching for file changes"}}"""
        )
        val collector = collector(matcher, sink, background = true)
        collector.output("out", "a.ts:1:1 - first\nb.ts:2:2 - second\nWatching for file changes\n")
        assertEquals(setOf(temp.resolve("a.ts"), temp.resolve("b.ts")), sink.files("tsc"))
        collector.output("out", "Starting\nb.ts:3:3 - again\n")
        assertEquals(setOf(temp.resolve("a.ts"), temp.resolve("b.ts")), sink.files("tsc"))
        collector.output("out", "Watching for file changes\n")
        assertEquals(setOf(temp.resolve("b.ts")), sink.files("tsc"))
        assertEquals("again", sink.filed.getValue("tsc").getValue(temp.resolve("b.ts")).single().message)
    }

    @Test
    fun `fragmented coloured output is matched line by line and a final line without a newline on done`() {
        val sink = Sink()
        val collector = collector(
            matchers("""{"owner":"cc","pattern":{"regexp":"^(.*):(\\d+):(\\d+): (.*)$","message":4}}"""),
            sink
        )
        collector.output("out", "\u001b[1ma.c:1")
        collector.output("err", "unrelated\r\n")
        collector.output("out", ":2: \u001b[31mbroken\u001b[0m\r\nb.c:1:1: last")
        collector.done()
        assertEquals("broken", sink.filed.getValue("cc").getValue(temp.resolve("a.c")).single().message)
        assertEquals("last", sink.filed.getValue("cc").getValue(temp.resolve("b.c")).single().message)
    }

    @Test
    fun `applyTo leaves out open or closed documents`() {
        val open = temp.resolve("open.ts")
        val sink = Sink(setOf(open))
        val collector = collector(matchers($$""""$gulp-tsc""""), sink)
        collector.output("out", "open.ts(1,1): 2304 Cannot find name\nclosed.ts(1,1): 2304 Cannot find name\n")
        collector.done()
        assertEquals(setOf(temp.resolve("closed.ts")), sink.files("typescript"))
    }

    @Test
    fun `file locations resolve relative, auto-detected, absolute and searched names`() {
        temp.resolve("sub/deep").createDirectories()
        temp.resolve("sub/deep/found.c").createFile()
        val cwd = temp.resolve("sub")
        val files = TaskProblemFiles(temp, cwd)
        assertEquals(cwd.resolve("x.c"), files.resolve("x.c", TaskProblemMatcher.FileLocation.Relative($$"${cwd}")))
        assertEquals(
            temp.resolve("x.c"),
            files.resolve("x.c", TaskProblemMatcher.FileLocation.Relative($$"${workspaceFolder}"))
        )
        assertEquals(
            cwd.resolve("deep/found.c"),
            files.resolve("deep/found.c", TaskProblemMatcher.FileLocation.AutoDetect($$"${cwd}"))
        )
        assertEquals(
            cwd.resolve("missing.c"),
            files.resolve("missing.c", TaskProblemMatcher.FileLocation.AutoDetect($$"${workspaceFolder}")),
            "What is not there relative to the prefix is taken as absolute, which a relative name is from the task's directory"
        )
        assertEquals(
            temp.resolve("sub/deep/found.c"), files.resolve(
                "deep/found.c",
                TaskProblemMatcher.FileLocation.Search(listOf($$"${workspaceFolder}"), emptyList())
            )
        )
        assertNull(
            files.resolve(
                "deep/found.c",
                TaskProblemMatcher.FileLocation.Search(
                    listOf($$"${workspaceFolder}"),
                    listOf($$"${workspaceFolder}/sub")
                )
            )
        )
    }

    @Test
    fun `unreadable matchers are reported and the readable ones kept`() {
        val result = TaskProblemMatchers.diagnostics(
            json($$"""["$absent", {"pattern":{"regexp":"["}}, {"owner":"ok","pattern":{"regexp":"x"}}, {"background":{"beginsPattern":"a","endsPattern":"b"}}]"""),
            emptyMap(), emptyMap()
        )
        assertEquals(listOf("ok"), result.matchers.map { it.owner })
        assertEquals(2, result.errors.size)
        assertContains(result.errors[0], "absent")
        assertContains(result.errors[1], "invalid regexp")
    }

    @Test
    fun `tasks json and extension tasks carry their matchers`() {
        temp.resolve(".vscode").createDirectories()
        temp.resolve(".vscode/tasks.json").toFile().writeText(
            $$"""{"version":"2.0.0","tasks":[
            {"label":"build","command":"make","problemMatcher":["$go"]}]}"""
        )
        assertEquals(
            listOf("go"),
            TaskSources.resolve(
                temp,
                TaskSource.TasksJson("build"),
                CoreProblemMatchers.matchers,
                CoreProblemMatchers.patterns
            ).problems.matchers.map { it.owner })
        val extension = TaskSource.Extension(
            "check",
            "x",
            buildJsonObject { put("type", "cargo") },
            TaskCommand.Process("cargo"),
            listOf($$"$msCompile")
        )
        assertEquals(
            listOf("msCompile"),
            TaskSources.resolve(
                temp,
                extension,
                CoreProblemMatchers.matchers,
                CoreProblemMatchers.patterns
            ).problems.matchers.map { it.owner })
    }
}
