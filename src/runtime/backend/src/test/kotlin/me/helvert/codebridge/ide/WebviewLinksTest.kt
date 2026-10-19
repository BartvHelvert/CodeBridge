package me.helvert.codebridge.ide

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.Urls
import me.helvert.codebridge.exthost.protocol.payload.Webviews
import me.helvert.codebridge.started
import me.helvert.codebridge.workbench.HostedUrls
import me.helvert.codebridge.workbench.HostedWebviews
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Links into the editor a page follows reach the extension they name, once the user allows it, as in VS Code. */
class WebviewLinksTest : EditorFixture() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val delivered = CopyOnWriteArrayList<UriComponents>()
    private val asked = CopyOnWriteArrayList<Messages.Message>()
    private var answer: Int? = 0
    private lateinit var urls: HostedUrls

    @AfterEach
    fun close() {
        scope.cancel()
        if (::urls.isInitialized) urls.close()
    }

    /** The project's host: its extension `me.cats` handles links, under the editor's own scheme. */
    private fun host() {
        urls = HostedUrls(
            "vscodium",
            callHost = { _, _, args ->
                delivered += UriComponents.from(Json.parseToJsonElement(args).jsonArray[1])!!
                "null"
            },
            activate = { _, _ -> },
            ask = { message -> asked += message; answer },
            owner = fixture.project,
        )
        started {
            urls.handle(
                MainThreadCall(
                    Urls.SERVICE, Urls.REGISTER,
                    """[7,{"value":"me.cats","_lower":"me.cats"},"Cats"]""",
                )
            )
        }.get(5, TimeUnit.SECONDS)
    }

    private fun webviews() = HostedWebviews(fixture.project, scope).apply {
        handle(
            MainThreadCall(
                Webviews.PANELS, Webviews.CREATE_PANEL, """[
            {"id":{"value":"me.cats"},"location":{"scheme":"file","path":"/ext/cats"}},"cats","catCoding",
            {"title":"Cats","webviewOptions":{"enableScripts":true}},{"viewColumn":-1}]"""
            )
        )
    }

    @Test
    fun `a link into the editor goes to the extension it names, once the user allows it`() {
        host()
        val webviews = webviews()
        runBlocking { webviews.runLink("cats", "vscode://me.cats/signed-in?code=1") }

        assertEquals(1, asked.size)
        val uri = delivered.single()
        assertEquals("vscodium", uri.scheme)
        assertEquals("me.cats", uri.authority)
        assertEquals("/signed-in", uri.path)
        assertEquals("code=1", uri.query)
    }

    @Test
    fun `a link into the editor the user declines goes nowhere`() {
        host()
        answer = 1
        val webviews = webviews()
        runBlocking { webviews.runLink("cats", "vscode://me.cats/signed-in") }

        assertEquals(1, asked.size)
        assertTrue(delivered.isEmpty())
    }
}
