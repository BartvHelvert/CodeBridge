package me.helvert.codebridge.notebook

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.View
import kotlin.math.ceil

/**
 * Cell outputs, each drawn in the first format it offers that can be, in VS Code's order: HTML
 * before markdown before images before text. Text, streams, errors, images, markdown and HTML
 * without scripts are drawn with the IDE's own components; HTML with scripts and SVG need a page in
 * the IDE's browser ([BrowserOutputFactory]), and without one the output's next format is drawn --
 * or, for HTML it has nothing else for, what the IDE's HTML pane makes of it without its scripts.
 * Renderers' own formats, such as widgets', show their plain text, or say what they are.
 */
internal object OutputViews {
    const val ERROR = "application/vnd.code.notebook.error"
    const val STDOUT = "application/vnd.code.notebook.stdout"
    const val STDERR = "application/vnd.code.notebook.stderr"
    private const val HTML = "text/html"
    private const val JAVASCRIPT = "application/javascript"
    private const val SVG = "image/svg+xml"
    private const val MARKDOWN = "text/markdown"

    /** The formats drawn, the one preferred first: an output in several is drawn in the first of these it has. */
    private val PREFERRED = listOf(
        ERROR, STDOUT, STDERR, HTML, JAVASCRIPT, SVG, MARKDOWN,
        "image/png", "image/jpeg", "image/gif", "image/bmp",
        "application/json", "text/x-json", "text/latex", "text/plain",
    )

