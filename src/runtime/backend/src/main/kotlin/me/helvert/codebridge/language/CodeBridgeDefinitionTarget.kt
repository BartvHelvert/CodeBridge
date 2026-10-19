package me.helvert.codebridge.language

import com.intellij.model.Pointer
import com.intellij.navigation.NavigatableSymbol
import com.intellij.navigation.SymbolNavigationService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationSymbol
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import me.helvert.codebridge.exthost.protocol.payload.LanguageFeatures.DefinitionLink
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.workbench.HostedContentProviders
import javax.swing.Icon

/** Resolves extension definition links to navigatable positions, including files without token PSI. */
internal object CodeBridgeDefinitionTargets {
    /** [usages] is what Find Usages searches for when it starts from this target. */
    internal fun target(project: Project, link: DefinitionLink, usages: CodeBridgeUsagesTarget? = null): CodeBridgeDefinitionTarget? {
        val file = fileOf(project, link.resource) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return CodeBridgeDefinitionTarget(psi, offsetOf(document, link.caret.lineNumber, link.caret.column), usages)
    }

    /**
     * The file [resource] names: on disk, or made up by the extension that provides its scheme --
     * a library class, which Java's language server names under `jdt:`.
     */
    private fun fileOf(project: Project, resource: UriComponents): VirtualFile? {
        if (resource.scheme != UriComponents.FILE) return HostedContentProviders.getInstance(project).document(resource)
        val path = runCatching { resource.toPath() }.getOrNull() ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    /**
     * VS Code's 1-based line and column as an offset, clamped rather than trusted.
     *
     * [DocumentLines] does the same arithmetic for a diagnostic, and deliberately not here: that one
     * indexes plain text so it can be tested without a project, while a loaded document already
     * carries a line index there is no reason to rebuild for every definition followed.
     */
    internal fun offsetOf(document: Document, line: Int, column: Int): Int {
        if (document.lineCount == 0) return 0
        val index = (line - 1).coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(index)
        return (start + (column - 1).coerceAtLeast(0)).coerceAtMost(document.getLineEndOffset(index))
    }

}

/**
 * A position to navigate to, with no PSI element at it.
 *
 * The extension names a file and a position, and the target file's own PSI says nothing about what
 * is there: for a plain-text file it is a single leaf covering everything, so resolving the offset
 * to an element would land the caret at the top of the file. This carries the offset itself, which
 * is the same reason [CodeBridgeHoverTargetProvider] is a target provider rather than a documentation
 * provider.
 *
 * [usages] remembers where the definition was asked from, which is where references are asked from
 * too; null when no extension offers to find references there.
 */
internal class CodeBridgeDefinitionTarget(
    private val target: PsiFile,
    private val offset: Int,
    val usages: CodeBridgeUsagesTarget?,
) : FakePsiElement() {

    /** Also the containing file, which is what makes a fake element valid and gives it a manager. */
    override fun getParent(): PsiElement = target

    override fun getContainingFile(): PsiFile = target

    override fun getTextOffset(): Int = offset

    /**
     * The symbol at the destination, as a row of Choose Implementation or Choose Declaration names
     * a Java class: its own name, what holds it, and an icon of its kind.
     *
     * The name is the word there, which is all a file without an outline says, as the platform's
     * own LSP client shows it. Where the extension has outlined the file -- one open in an editor
     * -- the outline names what holds the symbol and what kind it is, which is what tells two
     * implementations of `area` apart.
     */
    private val presentation: Presentation by lazy {
        val document = target.viewProvider.document ?: return@lazy Presentation(target.name, target.name, null)
        val word = identifierRange(document.charsSequence, offset)?.subSequence(document.charsSequence)?.toString()
        val line = "${target.name}:${document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1}"
        val path = target.virtualFile?.let { enclosing(HostedLanguageFeatures.getInstance(target.project).outline(it, document)) }.orEmpty()
        val own = path.lastOrNull()?.takeIf { it.nameStart == offset }
        val container = (if (own != null) path.dropLast(1) else path).lastOrNull()
        Presentation(
            name = own?.name ?: word ?: target.name,
            location = container?.let { listOf(it.name, it.detail).filter(String::isNotBlank).joinToString(" ") } ?: line,
            icon = own?.let { CodeBridgeWorkspaceSymbol.icon(it.kind) },
        )
    }

    /** The symbols of [outline] that span [offset], outermost first. */
    private fun enclosing(outline: List<HostedLanguageFeatures.OutlineSymbol>): List<HostedLanguageFeatures.OutlineSymbol> {
        val path = mutableListOf<HostedLanguageFeatures.OutlineSymbol>()
        var level = outline
        while (true) {
            val next = level.firstOrNull { offset >= it.start && offset < it.end } ?: return path
            path += next
            level = next.children
        }
    }

    private data class Presentation(val name: String, val location: String, val icon: Icon?)

    override fun getName(): String = presentation.name

    /** Only seen when a symbol has more than one destination and the platform offers a choice. */
    override fun getLocationString(): String = presentation.location

    override fun getIcon(open: Boolean): Icon? = presentation.icon ?: target.getIcon(0)

    override fun getIcon(flags: Int): Icon? = getIcon(false)

    override fun canNavigate(): Boolean = target.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    /**
     * The request rather than the act, which is what reaches a split frontend.
     *
     * The platform asks a target for this before it asks it to navigate; answering means the
     * frontend opens the editor, where in split mode the backend has none to open.
     */
    override fun navigationRequest(): NavigationRequest? {
        val file = target.virtualFile ?: return null
        return NavigationRequest.sourceNavigationRequest(target.project, file, offset)
    }

    /** The monolith's path, where there is no request to hand anywhere. */
    override fun navigate(requestFocus: Boolean) {
        val file = target.virtualFile ?: return
        OpenFileDescriptor(target.project, file, offset).navigate(requestFocus)
    }
}

/**
 * What a reference to an extension's definition resolves to: [target], as a symbol of its own.
 *
 * Navigating it is navigating [target]; Find Usages finds [CodeBridgeDefinitionTarget.usages] by its type,
 * through [CodeBridgeSymbolSearchTargetFactory]. Its documentation is only the hint Ctrl+hover shows --
 * the destination file's name, as the platform gives for the element -- since what an extension
 * documents is shown by [CodeBridgeHoverTargetProvider]. [source] is where it was asked from, which is
 * where Go To Type Declaration asks too, through [CodeBridgeSymbolTypeProvider].
 */
internal class CodeBridgeDefinitionSymbol(
    val target: CodeBridgeDefinitionTarget,
    val source: CodeBridgeNavigationSource,
) : NavigatableSymbol, DocumentationSymbol {

    override fun createPointer(): Pointer<out CodeBridgeDefinitionSymbol> = Pointer.hardPointer(this)

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        listOf(SymbolNavigationService.getInstance().psiElementNavigationTarget(target))

    override fun getDocumentationTarget(): DocumentationTarget = Hint(target.containingFile.name)

    override fun equals(other: Any?): Boolean = other is CodeBridgeDefinitionSymbol && other.target == target

    override fun hashCode(): Int = target.hashCode()

    private class Hint(private val name: String) : DocumentationTarget {
        override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)
        override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name).presentation()
        override fun computeDocumentationHint(): String = name
    }
}
