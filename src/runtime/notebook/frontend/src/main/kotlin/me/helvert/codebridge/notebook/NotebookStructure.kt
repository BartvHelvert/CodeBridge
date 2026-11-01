package me.helvert.codebridge.notebook

import com.intellij.ide.structureView.FileEditorPositionListener
import com.intellij.ide.structureView.ModelListener
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.Grouper
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.vfs.VirtualFile
import me.helvert.codebridge.icons.Codicons
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon

/**
 * The Structure view and File Structure popup of a notebook: its [NotebookOutline], from the cells as
 * [NotebookEditor] last reported them. The view asks for its tree off the EDT, where the cells are
 * not to be read, hence the copy.
 *
 * In one process only: a split-mode backend runs the Structure view, for the editors it has open,
 * and the notebook's tab is the client's alone (see docs/platform-limitations.md).
 */
internal class NotebookStructure(private val notebook: NotebookEditor) : StructureViewModel, StructureViewModel.ElementInfoProvider {

    /** The cells, and the outline drawn from them once it is asked for. */
    private class Snapshot(val cells: List<NotebookOutline.Cell>) {
        val outline by lazy { NotebookOutline.of(cells) }
    }

    @Volatile private var snapshot = Snapshot(emptyList())
    /** The selected cell's handle. */
    @Volatile private var selected: Int? = null
    private val modelListeners = CopyOnWriteArrayList<ModelListener>()
    private val positionListeners = CopyOnWriteArrayList<FileEditorPositionListener>()

    init {
        notebook.follow(this)
    }

    /** The notebook's cells are now [cells]; on the EDT. */
    fun update(cells: List<NotebookOutline.Cell>) {
        if (cells == snapshot.cells) return
        snapshot = Snapshot(cells)
        modelListeners.forEach { it.onModelChanged() }
    }

    /** The cell with [handle] is now the selected one, or none is; on the EDT. */
    fun select(handle: Int?) {
        if (handle == selected) return
        selected = handle
        positionListeners.forEach { it.onCurrentElementChanged() }
    }

    override fun getRoot(): StructureViewTreeElement = NotebookElement(notebook.getFile(), snapshot.outline.map(::EntryElement))

    /** The first entry of the selected cell. */
    override fun getCurrentEditorElement(): Any? {
        val handle = selected ?: return null
        fun find(entries: List<NotebookOutline.Entry>): NotebookOutline.Entry? =
            entries.firstNotNullOfOrNull { if (it.handle == handle) it else find(it.children) }
        val entry = find(snapshot.outline) ?: return null
        return Place(entry.handle, entry.index)
    }

    override fun addEditorPositionListener(listener: FileEditorPositionListener) {
        positionListeners += listener
    }

    override fun removeEditorPositionListener(listener: FileEditorPositionListener) {
        positionListeners -= listener
    }

    override fun addModelListener(listener: ModelListener) {
        modelListeners += listener
    }

    override fun removeModelListener(listener: ModelListener) {
        modelListeners -= listener
    }

    override fun shouldEnterElement(element: Any?): Boolean = false

    // In the notebook's order: sorted by name, its headings would lose what they stand over.
    override fun getSorters(): Array<Sorter> = Sorter.EMPTY_ARRAY
    override fun getGroupers(): Array<Grouper> = Grouper.EMPTY_ARRAY
    override fun getFilters(): Array<Filter> = Filter.EMPTY_ARRAY

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element is EntryElement && element.entry.children.isEmpty()

    override fun dispose() {
        notebook.unfollow(this)
        modelListeners.clear()
        positionListeners.clear()
    }

    /**
     * An entry's value: the cell it is in, and which of the cell's entries it is, which typing around
     * it leaves as they are. The view rebuilds its tree on every edit and keeps a node -- open,
     * selected -- only while its value stays equal.
     */
    private data class Place(val handle: Int, val index: Int)

    /** The notebook, whose children are the outline's top level. */
    private class NotebookElement(private val file: VirtualFile, private val children: List<EntryElement>) : StructureViewTreeElement {
        override fun getValue(): Any = file
        override fun equals(other: Any?): Boolean = other is NotebookElement && other.file == file
        override fun hashCode(): Int = file.hashCode()
        override fun getPresentation(): ItemPresentation = presentation(file.presentableName, file.fileType.icon)
        override fun getChildren(): Array<StructureViewTreeElement> = children.toTypedArray()
        override fun navigate(requestFocus: Boolean) = Unit
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }

    /** A heading, or a code cell. */
    private inner class EntryElement(val entry: NotebookOutline.Entry) : StructureViewTreeElement {
        private val place = Place(entry.handle, entry.index)

        override fun getValue(): Any = place
        override fun equals(other: Any?): Boolean = other is EntryElement && other.place == place
        override fun hashCode(): Int = place.hashCode()
        override fun getPresentation(): ItemPresentation =
            presentation(entry.text, Codicons.icon(if (entry.code) "code" else "markdown"))

        override fun getChildren(): Array<StructureViewTreeElement> =
            entry.children.map(::EntryElement).toTypedArray()

        override fun navigate(requestFocus: Boolean) = notebook.showCell(entry.handle, entry.line, requestFocus)
        override fun canNavigate(): Boolean = true
        override fun canNavigateToSource(): Boolean = true
    }

    private companion object {
        fun presentation(text: String, icon: Icon?) = object : ItemPresentation {
            override fun getPresentableText(): String = text
            override fun getIcon(unused: Boolean): Icon? = icon
        }
    }
}
