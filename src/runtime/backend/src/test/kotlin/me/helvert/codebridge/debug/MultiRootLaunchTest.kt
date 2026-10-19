package me.helvert.codebridge.debug

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.launch.DebugSource
import me.helvert.codebridge.launch.LaunchEntries
import me.helvert.codebridge.workspace.WorkspaceChoices
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Launch configurations in a multi-root workspace: each folder's `launch.json`, and the `.code-workspace` file's own. */
class MultiRootLaunchTest {
    @TempDir
    lateinit var temp: Path

    private fun folder(name: String, launch: String? = null, settings: String? = null): Path {
        val folder = temp.resolve(name).createDirectories()
        launch?.let { folder.resolve(".vscode").createDirectories().resolve("launch.json").writeText(it) }
        settings?.let { folder.resolve(".vscode").createDirectories().resolve("settings.json").writeText(it) }
        return folder
    }

    /** `w.code-workspace` in [temp], chosen for the project as the user would. */
    private fun workspace(content: String) {
        temp.resolve("w.code-workspace").writeText(content)
        WorkspaceChoices.choose(temp, WorkspaceChoices.Choice.File("w.code-workspace"))
    }

    @Test
    fun `entries are listed per folder and for the workspace, named as VS Code names them`() {
        folder("app", launch = """{ "configurations": [{ "name": "Run", "type": "probe", "request": "launch" }] }""")
        folder(
            "lib", launch = """{ "configurations": [{ "name": "Run", "type": "probe", "request": "launch" }],
                                    "compounds": [{ "name": "Run", "configurations": ["Run"] }] }"""
        )
        workspace(
            """{ "folders": [{ "path": "app" }, { "path": "lib" }],
                       "launch": { "configurations": [{ "name": "All", "type": "probe", "request": "launch" }] } }"""
        )

        assertEquals(
            listOf(
                DebugSource.LaunchJson("All") to "All (workspace)",
                DebugSource.LaunchJson("Run", "app") to "Run (app)",
                DebugSource.LaunchJson("Run", "lib") to "Run (lib)",
                DebugSource.Compound("Run", "lib") to "Run (compound) (lib)",
            ),
            LaunchEntries.of(temp),
        )
    }

    @Test
    fun `a folder's entry reads its own folder and settings, and names the others`() {
        val app = folder(
            "app", launch = $$"""{ "configurations": [{ "name": "Run", "type": "probe", "request": "launch",
                                               "program": "${workspaceFolder}/main", "lib": "${workspaceFolder:lib}",
                                               "level": "${config:probe.level}" }] }""",
            settings = """{ "probe.level": "folder" }"""
        )
        val lib = folder("lib")
        workspace("""{ "folders": [{ "path": "app" }, { "path": "lib" }], "settings": { "probe.level": "workspace", "probe.other": 1 } }""")

        val raw = DebugLaunch.configuration(temp, "Run", "app")
        val settings = DebugLaunch.settings(temp, "app")
        val resolved = DebugLaunch.substitute(raw, temp, settings, folder = "app")

        assertEquals("$app/main", (resolved["program"] as JsonPrimitive).content)
        assertEquals(lib.toString(), (resolved["lib"] as JsonPrimitive).content)
        assertEquals("folder", (resolved["level"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(1), DebugLaunch.settings(temp, "app")["probe.other"])
    }

    @Test
    fun `the workspace file's own entries name their folder, as VS Code makes them`() {
        folder("app")
        workspace(
            $$"""{ "folders": [{ "path": "app" }],
                       "launch": { "configurations": [{ "name": "All", "type": "probe", "request": "launch", "cwd": "${workspaceFolder}" }] } }"""
        )

        assertNull(DebugLaunch.folderPath(temp, null))
        val raw = DebugLaunch.configuration(temp, "All")
        assertFailsWith<IllegalArgumentException> { DebugLaunch.substitute(raw, temp, buildJsonObject { }) }
        assertEquals("the launch section of w.code-workspace", DebugLaunch.origin(temp, null))
    }

    @Test
    fun `a compound's member in another folder is found there`() {
        folder("app", launch = """{ "configurations": [{ "name": "Server", "type": "probe", "request": "launch" }] }""")
        folder(
            "web", launch = """{ "configurations": [{ "name": "Client", "type": "probe", "request": "launch" }],
                                    "compounds": [{ "name": "Both", "configurations": ["Client", { "name": "Server", "folder": "app" }] }] }"""
        )
        workspace("""{ "folders": [{ "path": "app" }, { "path": "web" }] }""")

        val compound = DebugLaunch.compound(temp, "Both", "web")
        assertEquals(listOf("Client" to null, "Server" to "app"), compound.members.map { it.name to it.folder })
        assertEquals(
            buildJsonObject { put("name", "Server"); put("type", "probe"); put("request", "launch") },
            DebugLaunch.configuration(temp, "Server", "app")
        )
    }
}
