package me.helvert.codebridge.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.platform.project.projectId
import com.intellij.ui.ClickListener
import com.intellij.ui.NewUI
import com.intellij.ui.RowIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.messages.CodeBridgeQuickInputs
import me.helvert.codebridge.rpc.StatusItemDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * The status bar items VS Code extensions create, drawn in IntelliJ's status bar.
 *
 * One widget for all of them, because IntelliJ registers widgets up front and extensions create
 * items as they please: it holds a label per item and hides itself when there are none, which is
 * the case in every project without a running extension.
 *
 * Lives in the frontend because that is where the status bar is in split mode. The items are the
 * backend's, collected over RPC, and a click is sent back there to run the extension's command.
 */
internal class CodeBridgeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "VS Code Extension Status"
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = CodeBridgeStatusBarWidget(project, scope)

    companion object {
        const val ID = "CodeBridge.ExtensionStatus"
    }
}

internal class CodeBridgeStatusBarWidget(private val project: Project, private val scope: CoroutineScope) : CustomStatusBarWidget {
    private val panel = NonOpaquePanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        // The status bar highlights a whole widget on hover, which here is every item at once. It
        // skips a disabled one, and each item draws its own highlight instead (ItemLabel). Swing
        // does not pass `enabled` down, so the items still take clicks and show tooltips. See
        // docs/platform-limitations.md, "Status bar widgets are registered up front".
        isEnabled = false
        // Set, so the status bar does not give the panel a widget border: each item has its own.
        border = JBUI.Borders.empty()
        isVisible = false
    }

    init {
        scope.launch {
            // Reconnects, as the settings page does: a backend that restarts or reconnects ends the
            // flow, and the items must come back with it rather than stay frozen at the last state.
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().statusItems(project.projectId()).collect { items ->
                        withContext(Dispatchers.EDT) { render(items) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension status items; retrying", error)
                }
                withContext(Dispatchers.EDT) { render(emptyList()) }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    /** The items' tooltips, which the mouse can move into to click their links, as in VS Code. */
    private val hover = StatusHover(this)

    override fun ID(): String = CodeBridgeStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = panel

    private fun render(items: List<StatusItemDto>) {
        panel.removeAll()
        hover.clear()
        // VS Code's order: left-aligned items first, and within a side the higher priority first.
        items.sortedWith(compareByDescending<StatusItemDto> { it.alignLeft }.thenByDescending { it.priority ?: 0.0 })
            .forEach { panel.add(label(it)) }
        panel.isVisible = items.isNotEmpty()
        panel.revalidate()
        panel.repaint()
    }

    private fun label(item: StatusItemDto): JComponent {
        val text = StatusText.parse(item.text)
        val label = ItemLabel(text.label, iconFor(text.icons, item.severity))
        hover.install(label, item.tooltip) { index ->
            run(item.name, label) { runStatusAction(project.projectId(), item.entryId, index) }
        }
        if (item.clickable) {
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.highlights = true
            object : ClickListener() {
                override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                    run(item.name, label) { runStatusItem(project.projectId(), item.entryId) }
                    return true
                }
            }.installOn(label)
        }
        return label
    }

    /**
     * Runs an item's command on the backend. A picker the command opens -- the Java extension's
     * status menu is one -- belongs above the item that was clicked, so [anchor] is left for it.
     */
    private fun run(what: String, anchor: JComponent, call: suspend CodeBridgeApi.() -> Unit) {
        project.service<CodeBridgeQuickInputs>()
            .anchorNextPick { anchor.takeIf { it.isShowing } ?: panel.takeIf { it.isShowing } }
        scope.launch {
            runCatching { CodeBridgeApi.getInstance().call() }
                .onFailure { if (it is CancellationException) throw it else LOG.warn("Could not run $what", it) }
        }
    }

    /**
     * One item, highlighted on hover and press the way the status bar highlights a widget of its
     * own (`WidgetEffectRenderer`): the same colours, inset by the theme's hover insets, rounded in
     * the new UI. Only a clickable item [highlights], as in VS Code.
     */
    private class ItemLabel(text: String, icon: Icon?) : JBLabel(text, icon, LEFT) {
        var highlights = false
        private var hovered = false
        private var pressed = false

        init {
            border = JBUI.CurrentTheme.StatusBar.Widget.border()
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = update { hovered = true }
                override fun mouseExited(e: MouseEvent) = update { hovered = false; pressed = false }
                override fun mousePressed(e: MouseEvent) = update { pressed = SwingUtilities.isLeftMouseButton(e) }
                override fun mouseReleased(e: MouseEvent) = update { pressed = false }
            })
        }

        // Fills the status bar's height, as a widget does, so the highlight is as tall as theirs.
        override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, Short.MAX_VALUE.toInt())

        override fun paintComponent(g: Graphics) {
            if (highlights && (hovered || pressed)) paintHighlight(g)
            super.paintComponent(g)
        }

        private fun paintHighlight(g: Graphics) {
            val bounds = Rectangle(0, 0, width, height)
            if (NewUI.isEnabled()) {
                // Kept inside the label, as the status bar keeps a widget's hover inside the widget.
                val own = insets
                val hover = JBUI.CurrentTheme.StatusBar.hoverInsets()
                JBInsets.removeFrom(
                    bounds, Insets(
                        max(0, own.top - hover.top), max(0, own.left - hover.left),
                        max(0, own.bottom - hover.bottom), max(0, own.right - hover.right),
                    )
                )
            }
            val g2 = g.create() as Graphics2D
            try {
                g2.color = if (pressed) JBUI.CurrentTheme.StatusBar.Widget.PRESSED_BACKGROUND
                else JBUI.CurrentTheme.StatusBar.Widget.HOVER_BACKGROUND
                if (NewUI.isEnabled()) {
                    GraphicsUtil.setupAAPainting(g2)
                    val arc = JBUIScale.scale(4).toFloat()
                    g2.fill(
                        RoundRectangle2D.Float(
                            bounds.x.toFloat(),
                            bounds.y.toFloat(),
                            bounds.width.toFloat(),
                            bounds.height.toFloat(),
                            arc,
                            arc
                        )
                    )
                } else g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
            } finally {
                g2.dispose()
            }
        }

        private fun update(change: () -> Unit) {
            change()
            repaint()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeStatusBarWidget::class.java)

        private const val RETRY_MILLIS = 2_000L

        /**
         * The icons of an item, in the order the extension wrote them: VS Code draws every `$(name)`
         * in the text -- GitLens's Launchpad reads `$(rocket)$(gitlens-unplug) Launchpad` -- and a
         * `~spin` one as the IDE's spinner. A label has one icon, so several become a row of them.
         *
         * An item the extension coloured as a warning or error leads with the matching icon unless
         * it already shows it: IntelliJ's status bar has no coloured backgrounds, so the icon is all
         * that is left to say it.
         */
        internal fun iconFor(icons: List<Codicons.Reference>, severity: String?): Icon? {
            val drawn = icons.map(Codicons::icon).toMutableList()
            val alarm = when (severity) {
                "error" -> AllIcons.General.Error
                "warning" -> AllIcons.General.Warning
                else -> null
            }
            if (alarm != null && alarm !in drawn) drawn.add(0, alarm)
            return when (drawn.size) {
                0 -> null
                1 -> drawn.single()
                else -> RowIcon(*drawn.flatMapIndexed { index, icon ->
                    if (index == 0) listOf(icon) else listOf(
                        GAP,
                        icon
                    )
                }.toTypedArray())
            }
        }

        /** Between two icons of one item: VS Code's codicons sit a few pixels apart. */
        private val GAP: Icon = EmptyIcon.create(2, 16)
    }
}
