package me.helvert.codebridge.status

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.rpc.StatusItemDto
import me.helvert.codebridge.rpc.StatusTooltipActionDto
import me.helvert.codebridge.rpc.StatusTooltipBlockDto
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.AttributeSet
import javax.swing.text.Element
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent
import javax.swing.text.html.HTML
import kotlin.time.Duration.Companion.milliseconds

/**
 * Status bar items' tooltips, shown as VS Code shows them: the extension's Markdown, rendered, above
 * the item while the mouse is on it -- and while the mouse is on the hover itself, so its links can
 * be clicked. rust-analyzer's restart and open-logs, and GitLens's "Connect to GitHub", are links in
 * their items' tooltips and nowhere else.
 *
 * IntelliJ's own tooltips close as soon as the mouse leaves what they belong to, so this is a popup
 * of its own, one at a time.
 *
 * The backend sends the tooltip in blocks: HTML is drawn as the documentation pane draws it, a rule
 * as a separator, and a column of command links -- rust-analyzer's -- as the rows of a popup list.
 */
internal class StatusHover(parent: Disposable) : Disposable {

    private var popup: JBPopup? = null
    private var shownFor: JComponent? = null
    private var pending: JComponent? = null

    /** Shows the hover for [pending] once the mouse has rested on it. */
    private var showing: Job? = null

    /** Closes the hover once the mouse has been off both the item and the hover for a moment. */
    private var hiding: Job? = null
    private var awayTicks = 0

    private val contents = HashMap<JComponent, Content>()

    /** The images of the hover about to be shown, being fetched. */
    private var images: Job? = null

