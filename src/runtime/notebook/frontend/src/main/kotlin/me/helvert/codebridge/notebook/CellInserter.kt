package me.helvert.codebridge.notebook

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.hover.HoverListener
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The strip between two cells, or above the first, where a notebook offers to add a cell: a pill
 * split into Add Code Cell and Add Markdown Cell, as Kotlin notebooks draw it, which fades in while
 * the mouse is over the strip and out once it leaves, or shows always while [pinned]. The strip keeps
 * its height either way, so the cells don't move as the mouse passes. [insert] adds a cell of the
 * kind clicked where the strip is. Used on the EDT only.
 */
internal class CellInserter(parent: Disposable, private val insert: (kind: Int) -> Unit) : JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)) {
    private var hovered = false
    /** How far the buttons have faded in, from 0, hidden, to 1. */
    private var alpha = 0f
    private val fade = Timer(1000 / 60) { step() }

    /** Shows the buttons with the mouse elsewhere too: in an empty notebook, there is nothing else to click. */
    var pinned = false
        set(value) {
            field = value
            update()
        }

    init {
        isOpaque = false
        // The getter: in a subclass, `accessibleContext` is Swing's field, null until the getter fills it.
        @Suppress("UsePropertyAccessSyntax")
        getAccessibleContext().accessibleName = "Add Cell"
        add(Pill())
        object : HoverListener() {
            override fun mouseEntered(component: Component, x: Int, y: Int) = hover(true)
            override fun mouseMoved(component: Component, x: Int, y: Int) = Unit
            override fun mouseExited(component: Component) = hover(false)
        }.addTo(this, parent)
        Disposer.register(parent) { fade.stop() }
        show(0f)
    }

    private fun hover(on: Boolean) {
        hovered = on
        update()
    }

    private fun update() {
        if (pinned) {
            fade.stop()
            show(1f)
        } else if (alpha != target()) fade.start()
    }

    private fun target() = if (hovered || pinned) 1f else 0f

    /** Fades the buttons one frame on, toward shown or hidden. */
    private fun step() {
        val target = target()
        val by = 1000f / 60 / FADE_MILLIS
        show(if (target > alpha) minOf(alpha + by, target) else maxOf(alpha - by, target))
        if (alpha == target) fade.stop()
    }

    /** Draws the buttons at [value]; at 0 they are hidden, so they take no clicks. */
    private fun show(value: Float) {
        alpha = value
        val visible = value > 0f
        if (components.any { it.isVisible != visible }) {
            components.forEach { it.isVisible = visible }
            revalidate()
        }
        repaint()
    }

    override fun paintChildren(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            super.paintChildren(g2)
        } finally {
            g2.dispose()
        }
    }

    // As tall with its buttons hidden as with them shown: FlowLayout leaves no room for hidden ones.
    override fun getPreferredSize() = Dimension(super.getPreferredSize().width, JBUI.scale(HEIGHT))
    override fun getMinimumSize() = Dimension(0, JBUI.scale(HEIGHT))

    override fun doLayout() {
        super.doLayout()
        // Centred in the strip's height, which FlowLayout leaves them at the top of.
        for (button in components) button.setLocation(button.x, (height - button.height) / 2)
    }

    /** The two buttons, side by side in one rounded outline, a line between them. */
    private inner class Pill : JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)) {
        private val segments = listOf(Segment("Add Code Cell", CellKinds.CODE), Segment("Add Markdown Cell", CellKinds.MARKUP))

        init {
            isOpaque = false
            segments.forEach(::add)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                val line = JBUI.scale(1).toFloat()
                val shape = RoundRectangle2D.Float(line / 2, line / 2, width - line, height - line, height - line, height - line)
                g2.color = CellView.notebookBackground()
                g2.fill(shape)
                // The segment under the mouse, within the outline's rounded ends.
                segments.firstOrNull { it.state != null }?.let { segment ->
                    val fill = g2.create() as Graphics2D
                    try {
                        fill.clip(shape)
                        fill.color = segment.state
                        fill.fillRect(segment.x, 0, segment.width, height)
                    } finally {
                        fill.dispose()
                    }
                }
                g2.color = CellView.outline()
                g2.stroke = BasicStroke(line)
                g2.draw(shape)
                for (segment in segments.drop(1)) g2.drawLine(segment.x, 0, segment.x, height)
            } finally {
                g2.dispose()
            }
        }
    }

    /** One of the [Pill]'s buttons: its text, its background drawn by the pill. */
    private inner class Segment(text: String, private val kind: Int) : JBLabel(text) {
        private var over = false
        private var pressed = false

        /** The background the mouse gives it, if any. */
        val state get() = when {
            pressed -> JBUI.CurrentTheme.ActionButton.pressedBackground()
            over -> JBUI.CurrentTheme.ActionButton.hoverBackground()
            else -> null
        }

        init {
            font = JBFont.label()
            border = JBUI.Borders.empty(4, 14)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) = set { over = true }
                override fun mouseExited(event: MouseEvent) = set { over = false; pressed = false }
                override fun mousePressed(event: MouseEvent) = set { pressed = event.button == MouseEvent.BUTTON1 }
                override fun mouseReleased(event: MouseEvent) = set { pressed = false }
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) insert(kind)
                }
            })
        }

        private fun set(change: () -> Unit) {
            change()
            parent?.repaint()
        }
    }

    private companion object {
        const val HEIGHT = 30
        /** How long the buttons take to fade in or out. */
        const val FADE_MILLIS = 120
    }
}
