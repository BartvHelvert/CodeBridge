package me.helvert.codebridge.ide

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Webviews
import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.workbench.HostedWebviews
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which panels outlive their host: those whose extension registered a serializer, kept dormant with
 * what their page saved, and saved with the project so they come back when it opens again.
 */
class WebviewPanelsTest : EditorFixture() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun cancelScope() = scope.cancel()

    private fun HostedWebviews.call(service: String, method: String, args: String) =
        handle(MainThreadCall(service, method, args))

    private fun HostedWebviews.create(handle: String, viewType: String) = call(
        Webviews.PANELS, Webviews.CREATE_PANEL, """[
        {"id":{"value":"me.cats"},"location":{"scheme":"file","path":"/ext/cats"}},"$handle","$viewType",
        {"title":"Cats $handle","panelOptions":{"enableFindWidget":true},"webviewOptions":{"enableScripts":true},
         "serializeBuffersForPostMessage":true},
        {"viewColumn":-2,"preserveFocus":true}]"""
    )

    private fun HostedWebviews.all(): Map<String, WebviewDto> =
        runBlocking { views().first() }.associateBy { it.handle }

    @Test
    fun `a panel its extension can restore stays dormant when the host stops, with its page's state`() {
        val webviews = HostedWebviews(fixture.project, scope)
        webviews.create("kept", "catCoding")
        webviews.create("gone", "dogCoding")
        webviews.call(
            Webviews.PANELS,
            Webviews.REGISTER_SERIALIZER,
            """["catCoding",{"serializeBuffersForPostMessage":true}]"""
        )
        webviews.call(Webviews.SERVICE, Webviews.SET_HTML, """["kept","<p>cats</p>"]""")
        webviews.saveState("kept", """{"count":3}""")

        val live = webviews.all().getValue("kept")
        assertEquals("<p>cats</p>", live.html)
        assertEquals(WebviewDto.SIDE_GROUP, live.column)
        assertTrue(live.findWidget)
        assertTrue(live.binaryMessages)
        assertTrue(live.preserveFocus)

        webviews.hostStopped()
        val after = webviews.all()
        assertEquals(setOf("kept"), after.keys)
        val dormant = after.getValue("kept")
        assertTrue(dormant.dormant)
        assertEquals("", dormant.html)
        assertEquals("""{"count":3}""", dormant.restoredState)
    }

    @Test
    fun `clearing a page's html and setting it again is a change, however quickly it happens`() {
        val webviews = HostedWebviews(fixture.project, scope)
        webviews.create("graph", "catCoding")
        webviews.call(Webviews.SERVICE, Webviews.SET_HTML, """["graph","<p>cats</p>"]""")
        val first = webviews.all().getValue("graph")

        // How an extension reloads its page; whoever collects in between may see neither step.
        webviews.call(Webviews.SERVICE, Webviews.SET_HTML, """["graph",""]""")
        webviews.call(Webviews.SERVICE, Webviews.SET_HTML, """["graph","<p>cats</p>"]""")
        val reloaded = webviews.all().getValue("graph")

        assertEquals(first.html, reloaded.html)
        assertTrue(reloaded.htmlVersion > first.htmlVersion)
    }

    @Test
    fun `restorable panels are saved with the project and come back dormant`() {
        val first = HostedWebviews(fixture.project, scope)
        first.create("a", "catCoding")
        first.create("b", "dogCoding")
        first.call(Webviews.PANELS, Webviews.REGISTER_SERIALIZER, """["catCoding",{}]""")
        first.saveState("a", """{"count":1}""")
        first.shown("a", visible = false, active = true, position = 1)
        val saved = first.state
        assertEquals(listOf("a"), saved.panels.map { it.handle })

        val second = HostedWebviews(fixture.project, scope)
        second.loadState(saved)
        val restored = second.all().getValue("a")
        assertTrue(restored.dormant)
        assertEquals("Cats a", restored.title)
        assertEquals("catCoding", restored.viewType)
        assertEquals("me.cats", restored.extensionId)
        assertEquals("""{"count":1}""", restored.restoredState)
        // Back in the group it was in, in front as it was.
        assertEquals(1, restored.column)
        assertFalse(restored.preserveFocus)
        assertTrue(restored.scripts)
        assertTrue(restored.findWidget)
        // Saved again as it was, though nothing has restored it yet.
        assertEquals(listOf("a"), second.state.panels.map { it.handle })
    }

    @Test
    fun `closing a dormant panel forgets it`() {
        val webviews = HostedWebviews(fixture.project, scope)
        webviews.create("a", "catCoding")
        webviews.call(Webviews.PANELS, Webviews.REGISTER_SERIALIZER, """["catCoding",{}]""")
        webviews.hostStopped()
        webviews.close("a")
        assertNull(webviews.all()["a"])
        assertTrue(webviews.state.panels.isEmpty())
    }
}
