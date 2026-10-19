package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.started
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.*
import kotlin.test.*

/**
 * `MainThreadAuthentication` against a host that plays the provider: its sessions are [sessions],
 * and creating one signs in as the next of [signIns]. The user's answers are [answers], in order.
 */
class HostedAuthenticationTest {
    @TempDir lateinit var temp: Path

    private val sessions = CopyOnWriteArrayList<JsonObject>()
    private val signIns = ConcurrentLinkedQueue<String>()
    private val hostCalls = CopyOnWriteArrayList<Pair<String, JsonArray>>()
    private val asked = CopyOnWriteArrayList<Messages.Message>()
    private val answers = ConcurrentLinkedQueue<Int>() // -1 dismisses.
    private val activated = CopyOnWriteArrayList<Pair<String, String>>()
    private lateinit var auth: HostedAuthentication

    private fun session(account: String, vararg scopes: String) = buildJsonObject {
        put("id", "session-$account-${scopes.joinToString("+")}"); put("accessToken", "token-$account")
        putJsonObject("account") { put("id", "id-$account"); put("label", account) }
        putJsonArray("scopes") { scopes.forEach { add(it) } }
    }

    private fun start(multipleAccounts: Boolean = false, register: Boolean = true, manifests: List<JsonObject> = emptyList()) {
        auth = HostedAuthentication(
            callHost = { _, method, args ->
                val arguments = Json.parseToJsonElement(args).jsonArray
                hostCalls.add(method to arguments)
                when (method) {
                    $$"$getSessions" -> {
                        val scopes = (arguments[1] as? JsonArray)?.map { it.jsonPrimitive.content }
                        JsonArray(sessions.filter { scopes == null || it["scopes"]!!.jsonArray.map { s -> s.jsonPrimitive.content }.containsAll(scopes) }).toString()
                    }
                    $$"$createSession" -> session(signIns.remove(), *arguments[1].jsonArray.map { it.jsonPrimitive.content }.toTypedArray())
                        .also(sessions::add).toString()
                    else -> "null"
                }
            },
            manifests = manifests,
            activate = { extension, event ->
                activated.add(extension to event)
                if (register) handle($$"$registerAuthenticationProvider", provider(multipleAccounts))
            },
            ask = { message -> asked.add(message); answers.poll()?.takeIf { it >= 0 } },
            access = AuthenticationAccess(temp.resolve("authentication.json")),
            registrationTimeoutMillis = 300,
        )
    }

    private fun provider(multipleAccounts: Boolean) = buildJsonObject {
        put("id", "acme"); put("label", "Acme"); put("supportsMultipleAccounts", multipleAccounts)
    }

    private fun handle(method: String, vararg args: JsonElement): String? =
        started { auth.handle(MainThreadCall("MainThreadAuthentication", method, JsonArray(args.toList()).toString())) }
            .get(5, TimeUnit.SECONDS).json

    private fun register(multipleAccounts: Boolean = false) = handle($$"$registerAuthenticationProvider", provider(multipleAccounts))

    private fun getSession(extension: String = "p.ext", scopes: List<String> = listOf("read"), options: JsonObject = JsonObject(emptyMap())): JsonObject? =
        handle($$"$getSession", JsonPrimitive("acme"), JsonArray(scopes.map(::JsonPrimitive)), JsonPrimitive(extension),
            JsonPrimitive("Extension $extension"), options)?.let { Json.parseToJsonElement(it) as? JsonObject }

    private fun options(vararg flags: String) = buildJsonObject { flags.forEach { put(it, true) } }

    private fun failure(block: () -> Unit): MainThreadException {
        val error = assertFails(block)
        return generateSequence(error) { it.cause }.filterIsInstance<MainThreadException>().first()
    }

    /** The extension was told its sessions changed, and only it. */
    private fun told(extension: String) = hostCalls.any { (method, args) ->
        method == $$"$onDidChangeAuthenticationSessions" && args.getOrNull(2)?.jsonArray?.map { it.jsonPrimitive.content } == listOf(extension)
    }

