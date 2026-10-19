package me.helvert.codebridge.language

import com.intellij.icons.AllIcons
import com.intellij.navigation.ChooseByNameContributorEx2
import com.intellij.navigation.ColoredItemPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.WorkspaceSymbols
import javax.swing.Icon

/**
 * Go to Class and Go to Symbol, answered by the extensions' workspace symbol providers.
 *
 * VS Code has one search for these, Go to Symbol in Workspace; IntelliJ has two, so the answer to
 * that one search is split by kind. Go to Symbol offers every symbol, classes included, as it does
 * for the IDE's own languages; Go to Class only the kinds that are types. Search Everywhere asks both
 * at once and shows a symbol both offer once, since the two are equal.
 *
 * An extension is asked about what the user typed, never for every name it knows: the platform asks
 * for names first and then for the symbols behind each, and both come from the one query, which
 * [HostedLanguageFeatures.workspaceSymbols] asks once.
 */
internal abstract class CodeBridgeWorkspaceSymbolContributor : ChooseByNameContributorEx2, DumbAware {

    /** Whether symbols of this `SymbolKind` are offered. */
    protected abstract fun offers(kind: Int): Boolean

    override fun processNames(processor: Processor<in String>, parameters: FindSymbolParameters) {
        val names = symbols(parameters).mapTo(LinkedHashSet()) { it.name }
        for (name in names) if (!processor.process(name)) return
    }

    /** Asked with no pattern, so there is nothing to ask an extension. */
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) = Unit

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        for (symbol in symbols(parameters, name)) if (!processor.process(symbol)) return
    }

    private fun symbols(parameters: FindSymbolParameters, name: String? = null): List<CodeBridgeWorkspaceSymbol> {
        val project = parameters.project
        return HostedLanguageFeatures.getInstance(project).workspaceSymbols(parameters.completePattern)
            .filter { offers(it.symbol.kind) && (name == null || it.symbol.name == name) }
            .mapNotNull { offered ->
                val file = CodeBridgeWorkspaceSymbol.fileOf(offered.symbol.resource) ?: return@mapNotNull null
                if (!inSearchScope(project, parameters.searchScope, file)) return@mapNotNull null
                CodeBridgeWorkspaceSymbol(project, file, offered)
            }
    }
}

/** Go to Class: the kinds that are types. */
internal class CodeBridgeWorkspaceClassContributor : CodeBridgeWorkspaceSymbolContributor() {
    override fun offers(kind: Int): Boolean = kind in TYPE_KINDS

    private companion object {
        /** `SymbolKind` Class, Enum, Interface and Struct. */
        val TYPE_KINDS = setOf(4, 9, 10, 22)
    }
}

/** Go to Symbol: everything, as VS Code's own search offers it. */
internal class CodeBridgeWorkspaceSymbolsContributor : CodeBridgeWorkspaceSymbolContributor() {
    override fun offers(kind: Int): Boolean = true
}

/**
 * One symbol an extension named, as the popup lists it and opens it.
 *
 * Not a PSI element: the file's own PSI says nothing about what is at the symbol's position, and
 * listing a symbol should not cost loading its file. The position is only read once it is chosen.
 */
data class CodeBridgeWorkspaceSymbol(
    private val project: Project,
    private val file: VirtualFile,
    private val offered: HostedLanguageFeatures.OfferedSymbol,
) : NavigationItem, ColoredItemPresentation {

    override fun getName(): String = offered.symbol.name

    override fun getPresentation(): ItemPresentation = this

    override fun getPresentableText(): String = offered.symbol.name

    /** What VS Code shows beside a symbol: what it is in, and the file. */
    override fun getLocationString(): String =
        offered.symbol.containerName?.let { "$it (${file.name})" } ?: file.name

    override fun getIcon(unused: Boolean): Icon = icon(offered.symbol.kind)

    override fun getTextAttributesKey(): TextAttributesKey? =
        CodeInsightColors.DEPRECATED_ATTRIBUTES.takeIf { offered.symbol.deprecated }

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    /**
     * Opens the symbol's position, resolving it first when its provider fills positions in late.
     *
     * Resolving asks the extension, which is not done on the UI thread; a symbol that needs no
     * resolving opens straight away.
     */
    override fun navigate(requestFocus: Boolean) {
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.resolvesWorkspaceSymbol(offered)) {
            open(offered.symbol, requestFocus)
            return
        }
        val modality = ModalityState.defaultModalityState()
        // Resolved first, then opened on the editor thread, with the modality it was chosen in.
        Scope.getInstance(project).scope.launch {
            val resolved = features.resolveWorkspaceSymbol(offered)
            withContext(Dispatchers.EDT + modality.asContextElement()) { open(resolved, requestFocus) }
        }
    }

    private fun open(symbol: WorkspaceSymbols.Symbol, requestFocus: Boolean) {
        val target = fileOf(symbol.resource)?.takeIf { it.isValid } ?: file
        val offset = runReadActionBlocking {
            FileDocumentManager.getInstance().getDocument(target)
                ?.let { CodeBridgeDefinitionTargets.offsetOf(it, symbol.range.startLineNumber, symbol.range.startColumn) }
        } ?: 0
        OpenFileDescriptor(project, target, offset).navigate(requestFocus)
    }

    /** Where a resolve runs: it outlives the popup the symbol was chosen in, and ends with the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {

        fun fileOf(resource: UriComponents): VirtualFile? {
            if (resource.scheme != UriComponents.FILE) return null
            val path = runCatching { resource.toPath() }.getOrNull() ?: return null
            return LocalFileSystem.getInstance().findFileByNioFile(path)
        }

        /** The IDE's own icon for each `SymbolKind`, numbered from 0 as VS Code numbers them. */
        fun icon(kind: Int): Icon = when (kind) {
            0 -> AllIcons.FileTypes.Any_type
            1 -> AllIcons.Nodes.Module
            2, 3 -> AllIcons.Nodes.Package
            4, 22, 25 -> AllIcons.Nodes.Class
            5, 8, 24 -> AllIcons.Nodes.Method
            6 -> AllIcons.Nodes.Property
            7, 21, 23 -> AllIcons.Nodes.Field
            9 -> AllIcons.Nodes.Enum
            10 -> AllIcons.Nodes.Interface
            11 -> AllIcons.Nodes.Function
            13 -> AllIcons.Nodes.Constant
            else -> AllIcons.Nodes.Variable
        }
    }
}
