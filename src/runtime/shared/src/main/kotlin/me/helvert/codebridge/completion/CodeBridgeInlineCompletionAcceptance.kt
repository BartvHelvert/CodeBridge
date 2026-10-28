package me.helvert.codebridge.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEventListener
import com.intellij.codeInsight.inline.completion.InlineCompletionEventType
import com.intellij.codeInsight.inline.completion.InlineCompletionHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionInstallListener
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor

/**
 * Tells the backend when the user takes an inline completion, by what the document then reads, so
 * it can run the item's command.
 *
 * A split-mode client inserts the suggestion itself and forwards the Tab, which the backend runs
 * on its own copy of the session. The client's request for the next suggestion can reach the
 * backend first and end that copy, and the Tab then accepts nothing there. Registered for the
 * frontend only; the backend matches the document against what each item would have left, and
 * runs a command at most once however it hears of it.
 */
internal class CodeBridgeInlineCompletionAcceptance : InlineCompletionInstallListener {

    override fun handlerInstalled(editor: Editor, handler: InlineCompletionHandler) {
        val project = editor.project ?: return
        // The listener rather than `InlineCompletionEventAdapter`, whose internal, non-extendable
        // `onSuggestionInitialized` an implementation would override.
        handler.addEventListener(object : InlineCompletionEventListener {
            override fun on(event: InlineCompletionEventType) {
                if (event is InlineCompletionEventType.AfterInsert && !project.isDisposed) {
                    service<CodeBridgeCompletionCommandRequests>().sendInline(project, editor.document)
                }
            }
        })
    }
}
