package me.helvert.codebridge.language

import com.intellij.codeInsight.navigation.SymbolTypeProvider
import com.intellij.model.Symbol
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.PomDeclarationSearcher
import com.intellij.pom.PomNamedTarget
import com.intellij.pom.PomTarget
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Consumer
import com.intellij.util.Processor
import me.helvert.codebridge.exthost.protocol.payload.LanguageFeatures.DefinitionLink

/**
 * Where a navigation was asked from: the caret [offset] in [file].
 *
 * VS Code asks for a type definition or implementations at a position, as it asks for a
 * definition, so this is what the symbols a reference resolved to remember of it.
 */
internal class CodeBridgeNavigationSource(val file: PsiFile, val offset: Int) {

    /**
     * The type definitions at this position, asked for once.
     *
     * Go To Type Declaration asks about every symbol the reference resolved to, and all of them
     * come from here; asking the extension once per definition would offer each type that often.
     */
    val typeDefinitions: List<CodeBridgeDefinitionSymbol> by lazy {
        links(HostedLanguageFeatures::typeDefinitions)
            .mapNotNull { CodeBridgeDefinitionTargets.target(file.project, it) }
            .map { CodeBridgeDefinitionSymbol(it, this) }
    }

    /** Asks [request] at this position, in VS Code's one-based line and column. */
    fun links(request: HostedLanguageFeatures.(VirtualFile, Int, Int) -> List<DefinitionLink>): List<DefinitionLink> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val document = file.viewProvider.document ?: return emptyList()
        if (offset !in 0..document.textLength) return emptyList()
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line) + 1
        return HostedLanguageFeatures.getInstance(file.project).request(virtualFile, line + 1, column)
    }
}

/**
 * Go To Type Declaration, answered by `$provideTypeDefinition`.
 *
 * The platform asks this about the symbols a reference resolved to, which for an extension's file
 * are the [CodeBridgeDefinitionSymbol]s of [CodeBridgeSymbolReferenceProvider]: so there is a type to go to only
 * where there is also a definition, as there nearly always is.
 */
internal class CodeBridgeSymbolTypeProvider : SymbolTypeProvider {
    override fun getSymbolTypes(project: Project, symbol: Symbol): List<Symbol> =
        (symbol as? CodeBridgeDefinitionSymbol)?.source?.typeDefinitions ?: emptyList()
}

/**
 * The symbol at [range] in [file], as the place Go To Implementation starts from.
 *
 * Go To Implementation is still PSI-element based, and searches for what implements an element;
 * an extension's file has no element for a symbol, and VS Code asks at a position anyway. So this
 * stands in for one, found by [CodeBridgeImplementationDeclarationSearcher] and answered by
 * [CodeBridgeImplementationSearch]. A target rather than an element, as the platform's own LSP client
 * does it: the platform wraps it in an element of its own.
 */
internal class CodeBridgeImplementationTarget(val file: PsiFile, val range: TextRange) : PomNamedTarget {

    /** The chooser's title: "Choose Implementation of <name>". */
    override fun getName(): String =
        file.viewProvider.document?.charsSequence
            ?.takeIf { range.endOffset <= it.length }
            ?.let { range.subSequence(it).toString() }
            ?: file.name

    override fun isValid(): Boolean = file.isValid

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, range.startOffset).navigate(requestFocus)
    }

    override fun equals(other: Any?): Boolean =
        other is CodeBridgeImplementationTarget && other.file == file && other.range == range

    override fun hashCode(): Int = 31 * file.hashCode() + range.hashCode()
}

/**
 * Offers the word at the caret as something declared there, while Go To Implementation runs.
 *
 * Not keyed by language, unlike a target element evaluator, so a claimed file finds it whatever
 * language it reads as. And found as the element named at the caret rather than as one a reference
 * resolved to, which is what tells the platform not to offer the symbol as its own implementation
 * when the extension names none: it says "No implementations found", as VS Code does.
 *
 * Only during that action. Go To Declaration asks the same question, and a declaration at the
 * caret would turn every Ctrl+click the extension has no definition for into Show Usages.
 */
internal class CodeBridgeImplementationDeclarationSearcher : PomDeclarationSearcher() {
    override fun findDeclarationsAt(element: PsiElement, offsetInElement: Int, consumer: Consumer<in PomTarget>) {
        if (!CodeBridgeCurrentAction.isGotoImplementation) return
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!HostedLanguageFeatures.getInstance(file.project).canFindImplementations(virtualFile)) return
        val document = file.viewProvider.document ?: return
        val start = element.textRange?.startOffset ?: return
        val range = identifierRange(document.charsSequence, start + offsetInElement) ?: return
        consumer.consume(CodeBridgeImplementationTarget(file, range))
    }
}

/**
 * The action being performed, so far as [CodeBridgeImplementationDeclarationSearcher] needs to know it.
 *
 * By id, which an action overriding the platform's Go To Implementation keeps. One for the whole
 * application, as actions are performed on the EDT one at a time.
 */
internal object CodeBridgeCurrentAction {
    @Volatile
    internal var id: String? = null

    val isGotoImplementation: Boolean
        get() = id == IdeActions.ACTION_GOTO_IMPLEMENTATION
}

internal class CodeBridgeCurrentActionListener : AnActionListener {
    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        CodeBridgeCurrentAction.id = ActionManager.getInstance().getId(action)
    }

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        CodeBridgeCurrentAction.id = null
    }
}

/** What implements a [CodeBridgeImplementationTarget], answered by `$provideImplementation`. */
internal class CodeBridgeImplementationSearch :
    QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(true) {

    override fun processQuery(
        parameters: DefinitionsScopedSearch.SearchParameters,
        consumer: Processor<in PsiElement>
    ) {
        val source = (parameters.element as? PomTargetPsiElement)?.target as? CodeBridgeImplementationTarget ?: return
        val file = source.file
        for (link in CodeBridgeNavigationSource(
            file,
            source.range.startOffset
        ).links(HostedLanguageFeatures::implementations)) {
            val target = CodeBridgeDefinitionTargets.target(file.project, link) ?: continue
            if (!consumer.process(target)) return
        }
    }
}
