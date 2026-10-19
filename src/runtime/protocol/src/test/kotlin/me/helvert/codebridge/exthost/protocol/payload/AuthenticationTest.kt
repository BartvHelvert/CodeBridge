package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** `MainThreadAuthentication` calls as `ExtHostAuthentication` sends them, and the calls back. */
class AuthenticationTest {

    @Test
    fun `reads a provider registration`() {
        assertEquals(
            Authentication.Provider("github", "GitHub", true), Authentication.provider(
                """[{"id":"github","label":"GitHub","supportsMultipleAccounts":true,"supportedAuthorizationServers":null}]"""
            )
        )
        assertEquals(Authentication.Provider("x", "x", false), Authentication.provider("""[{"id":"x"}]"""))
    }

    @Test
    fun `reads a session request, with an option given as an object carrying the reason`() {
        val request = Authentication.sessionRequest(
            """["github",["repo","read:user"],"p.ext","Pull Requests",
            {"createIfNone":{"detail":"To list your pull requests"},"account":{"id":"1","label":"ada"}}]"""
        )
        assertEquals("github", request.providerId)
        assertEquals(listOf("repo", "read:user"), request.scopes)
        assertEquals("p.ext", request.extensionId)
        assertEquals("Pull Requests", request.extensionName)
        assertTrue(request.options.createIfNone)
        assertFalse(request.options.silent)
        assertEquals("To list your pull requests", request.options.detail)
        assertEquals(Authentication.Account("1", "ada"), request.options.account)
    }

    @Test
    fun `a challenge is answered with its fallback scopes, and refused without them`() {
        assertEquals(
            listOf("openid"), Authentication.sessionRequest(
                """["ms",{"wwwAuthenticate":"Bearer realm=x","fallbackScopes":["openid"]},"p.ext","P",{}]"""
            ).scopes
        )
        assertFailsWith<PayloadException> {
            Authentication.sessionRequest("""["ms",{"wwwAuthenticate":"Bearer realm=x"},"p.ext","P",{}]""")
        }
    }

    @Test
    fun `reads sessions, passing over anything that is not one`() {
        val sessions =
            Authentication.sessions("""[{"id":"s","accessToken":"t","account":{"id":"1","label":"ada"},"scopes":["a"]},{"id":"broken"}]""")
        assertEquals(listOf("ada"), sessions.map { it.account.label })
        assertEquals(listOf("a"), sessions.single().scopes)
        assertEquals(
            "t",
            Json.parseToJsonElement(sessions.single().raw.toString()).jsonObject["accessToken"].toString().trim('"')
        )
        assertTrue(Authentication.sessions("null").isEmpty())
    }

    @Test
    fun `asks the provider with options the host can revive`() {
        val all = Json.parseToJsonElement(Authentication.getSessions("github", null)).jsonArray
        assertEquals("null", all[1].toString())
        assertEquals("{}", all[2].toString())
        val create = Json.parseToJsonElement(
            Authentication.createSession(
                "github",
                listOf("repo"),
                Authentication.Account("1", "ada")
            )
        ).jsonArray
        assertEquals("""{"activateImmediate":true,"account":{"id":"1","label":"ada"}}""", create[2].toString())
    }

    @Test
    fun `tells everyone, or only the extensions named`() {
        assertEquals("""["github","GitHub"]""", Authentication.didChangeSessions("github", "GitHub"))
        assertEquals(
            """["github","GitHub",["p.ext"]]""",
            Authentication.didChangeSessions("github", "GitHub", listOf("p.ext"))
        )
        assertNull(Authentication.session(null))
    }
}
