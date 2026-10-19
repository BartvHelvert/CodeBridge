package me.helvert.codebridge.completion

import com.intellij.codeInsight.completion.FrontendFriendlyInsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import kotlinx.serialization.Serializable

/**
 * Puts a JSON array entry where the lookup inserted it, and walks [stops] of [text] -- a template's
 * placeholders, as offsets `start, end, start, end, ...` in visiting order -- with Tab, the first one
 * selected so typing replaces it; see [SnippetStops].
 *
 * Takes away the quotes of a string the entry was typed into, and adds a comma when another entry
 * follows. Frontend-friendly, so in split mode all of it happens on the client with the insertion: a
 * live template, which only the backend can start, would redraw the entry a round trip later.
 */
@Serializable
class CodeBridgeJsonEntryInsertHandler(private val text: String, private val stops: List<Int>) :
    FrontendFriendlyInsertHandler {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val chars = document.charsSequence
        var start = context.startOffset
        var end = context.tailOffset
        if (start > 0 && chars[start - 1] == '"') start--
        if (end < chars.length && chars[end] == '"') end++
        val next = chars.subSequence(end, chars.length).firstOrNull { !it.isWhitespace() }
        val separator = if (next != null && next != ']' && next != ',') "," else ""
        document.replaceString(start, end, text + separator)
        val ranges = stops.chunked(2).filter { it.size == 2 }.map { (from, to) ->
            val first = from.coerceIn(0, text.length)
            start + first until start + to.coerceIn(first, text.length)
        }
        if (ranges.isEmpty()) context.editor.caretModel.moveToOffset(start + text.length)
        else SnippetStops.start(context.editor, start, start + text.length, ranges)
    }
}
