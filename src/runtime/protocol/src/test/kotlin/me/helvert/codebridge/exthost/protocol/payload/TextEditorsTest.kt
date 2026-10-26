package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `MainThreadTextEditors` calls as the 1.143 host sends them. */
class TextEditorsTest {

    private val at = """{"startLineNumber":2,"startColumn":5,"endLineNumber":2,"endColumn":5}"""

    @Test
    fun `a snippet is one edit per range, whitespace adjusted unless the options say to keep it`() {
        val edits =
            TextEditors.snippet($$"""["7",3,"f($1)",[$$at,$$at],{"undoStopBefore":true,"undoStopAfter":true,"keepWhitespace":false}]""")

        assertEquals("7", edits.editorId)
        assertEquals(3, edits.versionId)
        assertEquals(List(2) { TextEdit(Range(2, 5, 2, 5), $$"f($1)", snippet = true) }, edits.edits)
        val kept = TextEditors.snippet("""["7",3,"x",[$at],{"keepWhitespace":true}]""")
        assertTrue(kept.edits.single().keepWhitespace)
    }
}
