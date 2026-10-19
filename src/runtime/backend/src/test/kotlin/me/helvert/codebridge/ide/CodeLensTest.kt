package me.helvert.codebridge.ide

import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code lenses, from the reply to what code vision is handed.
 *
 * Code vision itself belongs to the platform; what can go wrong here is everything before it:
 * asking when the document opens, resolving what arrived without a command, placing 1-based ranges
 * on a real document, keeping lenses on their lines while the next answer is out, and keeping the
 * host's lists exactly as long as their commands can still be clicked.
 */
class CodeLensTest : EditorFixture() {

    @BeforeEach
    fun reset() = onEdt { features().hostStopped() }

    @Test
    fun `every slot is registered`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        for (provider in slots()) {
            assertTrue(
                descriptor.contains("""<codeInsight.codeVisionProvider implementation="${provider.javaClass.name}"/>"""),
                provider.id
            )
        }
        assertTrue(
            descriptor.contains(
                """<config.codeVisionGroupSettingProvider implementation="me.helvert.codebridge.language.CodeBridgeCodeLensSettingProvider"/>"""
            )
        )
    }

    @Test
    fun `lenses land above what they are about, resolved, and their list is kept`() = onEdt {
        open(SOURCE)
        answer(LENSES, resolved = RESOLVED)
        mirror()
        register()
        settle()

        val drawn = compute()
        assertEquals(listOf("Run", "Debug", "3 references"), drawn.map { it.second.text() })
        assertEquals(listOf(TextRange(0, 22), TextRange(0, 22), TextRange(24, 31)), drawn.map { it.first })
        // Each slot draws one per line, which is all split mode lets a provider draw: Run in the
        // first, Debug chained after it in the second.
        assertEquals(
            listOf("codebridge.code.lenses.0", "codebridge.code.lenses.1", "codebridge.code.lenses.0"),
            drawn.map { it.second.providerId })
        assertEquals(
            listOf("codebridge.code.lenses.0"),
            slots()[1].relativeOrderings.map { (it as CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter).id },
        )
        assertTrue(slots().all { it.groupId == CodeBridgeCodeLensProvider.GROUP_ID })
        assertEquals(
            listOf("""[$HANDLE,{"cacheId":[$CACHE,2],"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":9}}]"""),
            resolvedLenses
        )
        // Their commands are deleted with the list, so it stays while they are shown.
        assertEquals(emptyList(), released)
    }

    @Test
    fun `lenses on one line share the first one's range`() = onEdt {
        // GitLens: recent change from column 1, authors from column 2, to order them in VS Code.
        // A split-mode click finds its entry by the range of the line's first lens alone.
        open(SOURCE)
        answer(
            """{"cacheId":$CACHE,"lenses":[
            {"cacheId":[$CACHE,0],"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":23},"command":{"id":"a","title":"You, 3 days ago"}},
            {"cacheId":[$CACHE,1],"range":{"startLineNumber":1,"startColumn":2,"endLineNumber":1,"endColumn":23},"command":{"id":"b","title":"1 author"}}
        ]}"""
        )
        mirror()
        register()
        settle()

        assertEquals(listOf(TextRange(0, 22), TextRange(0, 22)), compute().map { it.first })
    }

    @Test
    fun `clicking a lens runs its command, and a lens that is only text does nothing`() = onEdt {
        open(SOURCE)
        answer(
            """{"cacheId":$CACHE,"lenses":[
            {"cacheId":[$CACHE,0],"range":$FIRST,"command":{"id":"_internal_command_delegation","title":"Run","arguments":["$CACHE,0"]}},
            {"cacheId":[$CACHE,1],"range":$FIRST,"command":{"title":"2 implementations"}}
        ]}"""
        )
        mirror()
        register()
        settle()

        val (run, text) = compute().map { it.second }
        (run as ClickableTextCodeVisionEntry).onClick(fixture.editor)
        assertEquals(listOf("""["_internal_command_delegation","$CACHE,0"]"""), executed)
        assertTrue(text !is ClickableTextCodeVisionEntry && text is TextCodeVisionEntry)
    }

    @Test
    fun `a lens that resolves to nothing is not drawn, and a list with nothing shown goes back`() = onEdt {
        open(SOURCE)
        answer("""{"cacheId":$CACHE,"lenses":[{"cacheId":[$CACHE,0],"range":$FIRST}]}""", resolved = "")
        mirror()
        register()
        settle()

        assertEquals(emptyList(), compute())
        assertEquals(listOf("[$HANDLE,$CACHE]"), released)
    }

    @Test
    fun `lenses move with an edit, and an answer that reads the same keeps the drawn ones`() = onEdt {
        open(SOURCE)
        answer(LENSES, resolved = RESOLVED)
        mirror()
        register()
        settle()

        // The first line grows, and the provider answers with the same lenses, as a fresh list.
        answer(LENSES.replace("$CACHE", "${CACHE + 1}"), resolved = RESOLVED.replace("$CACHE", "${CACHE + 1}"))
        type(23, " // x")
        assertEquals(
            listOf(0, 0, 29),
            compute().map { it.first.startOffset },
            "Held lenses move while the answer is out"
        )
        settle()
        assertEquals(listOf(0, 0, 29), compute().map { it.first.startOffset })
        assertEquals(listOf("[$HANDLE,${CACHE + 1}]"), released, "The same lenses again: the new list goes back")

        // Something else to say, announced by the provider: the drawn list goes back instead.
        answer("""{"cacheId":${CACHE + 2},"lenses":[{"cacheId":[${CACHE + 2},0],"range":$FIRST,"command":{"id":"x","title":"Run"}}]}""")
        features().handle("MainThreadLanguageFeatures", $$"$emitCodeLensEvent", "[${HANDLE + 1}]")
        settle()
        assertEquals(listOf("Run"), compute().map { it.second.text() })
        assertEquals(listOf("[$HANDLE,$CACHE]"), released, "The replaced list goes back")
    }

    @Test
    fun `a provider that goes away takes its lenses with it, and closing a file releases its list`() = onEdt {
        open(SOURCE)
        answer(LENSES, resolved = RESOLVED)
        mirror()
        register()
        settle()
        assertEquals(3, compute().size)

        features().documentClosed(fixture.file.virtualFile)
        assertEquals(listOf("[$HANDLE,$CACHE]"), released)
        assertEquals(emptyList(), compute())

        released.clear()
        mirror()
        settle()
        assertEquals(3, compute().size)
        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[$HANDLE]")
        assertEquals(emptyList(), compute())
        assertEquals(listOf("[$HANDLE,$CACHE]"), released)
    }

    @Test
    fun `a provider for another language is not asked`() = onEdt {
        open(SOURCE)
        answer(LENSES, resolved = RESOLVED)
        mirror()
        register(language = "other")
        settle()
        assertEquals(emptyList(), compute())
        assertEquals(emptyList(), asked)
    }

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    /** A real file: the URI is built from `toNioPath`, which the fixture's own filesystem refuses. */
    @Suppress("SameParameterValue")
    private fun open(text: String) {
        fixture.openFileInEditor(file("example.txt", text))
    }

    @Suppress("SameParameterValue")
    private fun file(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun mirror() {
        val features = features()
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false),
        )
    }

    private fun register(language: String = "test") {
        features().handle(
            "MainThreadLanguageFeatures",
            $$"$registerCodeLensSupport",
            """[$HANDLE,[{"language":"$language"}],${HANDLE + 1}]""",
        )
    }

    /** Types [text] at [offset], and mirrors it as the editor's listener would. */
    @Suppress("SameParameterValue")
    private fun type(offset: Int, text: String) {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(offset, text) }
        features().documentChanged(fixture.file.virtualFile, HostedLanguageFeatures.Snapshot(document.text, true))
    }

    /** What the provider replies with, and with when a lens is resolved; everything sent is recorded. */
    private fun answer(reply: String, resolved: String = "") {
        released.clear()
        executed.clear()
        resolvedLenses.clear()
        asked.clear()
        features().callHostForTest { _, method, args, _ ->
            when (method) {
                $$"$provideCodeLenses" -> {
                    asked += args
                    reply
                }

                $$"$resolveCodeLens" -> {
                    resolvedLenses += args
                    resolved
                }

                $$"$releaseCodeLenses" -> {
                    released += args
                    ""
                }

                $$"$executeContributedCommand" -> {
                    executed += args
                    ""
                }

                else -> ""
            }
        }
    }

    private fun settle() {
        assertTrue(features().awaitCodeLensesForTest(10, TimeUnit.SECONDS), "The code lens request never settled")
    }

    private fun slots(): List<CodeBridgeCodeLensProvider> = listOf(
        CodeBridgeCodeLens0(), CodeBridgeCodeLens1(), CodeBridgeCodeLens2(), CodeBridgeCodeLens3(),
        CodeBridgeCodeLens4(), CodeBridgeCodeLens5(), CodeBridgeCodeLens6(), CodeBridgeCodeLens7(),
    ).also { assertEquals(CodeBridgeCodeLensProvider.IDS, it.map { slot -> slot.id }) }

    /** What code vision is handed for the editor by every slot, in document order, then slot order. */
    private fun compute(): List<Pair<TextRange, CodeVisionEntry>> =
        slots().flatMap { it.computeCodeVision(fixture.editor, Unit).result }.sortedBy { it.first.startOffset }

    private fun CodeVisionEntry.text(): String = (this as TextCodeVisionEntry).text

    private val released = CopyOnWriteArrayList<String>()
    private val executed = CopyOnWriteArrayList<String>()
    private val resolvedLenses = CopyOnWriteArrayList<String>()
    private val asked = CopyOnWriteArrayList<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-lens") }

    private companion object {
        //          0123456789012345678901 2345678901
        const val SOURCE = "fn main() { compute() }\nfn test\n"

        const val HANDLE = 21
        const val CACHE = 6

        const val FIRST = """{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":23}"""

        /** Two lenses with commands on the first line, and one on the second that comes without. */
        const val LENSES = """{"cacheId":$CACHE,"lenses":[
            {"cacheId":[$CACHE,0],"range":$FIRST,"command":{"id":"_internal_command_delegation","title":"$(play) Run","arguments":["$CACHE,0"]}},
            {"cacheId":[$CACHE,1],"range":$FIRST,"command":{"id":"_internal_command_delegation","title":"Debug","arguments":["$CACHE,1"]}},
            {"cacheId":[$CACHE,2],"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":9}}
        ]}"""

        const val RESOLVED =
            """{"cacheId":[$CACHE,2],"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":9},
            "command":{"id":"_internal_command_delegation","title":"3 references","arguments":["$CACHE,2"]}}"""
    }
}
