package me.helvert.codebridge.task

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.components.service
import com.intellij.testFramework.ExtensionTestUtil
import me.helvert.codebridge.ide.EditorFixture
import me.helvert.codebridge.launch.DebugSource
import me.helvert.codebridge.launch.CodeBridgeDebugConfigurationType
import me.helvert.codebridge.launch.CodeBridgeDebugRunConfiguration
import me.helvert.codebridge.launch.CodeBridgeLaunchConfigurations
import me.helvert.codebridge.workspace.WorkspaceChoices
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** `tasks.json` and `launch.json` as run configurations, grouped by the workspace folder they come from. */
class CodeBridgeTaskConfigurationsTest : EditorFixture() {

    /** The light test IDE does not load the plugin's configuration types. */
    @BeforeEach
    fun registerTypes() {
        ExtensionTestUtil.maskExtensions(
            ConfigurationType.CONFIGURATION_TYPE_EP,
            ConfigurationType.CONFIGURATION_TYPE_EP.extensionList + listOf(
                CodeBridgeTaskConfigurationType(),
                CodeBridgeDebugConfigurationType()
            ),
            fixture.testRootDisposable
        )
        // The light project, and its run configurations, outlive a test.
        onEdt {
            RunManager.getInstance(fixture.project)
                .let { manager -> manager.allSettings.forEach(manager::removeConfiguration) }
        }
    }

    /** Each task configuration as `folder/name`, `-/name` for one in no folder. */
    private fun tasks(): Set<String> =
        settings<CodeBridgeTaskRunConfiguration>().map { "${it.folderName ?: "-"}/${it.name}" }.toSet()

    @Test
    fun `in a single-folder workspace the tasks are listed in no folder, hidden ones left out`() {
        val workspace = Files.createTempDirectory("codebridge-tasks")
        workspace.resolve(".vscode").createDirectories().resolve("tasks.json").writeText(
            """
            { "tasks": [{ "label": "build", "command": "cargo build" }, { "label": "helper", "command": "x", "hide": true }] }
        """.trimIndent()
        )
        onEdt {
            // A run of it from before it was listed: the listed one takes its place.
            CodeBridgeTaskRuns.settings(fixture.project, TaskSource.TasksJson("build"), select = false)
            fixture.project.service<CodeBridgeTaskConfigurations>().sync(workspace)
            assertEquals(setOf("-/build"), tasks())
            val settings = settings<CodeBridgeTaskRunConfiguration>().single()
            assertEquals(false, settings.isTemporary)
            assertEquals(true, (settings.configuration as CodeBridgeTaskRunConfiguration).managed)
        }
    }

    @Test
    fun `in a multi-root workspace tasks and launch entries are each in the folder they come from, the workspace file's at the top`() {
        val workspace = multiRoot()
        onEdt {
            fixture.project.service<CodeBridgeTaskConfigurations>().sync(workspace)
            assertEquals(setOf("app/build (app)", "-/lint (workspace)"), tasks())

            fixture.project.service<CodeBridgeLaunchConfigurations>().sync(workspace)
            val launches = settings<CodeBridgeDebugRunConfiguration>()
                .associate { (it.configuration as CodeBridgeDebugRunConfiguration).source to "${it.folderName}/${it.name}" }
            assertEquals(mapOf<DebugSource?, String>(DebugSource.LaunchJson("Run", "app") to "app/Run (app)"), launches)
        }
    }

    @Test
    fun `a task removed from the file loses its configuration`() {
        val workspace = multiRoot()
        onEdt {
            val tasks = fixture.project.service<CodeBridgeTaskConfigurations>()
            tasks.sync(workspace)
            workspace.resolve("app/.vscode/tasks.json").writeText("""{ "tasks": [] }""")
            tasks.sync(workspace)
            assertEquals(setOf("-/lint (workspace)"), tasks())
        }
    }

    /** By the configuration's class: each test registers the types anew. */
    private inline fun <reified T> settings() =
        RunManager.getInstance(fixture.project).allSettings.filter { it.configuration is T }

    private fun multiRoot(): Path {
        val workspace = Files.createTempDirectory("codebridge-tasks")
        val vscode = workspace.resolve("app").createDirectories().resolve(".vscode").createDirectories()
        vscode.resolve("tasks.json").writeText("""{ "tasks": [{ "label": "build", "command": "cargo build" }] }""")
        vscode.resolve("launch.json")
            .writeText("""{ "configurations": [{ "name": "Run", "type": "probe", "request": "launch" }] }""")
        workspace.resolve("w.code-workspace").writeText(
            """
            { "folders": [{ "path": "app" }],
              "tasks": { "tasks": [{ "label": "lint", "command": "lint" }] } }
        """.trimIndent()
        )
        WorkspaceChoices.choose(workspace, WorkspaceChoices.Choice.File("w.code-workspace"))
        return workspace
    }
}
