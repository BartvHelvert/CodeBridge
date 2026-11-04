package me.helvert.codebridge.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.project.projectId
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefClient
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import me.helvert.codebridge.rpc.CodeBridgeApi
import me.helvert.codebridge.rpc.WorkerHostPage
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.net.URI
import java.util.*

/**
 * Runs one of the backend's web worker extension hosts in a page of the IDE's browser that is never
 * shown -- what VS Code's window does in a hidden frame -- for as long as [run] runs.
 *
 * The page starts VS Code's worker host script, which the backend serves, as the files the host
 * fetches for its extensions; it hands the host what the backend sends, and the backend what the
 * host sends, each in the order it came. See `worker-host.html` for the page's half.
 */
internal class WorkerHostBrowser(private val project: Project, private val id: String) : Disposable {
    /**
     * The browser's own client, with room for [query] made before the browser is: being never shown,
     * the browser is made at once, and a query made after that might never reach its page.
     */
    private val client: JBCefClient = JBCefApp.getInstance().createClient()
        .apply { setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 1) }
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setClient(client)
        // Never shown, so drawn off screen, made without a window to show it in, and drawn as seldom as it can be.
        .setOffScreenRendering(true)
        .setCreateImmediately(true)
        .setWindowlessFramerate(1)
        .build()
    private val query: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val pageHost = WorkerHostPage.host(id)
    private val address = "https://$pageHost${WorkerHostPage.INDEX}"

    /** Scheduled as [run] runs; read by the browser's threads, which serve the page's files in it. */
    @Volatile private var scope: CoroutineScope? = null
    /** What the host sent, in order, for the backend. */
    private val inbox = Channel<List<ByteArray>>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<String>()

    init {
        // The browser goes with its client.
        Disposer.register(this, client)
        Disposer.register(this, query)
        query.addHandler { request ->
            fromPage(request)
            null
        }
        browser.jbCefClient.addRequestHandler(Requests(), browser.cefBrowser)
        // The host's own failures are in the console of a page nobody can open; they land in the log.
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int,
            ): Boolean {
                if (level != CefSettings.LogSeverity.LOGSEVERITY_VERBOSE && level != CefSettings.LogSeverity.LOGSEVERITY_INFO)
                    LOG.warn("Web worker extension host $id: $message ($source:$line)")
                return false
            }
        }, browser.cefBrowser)
    }

    /**
     * Runs the host until the backend stops it, the page fails, or the caller is cancelled; the
     * backend hears of a failure here.
     */
    suspend fun run() = coroutineScope {
        scope = this
        val api = CodeBridgeApi.getInstance()
        val projectId = project.projectId()
        browser.loadURL(address)
        launch {
            for (batch in inbox) api.workerHostInbox(projectId, id, batch)
        }
        // Collected once the page can take what comes: until then the backend keeps it.
        val running = launch {
            ready.await()
            api.workerHostOutbox(projectId, id).collect(::deliver)
        }
        val failure = select<String?> {
            running.onJoin { null }
            stopped.onAwait { it }
        }
        if (failure != null) {
            LOG.warn("The web worker extension host $id stopped: $failure")
            withContext(NonCancellable) { runCatching { api.workerHostFailed(projectId, id, failure) } }
        }
        coroutineContext.cancelChildren()
    }

    /** Hands the host messages from the backend; the page keeps those that come before the host has a port. */
    private fun deliver(messages: List<ByteArray>) {
        val encoded = messages.joinToString(",", "[", "]") { "\"" + Base64.getEncoder().encodeToString(it) + "\"" }
        browser.cefBrowser.executeJavaScript("window.__codeBridgeDeliver($encoded);", address, 0)
    }

    /** What the page sent: a kind letter, then its body; see `worker-host.html`. Called on a browser thread. */
    private fun fromPage(request: String) {
        val body = request.drop(1)
        when (request.firstOrNull()) {
            'r' -> ready.complete(Unit)
            'c' -> LOG.info("The web worker extension host $id is running")
            'm' -> inbox.trySend(body.split(',').map { Base64.getDecoder().decode(it) })
            'e' -> stopped.complete(body)
        }
    }

    override fun dispose() {
        inbox.close()
    }

    /** Answers the page's address, and the files the host fetches under it; leaves the rest to the network. */
    private inner class Requests : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?, frame: CefFrame?, request: CefRequest, isNavigation: Boolean, isDownload: Boolean,
            requestInitiator: String?, disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler? {
            val url = request.url ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.rawAuthority?.lowercase() != pageHost) return null
            val scope = scope ?: return null
            val path = uri.path.orEmpty()
            return served(scope, "the web worker extension host") {
                if (path == WorkerHostPage.INDEX) Loaded(PAGE.replace("/*SEND*/", query.inject("payload")).toByteArray(), "text/html")
                else CodeBridgeApi.getInstance().workerHostResource(project.projectId(), id, path)?.let { found -> found.bytes?.let { Loaded(it, found.mimeType) } }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WorkerHostBrowser::class.java)

        private val PAGE: String =
            checkNotNull(WorkerHostBrowser::class.java.getResourceAsStream("/webview/worker-host.html")) { "Missing /webview/worker-host.html" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
