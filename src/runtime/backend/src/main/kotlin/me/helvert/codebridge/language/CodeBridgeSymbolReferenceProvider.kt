package me.helvert.codebridge.language

import com.intellij.model.Symbol
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import me.helvert.codebridge.exthost.protocol.payload.Range

/** A reference carries its source range even when the file has only one PSI leaf. */
internal class CodeBridgeSymbolReferenceProvider : ImplicitReferenceProvider {
    override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
        // TextMate often exposes one plain-text leaf covering the whole document. The platform
        // may never ask the PsiFile provider in that case, so answer for whichever ancestor it
        // visits first. The reference is the file's, so its range is measured from the file's
        // start, not from that element's: the two only agree for a leaf at offset 0.
        val file = element.containingFile
        val features = HostedLanguageFeatures.getInstance(file.project)
        if (!features.hasDefinitionProviders()) return null
        val virtualFile = file.virtualFile ?: return null
        val document = file.viewProvider.document ?: return null
        val elementStart = element.textRange?.startOffset ?: return null
        val absoluteOffset = elementStart + offsetInElement
        if (absoluteOffset !in 0..document.textLength) return null
        val line = document.getLineNumber(absoluteOffset)
        val links = features.definitions(virtualFile, line + 1, absoluteOffset - document.getLineStartOffset(line) + 1)
        if (links.isEmpty()) return null
        val range = links.firstNotNullOfOrNull { link ->
            link.origin?.let { symbolRange(document, it) }?.takeIf { it.containsOffset(absoluteOffset) }
        } ?: identifierRange(document.charsSequence, absoluteOffset) ?: return null
        val usages = CodeBridgeUsagesTarget(file, range).takeIf { features.canFindReferences(virtualFile) }
        val targets = links.mapNotNull { CodeBridgeDefinitionTargets.target(file.project, it, usages) }
        if (targets.isEmpty()) return null
        val source = CodeBridgeNavigationSource(file, absoluteOffset)
        return object : PsiSymbolReference {
            override fun getElement(): PsiElement = file
            override fun getRangeInElement(): TextRange = range
            override fun resolveReference(): Collection<Symbol> = targets.map { CodeBridgeDefinitionSymbol(it, source) }
        }
    }
}

/** Reject stale or empty ranges instead of painting unrelated text at a clamped position. */
internal fun symbolRange(document: Document, range: Range): TextRange? {
    fun offset(line: Int, column: Int): Int? {
        if (line !in 1..document.lineCount || column < 1) return null
        val start = document.getLineStartOffset(line - 1)
        val length = document.getLineEndOffset(line - 1) - start
        if (column - 1 > length) return null
        return start + column - 1
    }
    val start = offset(range.startLineNumber, range.startColumn) ?: return null
    val end = offset(range.endLineNumber, range.endColumn) ?: return null
    return if (end > start) TextRange(start, end) else null
}

/** Fallback for providers returning Locations without an originSelectionRange. */
internal fun identifierRange(text: CharSequence, offset: Int): TextRange? {
    if (offset !in 0..text.length) return null
    fun part(index: Int) = index in text.indices && Character.isJavaIdentifierPart(text[index])
    var start = offset
    if (!part(start)) {
        if (!part(start - 1)) return null
        start--
    }
    var end = start + 1
    while (part(start - 1)) start--
    while (part(end)) end++
    return TextRange(start, end)
}
