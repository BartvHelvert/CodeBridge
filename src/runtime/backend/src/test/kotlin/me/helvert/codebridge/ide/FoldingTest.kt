package me.helvert.codebridge.ide

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.Language
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.awaitCancellation
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeFoldingBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Folding, from an extension's folding ranges to the fold regions an editor shows.
 *
 * The folding pass and the regions belong to the platform; what can go wrong here is asking when the
 * document opens, turning lines into the offsets a fold covers, keeping the ranges in place while
 * the next answer is out, and having the editor fold afresh when an answer changes them.
 */
class FoldingTest : EditorFixture() {

    /** The test IDE does not load this module's descriptor, so the builder is registered here; see [OutlineTest]. */
    @BeforeEach
    fun reset() = onEdt {
        features().hostStopped()
        LanguageFolding.INSTANCE.addExplicitExtension(Language.ANY, CodeBridgeFoldingBuilder(), fixture.testRootDisposable)
        // Adding for ANY clears only ANY's cached builders. Plain text keeps the ones an earlier test
        // had the platform look up, without this one, and the editor would fold nothing; so it is
        // cleared too, now and once the builder is gone again.
        LanguageFolding.INSTANCE.clearCache(PlainTextLanguage.INSTANCE)
        Disposer.register(fixture.testRootDisposable) { LanguageFolding.INSTANCE.clearCache(PlainTextLanguage.INSTANCE) }
    }

