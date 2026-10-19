package me.helvert.codebridge.views

import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.JBColor
import com.intellij.ui.icons.IconReplacer
import com.intellij.ui.icons.ReplaceableIcon
import me.helvert.codebridge.rpc.TreeViewDto
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import kotlin.math.roundToInt

/** View badges contribute to their container even when the view itself is collapsed or hidden. */
internal fun containerBadgeCounts(views: List<TreeViewDto>): Map<String, Int> =
    views.groupBy { it.containerId }.mapValues { (_, members) ->
        members.sumOf { (it.badge ?: 0).coerceAtLeast(0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

/** Keeps the stripe icon's scalable contract and bounds, with a count in its lower right corner. */
internal class CountBadgeIcon(private val base: ScalableIcon, val count: Int) : ScalableIcon, ReplaceableIcon {
    internal val label: String get() = if (count > 99) "99+" else count.toString()

    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight
    override fun getScale(): Float = base.scale
    override fun scale(scaleFactor: Float): Icon =
        CountBadgeIcon(base.scale(scaleFactor) as ScalableIcon, count)

    override fun replaceBy(replacer: IconReplacer): Icon =
        CountBadgeIcon(replacer.replaceIcon(base) as? ScalableIcon ?: base, count)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        val paint = g.create() as Graphics2D
        try {
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paint.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // Use the actual icon bounds: they include both UI zoom and stripe scaling.
            val unit = iconHeight / 16f
            paint.font = Font(Font.DIALOG, Font.BOLD, 1).deriveFont(6f * unit)
            val metrics = paint.fontMetrics
            val height = (8 * unit).roundToInt().coerceAtMost(iconHeight)
            val width = (metrics.stringWidth(label) + (2 * unit).roundToInt())
                .coerceAtLeast(height).coerceAtMost(iconWidth)
            val left = x + iconWidth - width
            val top = y + iconHeight - height
            paint.color = JBColor.namedColor("ToolWindow.Notification.background", JBColor(0x007ACC, 0x007ACC))
            paint.fillRoundRect(left, top, width, height, height, height)
            paint.color = JBColor.namedColor("ToolWindow.Notification.foreground", JBColor.WHITE)
            paint.drawString(
                label, left + (width - metrics.stringWidth(label)) / 2,
                top + (height - metrics.height) / 2 + metrics.ascent
            )
        } finally {
            paint.dispose()
        }
    }

    companion object {
        fun withCount(base: Icon, count: Int): Icon =
            if (count > 0 && base is ScalableIcon) CountBadgeIcon(base, count) else base
    }
}
