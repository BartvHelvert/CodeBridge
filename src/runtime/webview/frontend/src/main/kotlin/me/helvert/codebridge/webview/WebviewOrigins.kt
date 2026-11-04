package me.helvert.codebridge.webview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CoroutineScope
import me.helvert.codebridge.rpc.CodeBridgeApi
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.network.CefRequest
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The origins webview pages are served at: one for each view type of an extension, as VS Code's
 * `WebviewOriginStore` gives them, so what the browser keeps for a page -- its service worker, the
 * files that keeps, its storage -- is there again when a page of that type is opened once more.
 *
 * The browser asks the origin itself for what its service worker fetches (`service-worker.js`), which
 * no browser's own request handler sees: the worker's requests are no page's. They are answered here,
 * for every page of the origin by its handle: the page's document, and the files it reads, from the
 * backend, which checks each against that page's webview.
 */
@Service
internal class WebviewOrigins(private val scope: CoroutineScope) {

    /** A page being shown, at its origin. */
    interface Page {
        val project: Project
        val handle: String
        /** The document the page now has. */
        val document: String
    }

    private val pages = ConcurrentHashMap<String, Page>()
    private val registered = ConcurrentHashMap.newKeySet<String>()

    /** Answers [page]'s requests at [host] from now on, until [detach]ed. */
    fun attach(host: String, page: Page) {
        pages[page.handle] = page
        // Once for each origin, for as long as the browser runs: CEF keeps a factory until it is replaced.
        if (registered.add(host)) CefApp.getInstance().registerSchemeHandlerFactory("https", host, Factory())
    }

    fun detach(page: Page) {
        pages.remove(page.handle, page)
    }

    private inner class Factory : CefSchemeHandlerFactory {
        override fun create(browser: CefBrowser?, frame: CefFrame?, schemeName: String?, request: CefRequest): CefResourceHandler {
            val uri = runCatching { URI(request.url) }.getOrNull()
            val query = uri?.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
            val page = query["handle"]?.let(pages::get)
            return servedResponse(scope, "a webview origin") {
                when (uri?.path) {
                    SERVICE_WORKER_PATH -> Loaded(SERVICE_WORKER, "text/javascript")
                    // What the service worker could not do, which only its own console would show.
                    "/service-worker-log" -> {
                        LOG.warn("Webview service worker at ${uri.host}: ${query["message"]}")
                        Loaded(ByteArray(0), "text/plain")
                    }
                    "/index.html" -> page?.let { Loaded(it.document.toByteArray(Charsets.UTF_8), "text/html") }
                    "/vscode-resource" -> page?.let { resource(it, query["url"], query["version"]) }
                    // Never the network: the origin is a real one's.
                    else -> null
                }
            }
        }
    }

    /** [url] for [page]: unchanged since [version], or its bytes with the version they are. */
    private suspend fun resource(page: Page, url: String?, version: String?): Loaded? {
        url ?: return null
        val found = CodeBridgeApi.getInstance().webviewResource(page.project.projectId(), page.handle, url, version) ?: return null
        val headers = mapOf("ETag" to found.version)
        return found.bytes?.let { Loaded(it, found.mimeType, headers = headers) }
            ?: Loaded(ByteArray(0), found.mimeType, status = 304, headers = headers)
    }

    companion object {
        const val SERVICE_WORKER_PATH: String = "/service-worker.js"
        private val SERVICE_WORKER: ByteArray = resource("service-worker.js")

        /**
         * The document a page is loaded with before its origin's service worker controls it: one
         * that starts the worker and loads the page again, then through it.
         */
        val STARTER: ByteArray = resource("service-worker-start.html")

        private val LOG = Logger.getInstance(WebviewOrigins::class.java)

        fun getInstance(): WebviewOrigins = service()

        /** The host the pages of view type [viewType] of extension [extensionId] are served at. */
        fun host(extensionId: String, viewType: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$extensionId\n$viewType".toByteArray(Charsets.UTF_8))
            val name = digest.take(16).joinToString("") { "%02x".format(it) }
            return "$name.vscode-webview.vscode-cdn.net"
        }

        private fun resource(name: String): ByteArray =
            checkNotNull(WebviewOrigins::class.java.getResourceAsStream("/webview/$name")) { "Missing /webview/$name" }
                .use { it.readBytes() }
    }
}
