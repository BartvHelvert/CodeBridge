package me.helvert.codebridge.extensions

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A counted group with the compact show-more control used by the plugin marketplace.
 *
 * A group that is not [collapsible] always shows every row and has no control.
 */
internal class MarketplaceSection(
    title: String, private val collapsedLimit: Int = 3,
    initiallyExpanded: Boolean = true,
    private val collapsible: Boolean = true
) : JPanel(BorderLayout()) {
    private val label = JBLabel(title)
    private val countLabel = JBLabel()
    private val expandText = JBLabel()
    private val expandChevron = JBLabel()
    private val expansionControl = JPanel(HorizontalLayout(JBUI.scale(2))).apply {
        isOpaque = false
        isVisible = false
        add(expandText)
        add(expandChevron)
    }

    // As in the IDE's storefront, the whole header is the toggle and gets a rounded hover highlight.
    private val header = SelectablePanel().apply {
        layout = BorderLayout()
        isOpaque = false
        selectionArc = JBUI.scale(8)
        selectionInsets = JBUI.insets(7, 8, 3, 8)
        preferredHeight = JBUI.scale(40)
    }
    private val toggle = JButton()
    private var expanded = initiallyExpanded || !collapsible
    val isExpanded: Boolean get() = expanded
    private val body = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            if (!expanded) {
                val list = findList(content)
                val hiddenHeight = list?.components?.filter { it.isVisible }
                    ?.drop(collapsedLimit)?.sumOf { it.preferredSize.height } ?: 0
                size.height = (size.height - hiddenHeight).coerceAtLeast(0)
            }
            return size
        }

        // Lay out the full list even when the group clips it to its first three rows.
        override fun doLayout() {
            content?.setBounds(0, 0, width, content!!.preferredSize.height)
        }
    }

    private fun findList(component: java.awt.Component?): ExtensionList<*>? {
        if (component is ExtensionList<*>) return component
        return (component as? java.awt.Container)?.components?.firstNotNullOfOrNull { findList(it) }
    }

    fun reveal(index: Int) {
        if (index >= collapsedLimit && !expanded) {
            expanded = true
            updateExpansion()
            revalidate()
        }
    }

    var title: String
        get() = label.text
        set(value) {
            label.text = value
        }

    var count: Int = 0
        set(value) {
            field = value
            countLabel.text = value.toString()
            val expandable = collapsible && value > collapsedLimit
            expansionControl.isVisible = expandable
            toggle.isEnabled = expandable
            toggle.cursor =
                if (expandable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            updateHover()
            revalidate()
        }

    /** Whether [count] is shown beside the title; it still decides whether the group can collapse. */
    var showCount: Boolean
        get() = countLabel.isVisible
        set(value) {
            countLabel.isVisible = value
        }

    var content: JComponent? = null
        set(value) {
            body.removeAll()
            field = value
            value?.let { body.add(it, BorderLayout.CENTER) }
            revalidate()
        }

    private fun updateExpansion() {
        expandText.text = if (expanded) "Show less" else "Show more"
        expandChevron.icon = if (expanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
    }

    private fun updateHover() {
        header.selectionColor =
            if (toggle.isEnabled && toggle.model.isRollover) MarketplaceColors.HOVER_BG else null
        header.repaint()
    }

    init {
        background = MarketplaceColors.MAIN_BG
        body.isOpaque = false
        label.font = label.font.deriveFont(Font.BOLD)
        countLabel.foreground = UIUtil.getContextHelpForeground()
        expandText.foreground = UIUtil.getContextHelpForeground()
        updateExpansion()
        // HorizontalLayout centres each child vertically, so title, count and control share a midline.
        val heading = JPanel(HorizontalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(label)
            add(countLabel)
        }
        toggle.apply {
            layout = BorderLayout()
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isRolloverEnabled = true
            isEnabled = false
            border = JBUI.Borders.empty(8, 16, 4, 12)
            add(heading, BorderLayout.CENTER)
            add(expansionControl, BorderLayout.EAST)
            model.addChangeListener { updateHover() }
            addActionListener {
                expanded = !expanded
                updateExpansion()
                this@MarketplaceSection.revalidate()
                this@MarketplaceSection.repaint()
            }
        }
        header.add(toggle, BorderLayout.CENTER)
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = 0 }
}
