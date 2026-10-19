package me.helvert.codebridge.ide

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.future.await
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeInlayHintClickHandler
import me.helvert.codebridge.language.CodeBridgeInlayHintsProvider
import me.helvert.codebridge.language.CodeBridgeInlayHintsProviderFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Inlay hints, from the reply to what the inlay pass is handed.
 *
 * The pass itself belongs to the platform; what can go wrong here is everything before it: asking
 * when the document opens, placing 1-based positions on a real document, keeping hints on their
 * words while the next answer is out, and letting the host drop the lists it was asked to keep.
 */
class InlayHintsTest : EditorFixture() {

    @BeforeEach
    fun reset() = onEdt {
        features().hostStopped()
        // The fixture does not load the plugin's descriptor; the test below checks the registration.
        ExtensionPointName<InlayHintsProviderFactory>("com.intellij.codeInsight.declarativeInlayProviderFactory").point
            .registerExtension(CodeBridgeInlayHintsProviderFactory(), fixture.testRootDisposable)
    }

    @Test
    fun `the factory is registered`() {
        // Whitespace collapsed, so that a reformatted descriptor -- an element wrapped over two lines -- still matches.
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
                .replace(Regex("\\s+"), " ")
        assertTrue(
            descriptor.contains(
                """<codeInsight.declarativeInlayProviderFactory implementation="me.helvert.codebridge.language.CodeBridgeInlayHintsProviderFactory"/>"""
            )
        )
        assertTrue(descriptor.contains("""<codeInsight.inlayActionHandler handlerId="${CodeBridgeInlayHintClickHandler.ID}""""))
        assertTrue(descriptor.contains("""implementationClass="me.helvert.codebridge.language.CodeBridgeInlayHintClickHandler"/>"""))
    }

    @Test
    fun `a hint lands where the host put it, and its list is released`() = onEdt {
        open(SOURCE)
        answer(HINTS)
        mirror()
        register()
        settle()

        val drawn = collect()
        assertEquals(listOf(9 to ": i32", 20 to "n:"), drawn.map { it.offset to it.text })
        // A type follows what it describes; a parameter name goes in front of its argument.
        assertEquals(listOf(true, false), drawn.map { it.relatedToPrevious })
        assertEquals(listOf("types", "parameters"), drawn.map { it.option })
        assertEquals("the type", drawn[0].tooltip)
        assertEquals(listOf("[$HANDLE,$CACHE]"), released)
    }

    @Test
    fun `the inlay pass draws an answer that arrives after it has run`() = onEdt {
        open(SOURCE)
        answer("""{"cacheId":$CACHE,"hints":[]}""")
        mirror()
        register()
        settle()
        fixture.doHighlighting()
        assertEquals(emptyList(), inlays())

        // A provider changing its mind changes no PSI, which is all the pass otherwise looks at.
        answer(HINTS)
        features().handle("MainThreadLanguageFeatures", $$"$emitInlayHintsEvent", "[$HANDLE]")
        settle()
        features().awaitRepaintsForTest()
        UIUtil.dispatchAllInvocationEvents()
        fixture.doHighlighting()
        assertEquals(listOf(9, 20), inlays())
    }

    @Test
    fun `a change reported while a request is out is asked about once it is answered`() = onEdt {
        open(SOURCE)
        // The first answer is held back, as a language server still indexing holds it; every later
        // one has the hints.
        val first = CompletableFuture<String>()
        val asked = AtomicInteger()
        features().callHostForTest { _, method, _, _ ->
            when {
                method != $$"$provideInlayHints" -> ""
                asked.getAndIncrement() == 0 -> first.await()
                else -> HINTS
            }
        }
        mirror()
        register()
        assertTrue(features().awaitInlayDebouncesForTest())
        assertEquals(1, asked.get())

        // Ready now, so it says so -- while its first answer, about the same text, is still out.
        features().handle("MainThreadLanguageFeatures", $$"$emitInlayHintsEvent", "[$HANDLE]")
        assertTrue(features().awaitInlayDebouncesForTest())
        first.complete("""{"cacheId":$CACHE,"hints":[]}""")
        awaitAsked(asked, 2)
        settle()
        assertEquals(listOf(9, 20), collect().map { it.offset })
    }

    @Test
    fun `an answer of nothing is asked about once more`() = onEdt {
        open(SOURCE)
        // What the host sends for a request the server abandoned, as rust-analyzer does the one it
        // provoked itself while it finishes loading -- and for a file with no hints, which is why
        // it is asked about once, not until it has some.
        val asked = AtomicInteger()
        features().callHostForTest { _, method, _, _ ->
            when {
                method != $$"$provideInlayHints" -> ""
                asked.getAndIncrement() == 0 -> ""
                else -> HINTS
            }
        }
        mirror()
        register()
        awaitAsked(asked, 2)
        settle()

        assertEquals(listOf(9, 20), collect().map { it.offset })
    }

    @Test
    fun `a file with no hints is asked about once more, and no more than that`() = onEdt {
        open(SOURCE)
        val asked = AtomicInteger()
        features().callHostForTest { _, method, _, _ ->
            if (method == $$"$provideInlayHints") asked.incrementAndGet()
            ""
        }
        mirror()
        register()
        awaitAsked(asked, 2)
        settle()
        // Long enough for a third question to have gone out, were one coming.
        Thread.sleep(300)
        settle()

        assertEquals(2, asked.get())
    }

    @Test
    fun `hints move with the text until the next answer`() = onEdt {
        open(SOURCE)
        answer(HINTS)
        mirror()
        register()
        settle()
        val file = fixture.file.virtualFile

        assertEquals(listOf(11, 22), features().inlayHints(file, "  $SOURCE").map { it.offset })
        // Typed inside `alpha`: the type after it moves along; the parameter hint after it as well.
        assertEquals(listOf(10, 21), features().inlayHints(file, SOURCE.replace("alpha", "alXpha")).map { it.offset })
        // `compute(1)` deleted: the hint inside it has nothing left to describe.
        assertEquals(listOf(9), features().inlayHints(file, SOURCE.replace("compute(1)", "2")).map { it.offset })
    }

    @Test
    fun `Ctrl+clicking a part that leads somewhere opens it there`() = onEdt {
        val other = file("other.txt", "first\nsecond line\n")
        val uri = UriComponents.of(other.toNioPath()).toJson()
        open(SOURCE)
        answer(
            """{"cacheId":$CACHE,"hints":[{"position":{"lineNumber":1,"column":10},"kind":1,"label":[
            {"label":": "},
            {"label":"Thing","location":{"uri":$uri,"range":{"startLineNumber":2,"startColumn":3,"endLineNumber":2,"endColumn":8}}}
        ]}]}"""
        )
        mirror()
        register()
        settle()

        val drawn = collect().single()
        assertEquals(listOf(": ", "Thing"), drawn.nodes)
        assertNull(drawn.clicks[0], "Plain text does nothing when clicked")
        click(requireNotNull(drawn.clicks[1]))
        // Navigation is submitted, not done on the spot.
        PlatformTestUtil.waitWithEventsDispatching("The click never opened the other file", {
            val selected = FileEditorManager.getInstance(fixture.project).selectedTextEditor
            selected != null && FileDocumentManager.getInstance().getFile(selected.document) == other &&
                    selected.caretModel.offset == "first\n".length + 2
        }, 10)
        val editor = requireNotNull(FileEditorManager.getInstance(fixture.project).selectedTextEditor)
        assertEquals(other, FileDocumentManager.getInstance().getFile(editor.document))
        assertEquals("first\n".length + 2, editor.caretModel.offset)
        // No command was involved, so the list went back straight away.
        assertEquals(listOf("[$HANDLE,$CACHE]"), released)
    }

    @Test
    fun `a part with a command runs it, and its list is kept for as long as it is drawn`() = onEdt {
        open(SOURCE)
        answer(commanded(CACHE))
        mirror()
        register()
        settle()

        assertEquals(emptyList(), released, "The host deletes a command with its list")
        click(requireNotNull(collect().single().clicks.single()))
        assertEquals(listOf("""["__vsc$CACHE","a"]"""), executed)

        // A new answer replaces the hints on screen -- the host renews a command's id with each
        // list it converts it for -- and the list behind the old ones can go.
        answer(commanded(CACHE + 1))
        features().handle("MainThreadLanguageFeatures", $$"$emitInlayHintsEvent", "[$HANDLE]")
        settle()
        assertEquals(listOf("[$HANDLE,$CACHE]"), released)

        features().documentClosed(fixture.file.virtualFile)
        assertEquals(listOf("[$HANDLE,$CACHE]", "[$HANDLE,${CACHE + 1}]"), released)
    }

    private fun commanded(cache: Int) = """{"cacheId":$cache,"hints":[{"position":{"lineNumber":1,"column":10},
        "label":[{"label":"run","command":{"id":"__vsc$cache","title":"Run","arguments":["a"]}}]}]}"""

    @Test
    fun `a part a provider fills in later is resolved when clicked, and then leads there`() = onEdt {
        val other = file("other.txt", "first\nsecond line\n")
        val uri = UriComponents.of(other.toNioPath()).toJson()
        open(SOURCE)
        // As rust-analyzer answers: parts, but no places, until a hint is resolved.
        answer(
            """{"cacheId":$CACHE,"hints":[{"position":{"lineNumber":1,"column":10},"kind":1,"cacheId":[$CACHE,0],
                "label":[{"label":": "},{"label":"Thing"}]}]}""",
            resolved = """{"position":{"lineNumber":1,"column":10},"kind":1,"cacheId":[$CACHE,0],"label":[{"label":": "},
                {"label":"Thing","location":{"uri":$uri,"range":{"startLineNumber":2,"startColumn":3,"endLineNumber":2,"endColumn":8}}}]}""",
        )
        mirror()
        register(resolves = true)
        settle()

        assertEquals(emptyList(), released, "The host resolves a hint from its list, so it has to be kept")
        val drawn = collect().single()
        click(requireNotNull(drawn.clicks[1]) { "A part that may lead somewhere has to be clickable before it is resolved" })
        assertEquals(listOf("[$HANDLE,[$CACHE,0]]"), resolvedHints)
        awaitEditorOn(other)
        assertEquals(
            "first\n".length + 2,
            requireNotNull(FileEditorManager.getInstance(fixture.project).selectedTextEditor).caretModel.offset
        )
    }

    @Test
    fun `an answer that only renews the ids it is resolved by changes nothing on screen`() = onEdt {
        val hints = { cache: Int ->
            """{"cacheId":$cache,"hints":[{"position":{"lineNumber":1,"column":10},"kind":1,"cacheId":[$cache,0],
                "label":[{"label":": "},{"label":"Thing"}]}]}"""
        }
        open(SOURCE)
        answer(hints(CACHE))
        mirror()
        register(resolves = true)
        settle()
        features().awaitRepaintsForTest()
        UIUtil.dispatchAllInvocationEvents()
        val restarts = features().restartsForTest()
        assertTrue(restarts > 0, "The first answer is new, and is drawn")

        answer(hints(CACHE + 1))
        features().handle("MainThreadLanguageFeatures", $$"$emitInlayHintsEvent", "[$HANDLE]")
        settle()
        features().awaitRepaintsForTest()
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(restarts, features().restartsForTest(), "Nothing new to draw, so nothing is redrawn")
        // The drawn hints keep the list they resolve from; the new one is let go.
        assertEquals(listOf("[$HANDLE,${CACHE + 1}]"), released)
        assertTrue(requireNotNull(collect().single().clicks[1]).contains("\"list\":$CACHE"))
    }

    /** Waits for a click that is answered asynchronously to open [file]. */
    private fun awaitEditorOn(file: VirtualFile) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            val editor = FileEditorManager.getInstance(fixture.project).selectedTextEditor
            if (editor != null && FileDocumentManager.getInstance().getFile(editor.document) == file) return
            Thread.sleep(5)
        }
        throw AssertionError("The click never opened ${file.name}")
    }