    private fun awaitTold(extension: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!told(extension)) { check(System.nanoTime() < deadline) { "Never told $extension: $hostCalls" }; Thread.sleep(10) }
    }

    /** Offers are shown on their own, after the request that made them is answered. */
    @Suppress("SameParameterValue")
    private fun awaitAsked(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (asked.size < count || answers.isNotEmpty()) { check(System.nanoTime() < deadline) { "Asked only $asked" }; Thread.sleep(10) }
        Thread.sleep(50) // The answer's handling, which follows the asking.
    }

    @AfterTest fun close() { if (::auth.isInitialized) auth.close() }

    @Test fun `ensuring a provider wakes the extension that contributes it and waits for it to register`() {
        val manifest = Json.parseToJsonElement("""{"publisher":"acme","name":"auth","contributes":{"authentication":[{"id":"acme","label":"Acme"}]}}""").jsonObject
        val bystander = Json.parseToJsonElement("""{"publisher":"other","name":"thing"}""").jsonObject
        start(manifests = listOf(manifest, bystander))
        handle($$"$ensureProvider", JsonPrimitive("acme"))
        assertEquals(listOf("acme.auth" to "onAuthenticationRequest:acme"), activated)
        handle($$"$ensureProvider", JsonPrimitive("acme"))
        assertEquals(1, activated.size, "A registered provider is not woken again")
    }

    @Test fun `a provider nobody registers is reported as VS Code reports it`() {
        start(register = false)
        assertEquals("No authentication provider 'nope' is currently registered.",
            failure { handle($$"$ensureProvider", JsonPrimitive("nope")) }.message)
    }

    @Test fun `a silent request with nothing signed in answers nothing and asks nobody`() {
        start(); register()
        assertNull(getSession(options = options("silent")))
        assertTrue(asked.isEmpty())
    }

    @Test fun `createIfNone asks before signing in, and the session is then the extension's without asking`() {
        start(); register()
        answers.add(1)
        assertEquals("User did not consent to login.", failure { getSession(options = options("createIfNone")) }.message)
        assertTrue(asked.single().modal)
        assertTrue("wants to sign in using Acme" in asked.single().text)

        signIns.add("ada"); answers.add(0)
        assertEquals("token-ada", getSession(options = options("createIfNone"))!!["accessToken"]!!.jsonPrimitive.content)
        asked.clear()
        assertEquals("token-ada", getSession(options = options("silent"))!!["accessToken"]!!.jsonPrimitive.content)
        assertEquals("token-ada", getSession()!!["accessToken"]!!.jsonPrimitive.content)
        assertTrue(asked.isEmpty(), "Asked again: $asked")
    }

    @Test fun `an extension never allowed an account is offered it, once, and told when the user allows it`() {
        start(); register()
        sessions.add(session("ada", "read"))
        assertNull(getSession(extension = "q.ext", options = options("silent")))
        answers.add(-1) // The first offer is dismissed.
        assertNull(getSession(extension = "q.ext"))
        awaitAsked(1)
        val offer = asked.single()
        assertFalse(offer.modal)
        assertEquals(listOf("Allow"), offer.items.map { it.title })

        answers.add(0)
        assertNull(getSession(extension = "q.ext"))
        awaitTold("q.ext")
        assertEquals("token-ada", getSession(extension = "q.ext", options = options("silent"))!!["accessToken"]!!.jsonPrimitive.content)
    }

    @Test fun `with nothing signed in the offer signs in, and the extension hears of it`() {
        start(); register()
        signIns.add("ada"); answers.add(0)
        assertNull(getSession())
        awaitTold("p.ext")
        awaitAsked(1)
        assertEquals(listOf("Sign In"), asked.single().items.map { it.title })
        assertEquals("token-ada", getSession(options = options("silent"))!!["accessToken"]!!.jsonPrimitive.content)
    }

    @Test fun `with several accounts the user picks one, or signs in to another`() {
        start(); register(multipleAccounts = true)
        sessions.add(session("ada", "read")); sessions.add(session("bob", "read"))
        answers.add(0); answers.add(1) // Allow, then the second account.
        assertEquals("token-bob", getSession(options = options("createIfNone"))!!["accessToken"]!!.jsonPrimitive.content)
        assertEquals(listOf("ada", "bob", "Sign in to another account", "Cancel"), asked.last().items.map { it.title })

        signIns.add("cy"); answers.add(0); answers.add(2)
        assertEquals("token-cy", getSession(extension = "q.ext", options = options("createIfNone"))!!["accessToken"]!!.jsonPrimitive.content)
        // Each extension keeps the account it chose.
        assertEquals("token-bob", getSession(options = options("silent"))!!["accessToken"]!!.jsonPrimitive.content)
        assertEquals("token-cy", getSession(extension = "q.ext", options = options("silent"))!!["accessToken"]!!.jsonPrimitive.content)
    }

    @Test fun `forceNewSession signs in again even with a session to hand`() {
        start(); register()
        sessions.add(session("ada", "read"))
        signIns.add("ada"); answers.add(0)
        getSession(options = options("forceNewSession"))
        assertTrue("sign in again" in asked.single().text)
        assertEquals(1, hostCalls.count { it.first == $$"$createSession" })
    }

    @Test fun `contradictory options are refused in VS Code's words`() {
        start(); register()
        assertEquals("Invalid combination of options. Please remove one of the following: createIfNone, silent",
            failure { getSession(options = options("createIfNone", "silent")) }.message)
    }

    @Test fun `accounts are listed once each`() {
        start(); register()
        sessions.add(session("ada", "read")); sessions.add(session("ada", "write")); sessions.add(session("bob", "read"))
        val accounts = Json.parseToJsonElement(handle($$"$getAccounts", JsonPrimitive("acme"))!!).jsonArray
        assertEquals(listOf("ada", "bob"), accounts.map { it.jsonObject["label"]!!.jsonPrimitive.content })
    }

    @Test fun `the host's own OAuth client for discovered servers is refused, not half served`() {
        start()
        assertTrue("not supported" in failure { handle($$"$waitForUriHandler", JsonPrimitive("x")) }.message!!)
    }

    @Test fun `what the user allowed outlives the project`() {
        val file = temp.resolve("authentication.json")
        AuthenticationAccess(file).apply {
            setAllowed("acme", "ada", "P.Ext", true)
            setPreference("p.ext", "acme", "ada")
        }
        AuthenticationAccess(file).apply {
            assertEquals(true, allowed("acme", "ada", "p.ext"))
            assertNull(allowed("acme", "ada", "q.ext"))
            assertEquals("ada", preference("P.EXT", "acme"))
            setPreference("p.ext", "acme", null)
            assertNull(preference("p.ext", "acme"))
        }
    }
}
