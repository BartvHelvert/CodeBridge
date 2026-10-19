package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Clipboard
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.rpc.ClientRequestDto
import me.helvert.codebridge.started
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** `env.openExternal` and `env.clipboard`: done by the client, and answered once it has. */
class HostedClientTest {

    private val client = HostedClient()

    private fun openUri(uri: String) = started {
        HostedWindow.handle(
            MainThreadCall(
                HostedWindow.SERVICE, HostedWindow.OPEN_URI,
                buildJsonArray { add(UriComponents.of(uri).toJson()); add(uri); addJsonObject {} }.toString()
            ),
            client::browse,
        )
    }

    private fun clipboard(call: MainThreadCall) = started { client.clipboard(call) }

    private fun request() = client.pending.value.single()

    @Test
    fun `a web link is answered once the client has opened it, with whether it did`() {
        val opened = openUri("https://gitkraken.dev/login?state=s")
        assertFalse(opened.isDone)
        assertEquals(
            ClientRequestDto(request().id, ClientRequestDto.BROWSE, "https://gitkraken.dev/login?state=s"),
            request()
        )

        client.answer(request().id, true, null)
        assertEquals("true", opened.get(5, TimeUnit.SECONDS).json)
        assertTrue(client.pending.value.isEmpty())

        val declined = openUri("https://example.com/")
        client.answer(request().id, false, null)
        assertEquals("false", declined.get(5, TimeUnit.SECONDS).json)
    }

    @Test
    fun `only web and mail links go to the browser`() {
        assertEquals("false", openUri("vscodium://acme.x/cb").get(5, TimeUnit.SECONDS).json)
        assertTrue(client.pending.value.isEmpty())
    }

    @Test
    fun `the clipboard is the client's, written and read back`() {
        val written =
            clipboard(MainThreadCall(Clipboard.SERVICE, Clipboard.WRITE, """["https://gitkraken.dev/login"]"""))
        assertEquals(
            ClientRequestDto(request().id, ClientRequestDto.WRITE_CLIPBOARD, "https://gitkraken.dev/login"),
            request()
        )
        client.answer(request().id, true, null)
        written.get(5, TimeUnit.SECONDS)

        val read = clipboard(MainThreadCall(Clipboard.SERVICE, Clipboard.READ, "[]"))
        assertEquals(ClientRequestDto.READ_CLIPBOARD, request().kind)
        client.answer(request().id, true, "https://gitkraken.dev/login")
        assertEquals("\"https://gitkraken.dev/login\"", read.get(5, TimeUnit.SECONDS).json)

        // An empty clipboard reads as "", which the host hands to the extension as is.
        val empty = clipboard(MainThreadCall(Clipboard.SERVICE, Clipboard.READ, "[]"))
        client.answer(request().id, true, null)
        assertEquals("\"\"", empty.get(5, TimeUnit.SECONDS).json)
    }

    @Test
    fun `whatever still waits when the host stops was not done`() {
        val opened = openUri("mailto:someone@example.com")
        val read = clipboard(MainThreadCall(Clipboard.SERVICE, Clipboard.READ, "[]"))
        client.hostStopped()
        assertEquals("false", opened.get(5, TimeUnit.SECONDS).json)
        assertEquals("\"\"", read.get(5, TimeUnit.SECONDS).json)
        assertTrue(client.pending.value.isEmpty())
    }

    @Test
    fun `a page naming a port on the backend's machine is the platform's to open, a client's own port is not`() {
        val client = HostedUrls.serving(63344)
        try {
            assertTrue(HostedClient.onBackendLoopback("http://localhost:3000/"))
            assertTrue(HostedClient.onBackendLoopback("http://[::1]:8080/cb"))
            assertFalse(HostedClient.onBackendLoopback("http://notlocalhost:3000/"))
            // A sign-in's loopback server, named in the provider's redirect.
            assertTrue(HostedClient.onBackendLoopback("https://login.example/authorize?redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fcallback"))
            assertFalse(
                HostedClient.onBackendLoopback(
                    "https://gitkraken.dev/login?redirect_uri=http%3A%2F%2F127.0.0.1%3A63344%2Fcodebridge%2Furi%2Ft%2Feamodio.gitlens%2Fdid-authenticate"
                )
            )
            assertFalse(HostedClient.onBackendLoopback("https://gitkraken.dev/login"))
            assertFalse(HostedClient.onBackendLoopback("http://localhost/"))
        } finally {
            client.close()
        }
    }
}
