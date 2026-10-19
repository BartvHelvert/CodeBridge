package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * `MainThreadAuthentication`: `vscode.authentication`, both halves of it.
 *
 * An extension that signs users in to a service registers a provider, and the workbench calls it
 * back over `ExtHostAuthentication` for sessions. An extension that needs a token asks the
 * workbench for a session from a provider by id; the workbench decides whether the user must be
 * asked first, which account to use, and whether the extension may have it at all.
 *
 * Sessions travel as the extension made them -- `{ id, accessToken, account: { id, label }, scopes }`
 * -- and are passed back to the host untouched, since the host revives nothing in them.
 */
object Authentication {

    const val SERVICE: String = "MainThreadAuthentication"
    const val EXT_HOST: String = "ExtHostAuthentication"

    const val REGISTER: String = $$"$registerAuthenticationProvider"
    const val UNREGISTER: String = $$"$unregisterAuthenticationProvider"
    const val ENSURE_PROVIDER: String = $$"$ensureProvider"
    const val SESSIONS_CHANGED: String = $$"$sendDidChangeSessions"
    const val GET_SESSION: String = $$"$getSession"
    const val GET_ACCOUNTS: String = $$"$getAccounts"
    const val REMOVE_SESSION: String = $$"$removeSession"

    /**
     * The host's own OAuth client for servers it discovers itself, which MCP servers use. It needs
     * URI handlers and a device code dialog, and is refused as a whole rather than half-served.
     */
    val DYNAMIC: Set<String> = setOf(
        $$"$registerDynamicAuthenticationProvider", $$"$sendDidChangeDynamicProviderInfo",
        $$"$waitForUriHandler", $$"$showContinueNotification", $$"$showDeviceCodeModal",
        $$"$promptForClientRegistration",
    )

    /** Calls to the provider, in the host. */
    const val PROVIDER_GET_SESSIONS: String = $$"$getSessions"
    const val PROVIDER_CREATE_SESSION: String = $$"$createSession"
    const val PROVIDER_REMOVE_SESSION: String = $$"$removeSession"

    /** Tells the host's consumers that a provider's sessions changed: `(id, label, extensionIds?)`. */
    const val DID_CHANGE_SESSIONS: String = $$"$onDidChangeAuthenticationSessions"
    @Suppress("unused") // part of the protocol, not called yet
    const val DID_UNREGISTER: String = $$"$onDidUnregisterAuthenticationProvider"

    /** The extension that contributes provider `id` wakes on this. */
    fun activationEvent(id: String): String = "onAuthenticationRequest:$id"

    data class Provider(val id: String, val label: String, val supportsMultipleAccounts: Boolean)

