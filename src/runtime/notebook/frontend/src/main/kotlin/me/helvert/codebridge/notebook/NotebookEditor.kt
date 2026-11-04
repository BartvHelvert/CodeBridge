package me.helvert.codebridge.notebook

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.projectId
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.ListLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds

/**
 * A notebook, as the backend keeps it: its cells one under another, a toolbar to run them and add
 * more, and the kernel that runs them. The backend's [NotebookApi.open] flow is followed for as long
 * as the editor is open; what the user does goes back in [calls], one at a time and in order, so the
 * backend sees edits as they were made. Used on the EDT, apart from those calls.
 */
internal class NotebookEditor(
    private val project: Project,
    /** The editor's tab: [target], or the [NotebookFile] standing for it. */
    private val file: VirtualFile,
    /** The notebook's file, on the backend. */
    private val target: VirtualFile,
    private val type: NotebookTypeDto,
) : UserDataHolderBase(), FileEditor, CellView.Owner {
    private val id = UUID.randomUUID().toString()
    private val notebooks = CodeBridgeNotebooks.getInstance(project)
    // Names the notebook to the Structure view, which outlines the file the focus is in: a cell's
    // editor has no file of its own.
    private val root = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            sink[LangDataKeys.STRUCTURE_VIEW_TARGET_FILE_KEY] = Optional.of(file)
        }
    }
    private val banners = JPanel(VerticalLayout(0))
    /** Has the focus when a cell is selected but not edited: VS Code's command mode. */
    // In the order of its components, which a cell inserted or moved between others takes its place
    // in: VerticalLayout keeps the order they were added in.
    // On the editor's background, as VS Code draws a notebook: the cells' frames stand out from it.
    // As wide as the notebook's view, never wider: a cell wraps its lines to fit rather than
    // stretching the notebook to its longest one.
    private val cellList = object : JPanel(ListLayout.vertical(JBUI.scale(2), ListLayout.Alignment.START, ListLayout.GrowPolicy.GROW)), Scrollable {
        override fun getBackground() = CellView.notebookBackground()
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false

        // Over the cells: where a dragged one would go.
        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            paintDropLine(g)
        }
    }.apply {
        border = JBUI.Borders.empty(4, 8, 24, 20)
        isFocusable = true
        accessibleContext.accessibleName = "Cells"
    }
    private val scroll = JBScrollPane(cellList).apply {
        border = JBUI.Borders.empty()
        viewport.background = CellView.notebookBackground()
    }
    private val message = JBLabel("Opening the notebook...", SwingConstants.CENTER).apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val cells = mutableListOf<CellView>()
    /** Above the first cell, where [cellList] starts: each cell has one below it for the rest. */
    private val leading = CellInserter(this) { addCell(it, 0) }
    private var kernels = NotebookEventDto.Kernels(emptyList(), null, detecting = false)
    private var loaded = false
    private var modified = false
    /** Says the file changed elsewhere while the notebook had changes of its own: see [NotebookEventDto.ChangedOnDisk]. */
    private var changedOnDisk: EditorNotificationPanel? = null
    /** The selected cell: the one being edited, or the one command-mode keys act on. */
    private var focused: CellView? = null
    /**
     * Where the selection starts, which ends on [focused]: Shift+Up, Shift+Down and Shift+click select
     * the cells between them as well. Null, or [focused] itself, for that cell alone.
     */
    private var anchor: CellView? = null
    private val listeners = PropertyChangeSupport(this)
    /** The Structure views and File Structure popups showing this notebook. */
    private val structures = CopyOnWriteArrayList<NotebookStructure>()
    private val calls = Channel<suspend NotebookApi.() -> Unit>(Channel.UNLIMITED)
    private val toolbar = ActionManager.getInstance().createActionToolbar("CodeBridge.Notebook", actions(), true)
    @Suppress("unused") // Ends by itself once [calls] is closed.
    private val job: Job

    init {
        toolbar.targetComponent = root
        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.CENTER)
        top.add(banners, BorderLayout.SOUTH)
        root.add(top, BorderLayout.NORTH)
        root.add(message, BorderLayout.CENTER)
        root.accessibleContext.accessibleName = "Notebook ${file.name}"
        notebooks.opened(this)
        // Save All's keys save the notebooks, which are no documents of the IDE's, and the IDE's
        // documents, as anywhere else. Here as well as in [NotebookSaveListener]: a split-mode client
        // saves its documents through the backend, and that listener never hears of it.
        ActionManager.getInstance().getAction(SAVE_ALL)?.shortcutSet?.let { keys ->
            object : DumbAwareAction() {
                override fun actionPerformed(event: AnActionEvent) {
                    FileDocumentManager.getInstance().saveAllDocuments()
                    notebooks.saveAll()
                }
            }.registerCustomShortcutSet(keys, root, this)
        }
        commandKeys()
        // Closing [calls] ends the editor: once what it queued has been made, the flow is let go,
        // which closes the notebook on the backend.
        job = notebooks.scope.launch {
            val following = launch { follow() }
            for (call in calls) runCatching { NotebookApi.getInstance().call() }.onFailure(::lost)
            following.cancel()
        }
    }

    private suspend fun follow() {
        try {
            NotebookApi.getInstance().open(project.projectId(), target.rpcId(), type.viewType, id).collect { event ->
                withContext(Dispatchers.EDT) { apply(event) }
            }
            // Ended without being let go: the backend no longer takes this editor's edits.
            withContext(Dispatchers.EDT) { if (loaded) say(LOST) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lost(failure)
            withContext(Dispatchers.EDT) { say(if (loaded) LOST else "Could not open the notebook: ${failure.message}") }
        }
    }

    private fun lost(failure: Throwable) {
        if (failure is CancellationException) return
        LOG.info("Lost the connection to the notebook ${file.name}", failure)
    }

    private fun call(block: suspend NotebookApi.(projectId: ProjectId) -> Unit) {
        val projectId = project.projectId()
        calls.trySend { block(projectId) }
    }

    /**
     * Asked once [calls] has made every edit queued before it, so the backend -- and the host -- hold
     * the text as typed: a completion is asked about the keystroke that opened it. Not itself queued,
     * so a question that waits on an extension holds up no edit made meanwhile.
     */
    override suspend fun <T> ask(request: suspend NotebookApi.(projectId: ProjectId, editorId: String) -> T): T {
        val projectId = project.projectId()
        val reached = CompletableDeferred<Unit>()
        if (calls.trySend { reached.complete(Unit) }.isSuccess) reached.await()
        return NotebookApi.getInstance().request(projectId, id)
    }

    // --------------------------------------------------------------------------------------- events

    private fun apply(event: NotebookEventDto) {
        when (event) {
            is NotebookEventDto.Loaded -> {
                loaded = true
                cells.forEach(Disposer::dispose)
                cells.clear()
                cellList.removeAll()
                cellList.add(leading)
                event.cells.forEach { insert(cells.size, it) }
                root.remove(message)
                root.add(scroll, BorderLayout.CENTER)
            }
            is NotebookEventDto.Failed -> say(event.message)
            is NotebookEventDto.Spliced -> {
                repeat(event.deleted) {
                    if (event.start < cells.size) {
                        val removed = cells.removeAt(event.start)
                        if (anchor === removed) anchor = null
                        // Before it goes, so its editor's focus is not passed on to another cell's.
                        if (focused === removed) {
                            select(cells.getOrNull(event.start) ?: cells.lastOrNull(), focus = removed.editing || cellList.isFocusOwner)
                        }
                        cellList.remove(removed.component)
                        Disposer.dispose(removed)
                    }
                }
                event.cells.forEachIndexed { offset, cell -> insert(event.start + offset, cell) }
            }
            is NotebookEventDto.Text -> cell(event.handle)?.setText(event.text)
            is NotebookEventDto.Selected -> {
                // Keep editing where the user went while the undo or redo was on its way.
                if (cellList.isFocusOwner || focused == null) {
                    event.handles.lastOrNull()?.let(::cell)?.let { last ->
                        select(last)
                        anchor = cell(event.handles.first())
                        highlight()
                    }
                }
            }
            is NotebookEventDto.Outputs -> cell(event.handle)?.setOutputs(event.outputs)
            is NotebookEventDto.Run -> {
                cell(event.handle)?.setRun(event.run)
                // Interrupt is enabled while a cell runs, now rather than on the toolbar's next update.
                toolbar.updateActionsAsync()
            }
            is NotebookEventDto.Kernels -> kernels = event
            is NotebookEventDto.Dirty -> {
                val was = modified
                modified = event.dirty
                if (was != modified) listeners.firePropertyChange(PropertyChangeEvent(this, FileEditor.getPropModified(), was, modified))
            }
            is NotebookEventDto.ChangedOnDisk -> showChangedOnDisk(event.changed)
            is NotebookEventDto.Problem -> banner(event.message)
            is NotebookEventDto.Problems -> cell(event.handle)?.setProblems(event.problems)
            is NotebookEventDto.Reveal -> cell(event.handle)?.let { cell ->
                select(cell, focus = false)
                cell.reveal(event.line, event.column)
            }
        }
        leading.pinned = loaded && cells.isEmpty()
        cellList.revalidate()
        cellList.repaint()
        outlineChanged()
    }

    private fun insert(index: Int, cell: CellDto): CellView {
        val view = CellView(project, cell, this)
        Disposer.register(this, view)
        val at = index.coerceIn(0, cells.size)
        cells.add(at, view)
        // After [leading].
        cellList.add(view.component, at + 1)
        return view
    }

    private fun cell(handle: Int) = cells.firstOrNull { it.handle == handle }

    private fun say(text: String) {
        message.text = text
        root.remove(scroll)
        root.add(message, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }

    private fun banner(text: String) {
        val panel = EditorNotificationPanel(this, EditorNotificationPanel.Status.Warning)
        panel.text = text
        panel.createActionLabel("Dismiss") {
            banners.remove(panel)
            banners.revalidate()
        }
        banners.add(panel)
        banners.revalidate()
    }

    /**
     * Offers to read the file again, letting go of the notebook's own changes, or to keep them, which
     * the next save writes over the file's, as the IDE asks about a document changed on disk.
     */
    private fun showChangedOnDisk(changed: Boolean) {
        changedOnDisk?.let(banners::remove)
        changedOnDisk = null
        if (changed) {
            val panel = EditorNotificationPanel(this, EditorNotificationPanel.Status.Warning)
            panel.text = "${target.name} changed on disk, and the notebook has changes of its own"
            panel.createActionLabel("Reload from Disk") { call { reload(it, id) } }
            panel.createActionLabel("Keep Changes") {
                banners.remove(panel)
                banners.revalidate()
                banners.repaint()
            }
            changedOnDisk = panel
            banners.add(panel)
        }
        banners.revalidate()
        banners.repaint()
    }

    // -------------------------------------------------------------------------------------- the cells

    override val scrollPane: JComponent get() = scroll

    override fun textChanged(cell: CellView, text: String) {
        val handle = cell.handle
        call { setText(it, id, handle, text) }
        outlineChanged()
    }

    override fun run(cell: CellView) = runCells(listOf(cell.handle))

    override fun runAbove(cell: CellView) = runCells(codeHandles(cells.subList(0, cells.indexOf(cell).coerceAtLeast(0))))

    override fun runBelow(cell: CellView) = runCells(codeHandles(cells.subList(cells.indexOf(cell).coerceAtLeast(0), cells.size)))

    override fun hasCodeAbove(cell: CellView) = cells.takeWhile { it !== cell }.any { it.kind == CellKinds.CODE }

    private fun codeHandles(cells: List<CellView>) = cells.filter { it.kind == CellKinds.CODE }.map { it.handle }

    override fun interrupt(cell: CellView) {
        val handle = cell.handle
        call { interrupt(it, id, listOf(handle)) }
    }

    override fun move(cell: CellView, delta: Int) {
        val from = cells.indexOf(cell)
        if (from < 0 || (from + delta).coerceIn(0, cells.size - 1) == from) return
        val handle = cell.handle
        val editing = cell.editing
        call {
            moveCell(it, id, handle, delta)
            // It is drawn anew where it went, and stays the cell selected, or edited, as in VS Code.
            whenShown({ moved -> moved.handle == handle && moved !== cell }) { moved -> if (editing) moved.focusEditor() else select(moved) }
        }
    }

    // ---------------------------------------------------------------------------------- drag and drop

    /** The cell whose drag handle is dragged, while one is. */
    private var dragHandle: CellView? = null
    /** The cells [dragHandle] drags, top to bottom: it, or the selection it is in, as in VS Code. */
    private var dragged = emptyList<CellView>()
    /** Whether [dragged] are dropped as copies of themselves: Ctrl held, or Alt on macOS, as in VS Code. */
    private var dragCopy = false
    /** The cell whose drag Esc cancelled: the mouse, not let go of yet, drags nothing until it is. */
    private var cancelled: CellView? = null
    /** The gap [dragged] would be dropped in, 0 above the first cell; -1 for none, where nothing would change. */
    private var dropGap = -1
    /** Where the mouse was last, on the screen, while a cell is dragged. */
    private var dragPoint: Point? = null
    /** Scrolls the notebook while a cell is dragged near its top or bottom edge. */
    private val autoscroll = Timer(1000 / 60) { scrollWhileDragging() }
    /** Esc cancels a drag wherever the focus is: in a cell's editor, the editor's own Esc would take it. */
    private val escape = KeyEventDispatcher { event ->
        if (dragHandle == null || event.keyCode != KeyEvent.VK_ESCAPE) return@KeyEventDispatcher false
        if (event.id == KeyEvent.KEY_PRESSED) endDrag(cancel = true)
        true
    }

    override fun inMultipleSelection(cell: CellView) = selection().let { it.size > 1 && cell in it }

    override fun drag(cell: CellView, event: MouseEvent, starting: Boolean) {
        // A new drag, though the last one's letting go was never heard of.
        if (starting) cancelled = null
        if (cancelled === cell) return
        if (dragHandle !== cell) {
            dragHandle = cell
            dragged = selection().takeIf { it.size > 1 && cell in it } ?: listOf(cell)
            autoscroll.start()
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape)
        }
        dragCopy = copies(event)
        dragPoint = event.locationOnScreen
        updateDrop()
    }

    override fun drop(cell: CellView, event: MouseEvent) {
        if (cancelled === cell) {
            cancelled = null
            return
        }
        if (dragHandle !== cell) return
        // Ctrl may have been pressed or let go of since the mouse last moved.
        dragCopy = copies(event)
        dragPoint = event.locationOnScreen
        updateDrop()
        val gap = dropGap
        val cells = dragged
        val copy = dragCopy
        endDrag(cancel = false)
        if (gap < 0) return
        val handles = cells.map { it.handle }
        call { projectId ->
            if (copy) selectWhenShown(copyCellsTo(projectId, id, handles, gap))
            else {
                moveCells(projectId, id, handles, gap)
                // They are drawn anew where they went, and stay the cells selected.
                selectWhenShown(handles, except = cells)
            }
        }
    }

    private fun copies(event: MouseEvent) = if (SystemInfo.isMac) event.isAltDown else event.isControlDown

    /** Stops the drag, dropping nothing; with [cancel], until the mouse is let go of. */
    private fun endDrag(cancel: Boolean) {
        if (cancel) cancelled = dragHandle
        dragHandle = null
        dragged = emptyList()
        dragPoint = null
        dropGap = -1
        autoscroll.stop()
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape)
        cellList.repaint()
    }

    /**
     * Finds the gap the mouse is nearest: above the first cell whose middle is below it. Above or
     * below the notebook's view, it is the gap at that edge, which scrolls; beside the view, there is
     * none, so letting go there changes nothing. Neither does dropping cells next to themselves, or,
     * as copies, between themselves.
     */
    private fun updateDrop() {
        if (dragHandle == null) return
        val screen = dragPoint ?: return
        val viewport = scroll.viewport
        val inView = Point(screen).also { SwingUtilities.convertPointFromScreen(it, viewport) }
        val gap = if (inView.x !in 0 until viewport.width) -1 else {
            val y = SwingUtilities.convertPoint(viewport, 0, inView.y.coerceIn(0, viewport.height - 1), cellList).y
            cells.indexOfFirst { y < it.middle }.let { if (it < 0) cells.size else it }
        }
        val first = cells.indexOf(dragged.first())
        val last = cells.indexOf(dragged.last())
        val unchanged = if (dragCopy) first + 1..last else first..last + 1
        val shown = if (first < 0 || last < 0 || gap in unchanged) -1 else gap
        if (shown == dropGap) return
        dropGap = shown
        cellList.repaint()
    }

    /** Scrolls faster the further into the edge, or past it, the mouse is. */
    private fun scrollWhileDragging() {
        val screen = dragPoint ?: return
        val viewport = scroll.viewport
        val y = Point(screen).also { SwingUtilities.convertPointFromScreen(it, viewport) }.y
        val edge = JBUI.scale(AUTOSCROLL_EDGE)
        val depth = when {
            y < edge -> y - edge
            y > viewport.height - edge -> y - (viewport.height - edge)
            else -> return
        }
        val bar = scroll.verticalScrollBar
        val by = (depth / 2).coerceIn(-JBUI.scale(AUTOSCROLL_MAX), JBUI.scale(AUTOSCROLL_MAX))
        bar.value += if (by == 0) depth.sign else by
        updateDrop()
    }

    /** Where a dragged cell would go: a line in the gap, as VS Code draws its drop indicator. */
    private fun paintDropLine(g: Graphics) {
        if (dropGap < 0) return
        val y = if (dropGap == 0) leading.y + leading.height / 2 else cells.getOrNull(dropGap - 1)?.gapBelow ?: return
        val left = cells.firstOrNull()?.frameLeft ?: return
        val thickness = JBUI.scale(2)
        val g2 = g.create() as Graphics2D
        try {
            g2.color = JBUI.CurrentTheme.Focus.focusColor()
            g2.fillRect(left, y - thickness / 2, cellList.width - cellList.insets.right - left, thickness)
        } finally {
            g2.dispose()
        }
    }

    override fun delete(cell: CellView) {
        val handle = cell.handle
        // As in VS Code, the cell after it is selected in its place, or the one before at the end.
        if (cell === focused) {
            val index = cells.indexOf(cell)
            select(cells.getOrNull(index + 1) ?: cells.getOrNull(index - 1))
        }
        call { deleteCell(it, id, handle) }
    }

    override fun add(after: CellView, kind: Int) = addCell(kind, cells.indexOf(after) + 1)

    override fun focused(cell: CellView) {
        focused = cell
        anchor = null
        highlight()
    }

    override fun unfocused(cell: CellView) = highlight()

    override fun selected(cell: CellView) = select(cell)

    override fun extendSelection(cell: CellView) {
        val start = anchor ?: focused ?: cell
        select(cell)
        anchor = start
        highlight()
    }

    /** The selected cells, top to bottom. */
    private fun selection(): List<CellView> {
        val end = focused?.let(cells::indexOf)?.takeIf { it >= 0 } ?: return emptyList()
        val start = anchor?.let(cells::indexOf)?.takeIf { it >= 0 } ?: end
        return cells.subList(minOf(start, end), maxOf(start, end) + 1).toList()
    }

    /**
     * Selects [cell] without editing it -- or, for null, selects nothing -- and scrolls it into view;
     * with [focus], the notebook takes the focus for the keys of command mode.
     */
    private fun select(cell: CellView?, focus: Boolean = true) {
        focused = cell
        anchor = null
        if (focus) cellList.requestFocusInWindow()
        highlight()
        if (cell != null) SwingUtilities.invokeLater { cellList.scrollRectToVisible(cell.component.bounds) }
    }

    /** Draws the selected cell as such while the notebook has the focus. */
    private fun highlight() {
        val active = cellList.isFocusOwner
        val selection = if (active) selection() else listOfNotNull(focused?.takeIf { it.editing })
        cells.forEach { it.highlight(it in selection, it === focused && it in selection) }
        structures.forEach { it.select(focused?.handle) }
    }

    // ------------------------------------------------------------------------------ structure view

    /** [structure] shows this notebook from now on; it is told the cells once on the EDT. */
    fun follow(structure: NotebookStructure) {
        structures += structure
        notebooks.scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            outlineChanged()
            structure.select(focused?.handle)
        }
    }

    fun unfollow(structure: NotebookStructure) {
        structures -= structure
    }

    /** Tells the structure views the cells as they are now. */
    private fun outlineChanged() {
        if (structures.isEmpty()) return
        val outline = cells.map { NotebookOutline.Cell(it.handle, it.kind, it.text) }
        structures.forEach { it.update(outline) }
    }

    /** Selects the cell with [handle], as an entry of the structure view does, and edits [line] of it with [focus]. */
    fun showCell(handle: Int, line: Int, focus: Boolean) {
        val cell = cell(handle) ?: return
        select(cell, focus)
        if (focus && cell.kind == CellKinds.CODE) cell.reveal(line, 0)
    }

    /** Waits for a cell that is [wanted] to be drawn, as its event may still be on its way, then runs [then] on it. */
    private suspend fun whenShown(wanted: (CellView) -> Boolean, then: (CellView) -> Unit) = withContext(Dispatchers.EDT) {
        repeat(FOCUS_TRIES) {
            cells.firstOrNull(wanted)?.let { then(it); return@withContext }
            delay(FOCUS_DELAY)
        }
    }

    // ------------------------------------------------------------------------------- command mode

    /**
     * The keys of VS Code's command mode, while a cell is selected but not edited: undo and redo of
     * the notebook's cell edits, Delete for the selected cell, Up and Down to select another and
     * Enter to edit it. In a cell's editor the same keys are the editor's, so undo there undoes
     * typing, as VS Code's per-cell undo does.
     */
    private fun commandKeys() {
        cellList.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = highlight()
            override fun focusLost(event: FocusEvent) = highlight()
        })
        cellList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                cellList.requestFocusInWindow()
            }
        })
        commandKey(ActionManager.getInstance().getAction(IdeActions.ACTION_UNDO)?.shortcutSet) { replay(NotebookApi::undo) }
        commandKey(ActionManager.getInstance().getAction(IdeActions.ACTION_REDO)?.shortcutSet) { replay(NotebookApi::redo) }
        commandKey(CustomShortcutSet(KeyEvent.VK_DELETE)) { focused?.let(::delete) }
        commandKey(CustomShortcutSet(KeyEvent.VK_UP)) { step(-1) }
        commandKey(CustomShortcutSet(KeyEvent.VK_DOWN)) { step(1) }
        commandKey(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK))) { stretch(-1) }
        commandKey(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK))) { stretch(1) }
        commandKey(CustomShortcutSet(KeyEvent.VK_ENTER)) { focused?.focusEditor() }
    }

    /** Stretches the selection to the cell [delta] places past where it ends. */
    private fun stretch(delta: Int) {
        val index = focused?.let { cells.indexOf(it) + delta } ?: return
        cells.getOrNull(index)?.let(::extendSelection)
    }

    /** [keys] do [action] in command mode, and its menu item shows them. */
    private fun commandKey(keys: ShortcutSet?, action: CellAction) {
        if (keys == null) return
        // Only sets the shortcut the menu item shows: with no component, no key is bound.
        action.registerCustomShortcutSet(keys, null)
        commandKey(keys) {
            val cells = selection()
            if (cells.isNotEmpty() && action.enabled(cells)) action.perform(cells)
        }
    }

    private fun commandKey(keys: ShortcutSet?, perform: () -> Unit) {
        if (keys == null) return
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = cellList.isFocusOwner
            }

            override fun actionPerformed(event: AnActionEvent) = perform()
        }.registerCustomShortcutSet(keys, cellList, this)
    }

    /** Selects the cell [delta] places from the selected one, or the first when none is. */
    private fun step(delta: Int) {
        val index = focused?.let { cells.indexOf(it) + delta } ?: 0
        cells.getOrNull(index.coerceIn(0, cells.size - 1))?.let(::select)
    }

    /** Undoes or redoes the notebook's last cell edit; its flow restores selection after the edits. */
    private fun replay(step: suspend NotebookApi.(ProjectId, String) -> List<Int>) {
        call { step(it, id) }
    }

    private fun runCells(handles: List<Int>) {
        call {
            if (!run(it, id, handles)) withContext(Dispatchers.EDT) { chooseKernel { runCells(handles) } }
        }
    }

    /**
     * Selects cells [handles] once they are drawn, as VS Code selects the cells an edit made: drawn
     * anew, when they are not [except], the views they had.
     */
    private suspend fun selectWhenShown(handles: List<Int>, except: List<CellView> = emptyList()) {
        if (handles.isEmpty()) return
        whenShown({ it.handle == handles.last() && it !in except }) { last ->
            select(last)
            anchor = cell(handles.first())
            highlight()
        }
    }

    /**
     * Adds a cell at [index]: by default after the selected one, or at the end. The new one gets the
     * focus once it arrives.
     */
    private fun addCell(kind: Int, index: Int = focused?.let { cells.indexOf(it) + 1 } ?: cells.size) {
        val before = cells.map { it.handle }.toSet()
        call {
            insertCell(it, id, index, kind)
            // The new cell's event is already on its way back; focus it once it has been drawn.
            whenShown({ cell -> cell.handle !in before }) { cell -> cell.focusEditor() }
        }
    }

    // ---------------------------------------------------------------------------------------- kernels

    /** Lets the user pick a kernel, then runs [then]; says why not when there is none to pick. */
    private fun chooseKernel(then: () -> Unit = {}) {
        if (kernels.kernels.isEmpty()) {
            banner(
                if (kernels.detecting) "Still looking for kernels that can run this notebook; try again in a moment."
                else "No installed extension provides a kernel for ${type.displayName}. Install one, such as Jupyter, to run cells."
            )
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(kernels.kernels)
            .setTitle("Select Kernel")
            .setRenderer(listCellRenderer<KernelDto> {
                text(value.label)
                value.description?.let { text(it) { foreground = greyForeground } }
                toolTipText = value.detail
            })
            .setItemChosenCallback { kernel ->
                call { selectKernel(it, id, kernel.id) }
                then()
            }
            .createPopup()
            .showInCenterOf(root)
    }

    private fun actions() = DefaultActionGroup().apply {
        // Named in the toolbar, as VS Code names them: an icon alone did not say what it adds.
        add(action("Code", AllIcons.General.Add, "Add a code cell below the selected one", showText = true) { addCell(CellKinds.CODE) })
        add(action("Markdown", AllIcons.General.Add, "Add a markdown cell below the selected one", showText = true) { addCell(CellKinds.MARKUP) })
        add(Separator.getInstance())
        add(action("Run All", AllIcons.Actions.RunAll) { runCells(codeHandles(cells)) })
        add(object : DumbAwareAction("Interrupt", "Stop the cells that are running", AllIcons.Actions.Suspend) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = cells.any { it.run.state != RunState.IDLE }
            }

            override fun actionPerformed(event: AnActionEvent) {
                val busy = cells.filter { it.run.state != RunState.IDLE }.map { it.handle }
                call { interrupt(it, id, busy) }
            }
        })
        add(action("Clear All Outputs", AllIcons.Actions.ClearCash) { call { clearOutputs(it, id, null) } })
        add(Separator.getInstance())
        add(object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                val selected = kernels.kernels.firstOrNull { it.id == kernels.selected }
                event.presentation.text = selected?.label ?: if (kernels.detecting) "Detecting Kernels..." else "Select Kernel"
                event.presentation.description = "The kernel that runs this notebook's cells"
                event.presentation.icon = AllIcons.Nodes.Console
                event.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }

            override fun actionPerformed(event: AnActionEvent) = chooseKernel()
        })
    }

    private fun action(text: String, icon: Icon, description: String? = null, showText: Boolean = false, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                if (showText) event.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }

            override fun actionPerformed(event: AnActionEvent) = perform()
        }

    // -------------------------------------------------------------------------------------- cell menu

    /**
     * One of the cell menu's actions. It acts on the cell whose toolbar it was chosen from -- and on
     * the cells selected with it, when that one is among them, as VS Code's do -- or, in command
     * mode, on the selected cells.
     */
    private open inner class CellAction(
        text: String,
        val enabled: (List<CellView>) -> Boolean = { true },
        val perform: (List<CellView>) -> Unit,
    ) : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(event: AnActionEvent) {
            val cells = targets(event)
            event.presentation.isEnabled = cells.isNotEmpty() && enabled(cells)
        }

        override fun actionPerformed(event: AnActionEvent) {
            val cells = targets(event)
            if (cells.isNotEmpty()) perform(cells)
        }
    }

    /** A [CellAction] shown only for cells it would do something to: Change Cell to Markdown, or to Code. */
    private inner class HiddenCellAction(text: String, enabled: (List<CellView>) -> Boolean, perform: (List<CellView>) -> Unit) :
        CellAction(text, enabled, perform) {
        override fun update(event: AnActionEvent) {
            super.update(event)
            event.presentation.isVisible = event.presentation.isEnabled
        }
    }

    private fun targets(event: AnActionEvent): List<CellView> {
        val cell = event.getData(CellView.DATA_KEY) ?: return selection()
        return selection().takeIf { cell in it } ?: listOf(cell)
    }

    private fun copy(cells: List<CellView>, cut: Boolean) {
        val handles = cells.map { it.handle }
        // Their text goes on the system clipboard too, as VS Code's Copy Cell puts it there.
        CopyPasteManager.getInstance().setContents(StringSelection(cells.joinToString("\n\n") { it.text }))
        notebooks.copiedCells = true
        // As with Delete Cell, the cell after them is selected in their place, or the one before at the end.
        if (cut && focused in cells) select(this.cells.getOrNull(this.cells.indexOf(cells.last()) + 1) ?: this.cells.getOrNull(this.cells.indexOf(cells.first()) - 1))
        call { copyCells(it, id, handles, cut) }
    }

    /** Pastes the copied cells below [cells]. */
    private fun paste(cells: List<CellView>) {
        val index = this.cells.indexOf(cells.last()) + 1
        call { selectWhenShown(pasteCells(it, id, index)) }
    }

    /** [cell] and the one [delta] places from it, in order, when that one is there and of the same kind. */
    private fun withNeighbour(cell: CellView, delta: Int): List<CellView>? {
        val other = cells.getOrNull(cells.indexOf(cell) + delta)?.takeIf { it.kind == cell.kind } ?: return null
        return if (delta < 0) listOf(other, cell) else listOf(cell, other)
    }

    private fun join(cells: List<CellView>) {
        val handles = cells.map { it.handle }
        call { projectId -> joinCells(projectId, id, handles)?.let { selectWhenShown(listOf(it)) } }
    }

    private fun changeKind(cells: List<CellView>, kind: Int) {
        val handles = cells.filter { it.kind != kind }.map { it.handle }
        call { projectId -> selectWhenShown(handles.mapNotNull { changeCellKind(projectId, id, it, kind) }) }
    }

    private fun insert(cells: List<CellView>, kind: Int, below: Boolean) =
        if (below) addCell(kind, this.cells.indexOf(cells.last()) + 1) else addCell(kind, this.cells.indexOf(cells.first()))

    /** As VS Code's `notebook.cellToolbarLocation` does, for each notebook type. */
    override val toolbarOnLeft: Boolean get() = PropertiesComponent.getInstance().getBoolean(toolbarKey())

    private fun toolbarKey() = "CodeBridge.notebook.cellToolbarLeft.${type.viewType}"

    /** Lays the cells out again: their toolbars moved. */
    fun relayout() {
        cells.forEach { it.component.revalidate() }
        cellList.repaint()
    }

    /** VS Code's cell menu, behind each cell's "..." button, with the keys its actions have in command mode. */
    override val cellMenu: ActionGroup = DefaultActionGroup("More Actions", true).apply {
        templatePresentation.icon = AllIcons.Actions.More
        // A plain "...", as VS Code's, without the toolbar's drop-down mark.
        templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        val ide = ActionManager.getInstance()
        val cut = CellAction("Cut Cell") { copy(it, cut = true) }
        val copy = CellAction("Copy Cell") { copy(it, cut = false) }
        val paste = CellAction("Paste Cell", { notebooks.copiedCells }, ::paste)
        commandKey(ide.getAction(IdeActions.ACTION_CUT)?.shortcutSet, cut)
        commandKey(ide.getAction(IdeActions.ACTION_COPY)?.shortcutSet, copy)
        commandKey(ide.getAction(IdeActions.ACTION_PASTE)?.shortcutSet, paste)
        addAll(cut, copy, paste)
        add(Separator.getInstance())
        add(DefaultActionGroup("Insert Cell", true).apply {
            val codeAbove = CellAction("Insert Code Cell Above") { insert(it, CellKinds.CODE, below = false) }
            val codeBelow = CellAction("Insert Code Cell Below") { insert(it, CellKinds.CODE, below = true) }
            commandKey(CustomShortcutSet(KeyEvent.VK_A), codeAbove)
            commandKey(CustomShortcutSet(KeyEvent.VK_B), codeBelow)
            addAll(
                codeAbove,
                codeBelow,
                CellAction("Insert Markdown Cell Above") { insert(it, CellKinds.MARKUP, below = false) },
                CellAction("Insert Markdown Cell Below") { insert(it, CellKinds.MARKUP, below = true) },
            )
        })
        add(Separator.getInstance())
        val toMarkdown = HiddenCellAction("Change Cell to Markdown", { cells -> cells.any { it.kind == CellKinds.CODE } }) { changeKind(it, CellKinds.MARKUP) }
        val toCode = HiddenCellAction("Change Cell to Code", { cells -> cells.any { it.kind == CellKinds.MARKUP } }) { changeKind(it, CellKinds.CODE) }
        commandKey(CustomShortcutSet(KeyEvent.VK_M), toMarkdown)
        commandKey(CustomShortcutSet(KeyEvent.VK_Y), toCode)
        addAll(toMarkdown, toCode)
        add(CellAction("Join With Previous Cell", { withNeighbour(it.first(), -1) != null }) { join(withNeighbour(it.first(), -1)!!) })
        add(CellAction("Join With Next Cell", { withNeighbour(it.last(), 1) != null }) { join(withNeighbour(it.last(), 1)!!) })
        add(CellAction("Join Selected Cells", { cells -> cells.size > 1 && cells.all { it.kind == cells[0].kind } }, ::join))
        val copyDown = CellAction("Copy Cell Down") { cells ->
            val handles = cells.map { it.handle }
            call { selectWhenShown(duplicateCells(it, id, handles)) }
        }
        commandKey(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_DOWN_MASK)), copyDown)
        add(copyDown)
        add(Separator.getInstance())
        add(object : ToggleAction("Show Cell Line Numbers"), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(event: AnActionEvent) = targets(event).firstOrNull()?.lineNumbers == true
            override fun setSelected(event: AnActionEvent, state: Boolean) = targets(event).forEach { it.lineNumbers = state }
        })
        add(object : DumbAwareAction("Toggle Cell Toolbar Position") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) {
                PropertiesComponent.getInstance().setValue(toolbarKey(), !toolbarOnLeft)
                notebooks.toolbarMoved()
            }
        })
    }


    // ------------------------------------------------------------------------------------ FileEditor

    /** A save is queued and not yet made: another would write the same. */
    private val saving = AtomicBoolean()

    /** Writes the notebook through its extension's serializer. */
    fun save() {
        if (!saving.compareAndSet(false, true)) return
        LOG.info("Saving the notebook ${file.name}")
        call {
            saving.set(false)
            save(it, id)
        }
    }

    override fun getStructureViewBuilder(): StructureViewBuilder = object : TreeBasedStructureViewBuilder() {
        override fun createStructureViewModel(editor: Editor?): StructureViewModel = NotebookStructure(this@NotebookEditor)
        override fun isRootNodeShown(): Boolean = false
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent? = null
    override fun getName(): String = type.displayName
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = modified
    override fun isValid(): Boolean = target.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = listeners.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = listeners.removePropertyChangeListener(listener)

    override fun dispose() {
        endDrag(cancel = false)
        notebooks.closed(this)
        calls.close()
    }

    companion object {
        private val LOG = Logger.getInstance(NotebookEditor::class.java)
        private const val FOCUS_TRIES = 20
        private val FOCUS_DELAY = 50.milliseconds
        /** How near the notebook's top or bottom edge a dragged cell scrolls it, in px. */
        private const val AUTOSCROLL_EDGE = 40
        /** The most a dragged cell scrolls the notebook by at a time, in px. */
        private const val AUTOSCROLL_MAX = 30

        private const val LOST = "Lost the connection to the notebook. Close and reopen it to go on editing."

        /** File | Save All's action id. */
        private const val SAVE_ALL = "SaveAll"
    }
}
