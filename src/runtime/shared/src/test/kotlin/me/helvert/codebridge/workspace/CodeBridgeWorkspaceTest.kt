package me.helvert.codebridge.workspace

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeBridgeWorkspaceTest {
    @TempDir lateinit var temp: Path

    private fun folder(name: String): Path = Files.createDirectories(temp.resolve(name))

    private fun choose(name: String?) =
        WorkspaceChoices.choose(temp, name?.let(WorkspaceChoices.Choice::File) ?: WorkspaceChoices.Choice.FolderOnly)

    @Test
    fun `without a chosen workspace file the project directory is the one folder`() {
        val layout = CodeBridgeWorkspace.of(temp)

        assertFalse(layout.isMultiRoot)
        assertEquals(listOf(temp.toAbsolutePath().normalize()), layout.folders.map { it.path })
        assertEquals(temp.fileName.toString(), layout.name)
    }

    @Test
    fun `a workspace file is used once chosen, never just because it is there`() {
        folder("a"); folder("b")
        Files.writeString(temp.resolve("front.code-workspace"), """{"folders": [{"path": "a"}]}""")
        Files.writeString(temp.resolve("all.code-workspace"), """{"folders": [{"path": "a"}, {"path": "b"}]}""")

        assertEquals(listOf("all.code-workspace", "front.code-workspace"), CodeBridgeWorkspace.candidates(temp).map { it.fileName.toString() })
        assertFalse(CodeBridgeWorkspace.of(temp).isMultiRoot, "There, but not chosen: as in VS Code")

        choose("front.code-workspace")
        assertEquals(listOf("a"), CodeBridgeWorkspace.of(temp).folders.map { it.name })
        choose("all.code-workspace")
        assertEquals(listOf("a", "b"), CodeBridgeWorkspace.of(temp).folders.map { it.name })
        choose(null)
        assertFalse(CodeBridgeWorkspace.of(temp).isMultiRoot, "The folder alone, by choice")
    }

    @Test
    fun `a workspace file's folders are read as VS Code reads them`() {
        val app = folder("app")
        val outside = Files.createDirectories(temp.resolveSibling("${temp.fileName}-lib"))
        Files.writeString(temp.resolve("both.code-workspace"), """
            {
              // Comments and trailing commas, as VS Code allows.
              "folders": [
                {"path": "app"},
                {"name": "Library", "uri": "${outside.toUri()}"},
                {"path": "missing"},
                {"path": "./app"},
              ],
            }
        """.trimIndent())
        choose("both.code-workspace")

        val layout = CodeBridgeWorkspace.of(temp)

        assertTrue(layout.isMultiRoot)
        assertEquals("both (Workspace)", layout.name)
        assertEquals(listOf("app" to app, "Library" to outside.toAbsolutePath().normalize()), layout.folders.map { it.name to it.path })
        assertEquals(listOf(0, 1), layout.folders.map { it.index })
        assertEquals("app", layout.folderOf(app.resolve("src/main.rs"))?.name)
        assertNull(layout.folderOf(temp.resolve("elsewhere.txt")))
    }

    @Test
    fun `a chosen workspace file that cannot be used leaves the directory the workspace, and says why`() {
        Files.writeString(temp.resolve("broken.code-workspace"), "{\"folders\": [")
        choose("broken.code-workspace")
        val problems = mutableListOf<String>()

        assertFalse(CodeBridgeWorkspace.of(temp, problems::add).isMultiRoot)
        assertTrue(problems.single().contains("broken.code-workspace"), problems.toString())

        Files.writeString(temp.resolve("broken.code-workspace"), """{"folders": [{"path": "nowhere"}]}""")
        assertFalse(CodeBridgeWorkspace.of(temp, problems::add).isMultiRoot)
        assertTrue(problems.last().contains("names no folder"), problems.toString())

        Files.delete(temp.resolve("broken.code-workspace"))
        assertFalse(CodeBridgeWorkspace.of(temp, problems::add).isMultiRoot)
        assertTrue(problems.last().contains("is gone"), problems.toString())
    }

    @Test
    fun `the workspace file's sections are read on demand`() {
        folder("app")
        val file = Files.writeString(temp.resolve("w.code-workspace"), """{"folders": [{"path": "app"}], "settings": {"a.b": 1}}""")
        choose("w.code-workspace")
        val layout = CodeBridgeWorkspace.of(temp)

        assertEquals("1", layout.section("settings")?.get("a.b").toString())
        assertNull(layout.section("launch"))
        Files.writeString(file, "{")
        assertNull(layout.section("settings"), "A file half-way through an edit has no sections")
    }
}
