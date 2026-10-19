package me.helvert.codebridge.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.*
import me.helvert.codebridge.rpc.DecorationRangeDto
import me.helvert.codebridge.rpc.DecorationTextDto
import me.helvert.codebridge.rpc.DecorationsDto
import me.helvert.codebridge.started
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `setDecorations`, drawn: highlighters on the document, text as inlays, hovers in Quick Documentation.
 *
 * GitLens' current-line blame is the shape: a whole-line type, a hover per range, and the blame
 * itself as `after` text the range carries in its own render options.
 */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class EditorDecorationsTest : EditorFixture() {

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)
    private val decorations get() = CodeBridgeEditorDecorations.getInstance(fixture.project)
    private val editors get() = CodeBridgeTextEditors.getInstance(fixture.project)

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        features.hostStopped()
        decorations.hostStopped()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    @Test
    fun `draws a type's ranges, their text and their hovers, and takes them off again`() = onEdt {
        val path = Files.createTempFile("codebridge-decorations", ".txt")
        Files.writeString(path, "let x = 1;\nlet y = 2;\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        val file = fixture.file.virtualFile
        val document = fixture.editor.document
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "plaintext", HostedLanguageFeatures.Snapshot(document.text, false))
        val editor = features.editorOf(file)

        call(
            TextEditorDecorations.REGISTER,
            """["ext","line",{"isWholeLine":true,"backgroundColor":"#ff000080","after":{"color":"#999"}}]"""
        )
        call(
            TextEditorDecorations.SET, """["$editor","line",[{
            "range":{"startLineNumber":2,"startColumn":11,"endLineNumber":2,"endColumn":11},
            "hoverMessage":{"value":"**blame**","isTrusted":false},
            "renderOptions":{"after":{"contentText":" you, now"}}}]]"""
        )

        val drawn = ours(document)
        assertEquals(1, drawn.size)
        val line = drawn.single()
        assertEquals(HighlighterTargetArea.LINES_IN_RANGE, line.targetArea)
        assertEquals(Color(255, 0, 0, 128), line.getTextAttributes(null)?.backgroundColor)
        assertEquals(21, line.startOffset)
        val placed = decorations.textOn(file).single()
        assertEquals(21 to " you, now", placed.offset to placed.text)
        assertTrue(placed.after)

        // A whole-line decoration's hover answers anywhere on its line, and not on the line above.
        assertEquals(listOf("**blame**"), decorations.hoversAt(file, document, 13))
        assertEquals(emptyList(), decorations.hoversAt(file, document, 3))
        // With no hover provider at all, the decoration's hover is still offered.
        assertEquals(1, CodeBridgeHoverTargetProvider().documentationTargets(fixture.file, 13).size)
        assertEquals(0, CodeBridgeHoverTargetProvider().documentationTargets(fixture.file, 3).size)

        // A whole-line range's text goes at the ends of its lines, wherever the range itself starts:
        // Error Lens puts its message on a range at the diagnostic, here `y`, and expects it after the line.
        call(
            TextEditorDecorations.SET, """["$editor","line",[{
            "range":{"startLineNumber":2,"startColumn":5,"endLineNumber":2,"endColumn":5},
            "renderOptions":{"before":{"contentText":"<"},"after":{"contentText":"unused variable"}}}]]"""
        )
        assertEquals(listOf(11 to "<", 21 to "unused variable"), decorations.textOn(file).map { it.offset to it.text })

        // A second call replaces the first: VS Code's setDecorations is not additive.
        call(TextEditorDecorations.SET_FAST, """["$editor","line",[1,1,1,4]]""")
        assertEquals(listOf(0), ours(document).map { it.startOffset })
        assertTrue(decorations.textOn(file).isEmpty())

        call(TextEditorDecorations.REMOVE, """["line"]""")
        assertTrue(ours(document).isEmpty())
        // Its decorations went with the type, and new ones under a key never registered are not drawn.
        call(TextEditorDecorations.SET_FAST, """["$editor","line",[1,1,1,4]]""")
        assertTrue(ours(document).isEmpty())
    }

    @Test
    fun `decorations go with the document's editor`() = onEdt {
        val path = Files.createTempFile("codebridge-decorations", ".txt")
        Files.writeString(path, "abc\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        val file = fixture.file.virtualFile
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "plaintext", HostedLanguageFeatures.Snapshot("abc\n", false))
        call(TextEditorDecorations.REGISTER, """["ext","k",{"color":"red"}]""")
        call(TextEditorDecorations.SET_FAST, """["${features.editorOf(file)}","k",[1,1,1,3]]""")
        assertEquals(1, ours(fixture.editor.document).size)

        features.documentClosed(file)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(ours(fixture.editor.document).isEmpty())
    }

    @Test
    fun `a subscribed frontend takes the paint and text over, and hands them back when it goes`() = onEdt {
        val path = Files.createTempFile("codebridge-decorations", ".txt")
        Files.writeString(path, "let x = 1;\nlet y = 2;\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        val file = fixture.file.virtualFile
        val document = fixture.editor.document
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "plaintext", HostedLanguageFeatures.Snapshot(document.text, false))
        val editor = features.editorOf(file)
        call(TextEditorDecorations.REGISTER, """["ext","line",{"isWholeLine":true,"backgroundColor":"#ff000080"}]""")
        call(
            TextEditorDecorations.REGISTER,
            """["ext","text",{"after":{"contentText":"!","color":"#00ff00","backgroundColor":"#0000ff","margin":"0 0 0 4ch","width":"10ch"}}]""",
        )
        call(TextEditorDecorations.SET_FAST, """["$editor","line",[2,5,2,5]]""")
        call(TextEditorDecorations.SET_FAST, """["$editor","text",[1,1,1,4]]""")
        val red = Color(255, 0, 0, 128)
        assertEquals(
            red,
            ours(document).single { it.targetArea == HighlighterTargetArea.LINES_IN_RANGE }
                .getTextAttributes(null)?.backgroundColor
        )

        // The text moves before the frontend connects; it is told where the paint is now.
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "\n\n") }
        val received = CopyOnWriteArrayList<DecorationsDto>()
        val frontend = CoroutineScope(Dispatchers.Default).launch { decorations.published().collect { received += it } }
        try {
            awaitOnEdt { received.size == 2 && ours(document).all { it.getTextAttributes(null) == null } }
            val line = received.single { it.key == "line" }
            assertEquals(file.path, line.path)
            assertTrue(line.wholeLine)
            assertEquals(listOf(DecorationRangeDto(17, 17)), line.ranges)
            val look = requireNotNull(line.look)
            assertEquals(red.rgb, look.dark?.background)
            assertEquals(red.rgb, look.light?.background)
            // A type with only text goes too, its text with each range: the frontend draws it
            // with the paint, and no inlay is drawn here.
            val text = received.single { it.key == "text" }
            assertNull(text.look)
            // VS Code's default range behaviour grows at both edges, so the lines typed at its start joined it.
            // Its box goes as the extension wrote it, for the frontend to measure in its own font.
            assertEquals(
                listOf(
                    DecorationRangeDto(
                        0,
                        5,
                        after = DecorationTextDto("!", Color.GREEN.rgb, "10ch", Color.BLUE.rgb, "0 0 0 4ch")
                    )
                ),
                text.ranges,
            )
            assertNull(CodeBridgeDecorationInlaysProvider().createCollector(fixture.file, fixture.editor))

            // While it is connected, new ranges go to it unpainted here.
            call(TextEditorDecorations.SET_FAST, """["$editor","line",[1,1,1,1]]""")
            awaitOnEdt { received.size == 3 }
            assertEquals(listOf(DecorationRangeDto(0, 0)), received.last().ranges)
            assertNull(ours(document).single { it.targetArea == HighlighterTargetArea.LINES_IN_RANGE }
                .getTextAttributes(null))

            call(TextEditorDecorations.REMOVE, """["line"]""")
            awaitOnEdt { received.size == 4 }
            val removed = received.last()
            assertEquals("line" to emptyList(), removed.key to removed.ranges)
            assertNull(removed.look)
        } finally {
            frontend.cancel()
        }
        call(TextEditorDecorations.REGISTER, """["ext","line",{"isWholeLine":true,"backgroundColor":"#ff000080"}]""")
        call(TextEditorDecorations.SET_FAST, """["$editor","line",[1,1,1,1]]""")
        awaitOnEdt {
            ours(document).singleOrNull { it.targetArea == HighlighterTargetArea.LINES_IN_RANGE }
                ?.getTextAttributes(null)?.backgroundColor == red
        }
        // With no frontend, the text is an inlay here again.
        assertTrue(CodeBridgeDecorationInlaysProvider().createCollector(fixture.file, fixture.editor) != null)
    }

    @Test
    fun `a subscribed frontend draws the gutter icon, fetching its image by key`() = onEdt {
        val path = Files.createTempFile("codebridge-decorations", ".txt")
        Files.writeString(path, "let x = 1;\n")
        val svg = Files.createTempFile("codebridge-gutter", ".svg")
        Files.writeString(
            svg,
            """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><rect width="16" height="16"/></svg>"""
        )
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        val file = fixture.file.virtualFile
        val document = fixture.editor.document
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "plaintext", HostedLanguageFeatures.Snapshot(document.text, false))
        val editor = features.editorOf(file)
        val icon = UriComponents.of(svg).path
        call(
            TextEditorDecorations.REGISTER,
            $$"""["ext","mark",{"gutterIconPath":{"$mid":1,"scheme":"file","path":"$$icon"}}]"""
        )
        call(
            TextEditorDecorations.SET, """["$editor","mark",[{
            "range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":1},
            "hoverMessage":{"value":"bookmark","isTrusted":false}}]]"""
        )
        // Drawn here while no frontend is connected.
        assertTrue(ours(document).single().gutterIconRenderer != null)

        val received = CopyOnWriteArrayList<DecorationsDto>()
        val frontend = CoroutineScope(Dispatchers.Default).launch { decorations.published().collect { received += it } }
        try {
            awaitOnEdt { received.isNotEmpty() && ours(document).single().gutterIconRenderer == null }
            // An icon alone, no paint or text, is still something for the frontend to draw.
            val mark = received.single()
            assertNull(mark.look)
            assertEquals("bookmark", mark.ranges.single().gutterTooltip)
            val key = requireNotNull(mark.gutterIcon)
            assertTrue(Files.readAllBytes(svg).contentEquals(decorations.gutterIconBytes(key)))
            assertNull(decorations.gutterIconBytes("not-a-key"))
        } finally {
            frontend.cancel()
        }
        awaitOnEdt { ours(document).single().gutterIconRenderer != null }
    }

    @Test
    fun `a call repeating the last one keeps the decorations where the text moved them`() = onEdt {
        val path = Files.createTempFile("codebridge-decorations", ".txt")
        Files.writeString(path, "let x = 1;\nlet y = 2;\n")
        fixture.openFileInEditor(requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)))
        val file = fixture.file.virtualFile
        val document = fixture.editor.document
        features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(file, "plaintext", HostedLanguageFeatures.Snapshot(document.text, false))
        val editor = features.editorOf(file)
        call(TextEditorDecorations.REGISTER, """["ext","k",{"color":"red","rangeBehavior":1}]""")
        fun drawn() = ours(document).single().let {
            document.getText(
                com.intellij.openapi.util.TextRange(
                    it.startOffset,
                    it.endOffset
                )
            ) to it.startOffset
        }

        // `x`, then two lines typed above it.
        call(TextEditorDecorations.SET_FAST, """["$editor","k",[1,5,1,6]]""")
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "\n\n") }
        assertEquals("x" to 6, drawn())

        // The extension says the same again, from what it knew before the typing: Error Lens's
        // shape. What was drawn stays on `x`, rather than going to line 1, which is empty now.
        call(TextEditorDecorations.SET_FAST, """["$editor","k",[1,5,1,6]]""")
        assertEquals("x" to 6, drawn())

        // Something new is drawn at once: `y`, on the fourth line.
        call(TextEditorDecorations.SET_FAST, """["$editor","k",[4,5,4,6]]""")
        assertEquals("y" to 17, drawn())
    }

    @Test
    fun `reads what CSS an editor can draw`() {
        val red = TextEditorDecorations.Color.Css("red")
        fun attributes(style: TextEditorDecorations.Style) = DecorationStyles.attributes(style, style)

        val boxed = attributes(TextEditorDecorations.Style(border = "1px solid red"))
        assertEquals(EffectType.BOXED to Color.RED, boxed?.effectType to boxed?.effectColor)
        val rounded =
            attributes(TextEditorDecorations.Style(border = "1px solid", borderColor = red, borderRadius = "3px"))
        assertEquals(EffectType.ROUNDED_BOX, rounded?.effectType)
        val wavy = attributes(TextEditorDecorations.Style(textDecoration = "underline wavy #00ff00; opacity: 0.5"))
        assertEquals(EffectType.WAVE_UNDERSCORE to Color.GREEN, wavy?.effectType to wavy?.effectColor)
        val struck = attributes(TextEditorDecorations.Style(textDecoration = "line-through", color = red))
        assertEquals(EffectType.STRIKEOUT to Color.RED, struck?.effectType to struck?.effectColor)
        assertEquals(
            Font.BOLD or Font.ITALIC,
            attributes(TextEditorDecorations.Style(fontWeight = "700", fontStyle = "italic"))?.fontType
        )
        assertEquals(
            Color.BLUE,
            attributes(TextEditorDecorations.Style(overviewRulerColor = TextEditorDecorations.Color.Css("blue")))?.errorStripeColor
        )
        // Nothing an editor draws on the text: no attributes at all, so the highlighter paints nothing.
        assertNull(attributes(TextEditorDecorations.Style(border = "none", textDecoration = "none")))
        assertNull(attributes(TextEditorDecorations.Style()))

        val themed = DecorationStyles.attributes(
            TextEditorDecorations.Style(color = TextEditorDecorations.Color.Css("#000")),
            TextEditorDecorations.Style(color = TextEditorDecorations.Color.Css("#fff")),
        )?.foregroundColor
        assertTrue(themed is com.intellij.ui.JBColor, "Light and dark differ, so the colour follows the theme")
    }

    @Test
    fun `reads CSS colours`() {
        assertEquals(Color(255, 0, 0, 128), CssColors.parse("#ff000080"))
        assertEquals(Color(10, 20, 30, 127), CssColors.parse("rgba(10, 20, 30, 0.5)"))
        assertEquals(Color(10, 20, 30), CssColors.parse("rgb(10 20 30)"))
        assertEquals(Color.RED, CssColors.parse("Red"))
        assertNull(CssColors.parse("transparent"))
        assertNull(CssColors.parse("no-such-colour"))
        assertNull(CssColors.parse("var(--x)"))
    }

    private fun call(method: String, args: String) {
        val answer = started { editors.handle(method, args) }
        assertTrue(answer.isDone)
        assertNull(answer.get().json)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
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

    /** The highlighters decorations put on [document]. */
    private fun ours(document: com.intellij.openapi.editor.Document): List<RangeHighlighter> =
        DocumentMarkupModel.forDocument(document, fixture.project, true).allHighlighters
            .filter { it.isValid && it.layer == com.intellij.openapi.editor.markup.HighlighterLayer.ADDITIONAL_SYNTAX + 1 }
}
