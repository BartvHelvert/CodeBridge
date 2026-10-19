package me.helvert.codebridge.views

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.OnePixelDivider
import com.intellij.ui.IslandsState
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The views of one container stacked one above the other, as VS Code shows them in its side bar:
 * each under a header that collapses it, the open ones sharing the height, which dragging the
 * divider above a header moves between the open views above and below it. Drawn as IntelliJ draws
 * its own: plain titles, a minimize button, and one-pixel dividers on a continuous surface beneath
 * the shared tool window title. A container of one view shows it without a header, its title being the tool
 * window's. Used on the EDT only.
 *
 * Which views are collapsed, and how the height is shared, is remembered per project. Which are in the
 * stack at all is [TreeViewWindows]'s to decide.
 *
 * @param changed told when a view is opened or collapsed, which changes whether it is shown.
 */
internal class ViewStack(project: Project, private val changed: () -> Unit) : JPanel(null) {
    private val properties = PropertiesComponent.getInstance(project)
    private val sections = mutableListOf<Section>()
    /** The only view, which then has no header; null for a stack of several, or none. */
    val single: CodeBridgeViewTab? get() = sections.singleOrNull()?.tab

    fun add(tab: CodeBridgeViewTab) {
        val section = Section(tab)
        sections += section
        add(section.header)
        add(tab.component)
        relaid()
    }

    fun remove(tab: CodeBridgeViewTab) {
        val section = sections.firstOrNull { it.tab === tab } ?: return
        sections -= section
        remove(section.header)
        remove(tab.component)
        relaid()
    }

    /** Puts the views in [order], VS Code's order of them in their container. */
    fun arrange(order: List<CodeBridgeViewTab>) {
        val wanted = sections.sortedBy { section -> order.indexOfFirst { it === section.tab } }
        if (wanted == sections) return
        sections.clear()
        sections += wanted
        relaid()
    }

    /** Redraws [tab]'s header, for a changed title or title actions. */
    fun refresh(tab: CodeBridgeViewTab) {
        sections.firstOrNull { it.tab === tab }?.refresh()
        relaid()
    }

    /** Whether [tab] is open: not collapsed, or the only view. */
    fun isOpen(tab: CodeBridgeViewTab): Boolean = sections.size == 1 || sections.any { it.tab === tab && it.open }

    /** Opens [tab] if collapsed. */
    fun open(tab: CodeBridgeViewTab) {
        sections.firstOrNull { it.tab === tab }?.takeUnless { it.open }?.toggle()
    }

    private fun relaid() {
        revalidate()
        repaint()
    }

    override fun doLayout() {
        val islands = IslandsState.isEnabled()
        // The first header sits right under the tool window's own, which needs no divider.
        for ((index, section) in sections.withIndex()) section.frame(divided = index > 0, islands)
        val stacked = sections.size > 1
        val open = sections.filter { !stacked || it.open }
        val headers = if (stacked) sections.sumOf { it.headerHeight } else 0
        val free = (height - headers).coerceAtLeast(0)
        val weights = open.sumOf { it.weight }
        var given = 0
        var y = 0
        for (section in sections) {
            section.header.isVisible = stacked
            if (stacked) {
                val h = section.headerHeight
                section.header.setBounds(0, y, width, h)
                y += h
            }
            val body = section.tab.component
            body.isVisible = section in open
            if (!body.isVisible) continue
            val h = if (section === open.last()) free - given else (free * section.weight / weights).roundToInt()
            body.setBounds(0, y, width, h)
            given += h
            y += h
        }
    }

    /**
     * Resizing by dragging [section]'s header: height moves between the nearest open view above it
     * and the nearest open one from it down.
     */
    private inner class Drag(section: Section) {
        private val index = sections.indexOf(section)
        private val above = sections.take(index).lastOrNull { it.open }
        private val below = sections.drop(index).firstOrNull { it.open }
        private val aboveHeight = above?.tab?.component?.height ?: 0
        private val belowHeight = below?.tab?.component?.height ?: 0

        fun to(dy: Int) {
            if (above == null || below == null) return
            val both = aboveHeight + belowHeight
            if (both <= 0) return
            val min = JBUI.scale(MIN_HEIGHT).coerceAtMost(both / 2)
            val newAbove = (aboveHeight + dy).coerceIn(min, both - min)
            val weight = above.weight + below.weight
            above.weight = weight * newAbove / both
            below.weight = weight - above.weight
            relaid()
        }

        fun done() {
            above?.remember()
            below?.remember()
        }
    }

    private inner class Section(val tab: CodeBridgeViewTab) {
        private val id = tab.view.id

        /** As the user last left it, or as the extension declared it: `"visibility": "collapsed"` starts closed. */
        var open: Boolean = properties.getValue(collapsedKey(id))?.let { it != "true" } ?: !tab.view.collapsedByDefault
            private set
        var weight: Double = properties.getValue(weightKey(id))?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0

        private val title = SimpleColoredComponent().apply { isOpaque = false }
        private var toolbarFor: List<AnAction>? = null
        private var toolbar: ActionToolbar? = null
        private val toggleAction = object : DumbAwareAction("Collapse View", null, AllIcons.General.HideToolWindow) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.text = if (open) "Collapse View" else "Expand View"
                e.presentation.icon = if (open) AllIcons.General.HideToolWindow else AllIcons.General.ChevronDown
            }

