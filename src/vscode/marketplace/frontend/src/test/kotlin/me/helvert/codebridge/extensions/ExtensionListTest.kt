package me.helvert.codebridge.extensions

import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The list both tabs show extensions in: what replaced the IDE's own `PluginsGroupComponent` and
 * `MultiSelectionEventHandler` when the fork of them was dropped.
 */
class ExtensionListTest {

    private class Row(item: String) : JPanel(), ExtensionListRow {
        val label = JLabel(item).also { add(it) }
        var state = RowState.NONE
        var activated = 0
        override fun showState(state: RowState) { this.state = state }
        override fun activate() { activated++ }
    }

    private val chosen = ArrayList<String>()
    private val list = ExtensionList<String>({ it }) { chosen.add(it) }
    private val rows = HashMap<String, Row>()

    private fun show(vararg items: String) {
        rows.clear()
        list.show(items.toList()) { item -> Row(item).also { rows[item] = it } }
    }

    private fun press(code: Int) {
        val key = list.getInputMap(JComponent.WHEN_FOCUSED)[KeyStroke.getKeyStroke(code, 0)]
        list.actionMap[key].actionPerformed(ActionEvent(list, ActionEvent.ACTION_PERFORMED, null))
    }

    private fun mouse(component: Component, id: Int) {
        component.dispatchEvent(MouseEvent(component, id, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
    }

    @Test
    fun `clicking anywhere in a row chooses it`() {
        show("a", "b", "c")
        // On the row's label, not the row: the mouse over a row is usually over one of its parts.
        mouse(rows.getValue("b").label, MouseEvent.MOUSE_PRESSED)

        assertEquals(listOf("b"), chosen)
        assertEquals("b", list.selected)
        assertEquals(RowState.SELECTED, rows.getValue("b").state)
        assertEquals(RowState.NONE, rows.getValue("a").state)
    }

    @Test
    fun `the arrow keys and Home and End move the selection`() {
        show("a", "b", "c")
        press(KeyEvent.VK_DOWN)
        assertEquals("a", list.selected, "with nothing selected, Down starts at the top")
        press(KeyEvent.VK_DOWN)
        press(KeyEvent.VK_DOWN)
        press(KeyEvent.VK_DOWN)
        assertEquals("c", list.selected, "Down stops at the last row")
        press(KeyEvent.VK_HOME)
        assertEquals("a", list.selected)
        press(KeyEvent.VK_END)
        assertEquals("c", list.selected)
        press(KeyEvent.VK_UP)
        assertEquals(listOf("a", "b", "c", "c", "a", "c", "b"), chosen)
    }

    @Test
    fun `Enter activates the selected row`() {
        show("a", "b")
        press(KeyEvent.VK_ENTER)
        assertEquals(0, rows.values.sumOf { it.activated }, "nothing selected, nothing activated")
        press(KeyEvent.VK_DOWN)
        press(KeyEvent.VK_ENTER)
        assertEquals(1, rows.getValue("a").activated)
    }

    @Test
    fun `hover follows the mouse and leaves the selection alone`() {
        show("a", "b")
        list.selected = "a"
        mouse(rows.getValue("b").label, MouseEvent.MOUSE_ENTERED)

        assertEquals(RowState.HOVER, rows.getValue("b").state)
        assertEquals(RowState.SELECTED, rows.getValue("a").state)
        assertEquals(emptyList(), chosen, "hovering chooses nothing")
    }

    @Test
    fun `rebuilding keeps the selection on an item still there`() {
        show("a", "b")
        list.selected = "b"
        show("a", "b", "c")
        assertEquals("b", list.selected)
        assertEquals(RowState.SELECTED, rows.getValue("b").state, "the new row for b is drawn selected")

        show("a", "c")
        assertNull(list.selected, "b is gone, so nothing is selected")
    }

    @Test
    fun `setting the selection does not report a choice`() {
        show("a", "b")
        list.selected = "b"
        assertEquals(emptyList(), chosen)
    }
}
