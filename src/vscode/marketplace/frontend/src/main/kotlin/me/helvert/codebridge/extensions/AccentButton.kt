package me.helvert.codebridge.extensions

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Dimension
import javax.swing.JButton

/** Theme-aware action buttons, using neutral outlines for marketplace rows and details. */
class AccentButton(text: String, private val filled: Boolean = true) : JButton(text) {

    /** Row background to blend into; null means use the theme's page background. */
    private var surface: java.awt.Color? = null

    init {
        isOpaque = false
        applyColors()
    }

    /** Swing calls this whenever the look and feel changes, which is when named colours change. */
    override fun updateUI() {
        super.updateUI()
        // Also runs from JButton's constructor, before `init`; `filled` is not assigned yet then,
        // so this reads false and the colours are corrected by the `applyColors` call in `init`.
        applyColors()
    }

    /**
     * The colour behind the button, so an outlined one blends into the row it sits on.
     *
     * `InstallButton` uses `Plugins.Button.installBackground` for the unfilled style, and the
     * current theme does not define that key -- it falls back to the *page* background, which on a
     * selected row paints a visible box in the wrong colour. Following the row surface is what the
     * outlined style is supposed to look like.
     */
    fun setSurface(color: java.awt.Color) {
        surface = color
        applyColors()
    }

    private fun applyColors() {
        val background = if (filled) FILL_BACKGROUND else (surface ?: BACKGROUND)
        putClientProperty("JButton.backgroundColor", background)
        putClientProperty("JButton.textColor", if (filled) FILL_FOREGROUND else FOREGROUND)
        putClientProperty("JButton.borderColor", BORDER)
    }

    /**
     * Fixes the width to [unscaled] logical pixels plus the border, matching how the IDE sizes its
     * plugin buttons so a row of them lines up regardless of label length.
     */
    fun setFixedWidth(unscaled: Int) {
        preferredSize = null
        val insets = border?.getBorderInsets(this)
        val width = JBUIScale.scale(unscaled) + (insets?.let { it.left + it.right } ?: 0)
        preferredSize = Dimension(width, preferredSize.height)
    }

    companion object {
        /**
         * `ColorButton.setWidth72`: the width the IDE pins row buttons to, unscaled.
         *
         * Rows give the name only the space the buttons leave, so a natural-width button silently
         * truncates every plugin name beside it.
         */
        const val ROW_WIDTH: Int = 72

        // Matches com.intellij.ide.plugins.newui.InstallButton: the same keys, the same fallbacks.
        private val FILL_FOREGROUND: JBColor =
            JBColor.namedColor("Plugins.Button.installFillForeground", 0xFFFFFF, 0xBBBBBB)
        private val FILL_BACKGROUND: JBColor =
            JBColor.namedColor("Plugins.Button.installFillBackground", 0x5D9B47, 0x2B7B50)
        private val FOREGROUND: JBColor = JBColor.namedColor("Button.foreground", 0x27282E, 0xDFE1E5)
        private val BACKGROUND: JBColor = JBColor.namedColor("Plugins.Button.installBackground", 0xFFFFFF, 0x3C3F41)
        private val BORDER: JBColor = JBColor.namedColor("Component.borderColor", 0xC9CCD6, 0x43454A)
    }
}
