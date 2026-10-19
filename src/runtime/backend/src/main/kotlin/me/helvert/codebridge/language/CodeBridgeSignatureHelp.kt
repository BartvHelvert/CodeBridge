package me.helvert.codebridge.language

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.lang.documentation.QuickDocHighlightingHelper.appendStyledFragment
import com.intellij.lang.parameterInfo.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.light.LightElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.SignatureHelp

/**
 * An extension's signature help, as IntelliJ's Parameter Info popup.
 *
 * The owner is a stable light element anchored near the caret. The PSI of a claimed file is usually
 * one plain-text leaf, rebuilt on every edit, and the popup closes when its owner stops being the same
 * element. The owner's range also anchors the popup in a split-mode frontend, so using the whole
 * file would put it at offset zero. The call the caret is in is the extension's business, so every
 * update asks it again, as VS Code does while its widget is open, and closes the popup when it
 * answers with nothing -- which is how a `)` closes it.
 *
 * Registered for every language, after each language's own handlers: in a file the IDE has a
 * language of its own for, its own Parameter Info answers first.
 */
internal class CodeBridgeSignatureHelpHandler :
    ParameterInfoHandler<CodeBridgeSignatureHelpHandler.Owner, CodeBridgeSignatureHelpHandler.Item>, DumbAware {

    class Owner(val file: PsiFile, private val document: Document, offset: Int) :
        LightElement(file.manager, file.language) {
        private val anchor = document.createRangeMarker(offset, offset)
        override fun getContainingFile(): PsiFile = file
        override fun getParent() = file
        override fun getTextRange(): TextRange =
            TextRange(anchor.startOffset.coerceIn(0, document.textLength), document.textLength)

        override fun getTextOffset(): Int = textRange.startOffset
        override fun isValid(): Boolean = file.isValid && anchor.isValid
        override fun toString(): String = "Signature help owner"
        fun dispose() = anchor.dispose()
    }

    /**
     * One signature of the popup. Every item of one popup shares its [held] reply, which is
     * replaced as the extension answers again; [index] is the signature's place in it.
     */
    class Item(val held: Held, val index: Int)

    /** The reply on screen, and the service to release it to once it is replaced or gone. */
    class Held(
        val owner: Owner,
        val highlighter: EditorHighlighter,
        private val features: HostedLanguageFeatures,
        shown: HostedLanguageFeatures.ShownSignatureHelp,
        askedAt: Pair<Long, Int>
    ) {
        @Volatile
        var shown: HostedLanguageFeatures.ShownSignatureHelp = shown
            private set

        /** The document stamp and caret offset [shown] answers, so an update about the same need not ask. */
        @Volatile
        var askedAt: Pair<Long, Int> = askedAt
            private set

        fun replace(next: HostedLanguageFeatures.ShownSignatureHelp, at: Pair<Long, Int>) {
            val previous = shown
            shown = next
            askedAt = at
            release(previous)
        }

        fun release() {
            release(shown)
            owner.dispose()
        }

        private fun release(shown: HostedLanguageFeatures.ShownSignatureHelp) {
            shown.help.id?.let { features.releaseSignatureHelp(shown.handle, it) }
        }
    }

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): Owner? {
        val psi = context.file ?: return null
        val file = psi.virtualFile ?: return null
        val features = HostedLanguageFeatures.getInstance(context.project)
        if (!features.isMirrored(file)) return null
        val triggers = features.signatureHelpTriggers(file) ?: return null
        val document = context.editor.document
        val text = document.immutableCharSequence
        val offset = context.offset.coerceIn(0, text.length)
        features.awaitMirrored(file, text.toString(), MIRROR_WAIT_MILLIS)
        val trigger = triggerAt(text, offset, triggers.triggers, SignatureHelp.TRIGGER_INVOKE)
        val line = document.getLineNumber(offset)
        val shown = features.signatureHelp(file, line + 1, offset - document.getLineStartOffset(line) + 1, trigger)
            ?: return null
        val owner = Owner(psi, document, (offset - 1).coerceAtLeast(0))
        // Use the file's editor provider, including TextMate, with this editor's color scheme.
        val highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(file, context.editor.colorsScheme, context.project)
        val held = Held(owner, highlighter, features, shown, document.modificationStamp to offset)
        context.itemsToShow = Array(shown.help.signatures.size) { Item(held, it) }
        return owner
    }

    override fun showParameterInfo(element: Owner, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): Owner? {
        val psi = context.file ?: return null
        val file = psi.virtualFile ?: return null
        val owner = context.parameterOwner as? Owner ?: return null
        return owner.takeIf {
            it.isValid && it.file == psi && HostedLanguageFeatures.getInstance(context.project).isMirrored(file)
        }
    }

    override fun updateParameterInfo(parameterOwner: Owner, context: UpdateParameterInfoContext) {
        val items = context.objectsToView.filterIsInstance<Item>()
        val held = items.firstOrNull()?.held ?: return context.removeHint()
        val file = parameterOwner.file.virtualFile ?: return context.removeHint()
        val features = HostedLanguageFeatures.getInstance(context.project)
        val document = context.editor.document
        val offset = context.offset.coerceIn(0, document.textLength)
        val at = document.modificationStamp to offset
        if (held.askedAt != at) {
            val triggers = features.signatureHelpTriggers(file) ?: return context.removeHint()
            val text = document.immutableCharSequence
            features.awaitMirrored(file, text.toString(), MIRROR_WAIT_MILLIS)
            val trigger =
                triggerAt(text, offset, triggers.triggers + triggers.retriggers, SignatureHelp.TRIGGER_CONTENT_CHANGE)
            val line = document.getLineNumber(offset)
            val next = features.signatureHelp(
                file,
                line + 1,
                offset - document.getLineStartOffset(line) + 1,
                trigger,
                held.shown
            )
                ?: return context.removeHint()
            if (next.help.signatures.size != items.size) {
                // A popup's items are fixed once it is shown, and the caret has moved into another
                // call -- a nested one, typically. Shown afresh, the popup is that call's.
                next.help.id?.let { features.releaseSignatureHelp(next.handle, it) }
                context.removeHint()
                reopen(context, parameterOwner.file)
                return
            }
            held.replace(next, at)
        }
        val help = held.shown.help
        val active = items.getOrNull(help.activeSignature) ?: items.first()
        if (items.size > 1) context.highlightedParameter = active
        val signature = help.signatures.getOrNull(active.index)
        context.setCurrentParameter(signature?.let { it.activeParameter ?: help.activeParameter } ?: -1)
    }

    override fun updateUI(item: Item, context: ParameterInfoUIContext) {
        val help = item.held.shown.help
        val signature = help.signatures.getOrNull(item.index) ?: return context.setUIComponentVisible(false)
        context.setupRawUIComponentPresentation(html(signature, help.activeParameter, item.held.highlighter))
    }

    /** The caret is the place asked about, whitespace and all: a space after `,` is in the next argument. */
    override fun isWhitespaceSensitive(): Boolean = true

    override fun dispose(context: DeleteParameterInfoContext) {
        val controller = context.customContext as? ParameterInfoControllerBase ?: return
        (controller.objects?.firstOrNull() as? Item)?.held?.release()
    }

    private fun reopen(context: ParameterInfoContext, file: PsiFile) {
        val editor = context.editor
        val project = context.project
        Scope.getInstance(project).scope.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
            if (editor.isDisposed || PsiDocumentManager.getInstance(project)
                    .getPsiFile(editor.document) != file
            ) return@launch
            ShowParameterInfoHandler.invoke(project, editor, file, -1, null, false)
        }
    }

    /** Where a reopen runs: after the popup it replaces has gone, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        /** Short: the popup follows typing, and a request about slightly older text is still close. */
        private const val MIRROR_WAIT_MILLIS = 500L

        /**
         * Why help is asked for at [offset]: the character just typed, when it is one of [characters],
         * otherwise [otherwise].
         */
        fun triggerAt(text: CharSequence, offset: Int, characters: Set<String>, otherwise: Int): SignatureHelp.Trigger {
            val typed = text.getOrNull(offset - 1)?.toString()
            return if (typed != null && typed in characters) SignatureHelp.Trigger(
                SignatureHelp.TRIGGER_CHARACTER,
                typed
            )
            else SignatureHelp.Trigger(otherwise)
        }

        /**
         * A signature as the popup draws it: its label with the parameter being typed in bold, and
         * that parameter's documentation beneath, as VS Code shows it.
         *
         * The signature's own documentation is left out: it is usually the function's whole doc
         * comment, which Quick Documentation already shows, and the popup sits over the code.
         */
        fun html(
            signature: SignatureHelp.Signature,
            activeParameter: Int,
            highlighter: EditorHighlighter? = null
        ): String {
            val label = signature.label
            val parameter = signature.active(activeParameter)
            val range = parameter?.range
            val html = StringBuilder()
            highlighter?.setText(label)
            fun append(start: Int, end: Int) {
                if (start == end) return
                if (highlighter == null) {
                    html.append(StringUtil.escapeXmlEntities(label.substring(start, end)))
                    return
                }
                val tokens = highlighter.createIterator(start)
                while (!tokens.atEnd() && tokens.start < end) {
                    val fragment = label.substring(maxOf(start, tokens.start), minOf(end, tokens.end))
                    html.appendStyledFragment(StringUtil.escapeXmlEntities(fragment), tokens.textAttributes)
                    tokens.advance()
                }
            }
            if (range == null) append(0, label.length)
            else {
                append(0, range.first)
                html.append("<b>")
                append(range.first, range.last + 1)
                html.append("</b>")
                append(range.last + 1, label.length)
            }
            parameter?.documentation?.let { documentation ->
                html.append("<br>").append(StringUtil.escapeXmlEntities(documentation).replace("\n", "<br>"))
            }
            return html.toString()
        }
    }
}

/**
 * Opens signature help when a character an extension names is typed, as VS Code does.
 *
 * IntelliJ opens Parameter Info only where a language's own typing handler asks it to, and a file an
 * extension claims usually has no language that would. A popup already open follows the typing by
 * itself.
 */
internal class CodeBridgeSignatureHelpTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        val virtualFile = file.virtualFile ?: return Result.CONTINUE
        val triggers =
            HostedLanguageFeatures.getInstance(project).signatureHelpTriggers(virtualFile) ?: return Result.CONTINUE
        if (charTyped.toString() in triggers.triggers && !ParameterInfoControllerBase.existsWithVisibleHintForEditor(
                editor,
                true
            )
        ) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        }
        return Result.CONTINUE
    }
}
