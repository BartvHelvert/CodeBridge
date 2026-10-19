package me.helvert.codebridge.ide

import com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.*
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.components.breadcrumbs.StickyLineInfo
import com.intellij.xml.breadcrumbs.NavigatableCrumb
import kotlinx.coroutines.awaitCancellation
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.internal.CodeBridgeBreadcrumbsCollector
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeStructureViewBuilderProvider
import me.helvert.codebridge.language.CodeBridgeStructureViewModel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * Breadcrumbs and sticky lines, from an extension's document symbols to what the bar and the sticky
 * lines pass are handed.
 *
 * The bar, the pass and the panel belong to the platform; what can go wrong here is asking when the
 * document opens, which symbols are listed and which stick, and on which line, keeping them in place
 * while the next answer is out, telling the bar when one arrives, and the platform finding this
 * collector for the file at all.
 */
class OutlineTest : EditorFixture() {

    /**
     * The test IDE does not load this module's descriptor, so the collector is registered here and
     * the descriptor checked for it separately; what is asserted is that the platform then picks it
     * for a plain text file, over its own collectors.
     */
    @BeforeEach
    fun reset() = onEdt {
        features().hostStopped()
        FileBreadcrumbsCollector.EP_NAME.getPoint(fixture.project)
            .registerExtension(CodeBridgeBreadcrumbsCollector(fixture.project), fixture.testRootDisposable)
    }

    @Test
    fun `the collector is registered`() {
        val descriptor =
            Files.readString(
                Path.of("..", "internal", "backend", "src", "main", "resources", "CodeBridge.internal.backend.xml")
                    .toAbsolutePath()
            )
        assertTrue(descriptor.contains("""<fileBreadcrumbsCollector implementation="${CodeBridgeBreadcrumbsCollector::class.java.name}"/>"""))
    }

