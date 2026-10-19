package me.helvert.codebridge.ide

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.Position
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.WorkbenchCommands
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeWorkbenchCommands
import me.helvert.codebridge.started
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `vscode.executeDefinitionProvider` and its kin: answered by the providers that claim the file,
 * as the workbench asks them, else by IntelliJ's own resolution.
 */
class ExecuteProviderCommandsTest : EditorFixture() {

    @Test
    fun `definitions and hovers come from the extension that claims the file`() = onEdt {
        val file = open("claimed.txt", "fn main() { helper() }\nfn helper() {}\n")
        features().mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features().documentOpened(
            file,
            "test",
            HostedLanguageFeatures.Snapshot("fn main() { helper() }\nfn helper() {}\n", false)
        )
        features().handle(
            "MainThreadLanguageFeatures",
            $$"$registerDefinitionSupport",
            """[51,[{"language":"test"}]]"""
        )
        features().handle("MainThreadLanguageFeatures", $$"$registerHoverProvider", """[52,[{"language":"test"}]]""")
        val uri = UriComponents.of(file.toNioPath()).toJson()
        features().callHostForTest { _, method, _, _ ->
            
                when (method) {
                    $$"$provideDefinition" -> """[{"uri":$uri,"range":${
                        range(
                            2,
                            1,
                            2,
                            15
                        )
                    },"targetSelectionRange":${range(2, 4, 2, 10)}}]"""

                    $$"$provideHover" -> """{"contents":[{"value":"helper()"}]}"""
                    else -> ""
                }
            
        }

        val link = run(WorkbenchCommands.EXECUTE_DEFINITIONS, file, Position(1, 14)).single().jsonObject
        assertEquals(Json.parseToJsonElement(range(2, 4, 2, 10)), link["targetSelectionRange"])
        assertEquals(
            JsonPrimitive(1),
            link["uri"]!!.jsonObject[$$"$mid"],
            "Revived as a URI, so the host keeps it a link"
        )

        val hover = run(WorkbenchCommands.EXECUTE_HOVER, file, Position(1, 14)).single().jsonObject
        assertTrue("helper()" in hover.toString())
        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[51]")
        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[52]")
    }

    @Test
    fun `a file no extension claims answers from IntelliJ, here with nothing to resolve`() = onEdt {
        // Plain text has no references; that IntelliJ was asked, and answered, is what is checked.
        val file = open("plain.txt", "just words\n")
        assertTrue(run(WorkbenchCommands.EXECUTE_DEFINITIONS, file, Position(1, 2)).isEmpty())
        assertTrue(run(WorkbenchCommands.EXECUTE_REFERENCES, file, Position(1, 2)).isEmpty())
        assertEquals(JsonArray(emptyList()), run(WorkbenchCommands.EXECUTE_HOVER, file, Position(1, 2)))
    }

    @AfterEach
    fun close() = onEdt {
        LocalFileSystem.getInstance().findFileByNioFile(directory.resolve("claimed.txt"))
            ?.let(features()::documentClosed)
        features().hostStopped()
    }

    private fun run(command: String, file: VirtualFile, at: Position): JsonArray {
        val arguments = JsonArray(listOf(UriComponents.of(file.toNioPath()).toJson(), at.toJson()))
        val result = started { CodeBridgeWorkbenchCommands.execute(fixture.project, command, arguments) }
        return Json.parseToJsonElement(PlatformTestUtil.waitForFuture(result, 10_000)!!.json!!) as JsonArray
    }

    private fun open(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        return file
    }

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-execute") }

    private companion object {
        fun range(line: Int, column: Int, endLine: Int, endColumn: Int) =
            """{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$endLine,"endColumn":$endColumn}"""
    }
}
