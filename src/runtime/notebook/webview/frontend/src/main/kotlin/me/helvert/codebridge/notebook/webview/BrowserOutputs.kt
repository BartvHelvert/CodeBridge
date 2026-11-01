package me.helvert.codebridge.notebook.webview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.helvert.codebridge.notebook.BrowserOutputFactory
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Notebook outputs in the IDE's browser: see [BrowserOutputFactory]. */
internal class BrowserOutputs : BrowserOutputFactory {
    override fun create(html: String, background: Color, foreground: Color, scrollPane: JComponent, parent: Disposable): JComponent? {
        if (!JBCefApp.isSupported()) return null
        return BrowserOutput(html, background, foreground, scrollPane, parent).component
    }
}

/**
 * One output's page, as VS Code draws an output in its notebook's webview: the HTML as written, its
 * scripts run, and links opened in the browser rather than in the page. Drawn off screen, so it
 * scrolls with the notebook like any component, and as tall as its content, which the page reports
 * as it changes; the notebook scrolls, not the page, except sideways when it is wider than the cell.
 * Used on the EDT, except where the browser calls in from its own threads.
 */
private class BrowserOutput(html: String, background: Color, foreground: Color, scrollPane: JComponent, parent: Disposable) {
    private val browser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build()
    private val heights = JBCefJSQuery.create(browser as JBCefBrowserBase)
    /**
     * The content's height, in pixels, as the page last reported it. Not `height`: inside the panel
     * below, that name is the panel's own height, which would keep it as tall as it already is.
     */
    private var contentHeight = JBUI.scale(INITIAL_HEIGHT)

    val component: JComponent = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension = Dimension(0, contentHeight)
        override fun getMinimumSize(): Dimension = Dimension(0, contentHeight)
    }

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, heights)
        component.isOpaque = false
        component.add(browser.component, BorderLayout.CENTER)
        heights.addHandler { reported ->
            reported.toIntOrNull()?.let { SwingUtilities.invokeLater { resized(it) } }
            null
        }
        // The page's links, followed or opened as a window, go to the browser.
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean {
                if (!userGesture || frame?.isMain != true) return false
                request?.url?.let(BrowserUtil::browse)
                return true
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                targetUrl?.let(BrowserUtil::browse)
                return true
            }
        }, browser.cefBrowser)
        // The notebook scrolls, not the page: a wheel turned over it is handed on, unless it is turned
        // with Shift, to scroll the page sideways. Consumed, the page never hears of it.
        browser.cefBrowser.uiComponent.addMouseWheelListener { event ->
            if (event.isShiftDown) return@addMouseWheelListener
            scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(event.component, event, scrollPane))
            event.consume()
        }
        browser.setPageBackgroundColor(ColorUtil.toHtmlColor(background))
        browser.loadHTML(document(html, background, foreground))
    }

    private fun resized(reported: Int) {
        val new = reported.coerceAtLeast(1)
        if (new == contentHeight) return
        contentHeight = new
        component.revalidate()
        component.repaint()
    }

    /**
     * [html] in a page of the notebook's colours and the IDE's font, which reports its content's
     * height whenever that changes: as it loads, as its scripts draw, as it is laid out at a new width.
     */
    private fun document(html: String, background: Color, foreground: Color): String {
        val font = UIUtil.getLabelFont()
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <style>
            html, body { margin: 0; padding: 0; overflow-y: hidden; background: ${ColorUtil.toHtmlColor(background)}; color: ${ColorUtil.toHtmlColor(foreground)}; }
            body { font-family: '${font.family}', sans-serif; font-size: ${font.size}px; }
            #$OUTPUT { display: flow-root; overflow-x: auto; }
            </style></head>
            <body><div id="$OUTPUT">
            $html
            </div>
            <script>
            (function () {
              const output = document.getElementById('$OUTPUT');
              let last = -1;
              function report() {
                const height = Math.ceil(output.getBoundingClientRect().height);
                if (height === last) return;
                last = height;
                ${heights.inject("String(height)")}
              }
              new ResizeObserver(report).observe(output);
              window.addEventListener('load', report);
              report();
            })();
            </script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        /** Until the page says how tall it is. */
        private const val INITIAL_HEIGHT = 24
        private const val OUTPUT = "codebridge-output"
    }
}
