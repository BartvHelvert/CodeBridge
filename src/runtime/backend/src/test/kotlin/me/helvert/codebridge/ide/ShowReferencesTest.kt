package me.helvert.codebridge.ide

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.usages.UsageViewManager
import com.intellij.usages.impl.rules.UsageWithType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import me.helvert.codebridge.language.CodeBridgeWorkbenchCommands
import me.helvert.codebridge.started
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `editor.action.showReferences`, as rust-analyzer's "N implementations" hover link runs it. */
class ShowReferencesTest : EditorFixture() {

    @Test
    fun `opens the only place at its position`() {
        val target = file("target.rs", "first line\nsecond line\n")
        run(target, locations(location(target, line = 2, column = 8)))
        onEdt {
            val editor = assertNotNull(FileEditorManager.getInstance(fixture.project).selectedTextEditor)
            assertEquals(target.fileName.toString(), FileDocumentManager.getInstance().getFile(editor.document)?.name)
            // Line 2, column 8, both 1-based: "second |line".
            assertEquals("second line".indexOf("line") + "first line\n".length, editor.caretModel.offset)
        }
    }

    @Test
    fun `lists several places as usages, titled after the symbol`() {
        val asked = file("main.rs", "let boxed = Box::new(1);\n")
        val one = file("one.rs", "impl Box {}\n")
        val two = file("two.rs", "\nimpl Box {}\n")
        run(asked, locations(location(one, 1, 6), location(two, 2, 6)), column = 14)
        onEdt {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val view = assertNotNull(UsageViewManager.getInstance(fixture.project).selectedUsageView)
            assertEquals(2, view.usages.size)
            assertEquals("References to Box", view.presentation.tabText)
            // No usage type: not filed under "Unclassified" whatever the grouping setting.
            assertTrue(view.usages.all { it is UsageWithType && it.usageType == null })
            // The light project outlives this test; a view left open goes on updating its nodes in
            // the background and fails whichever test is tearing down once the project is disposed.
            view.close()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    @Test
    fun `refuses arguments it cannot read, and leaves other commands alone`() {
        val failed = started { CodeBridgeWorkbenchCommands.execute(fixture.project, "editor.action.showReferences", JsonArray(emptyList())) }
        assertFailsWith<java.util.concurrent.ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
        assertNull(started { CodeBridgeWorkbenchCommands.execute(fixture.project, "workbench.action.unknown", JsonArray(emptyList())) }
            .get(5, TimeUnit.SECONDS))
    }

    /** Off the editor thread: the command finishes there, and waiting on it from there would hang. */
    private fun run(asked: Path, locations: String, line: Int = 1, column: Int = 1) {
        val arguments = Json.parseToJsonElement("""[${uri(asked)},{"lineNumber":$line,"column":$column},$locations]""") as JsonArray
        assertNotNull(started { CodeBridgeWorkbenchCommands.execute(fixture.project, "editor.action.showReferences", arguments) }
            .get(30, TimeUnit.SECONDS))
    }

    private fun locations(vararg locations: String) = locations.joinToString(",", "[", "]")

    private fun location(file: Path, line: Int, column: Int) =
        """{"uri":${uri(file)},"range":{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$line,"endColumn":${column + 3}}}"""

    private fun uri(file: Path) = """{"scheme":"file","path":"${file.toUri().path}"}"""

    private fun file(name: String, text: String): Path =
        directory.resolve(name).also {
            it.writeText(text)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-references") }
}
