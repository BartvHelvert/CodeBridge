package me.helvert.codebridge.ide

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostDocuments
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
 * `vscode.executeDocumentSymbolProvider`, which GitLens runs for the code lenses it puts on symbols:
 * answered by the extensions that claim the file, else by IntelliJ's own structure.
 */
class DocumentSymbolCommandTest : EditorFixture() {

    @Test
    fun `answers from the extension that claims the file`() = onEdt {
        val file = open("example.txt", "fn main() {}\n")
        features().mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features().documentOpened(file, "test", HostedLanguageFeatures.Snapshot("fn main() {}\n", false))
        features().handle(
            "MainThreadLanguageFeatures",
            $$"$registerDocumentSymbolProvider",
            """[41,[{"language":"test"}],"Test"]"""
        )
        features().callHostForTest { _, method, _, _ ->
            
                if (method != $$"$provideDocumentSymbols") ""
                else """[{"name":"main","kind":11,"range":${range(1, 1, 1, 13)},"selectionRange":${
                    range(
                        1,
                        4,
                        1,
                        8
                    )
                },"children":[]}]"""
            
        }

        val symbols = run(file)
        val main = symbols.single().jsonObject
        assertEquals("main", main["name"]!!.jsonPrimitive.content)
        assertEquals(11, main["kind"]!!.jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement(range(1, 4, 1, 8)), main["selectionRange"])
        assertEquals(JsonArray(emptyList()), main["tags"], "The host reads tags without asking whether they are there")
        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[41]")
    }

    @Test
    fun `answers from IntelliJ's structure for a file no extension outlines`() = onEdt {
        val file = open("example.xml", "<root>\n  <child/>\n</root>\n")

        val root = run(file).single().jsonObject
        assertEquals("root", root["name"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(range(1, 1, 3, 8)), root["range"])
        assertEquals(Json.parseToJsonElement(range(1, 2, 1, 6)), root["selectionRange"], "The name, not the whole tag")
        val child = root["children"]!!.jsonArray.single().jsonObject
        assertEquals("child", child["name"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(range(2, 3, 2, 11)), child["range"])
    }

    @Test
    fun `answers nothing for a file with no structure`() = onEdt {
        assertTrue(run(open("notes.txt", "just words\n")).isEmpty())
    }

    @AfterEach
    fun close() = onEdt {
        LocalFileSystem.getInstance().findFileByNioFile(directory.resolve("example.txt"))
            ?.let(features()::documentClosed)
        features().hostStopped()
    }

    private fun run(file: VirtualFile): JsonArray {
        val arguments = JsonArray(listOf(UriComponents.of(file.toNioPath()).toJson()))
        val result =
            started { CodeBridgeWorkbenchCommands.execute(fixture.project, WorkbenchCommands.EXECUTE_DOCUMENT_SYMBOLS, arguments) }
        // Events dispatched while waiting: IntelliJ's structure is read once documents are committed.
        val json = PlatformTestUtil.waitForFuture(result, 10_000)!!.json!!
        return Json.parseToJsonElement(json) as JsonArray
    }

    private fun open(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        return file
    }

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-symbols") }

    private companion object {
        fun range(line: Int, column: Int, endLine: Int, endColumn: Int) =
            """{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$endLine,"endColumn":$endColumn}"""
    }
}
