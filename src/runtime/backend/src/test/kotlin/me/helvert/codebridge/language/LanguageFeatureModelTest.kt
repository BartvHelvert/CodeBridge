package me.helvert.codebridge.language

import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.manifest.LanguageContribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placing a pushed diagnostic in an IntelliJ document.
 *
 * Reading the diagnostic is the protocol module's problem and is tested there. What is left here is
 * the arithmetic of turning 1-based editor coordinates into an offset range, which is where a stale
 * push meets a document that has since been edited.
 */
class LanguageFeatureModelTest {

    @Test
    fun `marks the span vs code reported`() {
        val lines = DocumentLines("ERROR replace this word\nsecond line")
        val range = lines.rangeOf(problem(1, 1, 1, 6))
        assertEquals(0, range!!.startOffset)
        assertEquals(5, range.endOffset)
    }

    @Test
    fun `finds the right offset on a later line`() {
        val lines = DocumentLines("first\nsecond\nthird")
        assertEquals(6, lines.offsetOf(2, 1))
        assertEquals(13, lines.offsetOf(3, 1))
    }

    @Test
    fun `widens an empty range so it can be seen`() {
        // A zero-width annotation draws nothing; servers do report empty ranges.
        val range = DocumentLines("abc").rangeOf(problem(1, 2, 1, 2))
        assertEquals(1, range!!.startOffset)
        assertEquals(2, range.endOffset)
    }

    @Test
    fun `widens an empty range at the end of a line backwards, onto the code`() {
        // The shape rust-analyzer reports constantly: "expected `>`" points just past the last
        // character of a line rather than spanning anything. Widening forwards from there covers the
        // newline, and IntelliJ draws that as a wedge in the margin instead of a squiggle under the
        // code -- which reads as the error not being shown at all.
        val range = DocumentLines("routes: HashMap<String, BoxedCallback\nsecond").rangeOf(problem(1, 38, 1, 38))
        assertEquals(36, range!!.startOffset)
        assertEquals(37, range.endOffset, "The last character of the line, not the line break")
    }

    @Test
    fun `falls back to the line break on an empty line, which has nothing else`() {
        val range = DocumentLines("a\n\nb").rangeOf(problem(2, 1, 2, 1))
        assertEquals(2, range!!.startOffset)
        assertEquals(3, range.endOffset)
    }

    @Test
    fun `marks the last character for an empty range at the very end of the file`() {
        // "expected `}`" at end of file is an ordinary thing for a server to report, and dropping it
        // meant a real error the user could see in the problems list drew nothing in the editor.
        // There is nothing ahead of it to borrow, so it borrows the character behind.
        val range = DocumentLines("abc").rangeOf(problem(1, 4, 1, 4))
        assertEquals(2, range!!.startOffset)
        assertEquals(3, range.endOffset)
    }

    @Test
    fun `drops a diagnostic for a line an edit has removed`() {
        // Diagnostics are pushed asynchronously, so one can name a line that no longer exists.
        // Clamping collapses it to the end of the document, where there is nothing to show.
        assertNull(DocumentLines("only one line").rangeOf(problem(9, 40, 9, 80)))
    }

    @Test
    fun `clamps an end that outruns its line`() {
        // The common stale case: the line survived an edit but got shorter.
        val range = DocumentLines(TWO_LINES).rangeOf(problem(1, 1, 1, 99))
        assertEquals(0, range!!.startOffset)
        assertEquals(5, range.endOffset, "Clamped to the end of the line, not into the next one")
    }

    @Test
    fun `refuses a reversed range`() {
        assertNull(DocumentLines("abcdef").rangeOf(problem(1, 5, 1, 2)))
    }

    @Test
    fun `matches a language by extension and by filename`() {
        val language = LanguageContribution(id = "codebridgebundled", extensions = listOf(".codebridgebundled"), filenames = listOf("CodeBridgeFILE"))
        assertTrue(language.matches("example.codebridgebundled"))
        assertTrue(language.matches("EXAMPLE.CodeBridgeBUNDLED"), "Extensions are compared case-insensitively")
        assertTrue(language.matches("CodeBridgeFILE"))
        assertFalse(language.matches("example.codebridgeprobe"))
        assertFalse(language.matches("codebridgebundled"), "A bare extension without a dot is not a match")
    }

    private fun problem(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) =
        Marker(Range(startLine, startColumn, endLine, endColumn), Marker.ERROR, "boom", null)

    private companion object {
        const val TWO_LINES = "short\nsecond line"
    }
}