    /** `$registerAuthenticationProvider({ id, label, supportsMultipleAccounts, ... })`. */
    fun provider(argsJson: String): Provider {
        val details = Args.of(REGISTER, argsJson).obj(0)
        val id = details.string("id") ?: throw PayloadException("$REGISTER names no provider")
        return Provider(id, details.string("label") ?: id, details["supportsMultipleAccounts"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    /** The provider id that [method] -- unregister, ensure, get accounts -- is about. */
    fun providerId(method: String, argsJson: String): String = Args.of(method, argsJson).str(0)

    data class Account(val id: String, val label: String) {
        fun toJson(): JsonObject = buildJsonObject { put("id", id); put("label", label) }
    }

    /** A session, read for what the workbench decides by; [raw] is what goes back to the host. */
    data class Session(val id: String, val account: Account, val scopes: List<String>, val raw: JsonObject)

    fun session(element: JsonElement?): Session? {
        val session = element as? JsonObject ?: return null
        val account = session["account"] as? JsonObject ?: return null
        return Session(
            id = session.string("id") ?: return null,
            account = Account(account.string("id") ?: return null, account.string("label") ?: return null),
            scopes = (session["scopes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            raw = session,
        )
    }

    /** The provider's answer to `$getSessions`. */
    fun sessions(json: String?): List<Session> =
        (json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray).orEmpty().mapNotNull(::session)

    data class SessionsChanged(val providerId: String, val added: List<Session>, val removed: List<Session>, val changed: List<Session>)

    /** `$sendDidChangeSessions(id, { added, removed, changed })`. */
    fun sessionsChanged(argsJson: String): SessionsChanged {
        val args = Args.of(SESSIONS_CHANGED, argsJson)
        val event = args.objOrNull(1)
        fun list(name: String) = (event?.get(name) as? JsonArray).orEmpty().mapNotNull(::session)
        return SessionsChanged(args.str(0), list("added"), list("removed"), list("changed"))
    }

    /**
     * `getSession`'s `options`. `createIfNone` and `forceNewSession` may be an object carrying a
     * [detail] to show the user instead of `true`.
     */
    data class Options(
        val createIfNone: Boolean = false,
        val forceNewSession: Boolean = false,
        val silent: Boolean = false,
        val clearSessionPreference: Boolean = false,
        val account: Account? = null,
        val detail: String? = null,
        /** Passed to the provider as it came, so a provider that reads it sees what VS Code sends. */
        val authorizationServer: JsonElement? = null,
    )

    data class SessionRequest(
        val providerId: String,
        val scopes: List<String>,
        val extensionId: String,
        val extensionName: String,
        val options: Options,
    )

    /**
     * `$getSession(providerId, scopes, extensionId, extensionName, options)`.
     *
     * A challenge (`{ wwwAuthenticate, fallbackScopes }`) in place of the scopes is answered with its
     * fallback scopes, as VS Code answers it for a provider that does not read challenges.
     */
    fun sessionRequest(argsJson: String): SessionRequest {
        val args = Args.of(GET_SESSION, argsJson)
        val scopes = when (val requested = args.element(1)) {
            is JsonArray -> requested.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonObject -> (requested["fallbackScopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw PayloadException("$GET_SESSION: a challenge without fallback scopes is not supported")
            else -> throw PayloadException("$GET_SESSION: expected scopes, got ${requested ?: "nothing"}")
        }
        val options = args.objOrNull(4)
        fun flag(name: String): Boolean = when (val value = options?.get(name)) {
            is JsonObject -> true
            is JsonPrimitive -> value.booleanOrNull ?: false
            else -> false
        }
        fun detail(name: String) = (options?.get(name) as? JsonObject)?.string("detail")
        val account = (options?.get("account") as? JsonObject)?.let { account ->
            account.string("id")?.let { id -> Account(id, account.string("label") ?: id) }
        }
        return SessionRequest(
            providerId = args.str(0),
            scopes = scopes,
            extensionId = args.str(2),
            extensionName = args.strOrNull(3) ?: args.str(2),
            options = Options(
                createIfNone = flag("createIfNone"),
                forceNewSession = flag("forceNewSession"),
                silent = flag("silent"),
                clearSessionPreference = flag("clearSessionPreference"),
                account = account,
                detail = detail("forceNewSession") ?: detail("createIfNone"),
                authorizationServer = options?.get("authorizationServer")?.takeUnless { it is JsonNull },
            ),
        )
    }

    /** `$removeSession(providerId, sessionId)`, as either side sends it. */
    fun removal(argsJson: String): Pair<String, String> = Args.of(REMOVE_SESSION, argsJson).let { it.str(0) to it.str(1) }

    /** The provider options both `$getSessions` and `$createSession` take. */
    private fun providerOptions(account: Account?, authorizationServer: JsonElement?, activateImmediate: Boolean?) = buildJsonObject {
        activateImmediate?.let { put("activateImmediate", it) }
        account?.let { put("account", it.toJson()) }
        authorizationServer?.let { put("authorizationServer", it) }
    }

    /** `$getSessions(id, scopes?, options)`; no scopes asks for every session. */
    fun getSessions(providerId: String, scopes: List<String>?, account: Account? = null, authorizationServer: JsonElement? = null): String =
        buildJsonArray {
            add(providerId)
            add(scopes?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
            add(providerOptions(account, authorizationServer, null))
        }.toString()

    /** `$createSession(id, scopes, options)`. */
    fun createSession(providerId: String, scopes: List<String>, account: Account? = null, authorizationServer: JsonElement? = null): String =
        buildJsonArray {
            add(providerId)
            add(JsonArray(scopes.map(::JsonPrimitive)))
            add(providerOptions(account, authorizationServer, true))
        }.toString()

    fun removeSession(providerId: String, sessionId: String): String = buildJsonArray { add(providerId); add(sessionId) }.toString()

    /** `$onDidChangeAuthenticationSessions(id, label, extensionIds?)`: [extensionIds] null tells everyone. */
    fun didChangeSessions(providerId: String, label: String, extensionIds: List<String>? = null): String = buildJsonArray {
        add(providerId)
        add(label)
        extensionIds?.let { add(JsonArray(it.map(::JsonPrimitive))) }
    }.toString()

    @Suppress("unused") // part of the protocol, not called yet
    fun didUnregister(providerId: String): String = buildJsonArray { add(providerId) }.toString()

    fun accounts(accounts: List<Account>): String = JsonArray(accounts.map { it.toJson() }).toString()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
