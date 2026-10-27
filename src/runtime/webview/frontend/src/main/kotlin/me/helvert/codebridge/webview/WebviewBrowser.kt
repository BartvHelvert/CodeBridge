package me.helvert.codebridge.webview

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.project.projectId
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.helvert.codebridge.rpc.CodeBridgeApi
import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefResourceReadCallback
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI
import java.util.*
import javax.swing.JComponent

/**
 * One webview's page, in the IDE's browser: what VS Code's webview host does for a page.
 *
 * The page is served at an address of its own under `vscode-cdn.net`, where the files it asks for
 * by `asWebviewUri` are too, so the policy extensions write against `webview.cspSource` admits
 * them. Both are answered here, never from the network: the page from the HTML the extension set,
 * its files by the backend, which checks them against the webview's `localResourceRoots`.
 *
 * Messages go both ways through the page's `acquireVsCodeApi`; those for the page wait until it has
 * loaded, since its script cannot listen for them before. What it saves with `setState` is kept
 * here, and handed back when the page is loaded again -- when the extension changes its HTML.
 *
 * Used on the EDT, except where the browser calls in from its own threads.
 */
internal class WebviewBrowser(
    private val project: Project,
    parentScope: CoroutineScope,
    val handle: String,
    /** Whether the page is in a tool window, which has the panel's background rather than the editor's. */
    private val inPanel: Boolean,
) : Disposable {

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().setEnableOpenDevToolsMenuItem(true).build()
    private val query: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val pageHost = "$handle.vscode-webview.vscode-cdn.net"

    /**
     * Where the page now on screen was loaded from: a new address for each load, as VS Code gives its
     * pages. The browser ignores being sent again to the address it is already showing, so a page
     * set while the first, empty one was up would never be loaded.
     */
    private var address = "https://$pageHost/index.html"

    /** The number of the load now on screen, which its page says when it is ready; see [PageOutbox]. */
    private var load = 0

    val component: JComponent get() = browser.component

    /** Told when the page asks to search it, with Ctrl/Cmd+F; see [find]. */
    var findRequested: () -> Unit = {}

    /** What the page on screen was made from; null before the first [show]. */
    private var page: WebviewDto? = null
    /** The document served at [address]: read by the browser's own threads. */
    @Volatile private var document: String = ""
    /** What the page last saved with `setState`, as JSON. */
    @Volatile private var state: String? = null
    private val outbox = PageOutbox(::deliver)
    private val calls = WebviewCalls(scope) { CodeBridgeApi.getInstance() }
    private var theme = WebviewTheme.current(inPanel)

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { request ->
            fromPage(request)
            null
        }
        browser.jbCefClient.addRequestHandler(Requests(), browser.cefBrowser)
        browser.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            // `window.open` and `target="_blank"`: the browser, not another window of this one.
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String?, targetFrameName: String?): Boolean {
                targetUrl?.let { url -> scope.launch(Dispatchers.EDT) { follow(url) } }
                return true
            }
        }, browser.cefBrowser)
        // A page that fails says so only in its console: a script refused by its policy, a module that
        // did not load. Nobody sees that console unless it is opened, so its warnings land in the log.
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int,
            ): Boolean {
                // The out-of-process browser names the level as text and passes one it does not know
                // as DEFAULT, so that is kept too: better a stray line than a lost error.
                if (level != CefSettings.LogSeverity.LOGSEVERITY_VERBOSE && level != CefSettings.LogSeverity.LOGSEVERITY_INFO)
                    LOG.warn("Webview $handle: $message ($source:$line)")
                return false
            }
        }, browser.cefBrowser)
        browser.setPageBackgroundColor(WebviewTheme.css(background()))
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { themeChanged() })
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { themeChanged() })
    }

    /** Shows [webview]'s page, loading it again only when what it is made of changed. */
    fun show(webview: WebviewDto) {
        val previous = page
        page = webview
        if (previous == null) theme = WebviewTheme.current(inPanel, webview.themeColors)
        else if (previous.themeColors != webview.themeColors) themeChanged()
        if (previous != null && previous.html == webview.html && previous.htmlVersion == webview.htmlVersion &&
            previous.scripts == webview.scripts &&
            previous.binaryMessages == webview.binaryMessages) return
        load()
    }

    /** Hands [event] to the page, once it can listen. */
    fun post(event: WebviewEventDto) {
        // A page without scripts has nothing to hear it with, and would never say it is ready.
        if (event.kind != WebviewEventDto.MESSAGE || page?.scripts != true) return
        outbox.post(event)
    }

    private fun load() {
        val webview = page ?: return
        load = outbox.loading()
        // A restored panel's page starts from what it saved before its host went.
        if (state == null) state = webview.restoredState
        val bootstrap = WebviewPage.bootstrap(query.inject("payload"), state, theme, webview.binaryMessages, load)
        document = WebviewPage.document(webview.html, webview.scripts, bootstrap, theme)
        address = "https://$pageHost/index.html?id=$load"
        browser.loadURL(address)
    }

    private fun deliver(event: WebviewEventDto) {
        if (page?.scripts != true) return
        val buffers = event.buffers.joinToString(",", "[", "]") { "\"" + Base64.getEncoder().encodeToString(it) + "\"" }
        browser.cefBrowser.executeJavaScript("window.__codeBridgeReceive && window.__codeBridgeReceive(${JsonPrimitive(event.json)}, $buffers);", address, 0)
    }

    /** What the page's script sent: a kind letter, then its body; see `bootstrap.js`. Called on a browser thread. */
    private fun fromPage(request: String) {
        val body = request.drop(1)
        when (request.firstOrNull()) {
            'm' -> call { webviewMessage(project.projectId(), handle, body, emptyList()) }
            // A message with `ArrayBuffer`s: `[json, [base64, ...]]`.
            'b' -> {
                val parts = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return
                val json = parts.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return
                val buffers = (parts.getOrNull(1) as? JsonArray).orEmpty().mapNotNull { encoded ->
                    encoded.jsonPrimitive.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                }
                call { webviewMessage(project.projectId(), handle, json, buffers) }
            }
            's' -> {
                state = body
                call { webviewState(project.projectId(), handle, body) }
            }
            'f' -> scope.launch(Dispatchers.EDT) { findRequested() }
            // Ready, with the number of the load it is.
            'r' -> body.toIntOrNull()
                ?.let { its -> scope.launch(Dispatchers.EDT) { outbox.ready(its) } }
            'l' -> scope.launch(Dispatchers.EDT) { follow(body) }
        }
    }

    /**
     * A link the page followed out of itself: a web address opens in the browser, a `command:` link
     * is the extension's to run, if its options let the page run commands, and a link into the
     * editor (`vscode://publisher.name/...`) goes to the extension it names, once the user allows it.
     * The page's own resources are not on the web, and are not opened there.
     */
    private fun follow(url: String) {
        val scheme = url.substringBefore(':').lowercase()
        when {
            scheme == "command" -> if (page?.commandLinks == true) calls.callApart {
                runWebviewLink(
                    project.projectId(),
                    handle,
                    url
                )
            }

            scheme == "vscode" || scheme == "vscode-insiders" -> calls.callApart {
                runWebviewLink(
                    project.projectId(),
                    handle,
                    url
                )
            }

            host(url)?.endsWith(CDN) == true -> LOG.debug("Not opening $url of webview $handle outside it")
            scheme == "http" || scheme == "https" || scheme == "mailto" -> BrowserUtil.browse(url)
            else -> LOG.debug("Not following $url from webview $handle")
        }
    }

    /**
     * Searches the page for [text], highlighting every match: the first one, or with [next] the one
     * after the current, [forward] or back.
     */
    fun find(text: String, forward: Boolean, matchCase: Boolean, next: Boolean) {
        if (text.isEmpty()) stopFinding() else browser.cefBrowser.find(text, forward, matchCase, next)
    }

    fun stopFinding() = browser.cefBrowser.stopFinding(true)

    private fun themeChanged() {
        theme = WebviewTheme.current(inPanel, page?.themeColors.orEmpty())
        browser.setPageBackgroundColor(WebviewTheme.css(background()))
        // A page without scripts has no script to tell, so it is drawn again with the new colours.
        if (page?.scripts == true) browser.cefBrowser.executeJavaScript("window.__codeBridgeTheme && window.__codeBridgeTheme(${theme.json});", address, 0)
        else load()
    }

    private fun background() = if (inPanel) com.intellij.util.ui.UIUtil.getPanelBackground()
        else EditorColorsManager.getInstance().globalScheme.defaultBackground

    private fun call(work: suspend CodeBridgeApi.() -> Unit) = calls.call(work)

    override fun dispose() {
        scope.cancel()
    }

    /** A file the browser asked for, and its media type. */
    private class Loaded(val bytes: ByteArray, val mimeType: String)

    /** Answers the page's address and its resources' addresses; leaves the rest to the network. */
    private inner class Requests : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?, frame: CefFrame?, request: CefRequest, isNavigation: Boolean, isDownload: Boolean,
            requestInitiator: String?, disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler? {
            val url = request.url ?: return null
            val host = host(url) ?: return null
            return when {
                host == pageHost -> served { Loaded(document.toByteArray(Charsets.UTF_8), "text/html") }
                host.endsWith(RESOURCES) -> served {
                    CodeBridgeApi.getInstance().webviewResource(project.projectId(), handle, url)?.let { Loaded(it.bytes, it.mimeType) }
                }
                else -> null
            }
        }

        /** The page stays where it is: an address it navigates to is followed outside it. Its frames go where they like. */
        override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
            if (!frame.isMain) return false
            val url = request.url ?: return false
            if (host(url) == pageHost) return false
            scope.launch(Dispatchers.EDT) { follow(url) }
            return true
        }
    }

    private fun served(load: suspend () -> Loaded?) = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler = Served(load)
    }

    /**
     * One response, read when the browser opens it and handed over as it asks for it.
     *
     * Both of CEF's spellings are answered. The in-process browser calls [open] and [read]; the
     * out-of-process one -- the frontend's, in split mode -- forwards only [processRequest] and
     * [readResponse], and a handler without them fails every request as an unreachable site.
     */
    private inner class Served(private val load: suspend () -> Loaded?) : CefResourceHandlerAdapter() {
        @Volatile private var loaded: Loaded? = null
        private var offset = 0

        override fun open(request: CefRequest?, handleRequest: BoolRef, callback: CefCallback): Boolean {
            handleRequest.set(false)
            return processRequest(request, callback)
        }

        override fun processRequest(request: CefRequest?, callback: CefCallback): Boolean {
            scope.launch(Dispatchers.IO) {
                loaded = try {
                    load()
                } catch (cancelled: CancellationException) {
                    callback.cancel()
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Could not load a resource of webview $handle", error)
                    null
                }
                callback.Continue()
            }
            return true
        }

        override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
            val found = loaded
            if (found == null) {
                response.status = 404
                response.statusText = "Not Found"
                responseLength.set(0)
                return
            }
            response.status = 200
            response.statusText = "OK"
            response.mimeType = found.mimeType
            // The page's own address is another origin than its resources', and fonts and module
            // scripts are fetched with CORS.
            response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
            response.setHeaderByName("Cache-Control", "no-cache", true)
            responseLength.set(found.bytes.size)
        }

        override fun read(
            dataOut: ByteArray,
            bytesToRead: Int,
            bytesRead: IntRef,
            callback: CefResourceReadCallback?
        ): Boolean =
            readResponse(dataOut, bytesToRead, bytesRead, null)

        override fun readResponse(
            dataOut: ByteArray,
            bytesToRead: Int,
            bytesRead: IntRef,
            callback: CefCallback?
        ): Boolean {
            val bytes = loaded?.bytes
            val count = if (bytes == null) 0 else minOf(bytesToRead, bytes.size - offset)
            if (bytes == null || count <= 0) {
                bytesRead.set(0)
                return false
            }
            System.arraycopy(bytes, offset, dataOut, 0, count)
            offset += count
            bytesRead.set(count)
            return true
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WebviewBrowser::class.java)
        private const val CDN = ".vscode-cdn.net"
        private const val RESOURCES = ".vscode-resource$CDN"

        /**
         * [url]'s host as written. Not [URI.getHost]: a resource's host is usually `file%2B...`, its
         * `+` escaped by VS Code's `Uri`, and Java finds no host in a name with an escape in it.
         */
        internal fun host(url: String): String? = runCatching { URI(url).rawAuthority?.lowercase() }.getOrNull()
    }
}