    /** Where images are fetched and a waiting hover shown; ends with this. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("Status bar hover"))

    private class Content(val blocks: List<StatusTooltipBlockDto>, val link: (Int) -> Unit) {
        val html: String get() = blocks.mapNotNull { it.html }.joinToString("")
    }

    init {
        Disposer.register(parent, this)
    }

    /**
     * Shows [blocks] above [item] while it is hovered; [link] is called with the index of a
     * `codebridge-status:` link clicked in it. Replaces whatever was installed for [item] before.
     */
    fun install(item: JComponent, blocks: List<StatusTooltipBlockDto>?, link: (Int) -> Unit) {
        if (blocks.isNullOrEmpty()) {
            contents.remove(item)
            return
        }
        val first = contents.put(item, Content(blocks, link)) == null
        if (first) item.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (item !in contents) return
                if (shownFor === item) return
                pending = item
                // Its images load while the hover waits to appear; most are here by then.
                val html = contents[item]?.html ?: return
                images = scope.launch { HoverImages.fetch(html) }
                showing?.cancel()
                showing = scope.launch(onEdt) {
                    delay(SHOW_DELAY_MS.milliseconds)
                    pending?.let(::show)
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (pending === item) {
                    pending = null
                    showing?.cancel()
                }
            }

            // A click runs the item's command, as in VS Code, which puts the hover away.
            override fun mousePressed(e: MouseEvent) = close()
        })
    }

    /** Forgets everything installed: the items are about to be replaced. */
    fun clear() {
        contents.clear()
        pending = null
        showing?.cancel()
    }

    private fun show(item: JComponent) {
        val content = contents[item] ?: return
        if (!item.isShowing || !isPointerOver(item)) return
        val loading = images
        if (loading != null && !loading.isCompleted) {
            scope.launch {
                loading.join()
                withContext(onEdt) {
                    if (pending === item && shownFor !== item) show(item)
                }
            }
            return
        }
        close()
        // A popup's background, as the editor's documentation hover has: the theme draws its separators and
        // list rows for that one, and Islands draws them in the colour of a tooltip's.
        val panel = JPanel(VerticalLayout(0)).apply {
            background = JBUI.CurrentTheme.Popup.BACKGROUND
            // A separator's margin at the edges too, so text has as much room against the hover's
            // edge as against a separator, and a row between the two sits in the middle.
            val edge = (JBUI.CurrentTheme.Popup.separatorInsets() as? JBInsets)?.unscaled?.top ?: SEPARATOR_MARGIN
            border = JBUI.Borders.empty(edge, 0)
        }
        val panes = mutableListOf<JBHtmlPane>()
        for ((html, actions, separator) in content.blocks) panel.add(
            when {
                separator -> Separator()
                actions.isNotEmpty() -> actions(actions, content)
                else -> pane(html.orEmpty(), content).also { panes += it }
            }
        )
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setFocusable(false)
            .setRequestFocus(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()
        for (pane in panes) Disposer.register(popup, pane)
        this.popup = popup
        shownFor = item
        awayTicks = 0
        hiding?.cancel()
        hiding = scope.launch(onEdt) {
            while (true) {
                delay(HIDE_CHECK_MS.milliseconds)
                closeIfLeft()
            }
        }
        popup.show(RelativePoint(item, Point(0, -panel.preferredSize.height - JBUI.scale(GAP))))
    }

    /** [actions] as the rows of a popup list, to run on a click. */
    private fun actions(actions: List<StatusTooltipActionDto>, content: Content): JComponent =
        JPanel(VerticalLayout(0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(ROW_LIST_INSET, 0)
            for (action in actions) add(ActionRow(action) {
                close()
                content.link(action.index)
            })
        }

    @Suppress("HttpUrlsUsage") // Recognizes web links; makes none.
    private fun pane(source: String, content: Content): JBHtmlPane {
        val (html, images) = HoverImages.resolve(source)
        val pane = JBHtmlPane(
            JBHtmlPaneStyleConfiguration(),
            JBHtmlPaneConfiguration {
                iconResolver { src -> icon(src) }
                imageResolverFactory = { images }
            },
        )
        pane.isEditable = false
        pane.background = JBUI.CurrentTheme.Popup.BACKGROUND
        pane.border = JBUI.Borders.empty(PANE_INSET, SIDE_INSET)
        pane.text = html
        // As wide as the text needs, up to VS Code's hover width; longer paragraphs wrap.
        val maxWidth = JBUI.scale(MAX_WIDTH)
        if (pane.preferredSize.width > maxWidth) {
            pane.setSize(maxWidth, Short.MAX_VALUE.toInt())
            pane.preferredSize = Dimension(maxWidth, pane.preferredSize.height)
        }
        val buttons = LinkButtons(pane)
        pane.addHyperlinkListener(buttons::update)
        pane.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) = buttons.clear()
        })
        pane.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val href = event.description ?: return@addHyperlinkListener
            when {
                href.startsWith(StatusItemDto.LINK_SCHEME) -> {
                    close()
                    href.removePrefix(StatusItemDto.LINK_SCHEME).toIntOrNull()?.let(content.link)
                }

                href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:") -> {
                    close()
                    BrowserUtil.browse(href)
                }
            }
        }
        return pane
    }

    private fun closeIfLeft() {
        val item = shownFor
        val content = popup?.takeIf { it.isVisible }?.content
        if (item == null || content == null) return close()
        if (isPointerOver(item) || isPointerOver(content)) awayTicks = 0
        else if (++awayTicks * HIDE_CHECK_MS >= HIDE_DELAY_MS) close()
    }

    private fun close() {
        hiding?.cancel()
        popup?.cancel()
        popup = null
        shownFor = null
    }

    override fun dispose() {
        scope.cancel()
        clear()
        close()
    }

    /**
     * One action, laid out and highlighted as a row of IntelliJ's popup lists: the list's row
     * height, the icon at its gap from the label, and a rounded highlight under the mouse. Its text
     * lines up with the HTML around it.
     */
    private class ActionRow(action: StatusTooltipActionDto, run: () -> Unit) :
        JBLabel(action.label, action.icon?.let(::icon), LEFT) {
        private var hovered = false

        init {
            iconTextGap = JBUI.CurrentTheme.ActionsList.elementIconGap()
            border = JBUI.Borders.empty(0, SIDE_INSET)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = action.title
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = hover(true)
                override fun mouseExited(e: MouseEvent) = hover(false)
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) run()
                }
            })
        }

        override fun getPreferredSize(): Dimension =
            super.getPreferredSize().apply { height = maxOf(height, JBUI.CurrentTheme.List.rowHeight()) }

        override fun paintComponent(g: Graphics) {
            if (hovered) {
                val g2 = g.create() as Graphics2D
                try {
                    GraphicsUtil.setupAAPainting(g2)
                    g2.color = JBUI.CurrentTheme.List.Selection.background(true)
                    val inset = JBUI.scale(ROW_HIGHLIGHT_INSET).toFloat()
                    val arc = JBUI.CurrentTheme.Popup.Selection.ARC.float
                    g2.fill(RoundRectangle2D.Float(inset, 0f, width - 2 * inset, height.toFloat(), arc, arc))
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }

        private fun hover(on: Boolean) {
            hovered = on
            foreground = if (on) JBUI.CurrentTheme.List.Selection.foreground(true) else UIUtil.getLabelForeground()
            repaint()
        }
    }

    /**
     * The links of a row of nothing but links -- GitLens's commit toolbar, icons between `|`s --
     * highlighted under the mouse as toolbar buttons are. A link in running text keeps the pane's
     * own hover, an underline.
     */
    private class LinkButtons(private val pane: JBHtmlPane) {
        private var highlight: Any? = null

        fun update(event: HyperlinkEvent) {
            clear()
            if (event.eventType != HyperlinkEvent.EventType.ENTERED) return
            val leaf = event.sourceElement ?: return
            val range = buttonRange(leaf) ?: return
            highlight = runCatching { pane.highlighter.addHighlight(range.first, range.last, Painter) }.getOrNull()
        }

        fun clear() {
            highlight?.let(pane.highlighter::removeHighlight)
            highlight = null
        }

        /** Where the link of [leaf] starts and ends, if its paragraph holds only links. */
        private fun buttonRange(leaf: Element): IntRange? {
            val href = href(leaf) ?: return null
            val paragraph = leaf.parentElement ?: return null
            var start = -1
            var end = -1
            for (i in 0 until paragraph.elementCount) {
                val child = paragraph.getElement(i)
                val link = href(child)
                if (link == null) {
                    val text = pane.document.getText(child.startOffset, child.endOffset - child.startOffset)
                    if (!BETWEEN_BUTTONS.matches(text)) return null
                } else if (link == href && (start < 0 || child.startOffset == end)) {
                    if (start < 0) start = child.startOffset
                    end = child.endOffset
                }
            }
            return if (start < 0) null else start..end
        }

        private fun href(element: Element): String? =
            (element.attributes.getAttribute(HTML.Tag.A) as? AttributeSet)?.getAttribute(HTML.Attribute.HREF) as? String

        /** `ActionButton`'s hover: its colour and corners, a little wider than what it covers. */
        private object Painter : Highlighter.HighlightPainter {
            override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: JTextComponent) {
                val start = c.modelToView2D(p0) ?: return
                val end = c.modelToView2D(p1) ?: return
                // A link wrapped onto two lines is left alone: it is no button.
                if (start.y != end.y) return
                val pad = JBUI.scale(BUTTON_PADDING).toFloat()
                val arc = DarculaUIUtil.BUTTON_ARC.float
                val g2 = g.create() as Graphics2D
                try {
                    GraphicsUtil.setupAAPainting(g2)
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    g2.fill(
                        RoundRectangle2D.Double(
                            start.x - pad, start.y - pad, end.x - start.x + 2 * pad, start.height + 2 * pad,
                            arc.toDouble(), arc.toDouble(),
                        )
                    )
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /**
     * The Markdown's `---`, spaced as a popup list spaces its separators and kept to the width of the
     * text, as the documentation pane draws a rule.
     */
    private class Separator : JComponent() {
        private val margin get() = JBUI.CurrentTheme.Popup.separatorInsets().top

        override fun getPreferredSize() = Dimension(0, 2 * margin + JBUI.scale(1))

        override fun paintComponent(g: Graphics) {
            val side = JBUI.scale(SIDE_INSET)
            g.color = JBUI.CurrentTheme.Popup.separatorColor()
            g.fillRect(side, margin, width - 2 * side, JBUI.scale(1))
        }
    }

    companion object {
        /** VS Code's `workbench.hover.delay`. */
        private const val SHOW_DELAY_MS = 500L
        private const val HIDE_DELAY_MS = 300L
        private const val HIDE_CHECK_MS = 100L
        private const val MAX_WIDTH = 500
        private const val GAP = 4

        /**
         * Above and below each block of HTML, on top of its paragraphs' own padding; and left of all
         * text, the rows' labels included.
         */
        private const val PANE_INSET = 2
        private const val SIDE_INSET = 10

        /** Above and below a list of actions, and how far a row's highlight keeps from the sides. */
        private const val ROW_LIST_INSET = 2
        private const val ROW_HIGHLIGHT_INSET = 4

        /** `Popup.separatorInsets`' own default, unscaled. */
        private const val SEPARATOR_MARGIN = 4

        /** Around a link highlighted as a toolbar button. */
        private const val BUTTON_PADDING = 2

        /** What may stand between the links of a toolbar: spaces and the `|`s that group them. */
        private val BETWEEN_BUTTONS = Regex("""[\s |]*""")

        /** Where the hover is shown and put away: whatever dialog is open, as a Swing timer would. */
        private val onEdt get() = Dispatchers.EDT + ModalityState.any().asContextElement()

        /** An `<icon src="codicon:name">` the backend wrote for a `$(name)` in the tooltip. */
        internal fun icon(src: String): Icon? = Codicons.iconOf(src)

        private fun isPointerOver(component: JComponent): Boolean {
            if (!component.isShowing) return false
            val pointer = MouseInfo.getPointerInfo()?.location ?: return false
            val origin = component.locationOnScreen
            return Rectangle(origin.x, origin.y, component.width, component.height).contains(pointer)
        }
    }
}
