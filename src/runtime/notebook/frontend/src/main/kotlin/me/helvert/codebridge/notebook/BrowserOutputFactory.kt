package me.helvert.codebridge.notebook

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import java.awt.Color
import javax.swing.JComponent

/**
 * Draws the outputs only a browser can: HTML with scripts, and SVG. The notebook's webview module
 * provides one, since a page needs the IDE's browser (the Web Browser (JCEF) plugin), which this
 * module does not require; without it those outputs are drawn as well as the IDE's own components can.
 */
interface BrowserOutputFactory {
    /**
     * [html] as a page as tall as its content and as wide as the cell, on [background] in [foreground],
     * until [parent] is disposed; null when the browser cannot be used. A wheel turned over the page
     * scrolls [scrollPane], the notebook's, rather than the page.
     */
    fun create(html: String, background: Color, foreground: Color, scrollPane: JComponent, parent: Disposable): JComponent?

    companion object {
        val EP: ExtensionPointName<BrowserOutputFactory> = ExtensionPointName("me.helvert.codebridge.notebookBrowserOutput")
    }
}