    @Test
    fun `a long label is drawn whole`() = onEdt {
        val long = "impl Iterator<Item = (usize, String)>"
        open(SOURCE)
        answer("""{"cacheId":$CACHE,"hints":[{"label":": $long","position":{"lineNumber":1,"column":10},"kind":1}]}""")
        mirror()
        register()
        settle()

        val drawn = collect().single()
        assertEquals(": $long", drawn.text)
        assertTrue(drawn.nodes.all { it.length <= 30 }, "Every node must fit the platform's limit: ${drawn.nodes}")
    }

    @Test
    fun `a file with no provider for it has no collector`() = onEdt {
        open(SOURCE)
        answer(HINTS)
        assertNull(collector(), "Nothing is mirrored yet")
        mirror()
        assertNull(collector(), "No provider has registered")
        register(language = "other")
        settle()
        assertEquals(emptyList(), collect(), "The provider does not claim this file")
    }

    @Test
    fun `a provider that goes away takes its hints with it`() = onEdt {
        open(SOURCE)
        answer(HINTS)
        mirror()
        register()
        settle()
        assertEquals(2, collect().size)

        features().handle("MainThreadLanguageFeatures", $$"$unregister", "[$HANDLE]")
        assertNull(collector())
        assertEquals(emptyList(), features().inlayHints(fixture.file.virtualFile, SOURCE))
    }

