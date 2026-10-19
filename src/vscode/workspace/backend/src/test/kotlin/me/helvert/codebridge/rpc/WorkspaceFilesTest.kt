package me.helvert.codebridge.rpc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.workspace.JsoncEdit
import me.helvert.codebridge.workspace.WorkspaceChoices
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which file the Tools menu's open actions open, and what they make first. */
class WorkspaceFilesTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `a single folder has its own files, made when missing`() {
        assertNull(WorkspaceFiles.targets(temp))
        val tasks = assertNotNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.TASKS, null))
        assertEquals(temp.resolve(".vscode/tasks.json"), tasks.path)
        assertTrue(tasks.created)
        assertEquals(JsonPrimitive("2.0.0"), JsoncEdit.parse(Files.readString(tasks.path))["version"])
        assertFalse(assertNotNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.TASKS, null)).created)
        assertEquals("{\n}\n", Files.readString(assertNotNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.SETTINGS, null)).path))
    }

    @Test
    fun `in a multi-root workspace a folder's file is its own, and the workspace's is the file's section`() {
        Files.createDirectories(temp.resolve("app"))
        val workspace = Files.writeString(temp.resolve("w.code-workspace"), "{\n  // mine\n  \"folders\": [{\"path\": \"app\"}]\n}\n")
        WorkspaceChoices.choose(temp, WorkspaceChoices.Choice.File("w.code-workspace"))
        assertEquals(listOf("app"), WorkspaceFiles.targets(temp))

        val folder = assertNotNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.LAUNCH, null, "app"))
        assertEquals(temp.resolve("app/.vscode/launch.json"), folder.path)
        assertNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.LAUNCH, null, "nowhere"))

        assertFalse(WorkspaceFiles.exists(temp, WorkspaceFiles.LAUNCH, null))
        val section = assertNotNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.LAUNCH, null))
        assertEquals(workspace, section.path)
        assertTrue(section.created)
        val text = Files.readString(workspace)
        assertTrue(text.contains("// mine"), "The rest of the file is left as it was: $text")
        assertEquals(text.indexOf("\"launch\""), section.offset)
        assertEquals(JsonPrimitive("0.2.0"), JsoncEdit.parse(text)["launch"]!!.jsonObject["version"])
        assertTrue(WorkspaceFiles.exists(temp, WorkspaceFiles.LAUNCH, null))
        assertFalse(assertNotNull(WorkspaceFiles.prepare(temp, WorkspaceFiles.LAUNCH, null)).created)
    }
}
