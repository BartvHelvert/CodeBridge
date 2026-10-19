package me.helvert.codebridge.launch

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.ide.EditorFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A run configuration's form, applied: what it changed is written to its `launch.json` entry, and
 * the run configurations follow the file.
 */
class LaunchFormWriteTest : EditorFixture() {

    /** The light test IDE does not load the plugin's configuration types. */
    @BeforeEach
    fun registerTypes() {
        ExtensionTestUtil.maskExtensions(
            ConfigurationType.CONFIGURATION_TYPE_EP,
            ConfigurationType.CONFIGURATION_TYPE_EP.extensionList + listOf(CodeBridgeDebugConfigurationType()),
            fixture.testRootDisposable
        )
        // The light project, and its run configurations, outlive a test.
        onEdt {
            RunManager.getInstance(fixture.project)
                .let { manager -> manager.allSettings.forEach(manager::removeConfiguration) }
        }
    }

    private val launch = """
        {
            "configurations": [
                {
                    "name": "Server",
                    "type": "probe",
                    "request": "launch",
                    // Built first.
                    "program": "server",
                    "preLaunchTask": "build"
                },
                { "name": "Client", "type": "probe", "request": "launch" }
            ],
            "compounds": [{ "name": "Both", "configurations": ["Server", "Client"] }]
        }
    """.trimIndent()

    private fun workspace(): Path {
        val workspace = Files.createTempDirectory("codebridge-launch-form")
        workspace.resolve(".vscode").createDirectories().resolve("launch.json").writeText(launch)
        return workspace
    }

    private fun sync(workspace: Path) = fixture.project.service<CodeBridgeLaunchConfigurations>().sync(workspace)

    /**
     * The `launch.json` as saved. From its document once it has one: the local file system writes a
     * save out asynchronously, so the disk may not have it yet when this is asked.
     */
    private fun written(workspace: Path): String {
        val file = workspace.resolve(".vscode/launch.json")
        val manager = FileDocumentManager.getInstance()
        val document = LocalFileSystem.getInstance().findFileByNioFile(file)?.let(manager::getCachedDocument)
            ?: return file.readText()
        assertFalse(manager.isDocumentUnsaved(document), "The change was made but not saved")
        return document.text
    }

    private fun settings(name: String): RunnerAndConfigurationSettings =
        RunManager.getInstance(fixture.project).allSettings.single { (it.configuration as? CodeBridgeDebugRunConfiguration)?.source?.name == name }

    private fun configuration(name: String) = settings(name).configuration as CodeBridgeDebugRunConfiguration

    @Test
    fun `what the form changed is written to the entry, and nothing else`() {
        val workspace = workspace()
        onEdt {
            sync(workspace)
            configuration("Server").pending =
                EntryChanges(set = mapOf("program" to JsonPrimitive("server2")), removed = setOf("preLaunchTask"))
            sync(workspace)

            assertEquals(
                launch.replace(
                    "\"program\": \"server\",\n            \"preLaunchTask\": \"build\"\n",
                    "\"program\": \"server2\"\n"
                ),
                written(workspace)
            )
            assertNull(configuration("Server").pending)
        }
    }

    @Test
    fun `a rename moves the configuration to the entry's new name, and the compound with it`() {
        val workspace = workspace()
        onEdt {
            sync(workspace)
            val server = settings("Server")
            server.name = "Backend"
            (server.configuration as CodeBridgeDebugRunConfiguration).pending =
                EntryChanges(set = mapOf("name" to JsonPrimitive("Backend")))
            sync(workspace)

            val written = written(workspace)
            assertEquals(launch.replace("\"Server\"", "\"Backend\""), written)
            // The same run configuration, now of the renamed entry: nothing was made anew.
            assertEquals(DebugSource.LaunchJson("Backend"), (server.configuration as CodeBridgeDebugRunConfiguration).source)
            assertEquals("Backend", server.name)
            assertEquals(
                setOf("Backend", "Client", "Both"),
                RunManager.getInstance(fixture.project).allSettings.map { it.name }.toSet()
            )
        }
    }

    @Test
    fun `a compound's form writes the compound`() {
        val workspace = workspace()
        onEdt {
            sync(workspace)
            configuration("Both").pending = EntryChanges(set = mapOf("stopAll" to JsonPrimitive(true)))
            sync(workspace)

            assertEquals(
                launch.replace(
                    "\"configurations\": [\"Server\", \"Client\"] }",
                    "\"configurations\": [\"Server\", \"Client\"], \"stopAll\": true }"
                ),
                written(workspace)
            )
        }
    }

    @Test
    fun `a name the file refuses leaves the file alone and the configuration as the file has it`() {
        val workspace = workspace()
        onEdt {
            sync(workspace)
            val server = settings("Server")
            server.name = "Client"
            (server.configuration as CodeBridgeDebugRunConfiguration).pending =
                EntryChanges(set = mapOf("name" to JsonPrimitive("Client")))
            sync(workspace)

            assertEquals(launch, written(workspace))
            assertEquals(DebugSource.LaunchJson("Server"), (server.configuration as CodeBridgeDebugRunConfiguration).source)
            assertEquals("Server", server.name)
        }
    }

    @Test
    fun `pending changes are written out for the dialog to compare, and never read back`() {
        val configuration =
            CodeBridgeDebugRunConfiguration(fixture.project, CodeBridgeDebugConfigurationType().configurationFactories.first())
        configuration.source = DebugSource.LaunchJson("Server")
        configuration.managed = true
        configuration.pending = EntryChanges(set = mapOf("program" to JsonPrimitive("x")))
        val element = org.jdom.Element("configuration")
        configuration.writeExternal(element)

        val read = CodeBridgeDebugRunConfiguration(fixture.project, CodeBridgeDebugConfigurationType().configurationFactories.first())
        read.readExternal(element)

        assertEquals("""{"set":{"program":"x"},"removed":[]}""", element.getChildText("pendingLaunchJsonChanges"))
        assertNull(read.pending)
        assertEquals(DebugSource.LaunchJson("Server"), read.source)
    }
}