    @Test
    fun `the builder is registered for every language`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("""<lang.foldingBuilder language="" implementationClass="${CodeBridgeFoldingBuilder::class.java.name}"/>"""))
    }

    @Test
    fun `ranges fold from the end of their first line, nested, and crossing or one-line ranges are left out`() = onEdt {
        open(SOURCE)
        answer(RANGES)
        mirror()
        register()
        settle()

        assertEquals(listOf(fold(2, 6), fold(4, 5)), built().map { it.first })
        assertEquals(1, asked.size)
        assertTrue(asked.single().endsWith(",{}]"))
    }

    @Test
    fun `imports fold by default as the IDE's own setting says`() = onEdt {
        open(SOURCE)
        answer("""[{"start":2,"end":6,"kind":{"value":"imports"}},{"start":4,"end":5,"kind":{"value":"comment"}}]""")
        mirror()
        register()
        settle()

        val settings = CodeFoldingSettings.getInstance()
        val collapse = settings.COLLAPSE_IMPORTS
        try {
            settings.COLLAPSE_IMPORTS = true
            assertEquals(listOf(fold(2, 6) to true, fold(4, 5) to false), built())
            settings.COLLAPSE_IMPORTS = false
            assertEquals(listOf(fold(2, 6) to false, fold(4, 5) to false), built())
        } finally {
            settings.COLLAPSE_IMPORTS = collapse
        }
    }

    @Test
    fun `folds move with an edit while the answer is out, and typing on a first line stays shown`() = onEdt {
        open(SOURCE)
        answer(RANGES)
        mirror()
        register()
        settle()

        // The provider is slow to answer: what is held has to carry the folds on its own.
        answer(null)
        type(0, "// x\n")
        assertEquals(listOf(fold(3, 7), fold(5, 6)), built().map { it.first })
        type(fixture.editor.document.getLineEndOffset(2), " // y")
        assertEquals(listOf(fold(3, 7), fold(5, 6)), built().map { it.first })
    }

    @Test
    fun `the editor folds, and folds afresh when a new answer changes the ranges`() = onEdt {
        open(SOURCE)
        answer(RANGES)
        mirror()
        register()
        settle()
        // A folding pass that ran when the editor opened keeps its empty answer until the redraw
        // that follows the provider's has the editor build its folds afresh.
        features().awaitRepaintsForTest()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(fold(2, 6), fold(4, 5)), regions())

        // Only the answer changes; the PSI the folding pass caches its regions against does not.
        answer("""[{"start":2,"end":6}]""")
        features().handle("MainThreadLanguageFeatures", $$"$emitFoldingRangeEvent", "[$EVENT_HANDLE]")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (asked.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        settle()
        features().awaitRepaintsForTest()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(fold(2, 6)), regions())
    }

    @Test
    fun `a provider that goes away takes its folds with it, and one for another language is not asked`() = onEdt {
        open(SOURCE)
        answer(RANGES)
        mirror()
        register()
        settle()
        assertEquals(2, built().size)

        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[$HANDLE]")
        assertEquals(emptyList(), built())

        asked.clear()
        register(language = "other")
        settle()
        assertEquals(emptyList(), built())
        assertEquals(emptyList(), asked)
    }

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    private fun file(): VirtualFile = fixture.file.virtualFile

    /** A real file: the URI is built from `toNioPath`, which the fixture's own filesystem refuses. */
    @Suppress("SameParameterValue")
    private fun open(text: String) {
        val path = directory.resolve("example.txt").also { it.writeText(text) }
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
    }

    private fun mirror() {
        val features = features()
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file(), "test", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
    }

    private fun register(language: String = "test") {
        features().handle(
            "MainThreadLanguageFeatures", $$"$registerFoldingRangeProvider",
            """[$HANDLE,[{"language":"$language"}],{"value":"test.extension"},$EVENT_HANDLE]""",
        )
    }

    /** Types [text] at [offset], and mirrors it as the editor's listener would. */
    private fun type(offset: Int, text: String) {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(offset, text) }
        features().documentChanged(file(), HostedLanguageFeatures.Snapshot(document.text, true))
    }

    /** What the provider replies with, one reply per request, the last repeated; null never answers. */
    private fun answer(vararg replies: String?) {
        asked.clear()
        val queue =
            ConcurrentLinkedQueue(replies.dropLast(1).map { checkNotNull(it) { "Only the last reply can be null" } })
        val last = replies.last()
        features().callHostForTest { _, method, args, _ ->
            if (method != $$"$provideFoldingRanges") return@callHostForTest ""
            asked += args
            val reply = queue.poll() ?: last
            reply ?: awaitCancellation()
        }
    }

    private fun settle() {
        assertTrue(
            features().awaitFoldingRangesForTest(10, TimeUnit.SECONDS),
            "The folding range request never settled"
        )
    }

    /** What the builder hands the folding pass now: each range, and whether it starts folded. */
    private fun built(): List<Pair<TextRange, Boolean>> {
        val psi = fixture.file
        return CodeBridgeFoldingBuilder().buildFoldRegions(psi, fixture.editor.document, false)
            .map { it.range to (it.isCollapsedByDefault == true) }
    }

    /** The regions the editor holds once the folding pass has run, as the daemon would run it. */
    private fun regions(): List<TextRange> {
        CodeFoldingManager.getInstance(fixture.project).updateFoldRegions(fixture.editor)
        return fixture.editor.foldingModel.allFoldRegions.map { it.textRange }
    }

    /** The fold of 1-based lines [first] to [last]: from the end of the first to the end of the last. */
    private fun fold(first: Int, last: Int): TextRange {
        val document = fixture.editor.document
        return TextRange(document.getLineEndOffset(first - 1), document.getLineEndOffset(last - 1))
    }

    private val asked = CopyOnWriteArrayList<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-folding") }

    private companion object {
        /** `main` with its closing brace on line 7, holding an `if` whose brace is on line 6. */
        const val SOURCE = "/// doc\nfn main() {\n    let x = 1;\n    if x {\n        y();\n    }\n}\nfn one() {}\n"

        const val HANDLE = 41
        const val EVENT_HANDLE = 42

        /**
         * `main` and the `if`, as a language server folds them -- up to the line before the closing
         * brace -- with a range that crosses `main`, and one that folds no line away.
         */
        const val RANGES = """[{"start":2,"end":6},{"start":4,"end":5},{"start":3,"end":7},{"start":8,"end":8}]"""
    }
}
