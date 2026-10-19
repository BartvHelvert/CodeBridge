package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.Urls
import me.helvert.codebridge.started
import java.util.concurrent.*
import kotlin.test.*

/**
 * `MainThreadUrls` against a host whose extensions register their handlers when `activate`d, and a
 * user who answers [answers] in order.
 */
class HostedUrlsTest {

    private val delivered = CopyOnWriteArrayList<Pair<Int, UriComponents>>()
    private val activated = CopyOnWriteArrayList<String>()
    private val asked = CopyOnWriteArrayList<Messages.Message>()
    private val answers = ConcurrentLinkedQueue<Int>() // -1 dismisses.
    private val open = mutableListOf<HostedUrls>()

    @AfterTest
    fun closeAll() = open.forEach { it.close() }

    private fun urls(
        registersOnActivation: Boolean = true,
        owner: String? = null,
        client: (() -> HostedUrls.Client)? = { HostedUrls.Client(63342) }
    ): HostedUrls {
        lateinit var urls: HostedUrls
        urls = HostedUrls(
            "vscodium",
            callHost = { _, method, args ->
                assertEquals(Urls.HANDLE, method)
                val arguments = Json.parseToJsonElement(args).jsonArray
                delivered.add(arguments[0].jsonPrimitive.int to UriComponents.from(arguments[1])!!)
                "null"
            },
            activate = { extension, event ->
                assertEquals("onUri", event)
                activated.add(extension)
                if (registersOnActivation) urls.register(7, extension)
            },
            ask = { message ->
                asked.add(message)
                answers.poll()?.takeIf { it >= 0 }
            },
            owner = owner,
            client = client ?: HostedUrls.LATEST_CLIENT,
            registrationTimeoutMillis = 300,
        )
        return urls.also(open::add)
    }

    private fun HostedUrls.call(method: String, vararg args: JsonElement): String? =
        started { handle(MainThreadCall(Urls.SERVICE, method, JsonArray(args.toList()).toString())) }.get(5, TimeUnit.SECONDS).json

    private fun HostedUrls.register(handle: Int, extension: String) = call(
        Urls.REGISTER,
        JsonPrimitive(handle),
        buildJsonObject { put("value", extension); put("_lower", extension.lowercase()) },
        JsonPrimitive("Display $extension")
    )

    private fun HostedUrls.appLink(uri: String) =
        UriComponents.from(Json.parseToJsonElement(call(Urls.CREATE_APP_URI, UriComponents.of(uri).toJson())!!))!!

    private fun HostedUrls.linkAuthority(uri: String) = appLink(uri).authority

    /** The link the host is given for [uri], as the browser requests it. */
    private fun HostedUrls.link(uri: String): String {
        val link = appLink(uri)
        val requested = java.net.URI(link.uri)
        return requested.rawPath + (requested.rawQuery?.let { "?$it" } ?: "")
    }

    private fun deliver(request: String, focused: String? = null): String {
        val delivery = assertNotNull(HostedUrls.deliver(request, focused))
        return started { delivery.extension.await() }.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a link the host was given reaches the registered handler unasked`() {
        val urls = urls()
        urls.register(3, "Acme.Sign-In")
        val request = urls.link("vscodium://acme.sign-in/did-authenticate?state=s") + "&code=c"

        assertEquals("Display Acme.Sign-In", deliver(request))
        assertEquals(
            listOf(3 to UriComponents("vscodium", "acme.sign-in", "/did-authenticate", "state=s&code=c", "")),
            delivered
        )
        assertTrue(asked.isEmpty())
        assertTrue(activated.isEmpty())
    }

    @Test
    fun `an extension without a handler yet is activated for it`() {
        val urls = urls()
        deliver(urls.link("vscodium://acme.late/cb"))
        assertEquals(listOf("acme.late"), activated)
        assertEquals(7, delivered.single().first)
    }

    @Test
    fun `an extension that never registers fails the delivery`() {
        val urls = urls(registersOnActivation = false)
        val failure = assertFails { deliver(urls.link("vscodium://acme.silent/cb")) }
        assertIs<TimeoutException>(failure.cause)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a link without a known token asks first, and goes to the focused host`() {
        urls(owner = "elsewhere")
        val focused = urls(owner = "here")
        focused.register(5, "acme.x")

        answers.add(1)
        assertFails { deliver("/codebridge/uri/forged/acme.x/cb", focused = "here") }
        assertTrue(delivered.isEmpty())
        assertEquals("Allow 'Display acme.x' extension to open this URI?", asked.single().text)
        assertEquals("vscodium://acme.x/cb", asked.single().detail)

        answers.add(0)
        deliver("/codebridge/uri/forged/acme.x/cb", focused = "here")
        assertEquals(5, delivered.single().first)
        assertEquals("here", HostedUrls.deliver("/codebridge/uri/forged/acme.x/cb", "here")!!.owner)
    }

    @Test
    fun `an unregistered handler gets nothing, and a stopped host's links go nowhere`() {
        val urls = urls(registersOnActivation = false)
        urls.register(2, "acme.x")
        urls.call(Urls.UNREGISTER, JsonPrimitive(2))
        val request = urls.link("vscodium://acme.x/cb")
        assertFails { deliver(request) }
        assertTrue(delivered.isEmpty())

        urls.close(); open.remove(urls)
        assertNull(HostedUrls.deliver(request, null))
    }

    @Test
    fun `not a link of ours is not delivered`() {
        urls()
        assertNull(HostedUrls.deliver("/codebridge/uri/only-a-token", null))
    }

    @Test
    fun `links are given out on the latest connected client's server, and none without one`() {
        val urls = urls(client = null)
        assertFails { urls.link("vscodium://acme.x/cb") }

        val first = HostedUrls.serving(1111)
        val second = HostedUrls.serving(2222)
        assertEquals("127.0.0.1:2222", urls.linkAuthority("vscodium://acme.x/cb"))
        second.close()
        assertEquals("127.0.0.1:1111", urls.linkAuthority("vscodium://acme.x/cb"))
        first.close()
        assertFails { urls.link("vscodium://acme.x/cb") }
    }

    @Test
    fun `a client whose machine hands the editor's scheme to its launcher is given links under it`() {
        val urls = urls(client = { HostedUrls.Client(63344, setOf("vscodium")) })
        val link = urls.appLink("vscodium://acme.x/did-authenticate?state=s")
        assertEquals(
            UriComponents(
                "vscodium",
                "acme.x",
                "/did-authenticate",
                "state=s&codebridge=63344.${urls.token}",
                ""
            ), link
        )

        // Another scheme -- the user's own VS Code, say -- is left alone.
        val other = urls(client = { HostedUrls.Client(63344, setOf("vscode")) })
        assertEquals("http", other.appLink("vscodium://acme.x/cb").scheme)
    }
}