    @Test
    fun `symbols that span lines stick on their name's line, nested, and the platform asks this collector`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        assertTrue(
            FileBreadcrumbsCollector.findBreadcrumbsCollector(
                fixture.project,
                file()
            ) is CodeBridgeBreadcrumbsCollector
        )
        assertEquals(setOf(scope(2, 4, 7, 2), scope(4, 5, 6, 6)), collect())
        assertEquals(1, asked.size)
    }

    @Test
    fun `scopes move with an edit until the outline of the new text arrives`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        // Read on the EDT, which never waits for an answer: what is held carries the scopes on its own.
        answer(null)
        type(0, "// x\n")
        assertEquals(setOf(scope(3, 4, 8, 2), scope(5, 5, 7, 6)), collect())
        assertEquals(emptyList(), asked, "Nothing is asked until something reads the outline")
    }

    @Test
    fun `the pass and the bar ask for the outline of the text as it stands, once for each edit`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        val unasked = features().restartsForTest()
        register()
        settle()
        settleRedraws()
        assertTrue(features().restartsForTest() > unasked, "An outline nothing asked for is redrawn")

        answer(SYMBOLS.replace("\"inner\"", "\"outer\""))
        type(fixture.editor.document.textLength, " ")
        val restarts = features().restartsForTest()
        assertEquals(setOf(scope(2, 4, 7, 2), scope(4, 5, 6, 6)), inBackground { collect() })
        assertEquals(
            listOf("main", "outer"),
            inBackground { crumbs(at(5, 9)) },
            "The new answer, not the held one moved"
        )
        assertEquals(1, asked.size, "One request for the edit, however many lines the pass asks about")
        settle()
        settleRedraws()
        assertEquals(restarts, features().restartsForTest(), "What a pass asked for it draws itself")
    }

    @Test
    fun `a scope whose line was edited away is dropped, and the one around it kept`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        answer(null)
        // "    if x {" becomes "  zz x {": the edit begins before `inner`'s first character.
        val line = fixture.editor.document.getLineStartOffset(3)
        replace(line + 2, line + 6, "zz")
        assertEquals(setOf(scope(2, 4, 7, 2)), collect())
    }

    @Test
    fun `breadcrumbs list the symbols around the caret, single-line ones too, and go to a symbol's name`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        assertEquals(listOf("main", "x"), crumbs(at(3, 9)))
        assertEquals(listOf("main", "inner"), crumbs(at(5, 9)))
        assertEquals(listOf("main"), crumbs(at(7, 2)), "Just after the closing brace still counts")
        assertEquals(listOf("one"), crumbs(at(8, 5)))
        assertEquals(listOf("main"), crumbs(at(1, 3)), "The doc comment is in the range")
        assertEquals(
            emptyList(), collector().computeCrumbs(file(), fixture.editor.document, at(3, 9), false).toList(),
            "Breadcrumbs switched off for this editor"
        )

        val main =
            collector().computeCrumbs(file(), fixture.editor.document, at(3, 9), null).first() as NavigatableCrumb
        assertEquals(AllIcons.Nodes.Function, main.icon)
        assertEquals(TextRange(at(1, 1), at(7, 2)), main.highlightRange)
        main.navigate(fixture.editor, false)
        assertEquals(at(2, 4), fixture.editor.caretModel.offset)
        assertTrue(!fixture.editor.selectionModel.hasSelection())
        main.navigate(fixture.editor, true)
        assertEquals(at(1, 1), fixture.editor.selectionModel.selectionStart)
        assertEquals(at(7, 2), fixture.editor.selectionModel.selectionEnd)
    }

    @Test
    fun `the structure view lists the outline, follows the caret, and goes to a symbol's name`() = onEdt {
        val provider = CodeBridgeStructureViewBuilderProvider()
        open(SOURCE)
        assertNull(
            provider.getStructureViewBuilder(file().fileType, file(), fixture.project),
            "Nothing outlines the file yet"
        )
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        val builder =
            provider.getStructureViewBuilder(file().fileType, file(), fixture.project) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(fixture.editor)
        try {
            fun names(element: TreeElement): List<Any> = element.children.map {
                val children = names(it)
                if (children.isEmpty()) it.presentation.presentableText!! else it.presentation.presentableText!! to children
            }
            assertEquals(listOf("main" to listOf("x", "inner"), "one"), names(model.root))
            val main = model.root.children.first() as StructureViewTreeElement
            assertEquals(AllIcons.Nodes.Function, main.presentation.getIcon(false))

            fixture.editor.caretModel.moveToOffset(at(5, 9))
            val inner = (main.children[1] as StructureViewTreeElement).value
            assertEquals(inner, model.currentEditorElement)

            main.navigate(false)
            assertEquals(at(2, 4), fixture.editor.caretModel.offset)

            // The view rebuilds on every edit and keeps a node only while its element is equal.
            answer(null)
            type(0, "// x\n")
            val after = model.root.children.first() as StructureViewTreeElement
            assertEquals(
                model.root,
                builder.createStructureViewModel(fixture.editor).also { Disposer.dispose(it) }.root
            )
            assertEquals(main, after, "The same symbol, moved by the edit")
            assertEquals(main.hashCode(), after.hashCode())
            assertEquals(main.children.toList(), after.children.toList())
        } finally {
            Disposer.dispose(model)
        }
    }

    @Test
    fun `an outline arriving after the editor opens makes the structure view available without rebuilding on edits`() =
        onEdt {
            open(SOURCE)
            val provider = CodeBridgeStructureViewBuilderProvider()
            var refreshes = 0
            var model: StructureViewModel? = null
            // Simulate the tool window's initial unavailable result and re-query on a platform refresh.
            val factory = object : StructureViewFactory() {
                override fun refreshStructureView() {
                    refreshes++
                    model?.let(Disposer::dispose)
                    model = (provider.getStructureViewBuilder(
                        file().fileType,
                        file(),
                        fixture.project
                    ) as? TreeBasedStructureViewBuilder)
                        ?.createStructureViewModel(fixture.editor)
                }

                override fun createStructureView(
                    editor: FileEditor?,
                    model: StructureViewModel,
                    project: Project
                ): StructureView =
                    error("This test does not create a tool window")

                override fun createStructureView(
                    editor: FileEditor?,
                    model: StructureViewModel,
                    project: Project,
                    showRoot: Boolean
                ): StructureView =
                    error("This test does not create a tool window")
            }
            fixture.project.registerOrReplaceServiceInstance(
                StructureViewFactory::class.java,
                factory,
                fixture.testRootDisposable
            )
            assertNull(provider.getStructureViewBuilder(file().fileType, file(), fixture.project))

            try {
                // The server can initially answer with nothing while it loads the project.
                answer("", SYMBOLS)
                mirror()
                register()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (asked.size < 2 && System.nanoTime() < deadline) Thread.sleep(5)
                settle()
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                assertEquals(1, refreshes, "The first answer must make the tool window re-query the builder")
                val loaded = assertNotNull(model)
                assertEquals(listOf("main", "one"), loaded.root.children.map { it.presentation.presentableText })

                var updates = 0
                loaded.addModelListener { updates++ }
                answer(SYMBOLS.replace("\"inner\"", "\"outer\""))
                type(fixture.editor.document.textLength, " ")
                // The view rebuilds its tree on the edit, which asks for the outline of the new text.
                loaded.root.children
                settle()
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                assertEquals(1, refreshes, "Editing updates the existing model without recreating the view")
                assertEquals(1, updates)
                assertEquals("outer", loaded.root.children.first().children[1].presentation.presentableText)

                features().handle("MainThreadLanguageFeatures", $$"$unregister", "[$HANDLE]")
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                assertEquals(2, refreshes, "Removing the provider re-checks structure availability")
                assertNull(model)
            } finally {
                model?.let(Disposer::dispose)
            }
        }

    @Test
    fun `the structure model stays valid while typing and expires when its file is deleted`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        val model = CodeBridgeStructureViewModel(fixture.project, file(), fixture.editor)
        val withoutEditor = CodeBridgeStructureViewModel(fixture.project, file(), null)
        try {
            // The tool window checks this before reusing the view, independently of node equality.
            assertTrue(model.isValid, "An extension outline has no PSI file but is still valid")
            assertTrue(withoutEditor.isValid)
            answer(null)
            type(0, "// x\n")
            assertTrue(model.isValid, "Typing must not make the platform recreate the whole view")
            assertTrue(withoutEditor.isValid)
            assertEquals(2, model.root.children.size, "Keep the outline while the provider is answering")

            WriteCommandAction.runWriteCommandAction(fixture.project) { file().delete(this) }
            assertFalse(model.isValid)
            assertFalse(withoutEditor.isValid)
        } finally {
            Disposer.dispose(model)
            Disposer.dispose(withoutEditor)
        }
    }

    @Test
    fun `the bar is told when the document changes and when a new outline arrives, and shown only while breadcrumbs are`() =
        onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()

        // The first outline's own notice, still queued, is not what is being counted.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        var told = 0
        collector().watchForChanges(file(), fixture.editor, fixture.testRootDisposable) { told++ }
        answer(SYMBOLS.replace("\"inner\"", "\"outer\""))
        type(fixture.editor.document.textLength, " ")
            assertEquals(1, told, "The edit, for the bar to ask about the new text")
            // Asked for by the sticky lines pass, say: the bar still has to show it.
            inBackground { collect() }
        settle()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(2, told, "The new outline")
        assertEquals(listOf("main", "outer"), crumbs(at(5, 9)))

        val settings = EditorSettingsExternalizable.getInstance()
        val shown = settings.isBreadcrumbsShown
        try {
            settings.isBreadcrumbsShown = true
            assertTrue(!collector().requiresProvider())
            settings.isBreadcrumbsShown = false
            assertTrue(collector().requiresProvider())
        } finally {
            settings.isBreadcrumbsShown = shown
        }
    }

    @Test
    fun `an answer of nothing on open is asked about once more`() = onEdt {
        open(SOURCE)
        answer("", SYMBOLS)
        mirror()
        register()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (asked.size < 2 && System.nanoTime() < deadline) Thread.sleep(5)
        settle()
        assertEquals(2, asked.size)
        assertEquals(setOf(scope(2, 4, 7, 2), scope(4, 5, 6, 6)), collect())
    }

    @Test
    fun `a provider that goes away takes its scopes with it, and one for another language is not asked`() = onEdt {
        open(SOURCE)
        answer(SYMBOLS)
        mirror()
        register()
        settle()
        assertEquals(2, collect().size)

        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[$HANDLE]")
        assertEquals(emptySet(), collect())
        assertTrue(
            FileBreadcrumbsCollector.findBreadcrumbsCollector(
                fixture.project,
                file()
            ) !is CodeBridgeBreadcrumbsCollector
        )

        asked.clear()
        register(language = "other")
        settle()
        assertEquals(emptySet(), collect())
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
            "MainThreadLanguageFeatures",
            $$"$registerDocumentSymbolProvider",
            """[$HANDLE,[{"language":"$language"}],"Test"]""",
        )
    }

    /** Types [text] at [offset], and mirrors it as the editor's listener would. */
    private fun type(offset: Int, text: String) {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(offset, text) }
        features().documentChanged(file(), HostedLanguageFeatures.Snapshot(document.text, true))
    }

    /** Replaces [start] to [end] with [text], and mirrors it as the editor's listener would. */
    @Suppress("SameParameterValue")
    private fun replace(start: Int, end: Int, text: String) {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.replaceString(start, end, text) }
        features().documentChanged(file(), HostedLanguageFeatures.Snapshot(document.text, true))
    }

    /** What the provider replies with, one reply per request, the last repeated; null never answers. */
    private fun answer(vararg replies: String?) {
        asked.clear()
        val queue =
            ConcurrentLinkedQueue(replies.dropLast(1).map { checkNotNull(it) { "Only the last reply can be null" } })
        val last = replies.last()
        features().callHostForTest { _, method, args, _ ->
            if (method != $$"$provideDocumentSymbols") return@callHostForTest ""
            asked += args
            val reply = queue.poll() ?: last
            reply ?: awaitCancellation()
        }
    }

    private fun settle() {
        assertTrue(
            features().awaitDocumentSymbolsForTest(10, TimeUnit.SECONDS),
            "The document symbol request never settled"
        )
    }

    /**
     * Lets a redraw an answer started reach the daemon: the sticky lines are marked off the EDT
     * first, so the restart is not queued yet when the answer has been kept.
     */
    private fun settleRedraws() {
        Thread.sleep(100)
        features().awaitRepaintsForTest()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** [compute] in a read action off the EDT, where the bar and the sticky lines pass read the outline. */
    private fun <T> inBackground(compute: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> {
            ReadAction.computeBlocking<T, RuntimeException> { compute() }
        }.get(10, TimeUnit.SECONDS)

    private fun collector() = CodeBridgeBreadcrumbsCollector(fixture.project)

    /** The 1-based [line]:[column] of the editor's document as an offset. */
    private fun at(line: Int, column: Int): Int = fixture.editor.document.getLineStartOffset(line - 1) + column - 1

    /** What the bar shows with the caret at [offset]. */
    private fun crumbs(offset: Int): List<String> =
        collector().computeCrumbs(file(), fixture.editor.document, offset, null).map { it.text }

    /** What the sticky lines pass collects: the collector asked at the end of every line. */
    private fun collect(): Set<StickyLineInfo> {
        val document = fixture.editor.document
        val collector = collector()
        return (0 until document.lineCount).flatMap {
            collector.computeStickyLineInfos(file(), document, document.getLineEndOffset(it))
        }.toSet()
    }

    /** A sticky line from 1-based [line]:[column] to [endLine]:[endColumn] of the editor's document. */
    private fun scope(line: Int, column: Int, endLine: Int, endColumn: Int): StickyLineInfo {
        val document = fixture.editor.document
        return StickyLineInfo(
            document.getLineStartOffset(line - 1) + column - 1,
            document.getLineStartOffset(endLine - 1) + endColumn - 1,
            null,
        )
    }

    private val asked = CopyOnWriteArrayList<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-outline") }

    private companion object {
        const val SOURCE = "/// doc\nfn main() {\n    let x = 1;\n    if x {\n        y();\n    }\n}\nfn one() {}\n"

        const val HANDLE = 31

        private fun range(line: Int, column: Int, endLine: Int, endColumn: Int) =
            """{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$endLine,"endColumn":$endColumn}"""

        /**
         * `main`, whose range starts at its doc comment, holding a single-line `x` and a block that
         * spans lines; and `one`, all on one line. The host sends the tree already nested.
         */
        val SYMBOLS = """[
            {"name":"main","kind":11,"range":${range(1, 1, 7, 2)},"selectionRange":${range(2, 4, 2, 8)},"children":[
                {"name":"x","kind":12,"range":${range(3, 5, 3, 15)},"selectionRange":${
            range(
                3,
                9,
                3,
                10
            )
        },"children":[]},
                {"name":"inner","kind":2,"range":${range(4, 5, 6, 6)},"selectionRange":${
            range(
                4,
                5,
                4,
                9
            )
        },"children":[]}
            ]},
            {"name":"one","kind":11,"range":${range(8, 1, 8, 12)},"selectionRange":${range(8, 4, 8, 7)},"children":[]}
        ]"""
    }
}
