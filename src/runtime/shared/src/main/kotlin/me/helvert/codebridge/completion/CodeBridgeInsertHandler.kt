package me.helvert.codebridge.completion

import com.intellij.codeInsight.completion.FrontendFriendlyInsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import kotlinx.serialization.Serializable

/**
 * Replaces the filter text the lookup inserted with what the item actually inserts, and puts the
 * caret [caret] characters into it, with another caret at each of [carets].
 *
 * The others are a snippet's further `$0`s, where VS Code puts a cursor each: rust-analyzer's `pd`
 * inserts `eprintln!(" = {:?}", );` to be typed into in both places at once.
 *
 * Frontend-friendly, so in split mode it runs on the client along with the insertion itself. A
 * backend handler runs a round trip later: the caret is drawn at the end of the filter text first,
 * and only then jumps to where the item puts it -- inside `println!()`, say.
 */
@Serializable
class CodeBridgeInsertHandler(
    private val text: String,
    private val caret: Int,
    private val carets: List<Int> = emptyList(),
) : FrontendFriendlyInsertHandler {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        context.document.replaceString(context.startOffset, context.tailOffset, text)
        val editor = context.editor
        editor.caretModel.moveToOffset(context.startOffset + caret.coerceIn(0, text.length))
        for (offset in carets) {
            val position = editor.offsetToVisualPosition(context.startOffset + offset.coerceIn(0, text.length))
            editor.caretModel.addCaret(position, false)
        }
    }
}
