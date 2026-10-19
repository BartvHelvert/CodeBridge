package me.helvert.codebridge.completion

import com.intellij.codeInsight.completion.FrontendFriendlyInsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import kotlinx.serialization.Serializable

/**
 * Closes a `${variable}` the lookup has just completed the name of, unless a `}` is already there,
 * and puts the caret after it. Frontend-friendly for the reason [CodeBridgeInsertHandler] is.
 */
@Serializable
class CodeBridgeVariableInsertHandler : FrontendFriendlyInsertHandler {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val tail = context.tailOffset
        val text = context.document.charsSequence
        if (tail >= text.length || text[tail] != '}') context.document.insertString(tail, "}")
        context.editor.caretModel.moveToOffset(tail + 1)
    }
}
