package me.helvert.codebridge.language

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import me.helvert.codebridge.exthost.protocol.payload.Formatting
import me.helvert.codebridge.exthost.protocol.payload.TextEdit

/**
 * Reformat Code, answered by an extension's formatter.
 *
 * An asynchronous document service rather than a formatting model: an extension hands back edits to
 * the text, not a block structure, and may take seconds to do it -- `rustfmt` and `prettier` run as
 * processes. The platform takes the text this produces and applies it as the reformat, undo and
 * split mode included.
 *
 * Claims a file only when a formatter's selector does, so the IDE keeps its own formatter for every
 * file no extension offers to format -- including ones an extension mirrors for other features.
 */
internal class CodeBridgeFormattingService : AsyncDocumentFormattingService() {

    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        return HostedLanguageFeatures.getInstance(file.project).canFormat(virtualFile)
    }

    override fun getName(): String = "VS Code extension formatter"

    override fun getNotificationGroupId(): String = NOTIFICATION_GROUP

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        // A formatter rewrites what it likes. Where only whitespace may change -- the platform asks
        // this way when it reformats around another edit -- it is not the tool to ask.
        if (request.canChangeWhitespaceOnly()) return null
        val psiFile = request.context.containingFile
        val file = psiFile.virtualFile ?: return null
        val features = HostedLanguageFeatures.getInstance(psiFile.project)
        val indent = CodeStyle.getIndentOptions(psiFile)
        val options = Formatting.Options(indent.TAB_SIZE, !indent.USE_TAB_CHARACTER)
        val text = request.documentText
        val ranges = request.formattingRanges
        val whole = ranges.isEmpty() || ranges.any { it.startOffset <= 0 && it.endOffset >= text.length }

        return object : FormattingTask {
            @Volatile private var cancelled = false

            override fun run() {
                if (!features.awaitMirrored(file, text)) {
                    request.onError(TITLE, "The extension has not caught up with the latest edits to ${file.name} yet; try again.")
                    return
                }
                val edits = if (whole) formatWhole() else formatRanges()
                if (edits == null) {
                    request.onError(TITLE, "The extension formatter for ${file.name} failed or did not answer in time.")
                    return
                }
                if (cancelled) return
                val formatted = CodeBridgeTextEdits.apply(text, edits)
                if (formatted == null) request.onError(TITLE, "The extension's formatting edits do not fit ${file.name}.")
                else request.onTextReady(formatted)
            }

            private fun formatWhole(): List<TextEdit>? =
                features.formatting(file, null, CodeBridgeTextEdits.whole(text), options)?.edits

            /**
             * Each range asked for separately, and every answer applied to the original text together.
             *
             * The host's copy does not change between the requests -- nothing is mirrored until the
             * platform applies the result -- so each answer is about the same text. A formatter that
             * can only do the whole file is asked once, and its edits outside the ranges dropped.
             */
            private fun formatRanges(): List<TextEdit>? {
                val selected = ranges.map { TextRange(it.startOffset.coerceIn(0, text.length), it.endOffset.coerceIn(0, text.length)) }
                val edits = mutableListOf<TextEdit>()
                var answered = false
                for (range in selected) {
                    if (cancelled) break
                    val result = features.formatting(file, CodeBridgeTextEdits.rangeOf(text, range), CodeBridgeTextEdits.whole(text), options) ?: continue
                    answered = true
                    if (!result.wholeDocument) {
                        edits += result.edits
                        continue
                    }
                    edits += result.edits.filter { edit ->
                        val offsets = CodeBridgeTextEdits.offsets(text, edit.range) ?: return@filter false
                        selected.any { it.contains(offsets) }
                    }
                    // The whole document's edits cover every range at once.
                    break
                }
                return if (answered) edits.distinct() else null
            }

            /**
             * Stops between requests and drops the answer. A request already sent runs to its end in
             * the extension, which has no reason to stop a formatter halfway.
             */
            override fun cancel(): Boolean {
                cancelled = true
                return true
            }
        }
    }

    private companion object {
        /** Registered in the backend descriptor; the platform names it in timeout notifications. */
        const val NOTIFICATION_GROUP = "CodeBridge formatting"

        const val TITLE = "Extension formatter"
    }
}
