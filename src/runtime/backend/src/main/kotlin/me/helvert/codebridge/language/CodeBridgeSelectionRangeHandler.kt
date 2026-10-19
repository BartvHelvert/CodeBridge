package me.helvert.codebridge.language

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Extend Selection, from what an extension's selection range provider says.
 *
 * The platform walks from the leaf at the caret up through its parents, asking every handler about
 * each, and stops at the first element that yields a range around the selection. Only the leaf is
 * claimed here: the answer is about the caret, not the element, so asking again for each parent
 * would ask the extension the same question. The IDE's own word and line ranges still apply, and the
 * smallest range that encloses the selection wins, whichever handler offered it.
 */
internal class CodeBridgeSelectionRangeHandler : ExtendWordSelectionHandler {
    override fun canSelect(e: PsiElement): Boolean {
        if (e.firstChild != null) return false
        val file = e.containingFile?.virtualFile ?: return false
        val features = HostedLanguageFeatures.getInstance(e.project)
        return features.isMirrored(file) && features.hasSelectionRangeProviders(file)
    }

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange>? {
        val file = e.containingFile?.virtualFile ?: return null
        val document = editor.document
        if (cursorOffset !in 0..document.textLength) return null
        val line = document.getLineNumber(cursorOffset)
        val ranges = HostedLanguageFeatures.getInstance(e.project)
            .selectionRanges(file, line + 1, cursorOffset - document.getLineStartOffset(line) + 1)
        return ranges.mapNotNull { symbolRange(document, it) }.takeIf { it.isNotEmpty() }
    }
}
