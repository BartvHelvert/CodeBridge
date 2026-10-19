package me.helvert.codebridge.webview

import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto

/**
 * The webviews as the backend last described them, who listens to each, and the messages for a
 * webview nobody draws yet: a page's first messages are often posted while it is being made,
 * before its tab has asked for it -- and, as they come by a flow of their own, even before the
 * webview is described. Those for a webview that has come and gone are dropped. Used on the EDT only.
 */
internal class WebviewListeners {
    var all: Map<String, WebviewDto> = emptyMap()
        private set
    private val listeners = mutableMapOf<String, MutableList<CodeBridgeWebviews.Listener>>()
    private val unclaimed = mutableMapOf<String, ArrayDeque<WebviewEventDto>>()

    /** Every webview described so far, so a message for one not yet described can be told from one for a webview gone. */
    private val seen = mutableSetOf<String>()

    /**
     * Has [listener] told about webview [handle]: what it is now, if it is known yet, and the
     * messages that waited for it. Returns what stops that.
     */
    fun listen(handle: String, listener: CodeBridgeWebviews.Listener): () -> Unit {
        listeners.getOrPut(handle) { mutableListOf() } += listener
        all[handle]?.let(listener::updated)
        unclaimed.remove(handle)?.forEach(listener::event)
        return { listeners[handle]?.let { it -= listener; if (it.isEmpty()) listeners.remove(handle) } }
    }

    fun update(webviews: List<WebviewDto>) {
        val previous = all
        all = webviews.associateBy { it.handle }
        for ((handle, those) in listeners.toList()) {
            val now = all[handle]
            if (now != previous[handle]) those.toList().forEach { it.updated(now) }
        }
        seen += all.keys
        unclaimed.keys.removeAll { it in seen && it !in all }
    }

    /** Hands a message to its webview's listeners, or keeps it for them. */
    fun event(event: WebviewEventDto) {
        val those = listeners[event.handle]
        if (those != null) those.toList().forEach { it.event(event) }
        else if (event.handle in all || event.handle !in seen) {
            unclaimed.getOrPut(event.handle) { ArrayDeque() }.apply {
                addLast(event)
                while (size > MAX_UNCLAIMED) removeFirst()
            }
            // Webviews never described are not kept for long: only the latest few.
            val early = unclaimed.keys.filter { it !in seen }
            early.take((early.size - MAX_UNDESCRIBED).coerceAtLeast(0)).forEach(unclaimed::remove)
        }
    }

    private companion object {
        const val MAX_UNCLAIMED = 256
        const val MAX_UNDESCRIBED = 16
    }
}
