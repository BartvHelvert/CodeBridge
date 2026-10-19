package me.helvert.codebridge.language

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer

/** The platform schedules caret updates and clears old read/write highlights when the caret moves. */
internal class CodeBridgeHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {
    override fun createHighlightUsagesHandler(editor: Editor, psiFile: PsiFile): HighlightUsagesHandlerBase<PsiElement>? {
        val file = psiFile.virtualFile ?: return null
        val features = HostedLanguageFeatures.getInstance(psiFile.project)
        if (!features.hasHighlightProviders() || !features.isMirrored(file)) return null
        return object : HighlightUsagesHandlerBase<PsiElement>(editor, psiFile) {
            override fun getTargets(): List<PsiElement> = listOf(psiFile)

            override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
                selectionConsumer.consume(targets)
            }

            override fun computeUsages(targets: List<PsiElement>) {
                myReadUsages.clear()
                myWriteUsages.clear()
                val document = editor.document
                val offset = editor.caretModel.offset
                val stamp = document.modificationStamp
                val line = document.getLineNumber(offset)
                val highlights = features.documentHighlights(file, line + 1, offset - document.getLineStartOffset(line) + 1)
                if (document.modificationStamp != stamp || editor.caretModel.offset != offset) return
                for ((highlightRange, kind) in highlights) {
                    val range = symbolRange(document, highlightRange) ?: continue
                    if (kind == 3) myWriteUsages.add(range) else myReadUsages.add(range)
                }
            }
        }
    }
}
