package me.helvert.codebridge.workbench

import me.helvert.codebridge.workspace.WorkspaceChoices
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeBridgeWorkspaceFoldersTest {
    @TempDir lateinit var temp: Path

    @Test fun `only the workspace folders outside the project directory are indexed as extra roots`() {
        val project = Files.createDirectories(temp.resolve("project"))
        Files.createDirectories(project.resolve("app"))
        val outside = Files.createDirectories(temp.resolve("shared-lib"))
        Files.writeString(project.resolve("w.code-workspace"),
            "{\"folders\": [{\"path\": \"app\"}, {\"name\": \"Shared\", \"path\": \"../shared-lib\"}]}")
        WorkspaceChoices.choose(project, WorkspaceChoices.Choice.File("w.code-workspace"))

        assertEquals(listOf("Shared" to outside.toAbsolutePath().normalize()),
            CodeBridgeWorkspaceFolders.outsideFolders(project.toAbsolutePath().normalize()).map { it.name to it.path })
        assertEquals(emptyList(), CodeBridgeWorkspaceFolders.outsideFolders(Files.createDirectories(temp.resolve("single"))))
    }
}
