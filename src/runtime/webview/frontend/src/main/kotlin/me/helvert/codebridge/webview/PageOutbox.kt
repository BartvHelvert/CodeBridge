package me.helvert.codebridge.webview

import me.helvert.codebridge.rpc.WebviewEventDto

/**
 * Messages for the page on screen, held from the moment a page starts loading until it says it
 * can listen, then handed to [deliver] in the order they came.
 *
 * Each load is counted, and a page says which load it is: the one it replaced may still say it is
 * ready after the next has begun, when nothing in the new one listens yet.
 *
 * Used on the EDT only.
 */
internal class PageOutbox(private val deliver: (WebviewEventDto) -> Unit) {
    private var loads = 0
    private var ready = false
    private val pending = ArrayDeque<WebviewEventDto>()

    /** A page starts loading: messages wait for it. Returns its load's number. */
    fun loading(): Int {
        ready = false
        return ++loads
    }

    /** Page [load] can listen: what waited for it is delivered, if it is still the page on screen. */
    fun ready(load: Int) {
        if (load != loads) return
        ready = true
        while (pending.isNotEmpty()) deliver(pending.removeFirst())
    }

    /** Hands [event] to the page, or keeps it until it listens: the latest [MAX_PENDING], should it never. */
    fun post(event: WebviewEventDto) {
        if (ready) return deliver(event)
        pending.addLast(event)
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    companion object {
        /** As many as the backend holds for the frontend. */
        const val MAX_PENDING = 4_096
    }
}
