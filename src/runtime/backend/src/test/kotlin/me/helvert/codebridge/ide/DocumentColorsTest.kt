package me.helvert.codebridge.ide

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeDocumentColors
import me.helvert.codebridge.language.CodeBridgeEditorDecorations
import me.helvert.codebridge.rpc.DecorationsDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentColorsTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val decorations get() = CodeBridgeEditorDecorations.getInstance(fixture.project)

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        features.hostStopped()
        decorations.hostStopped()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    @Test
    fun `each colour gets a swatch before it, in its colour, and loses it with its provider`() = onEdt {
        open()
        val received = CopyOnWriteArrayList<DecorationsDto>()
        val frontend = CoroutineScope(Dispatchers.Default).launch { decorations.published().collect { received += it } }
        try {
            features.handle(
                "MainThreadLanguageFeatures",
                $$"$registerDocumentColorProvider",
                """[9,[{"language":"test"}]]"""
            )
            awaitOnEdt { received.any { it.key == KEY } }

            val swatch = received.last { it.key == KEY }.ranges.single()
            assertEquals(2 to 9, swatch.start to swatch.end)
            val before = requireNotNull(swatch.before)
            assertEquals(" ", before.text)
            assertEquals("0.8em", before.width)
            assertEquals(Color(255, 0, 0, 127).rgb, before.background)

            features.handle("MainThreadLanguageFeatures", $$"$unregister", "[9]")
            awaitOnEdt { received.last().let { it.key == KEY && it.ranges.isEmpty() } }
        } finally {
            frontend.cancel()
        }
    }

    @Test
    fun `an answer about text that has changed since is not drawn`() = onEdt {
        open()
        val received = CopyOnWriteArrayList<DecorationsDto>()
        val frontend = CoroutineScope(Dispatchers.Default).launch { decorations.published().collect { received += it } }
        try {
            val late = CompletableFuture<String>()
            features.callHostForTest { _, _, _, _ -> late.await() }
            features.handle(
                "MainThreadLanguageFeatures",
                $$"$registerDocumentColorProvider",
                """[9,[{"language":"test"}]]"""
            )
            val asked = CodeBridgeDocumentColors.getInstance(fixture.project).request(fixture.file.virtualFile, 0)
            features.documentChanged(fixture.file.virtualFile, HostedLanguageFeatures.Snapshot("changed\n", true))
            late.complete(REPLY)
            awaitOnEdt { asked.isCompleted }

            assertTrue(received.none { it.key == KEY }, "$received")
        } finally {
            frontend.cancel()
        }
    }

    @Test
    fun `a picked colour is written as the extension writes colours`() = onEdt {
        open()
        val asked = CopyOnWriteArrayList<String>()
        features.callHostForTest { _, method, args, _ ->
            asked += "$method $args"
            
                if (method == $$"$provideColorPresentations") """[{"label":"#0000ff"},{"label":"blue"}]""" else REPLY
            
        }
        features.handle(
            "MainThreadLanguageFeatures",
            $$"$registerDocumentColorProvider",
            """[9,[{"language":"test"}]]"""
        )

        val picked = CoroutineScope(Dispatchers.Default).async {
            CodeBridgeDocumentColors.getInstance(fixture.project).pick(fixture.file.virtualFile, 2, Color(0, 0, 255))
        }
        awaitOnEdt { picked.isCompleted }

        assertTrue(picked.getCompleted())
        assertEquals("a #0000ff b\n", fixture.editor.document.text)
        val presented = asked.single { it.startsWith($$"$provideColorPresentations") }
        assertTrue(
            presented.contains(""""color":[0.0,0.0,1.0,1.0],"range":{"startLineNumber":1,"startColumn":3,"""),
            presented
        )
    }

    private fun open() {
        val path = Files.createTempFile("codebridge-colors", ".txt")
        Files.writeString(path, "a #ff0000 b\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            fixture.file.virtualFile,
            "test",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
        features.callHostForTest { _, method, _, _ ->
            assertEquals($$"$provideDocumentColors", method)
            REPLY
        }
    }

    /** Runs the editor thread's queue until [condition] holds, failing after a few seconds. */
    private fun awaitOnEdt(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "Timed out")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private companion object {
        const val KEY = "codebridge.documentColors"

        /** `#ff0000` at half opacity, on columns 3 to 10. */
        const val REPLY =
            """[{"color":[1,0,0,0.5],"range":{"startLineNumber":1,"startColumn":3,"endLineNumber":1,"endColumn":10}}]"""
    }
}
