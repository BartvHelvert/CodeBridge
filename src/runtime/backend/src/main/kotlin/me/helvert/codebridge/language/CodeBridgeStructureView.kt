package me.helvert.codebridge.language

import com.intellij.ide.structureView.*
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * The Structure view and the File Structure popup, from an extension's document symbols, as VS
 * Code's Outline view is drawn from them.
 *
 * Registered without a file type, which the platform asks only once nothing registered for the
 * file's own type answers, and answering only for a file an extension outlines and whose language
 * has no structure view of its own: the same files `CodeBridgeBreadcrumbsCollector` answers for, from the
 * same outline. A split-mode client asks the backend for its structure, so this reaches it.
 */
internal class CodeBridgeStructureViewBuilderProvider : StructureViewBuilderProvider {

    override fun getStructureViewBuilder(fileType: FileType, file: VirtualFile, project: Project): StructureViewBuilder? {
        val features = project.serviceIfCreated<HostedLanguageFeatures>() ?: return null
        if (!features.outlines(file)) return null
        val language = (fileType as? LanguageFileType)?.language
        if (language != null && LanguageStructureViewBuilder.getInstance().forLanguage(language) != null) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = CodeBridgeStructureViewModel(project, file, editor)
        }
    }
}

/**
 * A file's outline as a tree, rebuilt whenever an extension sends a new one; the symbol under the
 * caret is the innermost one around it, as for breadcrumbs.
 */
internal class CodeBridgeStructureViewModel(
    private val project: Project,
    private val file: VirtualFile,
    editor: Editor?,
) : TextEditorBasedStructureViewModel(editor), StructureViewModel.ElementInfoProvider {

    init {
        project.messageBus.connect(this).subscribe(CodeBridgeOutlineListener.TOPIC, CodeBridgeOutlineListener { changed ->
            if (changed == file) fireModelUpdate()
        })
    }

    override fun getRoot(): StructureViewTreeElement = FileElement(project, file)

    // The base implementation requires a PSI file, which extension outlines do not have.
    // Reporting false makes StructureViewWrapperImpl repeatedly dispose and rebuild the view.
    override fun isValid(): Boolean = !project.isDisposed && file.isValid

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun getCurrentEditorElement(): Any? {
        val editor = editor ?: return null
        val offset = editor.caretModel.offset
        var level = SymbolElement.children(project, file, emptyList())
        var found: SymbolElement? = null
        while (true) {
            val around = level.firstOrNull { it.symbol.start <= offset && offset <= it.symbol.end } ?: break
            found = around
            level = around.children.toList()
        }
        return found?.value
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element is SymbolElement && element.symbol.children.isEmpty()
}

/**
 * The file, whose children are the outline's top level.
 *
 * Equal to any other element for the same file: the view rebuilds its tree on every edit, and keeps
 * a node -- open, selected, on screen -- only while its element equals the one it had.
 */
private class FileElement(private val project: Project, private val file: VirtualFile) : StructureViewTreeElement {

    override fun equals(other: Any?): Boolean = other is FileElement && other.file == file

    override fun hashCode(): Int = file.hashCode()

    override fun getValue(): Any = file

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = file.name
        override fun getIcon(unused: Boolean): Icon? = file.fileType.icon
    }

    override fun getChildren(): Array<StructureViewTreeElement> = SymbolElement.children(project, file, emptyList()).toTypedArray()

    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(project, file).navigate(requestFocus)

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true
}

/**
 * One symbol. Its [value] names it by its place in the outline rather than by its offsets, which
 * every edit moves; and it equals another element in the same place, since the view rebuilds its
 * tree on every edit and replaces -- redraws, collapses -- every node whose element is not equal.
 */
private class SymbolElement(
    private val project: Project,
    private val file: VirtualFile,
    val symbol: HostedLanguageFeatures.OutlineSymbol,
    private val path: List<String>,
) : StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = path

    override fun equals(other: Any?): Boolean = other is SymbolElement && other.file == file && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun getAlphaSortKey(): String = symbol.name

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = symbol.name
        override fun getLocationString(): String? = symbol.detail.takeIf { it.isNotBlank() }
        override fun getIcon(unused: Boolean): Icon = CodeBridgeWorkspaceSymbol.icon(symbol.kind)
    }

    override fun getChildren(): Array<SymbolElement> = place(project, file, symbol.children, path).toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val offset = symbol.nameStart.coerceAtMost(document?.textLength ?: symbol.nameStart)
        OpenFileDescriptor(project, file, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    companion object {
        /** The outline's top level for [file], as it stands in the document now. */
        fun children(project: Project, file: VirtualFile, path: List<String>): List<SymbolElement> {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
            val features = project.serviceIfCreated<HostedLanguageFeatures>() ?: return emptyList()
            return place(project, file, features.outlineRequesting(file, document), path)
        }

        /** Two siblings of one name and kind -- overloads -- are told apart by how many came before. */
        fun place(project: Project, file: VirtualFile, symbols: List<HostedLanguageFeatures.OutlineSymbol>, path: List<String>): List<SymbolElement> {
            val seen = HashMap<String, Int>()
            return symbols.map { symbol ->
                val name = "${symbol.kind}:${symbol.name}"
                val index = seen.merge(name, 1, Int::plus)!!
                SymbolElement(project, file, symbol, path + "$name#$index")
            }
        }
    }
}
