package me.helvert.codebridge.language

import com.intellij.find.usages.api.*
import com.intellij.find.usages.symbol.SymbolSearchTargetFactory
import com.intellij.model.Pointer
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.Query
import me.helvert.codebridge.exthost.protocol.payload.LanguageFeatures
import me.helvert.codebridge.exthost.protocol.payload.UriComponents

/**
 * Find Usages of the symbol at [range] in [file], answered by the extension's reference provider.
 *
 * VS Code asks for references at a position in the file the user is looking at, not of a
 * definition, so the target is that position rather than anything the definition resolved to. The
 * definition may be in a file the host has never been given, which no provider could answer about.
 */
internal class CodeBridgeUsagesTarget(private val file: PsiFile, private val range: TextRange) : SearchTarget {

    private val name: String
        get() = file.viewProvider.document?.charsSequence
            ?.takeIf { range.endOffset <= it.length }
            ?.let { range.subSequence(it).toString() }
            ?: file.name

    override fun createPointer(): Pointer<out SearchTarget> = Pointer.fileRangePointer(file, range, ::CodeBridgeUsagesTarget)

    override fun presentation(): TargetPresentation =
        TargetPresentation.builder(name).locationText(file.name, file.getIcon(0)).presentation()

    override val usageHandler: UsageHandler
        get() = UsageHandler.createEmptyUsageHandler(name)

    /** Blocking: the extension is asked, and a project-wide search may take it a while. */
    fun usages(scope: SearchScope): List<Usage> {
        val asked = runReadActionBlocking {
            val virtualFile = file.virtualFile ?: return@runReadActionBlocking null
            val document = file.viewProvider.document ?: return@runReadActionBlocking null
            if (range.startOffset > document.textLength) return@runReadActionBlocking null
            val line = document.getLineNumber(range.startOffset)
            Triple(virtualFile, line + 1, range.startOffset - document.getLineStartOffset(line) + 1)
        } ?: return emptyList()
        val (virtualFile, line, column) = asked
        val locations = HostedLanguageFeatures.getInstance(file.project).references(virtualFile, line, column)
        return runReadActionBlocking { locations.mapNotNull { usage(it, scope) } }
    }

    private fun usage(location: LanguageFeatures.Location, scope: SearchScope): Usage? {
        if (location.resource.scheme != UriComponents.FILE) return null
        val path = runCatching { location.resource.toPath() }.getOrNull() ?: return null
        val target = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        if (!inSearchScope(file.project, scope, target)) return null
        val psi = PsiManager.getInstance(file.project).findFile(target) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(target) ?: return null
        return CodeBridgeUsage(psi, symbolRange(document, location.range) ?: return null)
    }

    override fun equals(other: Any?): Boolean =
        other is CodeBridgeUsagesTarget && other.file == file && other.range == range

    override fun hashCode(): Int = 31 * file.hashCode() + range.hashCode()
}

/**
 * Whether [scope] covers [target], counting a file the project does not know as a library.
 *
 * A language server finds symbols wherever it likes -- a dependency's sources in a package cache, a
 * standard library -- and no scope the IDE builds contains a file outside the project, not even the
 * one for all places. Those files are nearly always dependencies, so they are kept by the scopes that
 * search libraries and left out of the ones that do not.
 */
internal fun inSearchScope(project: Project, scope: SearchScope, target: VirtualFile): Boolean =
    scope.contains(target) || scope is GlobalSearchScope && scope.isSearchInLibraries &&
            !ProjectFileIndex.getInstance(project).isInProject(target)

/**
 * One place the extension named.
 *
 * Never a declaration: the extension was asked to include the declaration and decides whether it
 * does, and nothing here knows which entry, if any, that is.
 */
private class CodeBridgeUsage(override val file: PsiFile, override val range: TextRange) : PsiUsage {

    override fun createPointer(): Pointer<out PsiUsage> = Pointer.fileRangePointer(file, range, ::CodeBridgeUsage)

    override val declaration: Boolean
        get() = false
}

/** The search target behind a symbol the reference provider resolved. */
internal class CodeBridgeSymbolSearchTargetFactory : SymbolSearchTargetFactory<CodeBridgeDefinitionSymbol> {
    override fun searchTarget(project: Project, symbol: CodeBridgeDefinitionSymbol): SearchTarget? = symbol.target.usages
}

internal class CodeBridgeReferenceSearcher : UsageSearcher {
    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> =
        (parameters.target as? CodeBridgeUsagesTarget)?.usages(parameters.searchScope).orEmpty()

    // The interface's own defaults, spelled out: left to it, Kotlin narrows them to these parameter
    // types with overrides that call the defaults, which the Plugin Verifier reports as calls to
    // override-only API.
    override fun collectSearchRequests(parameters: UsageSearchParameters): Collection<Query<out Usage>> = emptyList()

    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? = null
}
