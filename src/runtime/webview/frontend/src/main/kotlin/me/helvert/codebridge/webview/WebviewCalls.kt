package me.helvert.codebridge.webview

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Calls to the backend that answer nothing: what a page sent, where a webview is shown. Made in
 * [scope], to [target]; one that fails is logged, since the page has nobody to tell.
 *
 * Made one after another, each once the one before is done, so the extension hears them in the
 * order they were made, as VS Code keeps a page's messages in order.
 */
internal class WebviewCalls<T>(private val scope: CoroutineScope, private val target: suspend () -> T) {
    private val queue = Channel<suspend T.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (work in queue) make(work)
        }.invokeOnCompletion { queue.close() }
    }

    fun call(work: suspend T.() -> Unit) {
        queue.trySend(work)
    }

    /** A call that may take long, such as running a command, and that nothing after it waits for. */
    fun callApart(work: suspend T.() -> Unit) {
        scope.launch { make(work) }
    }

    private suspend fun make(work: suspend T.() -> Unit) {
        try {
            target().work()
        } catch (cancelled: CancellationException) {
            // The calls' own end; any other, such as a call that timed out, must not end those after it.
            currentCoroutineContext().ensureActive()
            LOG.debug("Webview call cancelled", cancelled)
        } catch (error: Exception) {
            LOG.debug("Webview call failed", error)
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(WebviewCalls::class.java)
    }
}
