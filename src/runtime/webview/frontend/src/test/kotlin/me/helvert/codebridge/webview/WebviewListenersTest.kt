package me.helvert.codebridge.webview

import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Messages for a page nobody draws yet wait for it. The webviews and their messages come from the
 * backend by two flows, so either may be first.
 */
class WebviewListenersTest {
    private val cats = WebviewDto("cats", WebviewDto.PANEL, "catCoding", "me.cats")

    private class Heard : CodeBridgeWebviews.Listener {
        val messages = mutableListOf<String>()
        override fun updated(webview: WebviewDto?) = Unit
        override fun event(event: WebviewEventDto) {
            messages += event.json
        }
    }

    private fun message(json: String) = WebviewEventDto(WebviewEventDto.MESSAGE, "cats", json)

    @Test
    fun `a message for a webview already described waits for its listener`() {
        val listeners = WebviewListeners()
        listeners.update(listOf(cats))
        listeners.event(message("1"))

        val heard = Heard()
        listeners.listen("cats", heard)
        assertEquals(listOf("1"), heard.messages)
    }

    @Test
    fun `a message that arrives before its webview is described waits for its listener`() {
        val listeners = WebviewListeners()
        // `$createPanel` then `$postMessage`, the message's flow faster than the webviews'.
        listeners.event(message("1"))
        listeners.update(listOf(cats))

        val heard = Heard()
        listeners.listen("cats", heard)
        assertEquals(listOf("1"), heard.messages)
    }

    @Test
    fun `messages for a webview that has gone are not kept`() {
        val listeners = WebviewListeners()
        listeners.update(listOf(cats))
        listeners.event(message("1"))
        listeners.update(emptyList())
        listeners.event(message("2"))

        val heard = Heard()
        listeners.listen("cats", heard)
        assertEquals(emptyList(), heard.messages)
    }

    @Test
    fun `only the latest few webviews never described have their messages kept`() {
        val listeners = WebviewListeners()
        for (i in 0 until 100) listeners.event(WebviewEventDto(WebviewEventDto.MESSAGE, "early$i", "$i"))
        listeners.update((0 until 100).map { WebviewDto("early$it", WebviewDto.PANEL, "catCoding", "me.cats") })

        val first = Heard().also { listeners.listen("early0", it) }
        val last = Heard().also { listeners.listen("early99", it) }
        assertEquals(emptyList(), first.messages)
        assertEquals(listOf("99"), last.messages)
    }
}
