package me.helvert.codebridge.webview

import me.helvert.codebridge.rpc.WebviewEventDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** Messages wait for the page that is to hear them, not one it has replaced. */
class PageOutboxTest {
    private val delivered = mutableListOf<String>()
    private val outbox = PageOutbox { delivered += it.json }

    private fun message(json: String) = WebviewEventDto(WebviewEventDto.MESSAGE, "cats", json)

    @Test
    fun `messages wait for the page to listen, then arrive in order`() {
        val load = outbox.loading()
        outbox.post(message("1"))
        outbox.post(message("2"))
        assertEquals(emptyList(), delivered)

        outbox.ready(load)
        outbox.post(message("3"))
        assertEquals(listOf("1", "2", "3"), delivered)
    }

    @Test
    fun `a replaced page saying it is ready does not let messages through to the page loading after it`() {
        val first = outbox.loading()
        // The extension sets its HTML again before the first page's signal is handled.
        val second = outbox.loading()
        outbox.ready(first)
        outbox.post(message("1"))
        assertEquals(emptyList(), delivered)

        outbox.ready(second)
        assertEquals(listOf("1"), delivered)
    }

    @Test
    fun `a page that never listens holds only the latest messages`() {
        val load = outbox.loading()
        repeat(PageOutbox.MAX_PENDING + 10) { outbox.post(message("$it")) }

        outbox.ready(load)
        assertEquals(PageOutbox.MAX_PENDING, delivered.size)
        assertEquals("10", delivered.first())
    }
}
