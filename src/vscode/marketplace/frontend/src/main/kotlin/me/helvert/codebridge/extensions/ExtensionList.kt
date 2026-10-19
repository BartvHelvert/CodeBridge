package me.helvert.codebridge.extensions

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.extensions.ui.RowStackLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** How a row is drawn: on its own, under the mouse, or chosen. */
enum class RowState { NONE, HOVER, SELECTED }

/** A row an [ExtensionList] can select and hover. */
interface ExtensionListRow {
    /** Recolours the row for [state]. */
    fun showState(state: RowState)

    /** Enter on the selected row: whatever its main button does, if it has one. */
    fun activate() = Unit
}

/**
 * The list both tabs show their extensions in: real row components stacked by [RowStackLayout],
 * with the selection, hover and keyboard handling the IDE's plugin list has.
 *
 * Rows are components rather than `JList` cells because `PluginRow` sizes the name from its own
 * width, which a renderer never has. A plain stack of them is not a list, though: without this, a
 * row could only be chosen by clicking it, and nothing showed which one the mouse was over. This is
 * the single-selection part of what the IDE's `MultiSelectionEventHandler` does -- the list never
 * offers anything to do to several extensions at once.
 *
 * [keyOf] identifies an item across rebuilds, so paging in more results or refreshing what is
 * installed keeps the selection where it was.
 */
class ExtensionList<T : Any>(
    private val keyOf: (T) -> String,
    private val onChosen: (T) -> Unit,
) : JPanel(RowStackLayout()) {

    private var items: List<T> = emptyList()
    private val rows = LinkedHashMap<String, JComponent>()
    private var selectedKey: String? = null
    private var hoverKey: String? = null

    /** The item shown as selected. Setting it only marks the row; it does not call `onChosen`. */
    var selected: T?
        get() = selectedKey?.let(::itemOf)
        set(value) {
            selectedKey = value?.let(keyOf)?.takeIf { it in rows }
            refreshStates()
        }

    init {
        isOpaque = true
        background = MarketplaceColors.MAIN_BG
        isFocusable = true
        bind(KeyEvent.VK_UP) { move(-1) }
        bind(KeyEvent.VK_DOWN) { move(1) }
        bind(KeyEvent.VK_HOME) { moveTo(0) }
        bind(KeyEvent.VK_END) { moveTo(rows.size - 1) }
        bind(KeyEvent.VK_PAGE_UP) { move(-page()) }
        bind(KeyEvent.VK_PAGE_DOWN) { move(page()) }
        bind(KeyEvent.VK_ENTER) { selectedKey?.let(rows::get)?.let { (it as ExtensionListRow).activate() } }
    }

    /**
     * Replaces the rows with one per item, keeping the selection on an item still present.
     *
     * With no items, [empty] is shown where the rows would be, when there is something to say.
     */
    fun <R> show(items: List<T>, empty: String? = null, rowFor: (T) -> R) where R : JComponent, R : ExtensionListRow {
        this.items = items
        removeAll()
        rows.clear()
        for (item in items) {
            val key = keyOf(item)
            val row = rowFor(item)
            rows[key] = row
            track(row, row, key)
            add(row)
        }
        if (items.isEmpty() && empty != null) {
            add(JBLabel(empty).apply {
                foreground = MarketplaceColors.GRAY
                border = JBUI.Borders.empty(16, 20)
            })
        }
        if (selectedKey !in rows) selectedKey = null
        if (hoverKey !in rows) hoverKey = null
        refreshStates()
        revalidate()
        repaint()
    }

    /** The row showing [item], to repaint when something it draws has changed. */
    fun rowOf(item: T): JComponent? = rows[keyOf(item)]

    private fun itemOf(key: String): T? = items.firstOrNull { keyOf(it) == key }

    private fun choose(key: String) {
        selectedKey = key
        refreshStates()
        rows[key]?.let { scrollRectToVisible(it.bounds) }
        itemOf(key)?.let(onChosen)
    }

    private fun move(delta: Int) {
        val current = rows.keys.indexOf(selectedKey)
        moveTo(if (current < 0) 0 else current + delta)
    }

    private fun moveTo(index: Int) {
        if (rows.isEmpty()) return
        choose(rows.keys.elementAt(index.coerceIn(0, rows.size - 1)))
    }

    /** As many rows as fit in what is visible, so Page Down moves by a screenful. */
    private fun page(): Int {
        val height = rows.values.firstOrNull()?.height?.takeIf { it > 0 } ?: return 1
        return (visibleRect.height / height).coerceAtLeast(1)
    }

    private fun refreshStates() {
        for ((key, row) in rows) {
            (row as ExtensionListRow).showState(
                when (key) {
                    selectedKey -> RowState.SELECTED
                    hoverKey -> RowState.HOVER
                    else -> RowState.NONE
                }
            )
        }
    }

    private fun hover(key: String?) {
        if (key == hoverKey) return
        hoverKey = key
        refreshStates()
    }

    /**
     * Listens on [component] and everything in it, since the mouse over a row is usually over one
     * of its labels or buttons rather than the row itself.
     */
    private fun track(component: Component, row: JComponent, key: String) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = hover(key)

            override fun mouseExited(e: MouseEvent) {
                // Leaving a label for the row around it is not leaving the row.
                if (!row.contains(SwingUtilities.convertPoint(e.component, e.point, row))) {
                    if (hoverKey == key) hover(null)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                // A button or checkbox takes focus for itself; anywhere else, the list does, so the
                // arrow keys move on from the row just clicked.
                if (!e.component.isFocusable || e.component === row) requestFocusInWindow()
                choose(key)
            }
        })
        component.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = hover(key)
        })
        if (component is Container) {
            for (child in component.components) track(child, row, key)
        }
    }

    private fun bind(code: Int, action: () -> Unit) {
        val name = "extensionList.$code"
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(code, 0), name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }
}
