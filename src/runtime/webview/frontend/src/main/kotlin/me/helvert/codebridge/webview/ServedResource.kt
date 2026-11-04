package me.helvert.codebridge.webview

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefResourceReadCallback
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * A file the browser asked for, and its media type; or with [status] 304, word that the copy the
 * asker has is the file as it is now. [headers] are sent besides the usual ones, such as its `ETag`.
 */
internal class Loaded(
    val bytes: ByteArray,
    val mimeType: String,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Answers a request with what [load] reads, in [scope]; nothing found is a 404. [what] names the page
 * in the log, when reading fails.
 */
internal fun served(scope: CoroutineScope, what: String, load: suspend () -> Loaded?): CefResourceRequestHandler =
    object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler =
            Served(scope, what, load)
    }

/** The response [served] gives, for a scheme handler factory, which answers requests no browser made. */
internal fun servedResponse(scope: CoroutineScope, what: String, load: suspend () -> Loaded?): CefResourceHandler =
    Served(scope, what, load)

/**
 * One response, read when the browser opens it and handed over as it asks for it.
 *
 * Both of CEF's spellings are answered. The in-process browser calls [open] and [read]; the
 * out-of-process one -- the frontend's, in split mode -- forwards only [processRequest] and
 * [readResponse], and a handler without them fails every request as an unreachable site.
 */
private class Served(
    private val scope: CoroutineScope,
    private val what: String,
    private val load: suspend () -> Loaded?,
) : CefResourceHandlerAdapter() {
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
                LOG.debug("Could not load a resource of $what", error)
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
        response.status = found.status
        response.statusText = if (found.status == 304) "Not Modified" else "OK"
        response.mimeType = found.mimeType
        // A page's own address is another origin than its resources', and fonts and module
        // scripts are fetched with CORS.
        response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
        response.setHeaderByName("Cache-Control", "no-cache", true)
        found.headers.forEach { (name, value) -> response.setHeaderByName(name, value, true) }
        responseLength.set(found.bytes.size)
    }

    override fun read(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefResourceReadCallback?): Boolean =
        readResponse(dataOut, bytesToRead, bytesRead, null)

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback?): Boolean {
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

    private companion object {
        val LOG = Logger.getInstance(Served::class.java)
    }
}
