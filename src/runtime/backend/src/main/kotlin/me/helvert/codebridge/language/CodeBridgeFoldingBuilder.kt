package me.helvert.codebridge.language

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.impl.AbstractFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import me.helvert.codebridge.exthost.protocol.payload.FoldingRanges

/**
 * Fold regions from an extension's folding ranges, as VS Code folds from its folding providers.
 *
 * Registered for every language, since a file an extension claims reads as TextMate or plain text,
 * and the IDE folds neither. A file whose language has a folding builder of its own is left to the
 * IDE, as its breadcrumbs and structure are: two builders folding one file would fold each scope
 * twice. The ranges are read from what [HostedLanguageFeatures] holds, which asks for them in the
 * background and has the folding pass run again when an answer changes them.
 *
 * A range keeps its first line shown and folds away the lines after it, as VS Code draws it: the fold
 * runs from the end of the first line to the end of the last.
 */
internal class CodeBridgeFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PsiFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val features = file.project.serviceIfCreated<HostedLanguageFeatures>() ?: return FoldingDescriptor.EMPTY_ARRAY
        val virtualFile = file.viewProvider.virtualFile
        if (!features.folds(virtualFile)) return FoldingDescriptor.EMPTY_ARRAY
        if (foldedByIde(file)) return FoldingDescriptor.EMPTY_ARRAY
        val node = file.node ?: return FoldingDescriptor.EMPTY_ARRAY
        val length = document.textLength
        return features.foldingRanges(virtualFile, document.immutableCharSequence)
            .filter { it.end <= length }
            .map { FoldingDescriptor(node, TextRange(it.start, it.end), null, PLACEHOLDER, collapsedByDefault(it.kind), emptySet()) }
            .toTypedArray()
    }

    /**
     * Whether the IDE folds [file] by a builder of its own. Plain text has one too, but it folds only
     * the custom file types a user defines in the IDE, by their braces.
     */
    private fun foldedByIde(file: PsiFile): Boolean =
        if (file.language.isKindOf(PlainTextLanguage.INSTANCE)) file.fileType is AbstractFileType
        else LanguageFolding.INSTANCE.allForLanguage(file.language).isNotEmpty()

    override fun getPlaceholderText(node: ASTNode): String = PLACEHOLDER

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /**
     * Whether a newly opened file shows a range of [kind] folded: by the IDE's own settings for the
     * kinds it has one for, as its own builders decide.
     */
    private fun collapsedByDefault(kind: String?): Boolean {
        val settings = CodeFoldingSettings.getInstance()
        return when (kind) {
            FoldingRanges.KIND_IMPORTS -> settings.COLLAPSE_IMPORTS
            FoldingRanges.KIND_REGION -> settings.COLLAPSE_CUSTOM_FOLDING_REGIONS
            else -> false
        }
    }

    private companion object {
        const val PLACEHOLDER = "..."
    }
}
