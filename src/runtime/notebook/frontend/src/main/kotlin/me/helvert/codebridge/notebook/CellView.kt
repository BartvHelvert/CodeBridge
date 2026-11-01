package me.helvert.codebridge.notebook

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.SoftWrapChangeListener
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.platform.project.ProjectId
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.hover.HoverListener
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.helvert.codebridge.icons.Codicons
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.Point
import java.awt.dnd.DragSource
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * One cell, drawn as VS Code draws it. A code cell is an IDE editor over its text, as tall as that
 * text, in a rounded frame, with its run state and outputs below. A markdown cell is its text
 * rendered, with no frame, until it is edited -- a double-click, or Enter -- and rendered again once
 * the editing stops. The selected cell has a bar beside it; its toolbar shows on it while it is
 * the one the selection ends on, or the mouse is over it, with VS Code's cell menu behind its "..."
 * button. Below it, a [CellInserter] adds a cell after it. Left of its frame is its drag handle,
 * which moves it to between other cells, as VS Code's does.
 *
 * Typing is sent to the backend as it happens; text another editor typed arrives with [setText].
 * Used on the EDT only.
 */
internal class CellView(private val project: Project, cell: CellDto, private val owner: Owner) : Disposable {
    interface Owner {
        fun textChanged(cell: CellView, text: String)
        /** Runs a code cell. */
        fun run(cell: CellView)
        /** Runs the code cells above [cell], not it, as VS Code's Execute Above Cells does. */
        fun runAbove(cell: CellView)
        /** Runs [cell] and the code cells below it, as VS Code's Execute Cell and Below does. */
        fun runBelow(cell: CellView)
        /** Whether there is a code cell above [cell] to run. */
        fun hasCodeAbove(cell: CellView): Boolean
        fun interrupt(cell: CellView)
        fun move(cell: CellView, delta: Int)
        /**
         * [cell] is dragged by its handle -- with the other cells selected with it -- and the mouse is
         * where [event] says, with the keys it says held; [starting] for the first move of a drag.
         */
        fun drag(cell: CellView, event: MouseEvent, starting: Boolean)
        /** [cell] was let go of, as [event] says, after [drag]: it goes where it was dragged to, if anywhere. */
        fun drop(cell: CellView, event: MouseEvent)
        /** Whether [cell] is one of several selected, which its drag handle drags together. */
        fun inMultipleSelection(cell: CellView): Boolean
        fun delete(cell: CellView)
        /** Adds a cell of [kind] right after [cell]. */
        fun add(after: CellView, kind: Int)
        /** The cell's editor got the focus. */
        fun focused(cell: CellView)
        /** The cell's editor lost the focus. */
        fun unfocused(cell: CellView)
        /** The cell was selected without editing it: Esc in its editor, or a click beside it. */
        fun selected(cell: CellView)
        /** The selection was stretched from where it starts to [cell]: a Shift+click. */
        fun extendSelection(cell: CellView)
        /** What the toolbar's "..." button offers: VS Code's cell menu, for the cell [DATA_KEY] names. */
        val cellMenu: ActionGroup
        /** Whether the cells' toolbars sit at the left of their frames, rather than at the right. */
        val toolbarOnLeft: Boolean
        /** The scroll pane the notebook scrolls in, which a wheel turned over a cell scrolls. */
        val scrollPane: JComponent
        /** Asks the backend about the notebook, once every edit made before has reached it. */
        suspend fun <T> ask(request: suspend NotebookApi.(projectId: ProjectId, editorId: String) -> T): T
    }

    val handle: Int = cell.handle
    val kind: Int = cell.kind
    var run: RunDto = cell.run
        private set

