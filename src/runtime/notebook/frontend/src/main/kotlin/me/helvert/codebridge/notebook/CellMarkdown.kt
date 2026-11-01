package me.helvert.codebridge.notebook

import com.intellij.ide.BrowserUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Dimension
import javax.swing.event.HyperlinkEvent
import javax.swing.text.View
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A markdown cell as VS Code shows it when it is not being edited: its text rendered, GitHub-flavoured,
 * in the IDE's documentation pane, with no frame of its own.
 */
internal object CellMarkdown {

    /** [markdown] as the HTML the pane shows. */
    fun toHtml(markdown: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(markdown as CharSequence)
        val html = HtmlGenerator(markdown, tree, flavour).generateHtml()
            .removeSurrounding("<body>", "</body>")
            // Swing spaces table cells apart, which doubles their borders.
            .replace("<table>", """<table cellspacing="0">""")
        // Nothing to see -- no text, or only an empty heading, list item or comment -- would leave
        // the cell a sliver, too thin even for its toolbar: it says how to edit it instead.
        return if (shows(html)) html else """<p class="$PLACEHOLDER">Empty markdown cell. Double-click or press Enter to edit.</p>"""
    }

    /** Whether [html] draws anything: text, or an element drawn without any. */
    private fun shows(html: String): Boolean {
        val withoutComments = html.replace(COMMENT, "")
        return VISIBLE_ELEMENT.containsMatchIn(withoutComments) ||
            withoutComments.replace(TAG, "").replace("&nbsp;", " ").isNotBlank()
    }

    /** A pane for a cell's rendered text. */
    fun pane(): JBHtmlPane = MarkdownCellPane(
        JBHtmlPaneStyleConfiguration.builder().enableInlineCodeBackground(true).enableCodeBlocksBackground(true).build(),
        JBHtmlPaneConfiguration.builder().customStyleSheet(css()).build(),
    ).apply {
        isOpaque = false
        isFocusable = false
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) (event.url?.toString() ?: event.description)?.let(BrowserUtil::browse)
        }
    }

    /** As tall as its text at the width it is given. */
    private class MarkdownCellPane(style: JBHtmlPaneStyleConfiguration, configuration: JBHtmlPaneConfiguration) :
        JBHtmlPane(style, configuration) {
        // Laid out at the width of the notebook, wrapping its lines, not at that of its longest one.
        override fun getPreferredSize(): Dimension {
            val width = parent?.width ?: 0
            if (width <= 0) return super.getPreferredSize()
            val insets = insets
            val view = getUI().getRootView(this)
            view.setSize((width - insets.left - insets.right).coerceAtLeast(1).toFloat(), 0f)
            return Dimension(width, ceil(view.getPreferredSpan(View.Y_AXIS)).toInt() + insets.top + insets.bottom)
        }

        // As narrow as the notebook, however long a word it can't break: else the cell grows wider
        // than the notebook to fit it, and what centres on the cell, as its Add Cell pill, goes off-screen.
        override fun getMinimumSize(): Dimension = super.getMinimumSize().also { it.width = 0 }
    }

    /** Headings sized as VS Code sizes them, against the IDE's font; `px` as at 100%, which the pane scales. */
    private fun css(): String {
        val base = (UIUtil.getLabelFont().size / JBUIScale.scale(1f)).roundToInt()
        fun size(factor: Double) = (base * factor).roundToInt()
        val border = ColorUtil.toHtmlColor(JBColor.border())
        val hint = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        return """
            h1 { font-size: ${size(2.0)}px; margin: 12px 0 8px 0; }
            h2 { font-size: ${size(1.5)}px; margin: 12px 0 6px 0; }
            h3 { font-size: ${size(1.25)}px; margin: 10px 0 4px 0; }
            h4, h5, h6 { font-size: ${base}px; margin: 8px 0 4px 0; }
            p { margin: 4px 0 4px 0; }
            ul, ol { margin-top: 2px; margin-bottom: 4px; }
            table { margin: 6px 0 6px 0; }
            th, td { border: 1px solid $border; padding: 3px 8px 3px 8px; }
            .$PLACEHOLDER { color: $hint; }
        """.trimIndent()
    }

    private const val PLACEHOLDER = "codebridge-placeholder"
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]*>")
    private val VISIBLE_ELEMENT = Regex("<(img|hr|input)\\b", RegexOption.IGNORE_CASE)
}
