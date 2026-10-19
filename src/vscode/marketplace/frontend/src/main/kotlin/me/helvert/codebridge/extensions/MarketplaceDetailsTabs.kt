package me.helvert.codebridge.extensions

import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.plaf.InsetsUIResource
import javax.swing.plaf.TabbedPaneUI
import javax.swing.plaf.UIResource

/**
 * The geometry of the IDE's unified plugin page, `PluginDetailsPageLayout.Unified`, shared by the
 * Marketplace and Installed details panes. Unscaled.
 */
internal object DetailsLayout {
    /** `contentHorizontalInset`: the header text and tab contents start here. */
    const val CONTENT_INSET = 16

    /**
     * `actionRowOutset`: the action row sits this much further left than the text, so the button's
     * outline, which is painted inside its bounds, lines up with the name above it.
     */
    const val ACTION_ROW_OUTSET = 3

    /** `tabStripLeftInset`: likewise, so the first tab's outline lines up with the text. */
    const val TAB_STRIP_LEFT_INSET = 12

    /** `createMainBorder`: the divider above the header, then the header's own insets. */
    fun headerBorder() = object : CustomLineBorder(MarketplaceColors.BORDER, JBUI.insetsTop(1)) {
        @Suppress("UseDPIAwareInsets") // values are already scaled
        override fun getBorderInsets(c: Component): Insets {
            val inset = JBUI.scale(CONTENT_INSET)
            return Insets(JBUI.scale(15), inset - JBUI.scale(ACTION_ROW_OUTSET), 0, inset)
        }
    }

    /** `createInsetRowsPanel`'s indent, for the text rows of a header bordered by [headerBorder]. */
    fun textRowsBorder() = JBUI.Borders.emptyLeft(ACTION_ROW_OUTSET)

    /** What every tab's content is inset by. */
    fun tabContentBorder() = JBUI.Borders.empty(16, CONTENT_INSET, 0, CONTENT_INSET)
}

/**
 * The detail tabs, built as `PluginDetailsPageComponent.createSwingTabs` builds the IDE's: a
 * [JBTabbedPane] with transparent content, the strip offset by [DetailsLayout.TAB_STRIP_LEFT_INSET],
 * and one divider below the strip.
 */
internal class MarketplaceDetailsTabs : JPanel(BorderLayout()) {
    private val pane = object : JBTabbedPane() {
        override fun setUI(ui: TabbedPaneUI) {
            putClientProperty("TabbedPane.hoverColor", MarketplaceColors.HOVER_BG)
            // The UI reads these defaults once, while it installs; they are not per-component properties.
            val defaults = UIManager.getDefaults()
            val contentOpaque = UIManager.getBoolean("TabbedPane.contentOpaque")
            val tabAreaInsets = defaults["TabbedPane.tabAreaInsets"]
            defaults["TabbedPane.contentOpaque"] = false
            val insets = tabAreaInsets as? Insets ?: JBUI.emptyInsets()
            val left = insets.left + JBUI.scale(DetailsLayout.TAB_STRIP_LEFT_INSET)
            defaults["TabbedPane.tabAreaInsets"] =
                if (insets is UIResource) InsetsUIResource(insets.top, left, insets.bottom, insets.right)
                else @Suppress("UseDPIAwareInsets") Insets(insets.top, left, insets.bottom, insets.right)
            try {
                super.setUI(ui)
            } finally {
                defaults["TabbedPane.contentOpaque"] = contentOpaque
                if (tabAreaInsets == null) defaults.remove("TabbedPane.tabAreaInsets")
                else defaults["TabbedPane.tabAreaInsets"] = tabAreaInsets
            }
        }

        // The IDE's unified page gets this line from `JBTabs`' border; a Swing strip has none of
        // its own, and the scrolling strip's `TabContainer` that could carry one is not built for
        // the wrapping layout.
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (tabCount == 0) return
            val bottom = (0 until tabCount).maxOf { getBoundsAt(it)?.let { r -> r.y + r.height } ?: 0 }
            g.color = MarketplaceColors.BORDER
            g.fillRect(0, bottom, width, JBUI.scale(1))
        }
    }

    init {
        isOpaque = false
        pane.isOpaque = false
        pane.border = JBUI.Borders.emptyTop(6)
        pane.background = MarketplaceColors.MAIN_BG
        add(pane, BorderLayout.CENTER)
    }

    fun addTab(title: String, component: JComponent) {
        val insets = pane.tabComponentInsets
        pane.tabComponentInsets = JBUI.emptyInsets()
        try {
            pane.addTab(title, component)
        } finally {
            pane.tabComponentInsets = insets
        }
    }
}