    private val document = EditorFactory.getInstance().createDocument(cell.text)
    private val editor = EditorFactory.getInstance().createEditor(document, project) as EditorEx
    /** A code cell's language features. */
    private val undo = CellUndo(editor, this)
    private val language = if (cell.kind == CellKinds.CODE) CellLanguage(project, this, editor) else null
    private val status = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.empty(4, 8, 2, 12)
    }
    /** While the cell runs, when it started, by this machine's clock. */
    private var runningSince = 0L
    /** Counts the [status] up while the cell runs. */
    private val ticker = Timer(100) { status.text = elapsed() }
    /** A code cell's execution order, `[*]` while it is queued or runs, at the bottom of its [runColumn]. */
    private val order = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = SwingConstants.CENTER
        // Level with the editor's last line, inside the frame's padding.
        border = JBUI.Borders.emptyBottom(6)
    }
    /** The outputs drawn, each with what its view lives as long as. Before [init], which shows the first. */
    private var shownOutputs = emptyList<ShownOutput>()
    private val outputs = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 2, 8)
    }
    /** What the cell's text is shown in: its editor, framed, or -- a markdown cell not being edited -- its rendering. */
    private val frame = Frame()
    private val editorPane = JPanel(BorderLayout()).apply {
        isOpaque = false
        // Inside the frame's rounded corners.
        border = JBUI.Borders.empty(6, 10)
        add(editor.component, BorderLayout.CENTER)
    }
    private val rendered = if (cell.kind == CellKinds.MARKUP) CellMarkdown.pane() else null
    /** A markdown cell is being edited: its editor shows rather than its rendering. */
    private var editingMarkup = false
    private var selected = false
    /** The selection ends on this cell: it shows its toolbar. */
    private var current = false
    private var hovered = false
    private val toolbar = Frame(floating = true)
    private val inserter = CellInserter(this) { owner.add(this, it) }
    /** A code cell's outline, animated while the cell runs. */
    private val running = RunningOutline()

    /**
     * The cell. It doesn't give the IDE's actions its editor as the file editor: in a JetBrains Client
     * they hand that to the backend, which has never seen it and fails on it -- Undo does.
     */
    val component: JPanel = object : JPanel(), UiDataProvider {
        // The toolbar is drawn over the cell.
        override fun isOptimizedDrawingEnabled() = false

        // The cell its toolbar's actions act on.
        override fun uiDataSnapshot(sink: DataSink) {
            sink[DATA_KEY] = this@CellView
        }
    }
    /** What [toolbar] shows; after [component], its target. */
    private val actions = actionToolbar()
    /** A code cell's Run Cell button, Stop Cell while it runs. */
    private val runButton = if (kind == CellKinds.CODE) runToolbar() else null
    /**
     * Left of the frame, as in VS Code: a code cell's run button at the top, shown while the cell is
     * selected, hovered or busy, and its [order] at the bottom. Every cell has it, so that cells line up.
     */
    private val runColumn = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.width = JBUI.scale(RUN_COLUMN) }
    }.apply {
        isOpaque = false
        // Centred between the selected cell's bar and the frame, not in the column, which also takes the
        // gutter's room right of the bar.
        border = JBUI.Borders.emptyRight(GUTTER - BAR_X - BAR_WIDTH)
        if (runButton != null) {
            // The button's room stays while it is hidden: the cell doesn't change height under the mouse.
            add(object : JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)) {
                override fun getPreferredSize(): Dimension = runButton.component.preferredSize
            }.apply {
                isOpaque = false
                add(runButton.component)
            }, BorderLayout.NORTH)
            add(order, BorderLayout.SOUTH)
        }
    }
    /** The run column and the frame beside it. */
    private val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(runColumn, BorderLayout.WEST)
        add(frame, BorderLayout.CENTER)
    }
    private val body = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!selected) return
            // The selected cell's bar, beside its text.
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                val bar = JBUI.scale(BAR_WIDTH).toFloat()
                g2.fill(RoundRectangle2D.Float(JBUI.scale(BAR_X).toFloat(), row.y.toFloat(), bar, (height - row.y).toFloat(), bar, bar))
            } finally {
                g2.dispose()
            }
        }
    }

    init {
        editor.putUserData(KEY, this)
        CellTypedHandler.install()
        language?.let { Disposer.register(this, it) }
        configure(cell)
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!applying) owner.textChanged(this@CellView, document.text)
                // At once: still a line short when Enter scrolls to the caret, the editor would show
                // only the new, empty line until the cell grew.
                resizeEditor()
                updateHeight()
            }
        }, this)
        editor.softWrapModel.addSoftWrapChangeListener(object : SoftWrapChangeListener {
            override fun softWrapsChanged() = updateHeight()
            override fun recalculationEnds() = updateHeight()
        })
        editor.component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateHeight()
        })
        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = owner.focused(this@CellView)
            override fun focusLost(event: FocusEvent) {
                owner.unfocused(this@CellView)
                // A markdown cell is rendered again once the focus has gone elsewhere -- another cell,
                // the notebook's command mode -- but not to another window, or within the cell.
                val to = event.oppositeComponent
                if (kind == CellKinds.MARKUP && !event.isTemporary && to != null && !SwingUtilities.isDescendingFrom(to, component)) {
                    SwingUtilities.invokeLater { stopEditing() }
                }
            }
        })
        // A click beside the editor, on the cell's frame or the outputs, selects the cell without editing it.
        val select = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = when {
                event.isShiftDown -> owner.extendSelection(this@CellView)
                // The selection is kept for a drag of it; it is left to this cell alone once the mouse
                // is let go of without one.
                event.component === component && onHandle(event.point) && owner.inMultipleSelection(this@CellView) -> Unit
                else -> owner.selected(this@CellView)
            }
        }
        component.addMouseListener(select)
        rendered?.addMouseListener(select)
        // A double-click on a markdown cell's rendering edits it. Heard from the presses and releases
        // rather than `mouseClicked`, which Swing drops when anything moved between them: on CI the
        // second of two double-clicks did not edit the cell.
        if (rendered != null) object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                focusEditor()
                return true
            }
        }.installOn(rendered)
        dragHandle()
        // The rendering's height follows its width: wrapped again, it is laid out again.
        frame.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (rendered != null && !editingMarkup) rendered.revalidate()
            }
        })
        // Shift+Enter is left to the editor, a new line, as in a chat's message box.
        enterShortcut(InputEvent.CTRL_DOWN_MASK) {
            if (kind == CellKinds.CODE) owner.run(this) else quitEdit()
        }
        escapeKey()
        // Undo and Redo in a cell take back its typing, kept by the cell: in a JetBrains Client the
        // IDE's own actions do not reach the cell's document. With nothing left to take back, the keys
        // do nothing, rather than fall through to those actions.
        undoKey(IdeActions.ACTION_UNDO, CellUndo::undo)
        undoKey(IdeActions.ACTION_REDO, CellUndo::redo)

        toolbar.add(actions.component, BorderLayout.CENTER)
        toolbar.isVisible = false
        // Not over the strip below the cell: that offers to add a cell, not to act on this one.
        object : HoverListener() {
            override fun mouseEntered(component: Component, x: Int, y: Int) = hover(y < inserter.y)
            override fun mouseMoved(component: Component, x: Int, y: Int) = hover(y < inserter.y)
            override fun mouseExited(component: Component) = hover(false)
        }.addTo(component, this)

        body.isOpaque = false
        body.border = JBUI.Borders.empty(TOP_GAP, GUTTER, 2, 0)
        body.add(row, BorderLayout.CENTER)
        // A code cell's run status sits at the top right of its outputs, out of their way. The
        // outputs line up with the frame, not the run column.
        body.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(RUN_COLUMN)
            add(outputs, BorderLayout.CENTER)
            if (kind == CellKinds.CODE) add(status, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        component.isOpaque = false
        component.layout = OverlayLayout()
        // First, to be drawn last: over the frame.
        component.add(toolbar)
        component.add(body)
        component.add(inserter)
        show()
        setOutputs(cell.outputs)
        setRun(cell.run)
        updateHeight()
    }

    /** Where the drag handle was pressed, until the cell is let go of. */
    private var pressedAt: Point? = null
    private var dragging = false

    /**
     * Pressed left of the frame -- on the gutter or the run column, not its run button -- and moved a
     * little, the cell is dragged, and the cells selected with it. The run column has no mouse
     * listeners of its own, so the cell hears of the mouse there.
     */
    private fun dragHandle() {
        val drag = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                pressedAt = event.point.takeIf { SwingUtilities.isLeftMouseButton(event) && onHandle(it) }
                dragging = false
            }

            override fun mouseDragged(event: MouseEvent) {
                val start = pressedAt ?: return
                if (!dragging && start.distance(event.point) < DragSource.getDragThreshold()) return
                val starting = !dragging
                dragging = true
                owner.drag(this@CellView, event, starting)
            }

            override fun mouseReleased(event: MouseEvent) {
                if (pressedAt == null) return
                pressedAt = null
                if (dragging) {
                    dragging = false
                    owner.drop(this@CellView, event)
                } else if (!event.isShiftDown && owner.inMultipleSelection(this@CellView)) owner.selected(this@CellView)
            }

            override fun mouseMoved(event: MouseEvent) {
                component.cursor = if (onHandle(event.point)) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) else null
            }
        }
        component.addMouseListener(drag)
        component.addMouseMotionListener(drag)
    }

    /** Whether [point], in the cell, is on its drag handle: left of its frame, above the strip below it. */
    private fun onHandle(point: Point) = point.y < inserter.y && point.x < SwingUtilities.convertPoint(row, frame.x, 0, component).x

    /** Half way down the cell, not counting the strip below it, in the notebook's list of cells. */
    val middle: Int get() = component.y + inserter.y / 2

    /** The middle of the strip below the cell, in the notebook's list of cells: where a cell dropped after it goes. */
    val gapBelow: Int get() = component.y + inserter.y + inserter.height / 2

    /** Where the cell's frame starts, right of its gutter and run column, in the notebook's list of cells. */
    val frameLeft: Int get() = component.x + JBUI.scale(GUTTER + RUN_COLUMN)

    private fun configure(cell: CellDto) {
        val extension = cell.fileExtension ?: if (cell.kind == CellKinds.MARKUP) ".md" else ".txt"
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, LightVirtualFile("cell$extension"))
        editor.settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isUseSoftWraps = true
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isAdditionalPageAtBottom = false
            isRightMarginShown = false
            isCaretRowShown = false
        }
        editor.setVerticalScrollbarVisible(false)
        editor.setHorizontalScrollbarVisible(false)
        editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        // The notebook scrolls, not each cell: a wheel turned over one is handed on to it.
        editor.scrollPane.isWheelScrollingEnabled = false
        editor.scrollPane.addMouseWheelListener { event ->
            owner.scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(event.component, event, owner.scrollPane))
        }
        editor.setBorder(JBUI.Borders.empty())
        editor.backgroundColor = cellBackground()
        @Suppress("UsePropertyAccessSyntax") // No getter: the property does not resolve.
        editor.gutterComponentEx.setPaintBackground(false)
    }

    /** Shows the cell's editor, or its rendering. */
    private fun show() {
        val shown = rendered?.takeUnless { editingMarkup }
        frame.removeAll()
        frame.framed = shown == null
        // First, to be drawn last: over the editor.
        if (kind == CellKinds.CODE) frame.add(running)
        if (shown != null) {
            shown.text = CellMarkdown.toHtml(document.text)
            frame.add(shown, BorderLayout.CENTER)
        } else frame.add(editorPane, BorderLayout.CENTER)
        component.revalidate()
        component.repaint()
    }

    private fun actionToolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        // A code cell's run button is in its run column instead.
        if (kind == CellKinds.MARKUP) group.add(object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.icon = if (editingMarkup) AllIcons.Actions.Checked else AllIcons.Actions.Edit
                event.presentation.text = if (editingMarkup) "Stop Editing Cell" else "Edit Cell"
            }

            override fun actionPerformed(event: AnActionEvent) = if (editingMarkup) quitEdit() else focusEditor()
        })
        // As in VS Code, a code cell's toolbar starts with running the cells around it.
        if (kind == CellKinds.CODE) {
            group.add(object : DumbAwareAction("Run Above Cells", null, Codicons.bundled("run-above")) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = owner.hasCodeAbove(this@CellView)
                }

                override fun actionPerformed(event: AnActionEvent) = owner.runAbove(this@CellView)
            })
            group.add(action("Run Cell and Below", Codicons.bundled("run-below")!!) { owner.runBelow(this) })
        }
        group.add(action("Move Cell Up", AllIcons.Actions.MoveUp) { owner.move(this, -1) })
        group.add(action("Move Cell Down", AllIcons.Actions.MoveDown) { owner.move(this, 1) })
        group.add(owner.cellMenu)
        group.add(action("Delete Cell", AllIcons.General.Delete) { owner.delete(this) })
        val toolbar = ActionManager.getInstance().createActionToolbar("CodeBridge.NotebookCell", group, true)
        toolbar.targetComponent = component
        toolbar.component.isOpaque = false
        // Each button already has 2 px beside it and 1 px above and below: this evens the space
        // around a hovered button to 3 px on every side.
        toolbar.component.border = JBUI.Borders.empty(2, 1)
        return toolbar
    }

    private fun runToolbar(): ActionToolbar {
        val group = DefaultActionGroup(object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                val busy = run.state != RunState.IDLE
                event.presentation.icon = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                event.presentation.text = if (busy) "Stop Cell" else "Run Cell"
            }

            override fun actionPerformed(event: AnActionEvent) =
                if (run.state != RunState.IDLE) owner.interrupt(this@CellView) else owner.run(this@CellView)
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("CodeBridge.NotebookCellRun", group, true)
        toolbar.targetComponent = component
        toolbar.component.isOpaque = false
        // Level with the frame's top padding, centred in the column.
        toolbar.component.border = JBUI.Borders.empty(3, 0, 0, 0)
        toolbar.component.isVisible = false
        return toolbar
    }

    private fun action(text: String, icon: Icon, perform: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(event: AnActionEvent) = perform()
    }

    private fun enterShortcut(modifiers: Int, perform: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) = perform()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifiers)), editor.contentComponent, this)
    }

    /**
     * Esc stops editing the cell, leaving it selected, as VS Code's `notebook.cell.quitEditing` does:
     * only when Esc has nothing else to do there -- no lookup, no selection, a single caret. Otherwise
     * the IDE's Esc does what it does. On the editor rather than as an Esc handler of the IDE's: once
     * the focus has gone from one cell's editor to another's, that handler was given the first one.
     */
    private fun escapeKey() {
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = !editor.isDisposed &&
                    LookupManager.getActiveLookup(editor) == null &&
                    editor.caretModel.caretCount == 1 &&
                    !editor.selectionModel.hasSelection()
            }

            override fun actionPerformed(event: AnActionEvent) = quitEdit()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyEvent.VK_ESCAPE), editor.contentComponent, this)
    }

    private fun undoKey(actionId: String, perform: (CellUndo) -> Boolean) {
        val keys = ActionManager.getInstance().getAction(actionId)?.shortcutSet ?: return
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = !editor.isDisposed
            }

            override fun actionPerformed(event: AnActionEvent) {
                perform(undo)
            }
        }.registerCustomShortcutSet(keys, editor.contentComponent, this)
    }

    /** Draws the cell as one of those selected, or not; [current] is the one the selection ends on. */
    fun highlight(on: Boolean, current: Boolean = on) {
        selected = on
        this.current = current
        updateToolbar()
        component.repaint()
    }

    private fun hover(on: Boolean) {
        if (hovered == on) return
        hovered = on
        updateToolbar()
    }

    private fun updateToolbar() {
        val visible = current || hovered
        runButton?.component?.isVisible = visible || run.state != RunState.IDLE
        if (toolbar.isVisible == visible) return
        toolbar.isVisible = visible
        component.revalidate()
        component.repaint()
    }

    /** Whether an [updateHeight] is already on its way. */
    private var heightQueued = false

    /**
     * Makes the cell as tall as its text, soft wraps counted, so the notebook rather than the cell
     * scrolls. Later rather than at once: resizing the editor wraps its lines again, which asks for
     * another update, and done at once that went round until the stack ran out.
     */
    private fun updateHeight() {
        if (heightQueued) return
        heightQueued = true
        SwingUtilities.invokeLater {
            heightQueued = false
            resizeEditor()
        }
    }

    /**
     * Gives the editor the height of its text. Laid out before the next paint, the editor's view is
     * then moved back up to where its whole text shows. Its size, not its preferred size, tells
     * whether it already has that height: the editor's preferred size follows its text by itself,
     * and the cell, not laid out again, stayed a line short until something else laid it out.
     */
    private fun resizeEditor() {
        if (editor.isDisposed) return
        val height = editor.contentComponent.preferredSize.height + editor.scrollPane.insets.let { it.top + it.bottom }
        if (editor.component.height == height && editor.component.preferredSize.height == height) return
        editor.component.preferredSize = Dimension(JBUI.scale(100), height)
        component.revalidate()
    }

    /** Edits the cell: a markdown cell shows its editor in place of its rendering. */
    fun focusEditor() {
        if (rendered != null && !editingMarkup) {
            editingMarkup = true
            show()
        }
        editor.contentComponent.requestFocusInWindow()
    }

    /** Renders a markdown cell again, once it is no longer being edited. */
    private fun stopEditing() {
        if (!editingMarkup || editor.isDisposed || editing) return
        editingMarkup = false
        show()
    }

    /** Whether the cell's editor shows line numbers, as VS Code's Show Cell Line Numbers toggles them. */
    var lineNumbers: Boolean
        get() = editor.settings.isLineNumbersShown
        set(value) {
            editor.settings.isLineNumbersShown = value
        }

    /** Whether the cell's editor has the focus: the user is typing in it. */
    val editing: Boolean get() = editor.contentComponent.isFocusOwner

    /** Stops editing the cell, which stays selected. */
    fun quitEdit() = owner.selected(this)

    val text: String get() = document.text

    /** Text another editor of the notebook typed. */
    fun setText(text: String) {
        if (document.text == text) return
        applying = true
        try {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        } finally {
            applying = false
        }
        undo.clear()
        if (rendered != null && !editingMarkup) rendered.text = CellMarkdown.toHtml(text)
    }

    /** Set while [setText] puts in what another editor typed, so it is not sent back. */
    private var applying = false

    /** What the extensions report as wrong in the cell now. */
    fun setProblems(list: List<CellProblemDto>) {
        language?.setProblems(list)
    }

    /** Edits the cell with the caret at VS Code's 1-based [line] and [column]: where a definition is. */
    fun reveal(line: Int, column: Int) {
        focusEditor()
        editor.caretModel.moveToOffset(CellLanguage.offsetOf(document, line, column))
    }

    /** Asks the backend about this cell's notebook; see [Owner.ask]. */
    suspend fun <T> ask(request: suspend NotebookApi.(projectId: ProjectId, editorId: String) -> T): T = owner.ask(request)

    private class ShownOutput(val output: OutputDto, val component: JComponent, val disposable: Disposable)

    /**
     * Shows [list]. An output that has not changed keeps its view, so a stream written to bit by bit
     * does not draw its cell's other outputs anew -- a page among them loading again each time.
     */
    fun setOutputs(list: List<OutputDto>) {
        val previous = shownOutputs.toMutableList()
        shownOutputs = list.map { output ->
            val kept = previous.firstOrNull { it.output.id == output.id && sameItems(it.output, output) }
            if (kept != null) {
                previous.remove(kept)
                kept
            } else {
                val disposable = Disposer.newDisposable(this, "output ${output.id}")
                ShownOutput(output, OutputViews.component(output, OutputViews.Place(owner.scrollPane, disposable)), disposable)
            }
        }
        previous.forEach { Disposer.dispose(it.disposable) }
        outputs.removeAll()
        shownOutputs.forEach { outputs.add(it.component) }
        outputs.isVisible = list.isNotEmpty()
        outputs.revalidate()
        outputs.repaint()
    }

    fun setRun(run: RunDto) {
        this.run = run
        if (kind != CellKinds.CODE) return
        order.text = if (run.state == RunState.IDLE) "[${run.order ?: " "}]" else "[*]"
        // Counted here, from how long the backend said the cell had run: its clock may not be ours.
        if (run.state == RunState.RUNNING) {
            runningSince = System.currentTimeMillis() - (run.millis ?: 0)
            ticker.start()
        } else ticker.stop()
        status.text = when (run.state) {
            RunState.PENDING -> "queued"
            RunState.RUNNING -> elapsed()
            RunState.IDLE -> when (run.success) {
                true -> "✓" + (run.millis?.let { " ${seconds(it)}" } ?: "")
                false -> "✗" + (run.millis?.let { " ${seconds(it)}" } ?: "")
                null -> ""
            }
        }
        status.foreground = if (run.success == false && run.state == RunState.IDLE) JBColor.RED else UIUtil.getContextHelpForeground()
        running.running = run.state == RunState.RUNNING
        updateToolbar()
        // Run turns into Stop and back now, not on the toolbar's next update, which waits for the
        // mouse or keyboard to rest.
        runButton?.updateActionsAsync()
    }

    private fun seconds(millis: Long) = if (millis < 1000) "${millis}ms" else "%.1fs".format(millis / 1000.0)

    /** How long the cell has run so far, in tenths of a second: in ms, it would flicker. */
    private fun elapsed() = "%.1fs".format((System.currentTimeMillis() - runningSince).coerceAtLeast(0) / 1000.0)

    override fun dispose() {
        ticker.stop()
        running.running = false
        EditorFactory.getInstance().releaseEditor(editor)
        rendered?.dispose()
    }

    /**
     * A rounded frame: around a cell's editor, a little lighter or darker than the notebook, its
     * outline the focus colour while the editor is typed in; or, [floating], around a cell's
     * toolbar. Unframed, it draws nothing: a rendered markdown cell is text on the notebook.
     */
    private inner class Frame(private val floating: Boolean = false) : JPanel(FrameLayout()) {
        var framed = true

        init {
            isOpaque = false
        }

        // The running outline is drawn over the editor.
        override fun isOptimizedDrawingEnabled() = false

        override fun paintComponent(g: Graphics) {
            if (!framed) return
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                val arc = JBUI.scale(ARC).toFloat()
                val line = JBUI.scale(1).toFloat()
                val shape = RoundRectangle2D.Float(line / 2, line / 2, width - line, height - line, arc, arc)
                g2.color = if (floating) notebookBackground() else cellBackground()
                g2.fill(shape)
                g2.color = if (!floating && editing) JBUI.CurrentTheme.Focus.focusColor() else outline()
                g2.stroke = BasicStroke(line)
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
        }
    }

    /** A [Frame]'s content fills it, and its [RunningOutline], when there is one, lies over it. */
    private class FrameLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component) = Unit
        override fun removeLayoutComponent(comp: Component) = Unit
        override fun preferredLayoutSize(parent: Container) = size(parent) { it.preferredSize }
        override fun minimumLayoutSize(parent: Container) = size(parent) { it.minimumSize }

        private fun size(parent: Container, of: (Component) -> Dimension): Dimension {
            val content = parent.components.firstOrNull { it !is RunningOutline }?.let(of) ?: Dimension()
            val insets = parent.insets
            return Dimension(content.width + insets.left + insets.right, content.height + insets.top + insets.bottom)
        }

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            for (child in parent.components) {
                if (child is RunningOutline) child.setBounds(0, 0, parent.width, parent.height)
                else {
                    child.setBounds(insets.left, insets.top, parent.width - insets.left - insets.right, parent.height - insets.top - insets.bottom)
                }
            }
        }
    }

    /**
     * While its cell runs, the outline of the cell's frame is drawn as the IDE draws an indeterminate
     * progress bar: bands of its colours, here running round the cell, along the top to the right and
     * back along the bottom. It only draws: the mouse goes through it to the editor beneath.
     */
    private class RunningOutline : JComponent() {
        private val timer = Timer(1000 / 60) { repaintOutline() }

        init {
            isOpaque = false
            isVisible = false
        }

        var running = false
            set(value) {
                if (field == value) return
                field = value
                isVisible = value
                if (value) timer.start() else timer.stop()
            }

        override fun contains(x: Int, y: Int) = false

        /** Repaints along the edges only, round corners and all, not the editor in between. */
        private fun repaintOutline() {
            val edge = minOf(JBUI.scale(ARC), width / 2, height / 2)
            repaint(0, 0, width, edge)
            repaint(0, height - edge, width, edge)
            repaint(0, 0, edge, height)
            repaint(width - edge, 0, edge, height)
        }

        override fun paintComponent(g: Graphics) {
            if (width == 0 || height == 0) return
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                val line = JBUI.scale(2).toFloat()
                val arc = JBUI.scale(ARC).toFloat()
                g2.stroke = BasicStroke(line)
                val shape = RoundRectangle2D.Float(line / 2, line / 2, width - line, height - line, arc, arc)
                val band = JBUI.scale(BAND).toFloat()
                val offset = (System.currentTimeMillis() % PERIOD) / PERIOD.toFloat() * 2 * band
                val colors = arrayOf<Color>(START, END)
                // The top half's bands move right, the bottom half's left: round the cell.
                for ((top, start) in listOf(true to offset, false to -offset)) {
                    val half = g2.create() as Graphics2D
                    try {
                        half.clipRect(0, if (top) 0 else height / 2, width, if (top) height / 2 else height - height / 2)
                        half.paint = LinearGradientPaint(start, 0f, start + band, 0f, floatArrayOf(0f, 1f), colors, MultipleGradientPaint.CycleMethod.REFLECT)
                        half.draw(shape)
                    } finally {
                        half.dispose()
                    }
                }
            } finally {
                g2.dispose()
            }
        }

        companion object {
            /** From one colour of the bands to the other, in px. */
            private const val BAND = 110
            /** How long the bands take to move on by two of them, in ms. */
            private const val PERIOD = 1200L
            private val START = JBColor.namedColor("ProgressBar.indeterminateStartColor", JBColor(0xD5DFF8, 0x3A4766))
            private val END = JBColor.namedColor("ProgressBar.indeterminateEndColor", JBColor(0x3574F0, 0x548AF7))
        }
    }

    /**
     * The cell's body fills it but for its [inserter], at the bottom; its toolbar, when it shows,
     * sits on the top edge of the cell's frame, at the right or -- [Owner.toolbarOnLeft] -- the left,
     * as VS Code's does.
     */
    private inner class OverlayLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component) = Unit
        override fun removeLayoutComponent(comp: Component) = Unit
        override fun preferredLayoutSize(parent: Container) = below(body.preferredSize)
        override fun minimumLayoutSize(parent: Container) = below(body.minimumSize)

        private fun below(size: Dimension) = Dimension(size.width, size.height + inserter.preferredSize.height)

        override fun layoutContainer(parent: Container) {
            val strip = inserter.preferredSize.height
            body.setBounds(0, 0, parent.width, parent.height - strip)
            // Centred under the frame, not the gutter and run column beside it.
            val gutter = JBUI.scale(GUTTER + RUN_COLUMN)
            inserter.setBounds(gutter, parent.height - strip, parent.width - gutter, strip)
            val size = toolbar.preferredSize
            val top = JBUI.scale(TOP_GAP)
            val x = if (owner.toolbarOnLeft) gutter + JBUI.scale(12) else parent.width - size.width - JBUI.scale(12)
            toolbar.setBounds(x, (top - size.height / 2).coerceAtLeast(0), size.width, size.height)
        }
    }

    companion object {
        /** The cell an editor shows, on that editor. */
        val KEY: Key<CellView> = Key.create("CodeBridge.NotebookCell")
        /** The cell whose toolbar an action was chosen from. */
        val DATA_KEY: DataKey<CellView> = DataKey.create("CodeBridge.NotebookCell")

        /** Above each cell's frame: room for half its toolbar. */
        private const val TOP_GAP = 14
        /** Left of each cell's frame: room for the selected cell's bar. */
        private const val GUTTER = 10
        /** The selected cell's bar, in the gutter. */
        private const val BAR_X = 2
        private const val BAR_WIDTH = 3
        /** Between the selected cell's bar and its frame: a code cell's run button and execution order. */
        private const val RUN_COLUMN = 36
        private const val ARC = 10

        /** The notebook's background: the editor's. */
        fun notebookBackground(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

        private fun sameItems(a: OutputDto, b: OutputDto): Boolean = a.items.size == b.items.size &&
            a.items.zip(b.items).all { (x, y) -> x.mime == y.mime && x.data.contentEquals(y.data) }

        /** A cell's frame: the notebook's background, a shade toward its text. */
        private fun cellBackground(): Color {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, 0.04)
        }

        /** The outline of a cell's frame. */
        fun outline(): Color {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, 0.14)
        }
    }
}