            override fun actionPerformed(e: AnActionEvent) = toggle()
        }

        val header: JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            isFocusable = true
            add(title, BorderLayout.CENTER)
        }

        /** Whether a divider is above the header: all but the first have one. */
        var divided: Boolean = false
            private set
        private var islands: Boolean = false

        fun frame(divided: Boolean, islands: Boolean) {
            if (header.border != null && divided == this.divided && islands == this.islands) return
            this.divided = divided
            this.islands = islands
            header.border = headerBorder(divided, islands)
        }

        init {
            val mouse = object : MouseAdapter() {
                private var pressedAt: Int? = null
                private var drag: Drag? = null
                private var onDivider = false

                override fun mousePressed(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1) return
                    pressedAt = e.yOnScreen
                    onDivider = onDivider(e)
                    header.requestFocusInWindow()
                }

                override fun mouseDragged(e: MouseEvent) {
                    val start = pressedAt ?: return
                    // As with IntelliJ's splitters, only the divider resizes; the title is for clicking.
                    if (!onDivider) return
                    val dy = e.yOnScreen - start
                    if (drag == null && abs(dy) < JBUI.scale(DRAG_THRESHOLD)) return
                    val resizing = drag ?: Drag(this@Section).also { drag = it }
                    resizing.to(dy)
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (pressedAt == null) return
                    pressedAt = null
                    val resized = drag?.done()
                    drag = null
                    if (resized == null && !onDivider && header.getMousePosition(true) != null) toggle()
                }

                override fun mouseMoved(e: MouseEvent) {
                    header.cursor = if (onDivider(e)) Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR) else null
                }

                /** A small hit area around the divider makes it easier to grab. */
                private fun onDivider(e: MouseEvent): Boolean {
                    if (!divided) return false
                    val sash = JBUI.scale(SASH)
                    return SwingUtilities.convertPoint(e.component, e.point, header).y < sash
                }
            }
            for (component in listOf(header, title)) {
                component.addMouseListener(mouse)
                component.addMouseMotionListener(mouse)
            }
            header.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_SPACE) toggle()
                }
            })
            refresh()
        }

        /** The title row and the divider above it. */
        val headerHeight: Int get() = maxOf(JBUI.scale(HEADER_HEIGHT) + header.insets.top, header.preferredSize.height)

        fun toggle() {
            val weights =
                toggledViewStackWeights(sections.map { it.weight }, sections.map { it.open }, sections.indexOf(this))
            sections.forEachIndexed { index, section ->
                section.weight = weights[index]
                section.remember()
            }
            open = !open
            // Stored either way: opening a view declared collapsed must stick too.
            properties.setValue(collapsedKey(id), (!open).toString())
            refresh()
            relaid()
            changed()
        }

        fun remember() = properties.setValue(weightKey(id), weight.toString())

        fun refresh() {
            val view = tab.view
            title.clear()
            title.append(view.tabTitle, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            view.description?.let { title.append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            title.toolTipText = view.description
            header.accessibleContext.accessibleName = view.tabTitle
            // Keep the expand control available when the view's contributed actions are hidden.
            val actions = (if (open) tab.titleActions else emptyList()) + toggleAction
            if (actions != toolbarFor) {
                toolbarFor = actions
                toolbar?.component?.let(header::remove)
                toolbar = actions.takeIf { it.isNotEmpty() }?.let {
                    ActionManager.getInstance()
                        .createActionToolbar("CodeBridge.TreeViewSection", DefaultActionGroup(it), true)
                        .apply {
                            isReservePlaceAutoPopupIcon = false
                            component.isOpaque = false
                            component.border = JBUI.Borders.empty()
                        }
                }
                toolbar?.component?.let { header.add(it, BorderLayout.EAST) }
            }
            // IntelliJ ignores actions targeting a hidden component. A collapsed view's
            // expand button must target its visible header. Update even if the actions are unchanged.
            toolbar?.targetComponent = if (open) tab.component else header
        }
    }

    private companion object {
        const val HEADER_HEIGHT = 26
        const val MIN_HEIGHT = 40
        const val DRAG_THRESHOLD = 3
        const val SASH = 4

        /** Thin section dividers within the tool window's continuous surface. */
        fun headerBorder(divided: Boolean, islands: Boolean) = JBUI.Borders.compound(
            when {
                divided -> JBUI.Borders.customLineTop(OnePixelDivider.BACKGROUND)
                else -> JBUI.Borders.emptyTop(1)
            },
            JBUI.Borders.empty(0, if (islands) 8 else 4, 0, 2),
        )

        fun collapsedKey(viewId: String) = "CodeBridge.view.collapsed.$viewId"
        fun weightKey(viewId: String) = "CodeBridge.view.weight.$viewId"
    }
}
