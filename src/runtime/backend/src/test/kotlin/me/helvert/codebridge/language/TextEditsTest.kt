package me.helvert.codebridge.language

import com.intellij.openapi.util.TextRange
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** VS Code's 1-based edits as offsets, and the refusals that keep a stale edit out of the file. */
class TextEditsTest {

    private fun edit(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, text: String) =
        TextEdit(Range(startLine, startColumn, endLine, endColumn), text)

    @Test
    fun `applies edits against the original text whatever order they come in`() {
        val text = "one two\nthree\n"
        val edits = listOf(edit(2, 1, 2, 6, "3"), edit(1, 5, 1, 8, "2"), edit(1, 1, 1, 4, "1"))
        assertEquals("1 2\n3\n", CodeBridgeTextEdits.apply(text, edits))
    }

    @Test
    fun `insertions at one point keep the order they were given in`() {
        assertEquals("abX", CodeBridgeTextEdits.apply("X", listOf(edit(1, 1, 1, 1, "a"), edit(1, 1, 1, 1, "b"))))
    }

    @Test
    fun `an insertion where a replacement starts goes before it, whichever was given first`() {
        // CodeLLDB's "Cargo ..." edit to launch.json: a line replaced, and text inserted at its start.
        val text = "{\n  ]\n}"
        val replaced = edit(2, 1, 2, 4, "  ]")
        val inserted = edit(2, 1, 2, 1, "    {}\n")
        assertEquals("{\n    {}\n  ]\n}", CodeBridgeTextEdits.apply(text, listOf(replaced, inserted)))
        assertEquals("{\n    {}\n  ]\n}", CodeBridgeTextEdits.apply(text, listOf(inserted, replaced)))
    }

    @Test
    fun `normalizes line breaks and flattens snippets`() {
        assertEquals("a\nb\n", CodeBridgeTextEdits.apply("", listOf(edit(1, 1, 1, 1, "a\r\nb\r\n"))))
        assertEquals(
            "fn main",
            CodeBridgeTextEdits.apply("", listOf(TextEdit(Range(1, 1, 1, 1), $$"${1:fn} main$0", snippet = true)))
        )
    }

    @Test
    fun `snippets going into an editor keep their expansion and are indented to their line`() {
        val indentation = Snippets.Indentation(4, spaces = true)
        val located = assertNotNull(
            CodeBridgeTextEdits.locate(
                "  ab\n", listOf(
                    edit(1, 1, 1, 1, "x"),
                    TextEdit(Range(1, 3, 1, 3), $$"{\n${1:v}\n}", snippet = true),
                    TextEdit(Range(1, 4, 1, 4), "a\n b$0", snippet = true, keepWhitespace = true),
                ), indentation
            )
        )

        assertEquals(listOf("a\n b", "{\n  v\n  }", "x"), located.map { it.text })
        // Where each snippet starts once all of them are in, the text before it having grown.
        assertEquals(listOf(3, 13), CodeBridgeTextEdits.placed(located).map { it.first })
    }

    @Test
    fun `refuses edits that do not fit or overlap`() {
        assertNull(CodeBridgeTextEdits.apply("abc", listOf(edit(2, 1, 2, 1, "x"))))
        assertNull(CodeBridgeTextEdits.apply("abc", listOf(edit(1, 1, 1, 9, "x"))))
        assertNull(CodeBridgeTextEdits.apply("abc\n", listOf(edit(1, 1, 1, 3, "x"), edit(1, 2, 1, 4, "y"))))
        assertNull(CodeBridgeTextEdits.apply("abc", listOf(edit(1, 3, 1, 2, "x"))))
        // The position just past the end of a line, and the empty last line, both exist.
        assertEquals("abc!\n?", CodeBridgeTextEdits.apply("abc\n", listOf(edit(1, 4, 1, 4, "!"), edit(2, 1, 2, 1, "?"))))
    }

    @Test
    fun `converts between offsets and ranges`() {
        val text = "ab\ncd"
        assertEquals(Range(1, 1, 2, 3), CodeBridgeTextEdits.whole(text))
        assertEquals(Range(1, 2, 2, 2), CodeBridgeTextEdits.rangeOf(text, TextRange(1, 4)))
        @Suppress("KotlinMisorderedAssertEqualsArguments") // the expected range comes first
        assertEquals(TextRange(1, 4), CodeBridgeTextEdits.offsets(text, Range(1, 2, 2, 2)))
        assertEquals(Range(2, 1, 2, 1), CodeBridgeTextEdits.rangeOf(text, TextRange(3, 3)))
    }
}