    private fun features() = HostedLanguageFeatures.getInstance(fixture.project)

    /** A real file: the URI is built from `toNioPath`, which the fixture's own filesystem refuses. */
    @Suppress("SameParameterValue")
    private fun open(text: String) {
        fixture.openFileInEditor(file("example.txt", text))
    }

    private fun file(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun click(payload: String) =
        CodeBridgeInlayHintClickHandler().handle(fixture.editor, StringInlayActionPayload(payload))

    private fun mirror() {
        val features = features()
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false),
        )
    }

    private fun register(language: String = "test", resolves: Boolean = false) {
        features().handle(
            "MainThreadLanguageFeatures", $$"$registerInlayHintsProvider",
            """[$HANDLE,[{"language":"$language"}],$resolves,null,"probe"]""",
        )
    }

    /** What the provider replies with, and with when a hint is resolved; releases are recorded. */
    private fun answer(reply: String, resolved: String = "") {
        released.clear()
        executed.clear()
        resolvedHints.clear()
        features().callHostForTest { _, method, args, _ ->
            when (method) {
                $$"$provideInlayHints" -> reply
                $$"$resolveInlayHint" -> {
                    resolvedHints += args
                    resolved
                }

                $$"$releaseInlayHints" -> {
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

    /** Waits for the [count]th question, which a filed answer asks from another thread. */
    @Suppress("SameParameterValue")
    private fun awaitAsked(asked: AtomicInteger, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (asked.get() < count && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(count, asked.get(), "The provider was not asked again")
    }

    private fun settle() {
        assertTrue(features().awaitInlayHintsForTest(), "The inlay hint request never settled")
    }

    private fun collector() =
        CodeBridgeInlayHintsProvider().createCollector(fixture.file, fixture.editor) as OwnBypassCollector?

    /** What the collector hands the pass, in document order. */
    private fun collect(): List<Drawn> {
        val sink = RecordingSink()
        collector()?.collectHintsForFile(fixture.file, sink)
        return sink.drawn
    }

    /** The offsets of the inline inlays on the editor now. */
    private fun inlays(): List<Int> =
        fixture.editor.inlayModel.getInlineElementsInRange(0, fixture.editor.document.textLength).map { it.offset }

    /** [clicks] is each node's click payload, null for a node that does nothing when clicked. */
    private class Drawn(
        val offset: Int,
        val relatedToPrevious: Boolean,
        val option: String?,
        val tooltip: String?,
        val nodes: List<String>,
        val clicks: List<String?>,
    ) {
        val text: String get() = nodes.joinToString("")
    }

    private class RecordingSink : InlayTreeSink {
        val drawn = mutableListOf<Drawn>()
        private var option: String? = null

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            val inline = position as InlineInlayPosition
            val recorder = RecordingBuilder()
            recorder.builder()
            drawn += Drawn(inline.offset, inline.relatedToPrevious, option, tooltip, recorder.nodes, recorder.clicks)
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) {
            option = optionId
            block()
            option = null
        }
    }

    private class RecordingBuilder : PresentationTreeBuilder {
        val nodes = mutableListOf<String>()
        val clicks = mutableListOf<String?>()
        private var scope: InlayActionData? = null

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder()
        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) = throw UnsupportedOperationException()

        override fun text(text: String, actionData: InlayActionData?) {
            nodes += text
            val action = actionData ?: scope
            assertTrue(
                action == null || action.handlerId == CodeBridgeInlayHintClickHandler.ID,
                "Clicks must reach the bridge's handler"
            )
            clicks += (action?.payload as? StringInlayActionPayload)?.text
        }

        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) {
            scope = actionData
            builder()
            scope = null
        }
    }

    private val released = CopyOnWriteArrayList<String>()
    private val executed = CopyOnWriteArrayList<String>()
    private val resolvedHints = CopyOnWriteArrayList<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-inlay") }

    private companion object {
        //          0123456789012345678901
        const val SOURCE = "let alpha = compute(1)\n"

        const val HANDLE = 11
        const val CACHE = 4

        const val HINTS = """{"cacheId":$CACHE,"hints":[
            {"label":": i32","cacheId":[$CACHE,0],"position":{"lineNumber":1,"column":10},"kind":1,"tooltip":"the type"},
            {"label":[{"label":"n"},{"label":":"}],"cacheId":[$CACHE,1],"position":{"lineNumber":1,"column":21},"kind":2}
        ]}"""
    }
}
