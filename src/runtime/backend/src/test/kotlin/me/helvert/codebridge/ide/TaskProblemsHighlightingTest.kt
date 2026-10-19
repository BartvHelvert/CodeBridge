package me.helvert.codebridge.ide

import com.intellij.lang.LanguageExtensionPoint
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeDiagnosticsAnnotator
import me.helvert.codebridge.task.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A task's output, through its problem matcher, to a highlight: a real process prints a compiler's
 * line and the daemon draws it in the file the line names.
 */
class TaskProblemsHighlightingTest : EditorFixture() {

    /** As in [DiagnosticsHighlightingTest]: a light fixture loads no descriptor. */
    @BeforeEach
    fun registerAnnotator() = onEdt {
        val descriptor = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge"),
            CodeBridgeDiagnosticsAnnotator::class.java.classLoader
        )
        ANNOTATORS.point.registerExtension(
            LanguageExtensionPoint("", CodeBridgeDiagnosticsAnnotator::class.java.name, descriptor),
            fixture.testRootDisposable,
        )
    }

    @Test
    fun `a problem a task prints is drawn in the file it names, and the next clean run withdraws it`() {
        onEdt { open("ERROR on the first line\nsecond line") }

        run("example.txt:2:1: $MESSAGE")
        onEdt {
            val highlight = highlights().single()
            assertEquals("ERROR on the first line\n".length, highlight.startOffset, "Line 2, column 1")
            assertTrue(
                highlight.description.startsWith("cc: "),
                "The matcher's source names who said it: ${highlight.description}"
            )
        }

        // The extension host going away takes its own diagnostics with it, not the task's.
        HostedLanguageFeatures.getInstance(fixture.project).hostStopped()
        onEdt { assertEquals(1, highlights().size) }

        run("nothing to report")
        onEdt { assertEquals(0, highlights().size, "A run that no longer mentions the file withdraws its problem") }
    }

    private fun run(output: String) {
        val task = ResolvedTask(
            "build", TaskCommand.Shell(commandLine = "echo $output"), JsonObject(emptyMap()),
            problems = TaskProblemMatchers.diagnostics(Json.parseToJsonElement(MATCHER), emptyMap(), emptyMap()),
        )
        val handler = TaskProcesses.start(directory, task, null, fixture.project.service<TaskProblems>())
        handler.startNotify()
        assertTrue(handler.waitFor(30_000), "The task did not finish")
    }

    @Suppress("SameParameterValue") // The text checked stays in the test that checks it.
    private fun open(text: String) {
        opened = directory.resolve("example.txt").also { it.writeText(text) }
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(opened)))
    }

    private fun highlights(): List<com.intellij.codeInsight.daemon.impl.HighlightInfo> {
        HostedLanguageFeatures.getInstance(fixture.project).awaitRepaintsForTest()
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        return fixture.doHighlighting().filter { it.description?.contains(MESSAGE) == true }
    }

    private lateinit var opened: Path

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-task-problems").toRealPath() }

    private companion object {
        val ANNOTATORS = ExtensionPointName<LanguageExtensionPoint<Annotator>>("com.intellij.annotator")

        const val MESSAGE = "Replace ERROR to clear this problem"
        const val MATCHER =
            """{"owner":"cc","source":"cc","pattern":{"regexp":"^(.*):(\\d+):(\\d+): (.*)$","message":4}}"""
    }
}
