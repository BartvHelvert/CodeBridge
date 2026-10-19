package me.helvert.codebridge.language

import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import me.helvert.codebridge.exthost.protocol.payload.LanguageFeatures
import me.helvert.codebridge.exthost.protocol.payload.Position
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.UriComponents

/**
 * Definitions and references from IntelliJ's own resolution, for `vscode.executeDefinitionProvider`
 * and `vscode.executeReferenceProvider` in a file no extension answers for -- Java, with no Java
 * extension -- as [IdeDocumentSymbols] answers its outline.
 *
 * Only places in local files: a definition in a library jar has no `file:` resource to name.
 */
internal object IdeLocations {

    suspend fun definitions(
        project: Project,
        file: VirtualFile,
        at: Position
    ): List<LanguageFeatures.DefinitionLink> =
        read(project) {
            val (psi, offset) = locate(project, file, at) ?: return@read emptyList()
            val reference = psi.findReferenceAt(offset) ?: return@read emptyList()
            // What was asked about, in the asked file: the host underlines it as the link's source.
            val origin =
                psi.viewProvider.document?.let { CodeBridgeTextEdits.rangeOf(it.immutableCharSequence, originOf(reference)) }
            targetsOf(reference).mapNotNull { target -> linkTo(target.navigationElement, origin) }.distinct()
        }

    /** Every reference to the symbol at [at] in the project, and its declaration, as VS Code asks with `includeDeclaration`. */
    suspend fun references(
        project: Project,
        file: VirtualFile,
        at: Position
    ): List<LanguageFeatures.Location> =
        read(project) {
            val (psi, offset) = locate(project, file, at) ?: return@read emptyList()
            val target = psi.findReferenceAt(offset)?.let(::targetsOf)?.firstOrNull() ?: declaredAt(psi, offset)
            ?: return@read emptyList()
            val declaration =
                nameRange(target)?.let { range -> target.containingFile?.virtualFile?.let { locationOf(it, range) } }
            val uses = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll()
                .mapNotNull { reference ->
                    val file = reference.element.containingFile?.virtualFile ?: return@mapNotNull null
                    locationOf(file, reference.rangeInElement.shiftRight(reference.element.textRange.startOffset))
                }
            (listOfNotNull(declaration) + uses).distinct()
        }

    private suspend fun <T> read(project: Project, compute: () -> List<T>): List<T> =
        constrainedReadAction(
            ReadConstraint.inSmartMode(project),
            ReadConstraint.withDocumentsCommitted(project),
            action = compute
        )

    private fun locate(project: Project, file: VirtualFile, at: Position): Pair<PsiFile, Int>? {
        if (!file.isValid) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = at.lineNumber - 1
        if (line !in 0 until document.lineCount) return null
        val offset = document.getLineStartOffset(line) + at.column - 1
        return (psi to offset).takeIf { offset in 0..document.getLineEndOffset(line) }
    }

    private fun targetsOf(reference: PsiReference): List<PsiElement> =
        (reference as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element }
            ?: listOfNotNull(reference.resolve())

    /** The declaration whose name is at [offset], for references asked about from the declaration itself. */
    private fun declaredAt(psi: PsiFile, offset: Int): PsiElement? {
        val leaf = psi.findElementAt(offset) ?: return null
        val owner = PsiTreeUtil.getParentOfType(leaf, PsiNameIdentifierOwner::class.java, false) ?: return null
        return owner.takeIf { it.nameIdentifier?.textRange?.containsOffset(offset) == true }
    }

    private fun originOf(reference: PsiReference): TextRange =
        reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)

    private fun nameRange(element: PsiElement): TextRange? =
        (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange ?: element.textRange

    private fun linkTo(target: PsiElement, origin: Range?): LanguageFeatures.DefinitionLink? {
        val file = target.containingFile?.virtualFile ?: return null
        val whole = target.textRange ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (whole.endOffset > document.textLength) return null
        val text = document.immutableCharSequence
        return LanguageFeatures.DefinitionLink(
            resourceOf(file) ?: return null,
            CodeBridgeTextEdits.rangeOf(text, whole),
            nameRange(target)?.takeIf { it.endOffset <= text.length }?.let { CodeBridgeTextEdits.rangeOf(text, it) },
            origin,
        )
    }

    private fun locationOf(file: VirtualFile, range: TextRange): LanguageFeatures.Location? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (range.endOffset > document.textLength) return null
        return LanguageFeatures.Location(
            resourceOf(file) ?: return null,
            CodeBridgeTextEdits.rangeOf(document.immutableCharSequence, range)
        )
    }

    private fun resourceOf(file: VirtualFile): UriComponents? =
        if (file.isInLocalFileSystem) runCatching { UriComponents.of(file.toNioPath()) }.getOrNull() else null
}
