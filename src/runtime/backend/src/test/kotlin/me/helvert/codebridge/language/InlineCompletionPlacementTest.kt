package me.helvert.codebridge.language

import com.intellij.openapi.util.TextRange
import me.helvert.codebridge.language.InlineCompletionPlacement.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where a VS Code inline completion goes as ghost text, and the ones ghost text cannot show. */
class InlineCompletionPlacementTest {

    private fun drawn(text: String) = Piece(text, kept = false)

    @Suppress("SameParameterValue") // The pair of drawn(), so the expected pieces read alike.
    private fun kept(text: String) = Piece(text, kept = true)

    @Test
    fun `text inserted at the caret is drawn`() {
        val text = "def f():\n    ret"
        assertEquals(listOf(drawn("urn 1")), InlineCompletionPlacement.place(text, text.length, null, "return 1"))
    }

    @Test
    @Suppress("KotlinMisorderedAssertEqualsArguments") // Already expected, actual; the literal "ret" misleads it.
    fun `the default range starts with the word the caret ends`() {
        // `ret` is replaced by `return 1`, so only what follows it is drawn.
        assertEquals(listOf(drawn("urn 1")), InlineCompletionPlacement.place("ret", 3, null, "return 1"))
    }

    @Test
    fun `a range starting earlier must be started by what is typed there`() {
        val text = "x = foo"
        assertEquals(listOf(drawn("(1)")), InlineCompletionPlacement.place(text, 7, TextRange(4, 7), "foo(1)"))
        assertNull(InlineCompletionPlacement.place(text, 7, TextRange(4, 7), "bar(1)"))
    }

    @Test
    fun `text after the caret the proposal keeps is typed over`() {
        // The editor closed the bracket; the proposal ends with it.
        val text = "print()"
        assertEquals(
            listOf(drawn("\"hi\""), kept(")")),
            InlineCompletionPlacement.place(text, 6, TextRange(0, 7), "print(\"hi\")"),
        )
    }

    @Test
    fun `kept text matches the last of the proposal's characters`() {
        assertEquals(
            listOf(drawn("f(x)"), kept(")")),
            InlineCompletionPlacement.place("g()", 2, TextRange(0, 3), "g(f(x))"),
        )
    }

    @Test
    fun `text after the caret the proposal drops cannot be shown`() {
        assertNull(InlineCompletionPlacement.place("print(]", 6, TextRange(0, 7), "print(1)"))
    }

    @Test
    fun `a range that leaves the caret out cannot be shown`() {
        assertNull(InlineCompletionPlacement.place("abc def", 2, TextRange(4, 7), "xyz"))
    }

    @Test
    fun `a proposal that adds nothing shows nothing`() {
        assertNull(InlineCompletionPlacement.place("done", 4, null, "done"))
        assertNull(InlineCompletionPlacement.place("f()", 2, TextRange(0, 3), "f()"))
    }
}