    private const val MAX_CHARS = 200_000
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val SCRIPT = Regex("<script\\b", RegexOption.IGNORE_CASE)
    private val SCRIPT_ELEMENT = Regex("<script\\b.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val prettyJson = Json { prettyPrint = true }

    /** Where an output is drawn: in a cell of the notebook that scrolls in [scrollPane], until [parent] is disposed. */
    class Place(val scrollPane: JComponent, val parent: Disposable)

    fun component(output: OutputDto, place: Place): JComponent {
        val items = PREFERRED.mapNotNull { mime -> output.items.firstOrNull { it.mime == mime } } +
            output.items.filter { it.mime !in PREFERRED && it.mime.startsWith("text/") }
        if (items.isEmpty()) return note("An output in ${output.items.joinToString { it.mime }.ifEmpty { "no format" }}, which this editor cannot show")
        for (item in items) drawn(item, place, last = item === items.last())?.let { return it }
        return note("An output in ${output.items.joinToString { it.mime }}, which this editor cannot show")
    }

    /** [item] drawn, or null when it cannot be here and the output's next format is to be tried instead; [last] has none. */
    private fun drawn(item: OutputItemDto, place: Place, last: Boolean): JComponent? = when {
        item.mime == ERROR -> error(item.data)
        item.mime == STDERR -> text(item.data.decodeToString(), JBColor.RED)
        item.mime == HTML -> {
            val html = item.data.decodeToString()
            if (SCRIPT.containsMatchIn(html)) browser(html, place) ?: if (last) html(html, place) else null
            else html(html, place)
        }
        item.mime == JAVASCRIPT -> browser("<script>${item.data.decodeToString()}</script>", place)
        item.mime == SVG -> browser("""<img src="data:image/svg+xml;base64,${Base64.getEncoder().encodeToString(item.data)}">""", place)
        item.mime == MARKDOWN -> markdown(item.data.decodeToString())
        item.mime.startsWith("image/") -> image(item.data) ?: note("An image that could not be read")
        item.mime == "application/json" || item.mime == "text/x-json" -> text(json(item.data.decodeToString()), null)
        else -> text(item.data.decodeToString(), null)
    }

    /** `{name, message, stack}`, as VS Code writes an error output. */
    private fun error(data: ByteArray): JComponent {
        val json = runCatching { Json.parseToJsonElement(data.decodeToString()) as? JsonObject }.getOrNull()
        fun field(key: String) = (json?.get(key) as? JsonPrimitive)?.contentOrNull
        val heading = listOfNotNull(field("name"), field("message")).joinToString(": ").ifEmpty { "Error" }
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }
        // Cut short rather than keep the cell wider than its view.
        val label = object : JBLabel(clean(heading)) {
            override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
        }
        panel.add(label.apply { foreground = JBColor.RED; font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.NORTH)
        field("stack")?.takeIf { it.isNotBlank() }?.let { panel.add(text(it, null), BorderLayout.CENTER) }
        return panel
    }

    private fun image(data: ByteArray): JComponent? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(data)) }.getOrNull() ?: return null
        return JBLabel(ImageIcon(image)).apply { horizontalAlignment = JBLabel.LEFT }
    }

    /** A page in the IDE's browser, or null when there is none. */
    private fun browser(html: String, place: Place): JComponent? {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return BrowserOutputFactory.EP.extensionList.firstNotNullOfOrNull {
            it.create(html, CellView.notebookBackground(), scheme.defaultForeground, place.scrollPane, place.parent)
        }
    }

    /** HTML in the IDE's HTML pane, without its scripts, which the pane cannot run. */
    private fun html(html: String, place: Place): JComponent {
        val pane = HtmlOutputPane(
            JBHtmlPaneStyleConfiguration.builder().build(),
            JBHtmlPaneConfiguration.builder().customStyleSheet(css()).build(),
        )
        pane.text = SCRIPT_ELEMENT.replace(html, "")
        return SidewaysScroller(pane, place.scrollPane)
    }

    private fun markdown(markdown: String): JComponent = CellMarkdown.pane().apply { text = CellMarkdown.toHtml(markdown) }

    private fun json(text: String): String =
        runCatching { prettyJson.encodeToString(Json.parseToJsonElement(text)) }.getOrDefault(text)

    private fun text(raw: String, color: java.awt.Color?): JComponent {
        val cleaned = clean(raw)
        val shown = if (cleaned.length > MAX_CHARS) cleaned.take(MAX_CHARS) + "\n... (${cleaned.length - MAX_CHARS} more characters)" else cleaned
        return object : JBTextArea(shown.trimEnd('\n')) {
            // Wrapped, its minimum width is the width it was last wrapped to; the notebook's layout
            // never makes a cell narrower than that, so the cell would never shrink with its view.
            override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
        }.apply {
            isEditable = false
            isOpaque = false
            // Wrapped to the cell's width, as the notebook is never wider than its view.
            lineWrap = true
            font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
            color?.let { foreground = it }
            border = JBUI.Borders.empty()
        }
    }

    private fun note(text: String): JComponent = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    /** Without the terminal's colour codes, which a kernel's tracebacks are full of, and with `\r\n` folded. */
    private fun clean(text: String): String = ANSI.replace(text, "").replace("\r\n", "\n")

    /** Tables ruled as a notebook's are, such as the `dataframe` tables pandas writes. */
    private fun css(): String {
        val border = ColorUtil.toHtmlColor(JBColor.border())
        return """
            table { margin: 4px 0 4px 0; }
            th, td { border: 1px solid $border; padding: 2px 6px 2px 6px; text-align: right; }
            th { font-weight: bold; }
        """.trimIndent()
    }

    /**
     * HTML laid out at the width it is given, its lines wrapped to it, or wider where something in it
     * cannot be -- a table of many columns -- for [SidewaysScroller] to scroll.
     */
    private class HtmlOutputPane(style: JBHtmlPaneStyleConfiguration, configuration: JBHtmlPaneConfiguration) :
        JBHtmlPane(style, configuration) {
        /** The width it is to be laid out at, which [SidewaysScroller] sets. */
        var available = 0

        init {
            isOpaque = false
            isFocusable = false
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) (event.url?.toString() ?: event.description)?.let(BrowserUtil::browse)
            }
        }

        override fun getPreferredSize(): Dimension {
            if (available <= 0) return super.getPreferredSize()
            val insets = insets
            val view = getUI().getRootView(this)
            val inner = (available - insets.left - insets.right).coerceAtLeast(1)
            view.setSize(inner.toFloat(), 0f)
            val width = maxOf(inner, ceil(view.getMinimumSpan(View.X_AXIS)).toInt())
            if (width != inner) view.setSize(width.toFloat(), 0f)
            return Dimension(width + insets.left + insets.right, ceil(view.getPreferredSpan(View.Y_AXIS)).toInt() + insets.top + insets.bottom)
        }

        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = false
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    }

    /**
     * [pane] as wide as the cell, scrolled sideways where it is wider. A wheel turned over it scrolls
     * the notebook's [notebook] scroll pane, unless Shift is held, which scrolls it sideways.
     */
    private class SidewaysScroller(private val pane: HtmlOutputPane, notebook: JComponent) :
        JBScrollPane(pane, VERTICAL_SCROLLBAR_NEVER, HORIZONTAL_SCROLLBAR_AS_NEEDED) {
        init {
            border = JBUI.Borders.empty()
            viewportBorder = null
            isOpaque = false
            viewport.isOpaque = false
            isWheelScrollingEnabled = false
            addMouseWheelListener { event ->
                if (event.isShiftDown) {
                    val bar = horizontalScrollBar
                    bar.value += event.unitsToScroll * bar.unitIncrement.coerceAtLeast(JBUI.scale(16))
                } else {
                    notebook.dispatchEvent(SwingUtilities.convertMouseEvent(event.component, event, notebook))
                }
            }
            // Its height depends on the width it is laid out at: laid out again at a new one.
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    if (pane.available == width) return
                    pane.available = width
                    revalidate()
                }
            })
        }

        // As narrow as the cell makes it: the pane scrolls, rather than widening the notebook.
        override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

        override fun getPreferredSize(): Dimension {
            if (pane.available <= 0 && width > 0) pane.available = width
            val wanted = pane.preferredSize
            val bar = if (pane.available > 0 && wanted.width > pane.available) horizontalScrollBar.preferredSize.height else 0
            return Dimension(minOf(wanted.width, pane.available.takeIf { it > 0 } ?: wanted.width), wanted.height + bar)
        }
    }
}
