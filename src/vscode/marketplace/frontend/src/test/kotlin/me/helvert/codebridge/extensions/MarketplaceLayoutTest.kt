package me.helvert.codebridge.extensions

import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.extensions.ui.PluginRow
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketplaceLayoutTest {
    @Test
    fun `install buttons and enable switches do not increase two-line row height`() = SwingUtilities.invokeAndWait {
        fun row(controlHeight: Int): PluginRow = PluginRow().apply {
            setIconComponent(JLabel().apply { preferredSize = JBUI.size(32, 32) })
            setNameComponent(JLabel("Extension").apply { preferredSize = JBUI.size(100, 18) })
            addLineComponent(JLabel("Publisher and version").apply { preferredSize = JBUI.size(150, 20) })
            addButtonComponent(JButton().apply { preferredSize = JBUI.size(72, controlHeight) })
        }

        val installRow = row(28)
        val enabledRow = row(16)
        assertEquals(
            enabledRow.preferredSize.height, installRow.preferredSize.height,
            "the Install button must fit beside both text lines without making suggested rows taller"
        )
        assertEquals(JBUI.scale(52), installRow.preferredSize.height)

        installRow.setSize(JBUI.scale(400), installRow.preferredSize.height)
        installRow.doLayout()
        val metadata = installRow.components[2]
        assertTrue(
            metadata.y + metadata.height <= installRow.height - installRow.insets.bottom,
            "the metadata line must fit inside the compact row"
        )
    }

    private class Row(height: Int) : JPanel(), ExtensionListRow {
        init {
            preferredSize = Dimension(2000, height)
        }

        override fun showState(state: RowState) = Unit
    }

    @Test
    fun `collapsed group shows exactly three complete rows and expands for keyboard selection`() =
        SwingUtilities.invokeAndWait {
            val list = ExtensionList<Int>({ it.toString() }) { }
            val heights = listOf(54, 63, 71, 80, 90)
            list.show(heights) { Row(it) }
            val section = MarketplaceSection("Installed", initiallyExpanded = false)
            section.content = list
            section.count = heights.size
            val collapsed = section.preferredSize.height
            assertEquals(0, section.preferredSize.width, "long names must not widen the settings dialog")
            assertFalse(section.isExpanded)

            section.reveal(2)
            assertFalse(section.isExpanded)
            section.reveal(3)
            assertTrue(section.isExpanded)
            assertEquals(80 + 90, section.preferredSize.height - collapsed)

            val header = section.components.filterIsInstance<JPanel>().first()
            header.components.filterIsInstance<JButton>().single().doClick()
            assertFalse(section.isExpanded)
            assertEquals(collapsed, section.preferredSize.height)
        }

    @Test
    fun `detail tabs switch content and retain a single selected tab`() = SwingUtilities.invokeAndWait {
        val tabs = MarketplaceDetailsTabs()
        val overview = JPanel()
        val info = JPanel()
        tabs.addTab("Overview", overview)
        tabs.addTab("Additional Info", info)
        val pane = tabs.components.filterIsInstance<JBTabbedPane>().single()
        assertEquals(listOf("Overview", "Additional Info"), (0 until pane.tabCount).map { pane.getTitleAt(it) })
        assertTrue(overview.isVisible)
        assertFalse(info.isVisible)

        pane.selectedIndex = 1
        assertFalse(overview.isVisible)
        assertTrue(info.isVisible)
        pane.selectedIndex = 0
        assertTrue(overview.isVisible)
        assertFalse(info.isVisible)
    }
}
