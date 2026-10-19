package me.helvert.codebridge.editors

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the operating system's `vscodium:` link becomes on the IDE's built-in server. */
class AppLinkLauncherTest {

    private val requested = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            requested.add(exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }
    private val port = server.address.port

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `a link the IDE gave out goes to the port and host it names, without its own parameter`() {
        AppLinkLauncher.main(arrayOf("vscodium://eamodio.gitlens/did-authenticate?state=s&codebridge=$port.0af3&code=a%20b"))
        assertEquals(listOf("/codebridge/uri/0af3/eamodio.gitlens/did-authenticate?state=s&code=a%20b"), requested)
    }

    @Test
    fun `a link without one goes to the fallback port, to be asked about`() {
        AppLinkLauncher.main(arrayOf("vscodium://acme.x/cb", port.toString()))
        assertEquals(listOf("/codebridge/uri/-/acme.x/cb"), requested)
    }

    @Test
    fun `a parameter that is not a port and a token is passed on as the link's own`() {
        AppLinkLauncher.main(arrayOf("vscodium://acme.x/cb?codebridge=../../x", port.toString()))
        assertEquals(listOf("/codebridge/uri/-/acme.x/cb"), requested)
    }

    @Test
    fun `nowhere to send it is nothing`() {
        AppLinkLauncher.main(arrayOf("vscodium://acme.x/cb"))
        AppLinkLauncher.main(arrayOf())
        assertTrue(requested.isEmpty())
    }
}
