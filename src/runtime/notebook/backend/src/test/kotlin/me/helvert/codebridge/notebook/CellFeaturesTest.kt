package me.helvert.codebridge.notebook

import me.helvert.codebridge.exthost.protocol.payload.CompletionItem
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.exthost.protocol.payload.Range
import kotlin.test.Test
import kotlin.test.assertEquals

class CellFeaturesTest {
    private fun item(label: String, insertText: String = label, sortText: String? = null, snippet: Boolean = false, replace: Range? = null) =
        CompletionItem(label, insertText, kind = 1, detail = null, documentation = null, filterText = null, sortText = sortText, snippet = snippet, replace = replace)

    @Test
    fun `ranks as VS Code does and places the caret where the snippet's first stop is`() {
        val items = CellFeatures.completions(listOf(
            item("zeta", sortText = "1"),
            item("alpha", sortText = "2"),
            item("call", insertText = $$"call(${1:x})$0", snippet = true, replace = Range(1, 3, 1, 5)),
        ))
        // By sortText, else the label, ignoring case: "1", "2", "call".
        assertEquals(listOf("zeta", "alpha", "call"), items.map { it.label })
        val call = items.last()
        assertEquals("call(x)", call.text)
        assertEquals("call(".length, call.caret)
        assertEquals(1 to 3, call.replaceLine to call.replaceColumn)
        val plain = items.first()
        assertEquals("zeta".length, plain.caret)
        assertEquals(null, plain.replaceLine)
    }

    @Test
    fun `a problem keeps its range, severity and message`() {
        val problems = CellFeatures.problems(listOf(Marker(Range(1, 2, 1, 6), 8, "bad", "probe")))
        assertEquals(listOf(CellProblemDto(1, 2, 1, 6, CellProblemDto.ERROR, "bad", "probe")), problems)
    }
}
