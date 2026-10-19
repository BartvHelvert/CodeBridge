package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `MainThreadUrls`, and the links into the editor a browser opens. */
class UrlsTest {

    @Test
    fun `reads a registration, the identifier as the host sends it`() {
        assertEquals(
            Urls.Request.Register(0, "Acme.Sign-In", "Acme Sign-In"),
            Urls.request(Urls.REGISTER, """[0,{"value":"Acme.Sign-In","_lower":"acme.sign-in"},"Acme Sign-In"]""")
        )
        assertEquals(Urls.Request.Register(1, "acme.x", "acme.x"), Urls.request(Urls.REGISTER, """[1,"acme.x",""]"""))
        assertEquals(Urls.Request.Unregister(4), Urls.request(Urls.UNREGISTER, "[4]"))
    }

    @Test
    fun `a link comes back as the uri it was made for, with what the provider added to the query`() {
        val callback = UriComponents("vscodium", "acme.sign-in", "/did authenticate", "state=a b", "")
        val link = Urls.appLink(callback, 63342, "t0k3n")
        assertEquals(
            "http://127.0.0.1:63342/codebridge/uri/t0k3n/acme.sign-in/did%20authenticate?state=a%20b",
            link.uri
        )

        // What the browser requests once the provider has added its code.
        val requested = java.net.URI(link.uri).rawPath + "?state=a%20b&code=xyz"
        assertEquals(
            Urls.Opened("t0k3n", callback.copy(query = "state=a b&code=xyz")),
            Urls.opened(requested, "vscodium")
        )
    }

    @Test
    fun `a link to the extension alone has no path`() {
        assertEquals(
            Urls.Opened("t", UriComponents("vscodium", "acme.x", "", "", "")),
            Urls.opened("/codebridge/uri/t/acme.x", "vscodium")
        )
        assertEquals(
            Urls.Opened("t", UriComponents("vscodium", "acme.x", "/", "", "")),
            Urls.opened("/codebridge/uri/t/acme.x/", "vscodium")
        )
    }

    @Test
    fun `anything else is no link`() {
        assertNull(Urls.opened("/codebridge/uri/t", "vscodium"))
        assertNull(Urls.opened("/codebridge/uri//acme.x", "vscodium"))
        assertNull(Urls.opened("/api/other/t/acme.x", "vscodium"))
        assertNull(Urls.opened("/codebridge/uri/t/acme x{", "vscodium"))
    }
}
