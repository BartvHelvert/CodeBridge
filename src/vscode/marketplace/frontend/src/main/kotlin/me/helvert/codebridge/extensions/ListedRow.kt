package me.helvert.codebridge.extensions

import com.intellij.platform.eel.isMac
import com.intellij.platform.eel.provider.localEel
import com.intellij.ui.ColorUtil
import com.intellij.ui.RelativeFont
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.extensions.ui.PluginRow
import me.helvert.codebridge.extensions.ui.TextHorizontalLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.accessibility.AccessibleContext
import javax.swing.*

/**
 * A row in an [ExtensionList], coloured the way the IDE colours a plugin row.
 *
 * The parts are added by the subclass in the order the IDE's row has them -- icon, name, buttons,
 * then the grey metrics line -- through the helpers here, which remember what has to be recoloured
 * when the row is hovered or selected. The colours follow `ListPluginComponent.updateColors`: the
 * hover or selection colour blended over the page background, the theme's selection foreground for
 * text when there is one, and the disabled foreground for an extension switched off.
 */
abstract class ListedRow : PluginRow(), ExtensionListRow {

    private var state = RowState.NONE
    private var name: JLabel? = null
    private val metrics = ArrayList<JLabel>()
    private val buttons = ArrayList<AccentButton>()

    /** Whether the extension is switched off, which greys the whole row. */
    protected open val dimmed: Boolean get() = false

    init {
        isOpaque = false
    }

    protected fun icon(icon: Icon) {
        val label = JLabel(icon)
        label.preferredSize = Dimension(JBUI.scale(ICON_SIZE), JBUI.scale(ICON_SIZE))
        label.verticalAlignment = SwingConstants.TOP
        label.isOpaque = false
        setIconComponent(label)
    }

    protected fun name(text: String) {
        val label = JLabel(text)
        label.font = label.font.deriveFont(Font.PLAIN)
        name = label
        setNameComponent(label)
        putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, text)
    }

    /** A button at the row's right, at the fixed width the IDE gives row buttons. */
    protected fun button(button: AccentButton) {
        button.setFixedWidth(AccentButton.ROW_WIDTH)
        buttons.add(button)
        addButtonComponent(button)
    }

    /** The grey line under the name; each entry is a label, or null to leave it out. */
    protected fun metrics(vararg entries: Metric?) {
        val line = JPanel(TextHorizontalLayout(7))
        line.isOpaque = false
        line.border = JBUI.Borders.emptyTop(5)
        for (entry in entries.filterNotNull()) {
            val label = JLabel(entry.text, entry.icon, SwingConstants.CENTER)
            label.isOpaque = false
            label.iconTextGap = 2
            if (localEel.platform.isMac) RelativeFont.TINY.install(label)
            metrics.add(label)
            // The last-but-trimmable entry, as the IDE's vendor: it gives way when space runs out.
            if (entry.trimmable) line.add(label, TextHorizontalLayout.FIX_LABEL) else line.add(label)
        }
        addLineComponent(line)
    }

    override fun showState(state: RowState) {
        this.state = state
        recolour()
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val graphics = g.create() as java.awt.Graphics2D
        try {
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            graphics.color = background
            val inset = JBUI.scale(8)
            val arc = JBUI.scale(8)
            graphics.fillRoundRect(inset, 0, (width - inset * 2).coerceAtLeast(0), height, arc, arc)
        } finally {
            graphics.dispose()
        }
        super.paintComponent(g)
    }

    /** Applies the current state again, after something [dimmed] depends on has changed. */
    protected fun recolour() {
        val overlay = when (state) {
            RowState.NONE -> null
            RowState.HOVER -> MarketplaceColors.HOVER_BG
            RowState.SELECTED -> MarketplaceColors.SELECTION_BG
        }
        background = overlay?.let { ColorUtil.alphaBlending(it, MarketplaceColors.MAIN_BG) } ?: MarketplaceColors.MAIN_BG
        val selectionForeground: Color? = if (state != RowState.NONE) UIManager.getColor("Plugins.selectionForeground") else null
        val nameForeground = selectionForeground ?: if (dimmed) MarketplaceColors.DISABLED_FG else null
        val otherForeground = selectionForeground ?: if (dimmed) MarketplaceColors.DISABLED_FG else MarketplaceColors.GRAY
        // Null leaves the name in the look and feel's own label colour, as the IDE does.
        name?.foreground = nameForeground
        for (label in metrics) label.foreground = otherForeground
        for (button in buttons) button.setSurface(background)
        repaint()
    }

    /** One entry of the metrics line. */
    protected class Metric(val text: String, val icon: Icon? = null, val trimmable: Boolean = false)

    companion object {
        /** Compact marketplace row logo, in logical pixels. */
        const val ICON_SIZE: Int = 32
    }
}
