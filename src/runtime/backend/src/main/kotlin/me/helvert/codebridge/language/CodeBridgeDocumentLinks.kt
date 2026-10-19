package me.helvert.codebridge.language

import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.navigation.NavigatableSymbol
import com.intellij.navigation.SymbolNavigationService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationSymbol
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.*
import kotlin.coroutines.cancellation.CancellationException

/**
 * The links an extension finds in a file, followed with Ctrl+click and underlined while Ctrl is
 * held over them, as the platform's own LSP client draws them and as VS Code does.
 *
 * Ahead of [CodeBridgeSymbolReferenceProvider]: where both answer, VS Code follows the link, and a link is
 * already held where a definition would be a round trip.
 */
internal class CodeBridgeDocumentLinkReferenceProvider : ImplicitReferenceProvider {
    override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val features = HostedLanguageFeatures.getInstance(file.project)
        if (!features.isMirrored(virtualFile) || !features.hasDocumentLinkProviders(virtualFile)) return null
        val document = file.viewProvider.document ?: return null
        val offset = (element.textRange?.startOffset ?: return null) + offsetInElement
        // The innermost, where one link sits inside another.
        val placed = features.documentLinks(virtualFile, document.immutableCharSequence)
            .filter { offset >= it.start && offset < it.end }
            .minByOrNull { it.end - it.start } ?: return null
        val symbol = CodeBridgeDocumentLinkSymbol(CodeBridgeDocumentLinkTarget(file, placed))
        return object : PsiSymbolReference {
            override fun getElement(): PsiElement = file
            override fun getRangeInElement(): TextRange = TextRange(placed.start, placed.end)
            override fun resolveReference(): Collection<Symbol> = listOf(symbol)
        }
    }
}

/**
 * Where a link leads, as something to navigate to.
 *
 * Not a place in a file, as [CodeBridgeDefinitionTarget] is: a link can lead to a web page, or run a
 * command, and where a file leads is not always known until the extension is asked. So navigating
 * is following the link, done here on the backend through the same paths `vscode.open` takes --
 * which reach a split-mode client themselves -- rather than a request handed to the frontend.
 */
internal class CodeBridgeDocumentLinkTarget(
    private val file: PsiFile,
    val placed: HostedLanguageFeatures.PlacedLink,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextOffset(): Int = placed.start

    override fun getName(): String = description(placed.link)

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun navigate(requestFocus: Boolean) {
        val features = HostedLanguageFeatures.getInstance(file.project)
        features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                follow(file.project, placed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not follow the link to ${description(placed.link)}", failure)
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CodeBridgeDocumentLinkTarget && other.file == file && other.placed == placed

    override fun hashCode(): Int = 31 * file.hashCode() + placed.hashCode()

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeDocumentLinkTarget::class.java)

        /**
         * Follows [placed]: a command runs, anything else opens as `vscode.open` opens it -- a file
         * at the line its fragment names, a web page in the browser, a document an extension
         * provides from the extension. A link that turns out to lead nowhere does nothing.
         */
        suspend fun follow(project: Project, placed: HostedLanguageFeatures.PlacedLink) {
            val features = HostedLanguageFeatures.getInstance(project)
            val target = features.resolveDocumentLink(placed)?.target ?: return
            DocumentLinks.commandHref(target)?.let { href ->
                val command = Markdown.command(href) ?: return
                features.executeCommand(command)
                return
            }
            val at = DocumentLinks.position(target)?.let { Range(it.lineNumber, it.column, it.lineNumber, it.column) }
            CodeBridgeWorkbenchCommands.open(
                project,
                WorkbenchCommands.Open(target.copy(fragment = fragmentOf(target, at)), at, false)
            )
        }

        /** A file's fragment names a line, which is said by the selection instead; a web page's is its own. */
        private fun fragmentOf(target: UriComponents, at: Range?): String =
            if (target.scheme == UriComponents.FILE && at != null) "" else target.fragment

        /** What a link says it is: its tooltip, or where it leads. */
        fun description(link: DocumentLinks.Link): String =
            link.tooltip ?: link.target?.let { DocumentLinks.commandHref(it) ?: it.uri } ?: "Follow link"
    }
}

/**
 * What a reference to a link resolves to. Its documentation is the hint Ctrl+hover shows: the
 * link's tooltip, or where it leads, as VS Code's hover on a link says.
 */
internal class CodeBridgeDocumentLinkSymbol(val target: CodeBridgeDocumentLinkTarget) : NavigatableSymbol, DocumentationSymbol {

    override fun createPointer(): Pointer<out CodeBridgeDocumentLinkSymbol> = Pointer.hardPointer(this)

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        listOf(SymbolNavigationService.getInstance().psiElementNavigationTarget(target))

    override fun getDocumentationTarget(): DocumentationTarget = Hint(target.name)

    override fun equals(other: Any?): Boolean = other is CodeBridgeDocumentLinkSymbol && other.target == target

    override fun hashCode(): Int = target.hashCode()

    private class Hint(private val text: String) : DocumentationTarget {
        override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)
        override fun computePresentation(): TargetPresentation = TargetPresentation.builder(text).presentation()

        /** HTML: a URL's `&` would otherwise start an entity. */
        override fun computeDocumentationHint(): String = StringUtil.escapeXmlEntities(text)
    }
}
