package me.helvert.codebridge.task

import kotlinx.serialization.json.*
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class TaskBackgroundTest {
    @TempDir lateinit var temp: Path
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun matcher(active: Boolean = false) = TaskBackground(active, Regex("^BEGIN$"), Regex("^READY$"))

    @Test fun `begin must precede end unless active on start`() {
        val monitor = TaskBackgroundMonitor(listOf(matcher()))
        monitor.output("out", "READY\n")
        assertFalse(monitor.ready.isCompleted)
        monitor.output("out", "BEGIN\nREADY\n")
        assertTrue(monitor.ready.isCompleted)
        val active = TaskBackgroundMonitor(listOf(matcher(true)))
        active.output("out", "READY\n")
        assertTrue(active.ready.isCompleted)
    }

    @Test fun `fragmented coloured lines and CRLF are matched on independent streams`() {
        val monitor = TaskBackgroundMonitor(listOf(matcher(true)))
        monitor.output("out", "RE")
        monitor.output("err", "unrelated\n")
        monitor.output("out", "\u001b[32mADY\u001b[0m\r")
        monitor.output("out", "\n")
        assertTrue(monitor.ready.isCompleted)
    }

    @Test fun `all matchers must finish their first cycle before readiness`() {
        val monitor = TaskBackgroundMonitor(listOf(matcher(true), TaskBackground(true, Regex("^BUILD$"), Regex("^BUILT$"))))
        monitor.output(1, "READY\nBUILD\n")
        assertFalse(monitor.ready.isCompleted)
        monitor.output(1, "BUILT\n")
        assertTrue(monitor.ready.isCompleted)
        monitor.output(1, "BEGIN\n")
        assertTrue(monitor.ready.isCompleted, "Later rebuilds do not retract the launch's first readiness result")
    }

    @Test fun `inline object patterns and named base overrides are resolved`() {
        val base = json("""{"background":{"activeOnStart":true,"beginsPattern":{"regexp":"BEGIN"},"endsPattern":{"regexp":"READY"}}}""").jsonObject
        val named = mapOf($$"$watch" to base)
        assertTrue(TaskProblemMatchers.background(json($$""""$watch""""), named).single().activeOnStart)
        val override = json($$"""{"base":"$watch","background":{"beginsPattern":"START","endsPattern":"DONE"}}""")
        assertEquals("DONE", TaskProblemMatchers.background(override, named).single().endsPattern.pattern)
        assertEquals("DONE", TaskProblemMatchers.background(override, emptyMap()).single().endsPattern.pattern)
    }

    @Test fun `invalid regex unknown matcher and circular references give actionable errors`() {
        assertContains(assertFailsWith<IllegalArgumentException> {
            TaskProblemMatchers.background(json("""{"background":{"beginsPattern":"[","endsPattern":"READY"}}"""), emptyMap())
        }.message!!, "beginsPattern")
        assertContains(assertFailsWith<IllegalArgumentException> {
            TaskProblemMatchers.background(JsonPrimitive($$"$absent"), emptyMap())
        }.message!!, "absent")
        assertContains(assertFailsWith<IllegalArgumentException> {
            TaskProblemMatchers.background(
                JsonPrimitive($$"$loop"),
                mapOf($$"$loop" to json($$"""{"base":"$loop"}""").jsonObject)
            )
        }.message!!, "Circular")
    }

    @Test fun `resolved tasks retain readiness and background metadata including defaults`() {
        temp.resolve(".vscode").createDirectories().resolve("tasks.json").writeText("""
            {"isBackground":true,"problemMatcher":{"background":{"activeOnStart":true,"beginsPattern":"BEGIN","endsPattern":"READY"}},
             "tasks":[{"label":"watch","type":"process","command":"watch"},
                      {"label":"build","command":"build","isBackground":false,"dependsOn":"watch"}]}
        """.trimIndent())
        val root = TaskSources.resolve(temp, TaskSource.TasksJson("build"), emptyMap())
        assertFalse(root.isBackground)
        val watcher = root.dependencies.single()
        assertTrue(watcher.isBackground)
        assertEquals("READY", watcher.background.single().endsPattern.pattern)
        assertEquals(JsonPrimitive(true), watcher.dto["isBackground"])
        assertEquals(1, watcher.dto.getValue("problemMatchers").jsonArray.size)
    }

    @Test fun `contributed matcher names are read from extension manifests`() {
        val definitions = TaskProblemMatchers.contributions(listOf(json("""
            {"contributes":{"problemMatchers":[{"name":"watch","background":{"activeOnStart":true,"beginsPattern":"BEGIN","endsPattern":"READY"}}]}}
        """).jsonObject))
        assertEquals(
            "READY",
            TaskProblemMatchers.background(JsonPrimitive($$"$watch"), definitions).single().endsPattern.pattern
        )
    }

    @Test fun `pinned tsc watch contribution recognizes compilation complete`() {
        val named = TaskProblemMatchers.builtins()
        if ($$"$tsc-watch" !in named) {
            println("skipping: bundled TypeScript matcher is not available"); return
        }
        val monitor = TaskBackgroundMonitor(TaskProblemMatchers.background(JsonPrimitive($$"$tsc-watch"), named))
        monitor.output(1, "12:00:00 AM - Starting compilation in watch mode...\n")
        assertFalse(monitor.ready.isCompleted)
        monitor.output(1, "12:00:01 AM - Found 0 errors. Watching for file changes.\n")
        assertTrue(monitor.ready.isCompleted)
    }
}
