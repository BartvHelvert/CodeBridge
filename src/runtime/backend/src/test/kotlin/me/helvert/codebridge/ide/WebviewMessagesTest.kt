package me.helvert.codebridge.ide

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Webviews
import me.helvert.codebridge.workbench.HostedWebviews
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Messages an extension posts to its page: each one the host was told was delivered reaches the frontend. */
class WebviewMessagesTest : EditorFixture() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cancelScope() = scope.cancel()

    private fun HostedWebviews.create(handle: String) = handle(
        MainThreadCall(
            Webviews.PANELS, Webviews.CREATE_PANEL, """[
        {"id":{"value":"me.cats"},"location":{"scheme":"file","path":"/ext/cats"}},"$handle","catCoding",
        {"title":"Cats","webviewOptions":{"enableScripts":true}},{"viewColumn":-1}]"""
        )
    )

    private fun HostedWebviews.post(handle: String, json: String): String? =
        handle(MainThreadCall(Webviews.SERVICE, Webviews.POST_MESSAGE, """["$handle","$json"]"""))

    @Test
    fun `a message posted while the frontend is not listening reaches it once it is`() {
        val webviews = HostedWebviews(fixture.project, scope)
        webviews.create("cats")
        // Between the frontend's connections: reconnecting, or not yet started.
        assertEquals(Webviews.DELIVERED, webviews.post("cats", "1"))

        val received = runBlocking { withTimeoutOrNull(2.seconds) { webviews.changes.first() } }
        assertEquals("1", received?.json)
    }

    @Test
    fun `messages posted while the frontend is busy all reach it, in order`() = runBlocking {
        val webviews = HostedWebviews(fixture.project, scope)
        webviews.create("cats")
        val drawn = CompletableDeferred<Unit>()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val collecting = launch(Dispatchers.Default) {
            webviews.changes
                // A frontend still busy on the EDT with the first one.
                .onEach { drawn.await() }
                .collect { received += it.json }
        }

        repeat(COUNT) { assertEquals(Webviews.DELIVERED, webviews.post("cats", "$it")) }
        drawn.complete(Unit)

        withTimeoutOrNull(5.seconds) { while (received.size < COUNT) delay(10.milliseconds) }
        collecting.cancel()
        assertEquals((0 until COUNT).map { "$it" }, received.toList())
    }

    @Test
    fun `a message there is no room for is reported as not delivered`() {
        val webviews = HostedWebviews(fixture.project, scope)
        webviews.create("cats")
        // No frontend takes them: posted until the queue is full.
        var posted = 0
        while (webviews.post("cats", "$posted") == Webviews.DELIVERED) posted++
        assertTrue(posted >= COUNT, "Held $posted messages before refusing one")

        val first = runBlocking { withTimeoutOrNull(2.seconds) { webviews.changes.first() } }
        assertEquals("0", first?.json)
    }

    private companion object {
        const val COUNT = 1_000
    }
}
