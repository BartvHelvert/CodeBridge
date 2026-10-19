package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Authentication
import me.helvert.codebridge.exthost.protocol.payload.Authentication.Account
import me.helvert.codebridge.exthost.protocol.payload.Authentication.Session
import me.helvert.codebridge.exthost.protocol.payload.Authentication.SessionRequest
import me.helvert.codebridge.exthost.protocol.payload.Messages
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadAuthentication` for one extension host: the providers its extensions register, and
 * the decisions VS Code's workbench makes when an extension asks one of them for a session.
 *
 * `$getSession` is VS Code's `MainThreadAuthentication.doGetSession`, step for step: a session the
 * extension may already use is answered silently; `createIfNone` and `forceNewSession` ask the user
 * to sign in, there and then; anything else answers nothing and leaves a request for the user to
 * act on later. VS Code keeps those requests as a badge on its Accounts menu; here each is a
 * notification with a button, and acting on it tells the extension its sessions changed, which is
 * what it waits for in VS Code too.
 *
 * The questions are asked through [ask], which draws them where the user is.
 */
internal class HostedAuthentication(
    private val callHost: suspend (String, String, String) -> String,
    private val manifests: List<JsonObject>,
    /** Activates an extension for an event, starting nothing new: the host asking is running. */
    private val activate: suspend (extensionId: String, event: String) -> Unit,
    private val ask: suspend (Messages.Message) -> Int?,
    private val access: AuthenticationAccess,
    /** How long `$ensureProvider` waits for an activated extension to register, as VS Code waits. */
    private val registrationTimeoutMillis: Long = 5_000,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val providers = ConcurrentHashMap<String, Authentication.Provider>()
    private val registrations = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Requests already waiting on the user, by extension and provider, so each is asked once. */
    private val requests = ConcurrentHashMap.newKeySet<String>()

    suspend fun handle(call: MainThreadCall): MainThreadResult = when (call.method) {
        Authentication.REGISTER -> {
            val provider = Authentication.provider(call.argsJson)
            providers[provider.id] = provider
            registrations.remove(provider.id)?.complete(Unit)
            MainThreadResult()
        }
        Authentication.UNREGISTER -> {
            providers.remove(Authentication.providerId(call.method, call.argsJson))
            MainThreadResult()
        }
        Authentication.SESSIONS_CHANGED -> {
            val change = Authentication.sessionsChanged(call.argsJson)
            val provider = providers[change.providerId]
            if (provider != null) tell(provider, null)
            MainThreadResult()
        }
        in Authentication.DYNAMIC -> throw MainThreadException("Error",
            "Authentication with servers the host discovers itself is not supported by CodeBridge")
        Authentication.ENSURE_PROVIDER -> { ensure(Authentication.providerId(call.method, call.argsJson)); MainThreadResult() }
        Authentication.GET_SESSION -> MainThreadResult(getSession(Authentication.sessionRequest(call.argsJson))?.raw?.toString())
        Authentication.GET_ACCOUNTS -> MainThreadResult(Authentication.accounts(accounts(Authentication.providerId(call.method, call.argsJson))))
        Authentication.REMOVE_SESSION -> {
            val (providerId, sessionId) = Authentication.removal(call.argsJson)
            callHost(Authentication.EXT_HOST, Authentication.PROVIDER_REMOVE_SESSION, Authentication.removeSession(providerId, sessionId))
            MainThreadResult()
        }
        else -> { LOG.debug("Not handled: ${call.service}.${call.method}"); MainThreadResult() }
    }

    /**
     * Wakes the extensions that provide [providerId] and waits for one to register it. The host asks
     * this before every `getSession`, so a consumer can be the first to need a provider.
     */
    private suspend fun ensure(providerId: String): Authentication.Provider {
        providers[providerId]?.let { return it }
        val registered = registrations.computeIfAbsent(providerId) { CompletableDeferred() }
        providers[providerId]?.let { return it }
        val event = Authentication.activationEvent(providerId)
        for (owner in owners(providerId)) scope.launch {
            try {
                activate(owner, event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not activate $owner for $event", failure)
            }
        }
        withTimeoutOrNull(registrationTimeoutMillis.milliseconds) { registered.await() }
        return providers[providerId] ?: throw unregistered(providerId)
    }

    /** Extensions that say they provide [providerId], as VS Code reads `contributes.authentication`. */
    private fun owners(providerId: String): List<String> {
        val event = Authentication.activationEvent(providerId)
        return manifests.filter { manifest ->
            val contributed = ((manifest["contributes"] as? JsonObject)?.get("authentication") as? JsonArray).orEmpty()
                .any { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == providerId }
            contributed || (manifest["activationEvents"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull == event }
        }.mapNotNull { manifest ->
            val publisher = manifest["publisher"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = manifest["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            "$publisher.$name"
        }
    }

    private suspend fun sessions(provider: Authentication.Provider, scopes: List<String>?, account: Account? = null,
                                 authorizationServer: kotlinx.serialization.json.JsonElement? = null): List<Session> =
        Authentication.sessions(callHost(Authentication.EXT_HOST, Authentication.PROVIDER_GET_SESSIONS,
            Authentication.getSessions(provider.id, scopes, account, authorizationServer)))

    private suspend fun create(provider: Authentication.Provider, scopes: List<String>, account: Account?,
                               authorizationServer: kotlinx.serialization.json.JsonElement?): Session {
        val reply = callHost(Authentication.EXT_HOST, Authentication.PROVIDER_CREATE_SESSION,
            Authentication.createSession(provider.id, scopes, account, authorizationServer))
        return Authentication.session(runCatching { kotlinx.serialization.json.Json.parseToJsonElement(reply) }.getOrNull())
            ?: throw MainThreadException("Error", "${provider.label} did not answer with a session")
    }

    /** One account per label, as VS Code lists them, in the order the provider gave its sessions. */
    private suspend fun accounts(providerId: String): List<Account> =
        sessions(ensure(providerId), null).map { it.account }.distinctBy { it.label }

    /** VS Code's `doGetSession`; see the class comment. */
    internal suspend fun getSession(request: SessionRequest): Session? {
        val options = request.options
        fun invalid(a: String, b: String) = MainThreadException("Error", "Invalid combination of options. Please remove one of the following: $a, $b")
        if (options.forceNewSession && options.createIfNone) throw invalid("forceNewSession", "createIfNone")
        if (options.forceNewSession && options.silent) throw invalid("forceNewSession", "silent")
        if (options.createIfNone && options.silent) throw invalid("createIfNone", "silent")

        val provider = providers[request.providerId] ?: throw unregistered(request.providerId)
        val extension = request.extensionId
        if (options.clearSessionPreference) access.setPreference(extension, provider.id, null)
        val sessions = sessions(provider, request.scopes, options.account, options.authorizationServer)
        val preference = access.preference(extension, provider.id)
        val preferred = if (options.account != null) sessions.firstOrNull { it.account.id == options.account!!.id }
            else preference?.let { label -> sessions.firstOrNull { it.account.label == label } }
        fun allowed(session: Session) = access.allowed(provider.id, session.account.label, extension) == true

        if (!options.forceNewSession && sessions.isNotEmpty()) {
            if (preferred != null && allowed(preferred)) return preferred
            // A provider with one account has nothing to choose between.
            if (!provider.supportsMultipleAccounts && allowed(sessions[0])) return sessions[0]
        }

        if (options.createIfNone || options.forceNewSession) {
            if (!consents(provider, request.extensionName, recreating = options.forceNewSession && sessions.isNotEmpty(), options.detail))
                throw MainThreadException("Error", "User did not consent to login.")
            val session = if (sessions.isNotEmpty() && !options.forceNewSession) {
                if (provider.supportsMultipleAccounts && options.account == null) choose(provider, request, sessions)
                else sessions[0]
            } else create(provider, request.scopes, options.account ?: preferred?.account, options.authorizationServer)
            grant(provider, extension, session)
            return session
        }

        // Silent and default: the first session the extension may already use, if it has no favourite.
        if (preferred == null && preference == null) sessions.firstOrNull(::allowed)?.let { return it }

        if (!options.silent) {
            if (sessions.isNotEmpty()) requestAccess(provider, request, sessions) else requestSignIn(provider, request)
        }
        return null
    }

    /** VS Code's login prompt: a modal question, with the extension's reason beneath when it gave one. */
    private suspend fun consents(provider: Authentication.Provider, extensionName: String, recreating: Boolean, detail: String?): Boolean {
        val text = if (recreating) "The extension '$extensionName' wants you to sign in again using ${provider.label}."
            else "The extension '$extensionName' wants to sign in using ${provider.label}."
        return ask(Messages.Message(Messages.Severity.INFO, text, detail, modal = true, source = null,
            items = listOf(Messages.Item("Allow", 0, false), Messages.Item("Cancel", 1, true)))) == 0
    }

    /**
     * Which of [sessions] -- or a new one -- the extension is to have. A quick pick in VS Code; a
     * modal question with a button per account here, which is as many as anyone signs in with.
     */
    private suspend fun choose(provider: Authentication.Provider, request: SessionRequest, sessions: List<Session>): Session {
        val accounts = sessions.distinctBy { it.account.label }
        val another = accounts.size
        val items = buildList {
            accounts.forEachIndexed { index, session -> add(Messages.Item(session.account.label, index, false)) }
            add(Messages.Item("Sign in to another account", another, false))
            add(Messages.Item("Cancel", another + 1, true))
        }
        val chosen = ask(Messages.Message(Messages.Severity.INFO,
            "The extension '${request.extensionName}' wants to access a ${provider.label} account", null,
            modal = true, source = null, items = items))
        return when (chosen) {
            null, another + 1 -> throw MainThreadException("Error", "User did not consent to account access.")
            another -> create(provider, request.scopes, null, request.options.authorizationServer)
            else -> accounts[chosen]
        }
    }

    /** The extension may use [session]'s account, and prefers it: VS Code records both on every sign-in. */
    private fun grant(provider: Authentication.Provider, extensionId: String, session: Session) {
        access.setAllowed(provider.id, session.account.label, extensionId, true)
        access.setPreference(extensionId, provider.id, session.account.label)
    }

    /** Sessions exist, but none the extension may use: offer to let it. */
    private fun requestAccess(provider: Authentication.Provider, request: SessionRequest, sessions: List<Session>) =
        offer(provider, request, "The extension '${request.extensionName}' wants to access your ${provider.label} account.", "Allow") {
            val session = if (sessions.distinctBy { it.account.label }.size > 1) choose(provider, request, sessions) else sessions[0]
            grant(provider, request.extensionId, session)
        }

    /** No session at all: offer to sign in. */
    private fun requestSignIn(provider: Authentication.Provider, request: SessionRequest) =
        offer(provider, request, "Sign in with ${provider.label} to use ${request.extensionName}.", "Sign In") {
            // Someone may have signed in meanwhile -- this extension, through another request.
            val existing = sessions(provider, request.scopes).firstOrNull { access.allowed(provider.id, it.account.label, request.extensionId) == true }
            grant(provider, request.extensionId, existing ?: create(provider, request.scopes, null, request.options.authorizationServer))
        }

    /**
     * A notification on the extension's behalf with one button, which runs [accepted] and then tells
     * the extension its sessions changed, so it asks again. One per extension and provider at a time.
     */
    private fun offer(provider: Authentication.Provider, request: SessionRequest, text: String, button: String, accepted: suspend () -> Unit) {
        val key = "${request.extensionId.lowercase()}\u0000${provider.id}"
        if (!requests.add(key)) return
        scope.launch {
            try {
                val choice = ask(Messages.Message(Messages.Severity.INFO, text, null, modal = false,
                    source = request.extensionName, items = listOf(Messages.Item(button, 0, false))))
                requests.remove(key)
                if (choice != 0) return@launch
                accepted()
                tell(provider, listOf(request.extensionId))
            } catch (cancelled: CancellationException) {
                requests.remove(key)
                throw cancelled
            } catch (failure: Exception) {
                requests.remove(key)
                LOG.warn("Could not sign ${request.extensionName} in with ${provider.label}", failure)
                ask(Messages.Message(Messages.Severity.ERROR, "Could not sign in with ${provider.label}: ${failure.message}",
                    null, modal = false, source = request.extensionName, items = emptyList()))
            }
        }
    }

    /** Tells the host's consumers -- or only [extensionIds] -- that [provider]'s sessions changed. */
    private fun tell(provider: Authentication.Provider, extensionIds: List<String>?) {
        scope.launch {
            try {
                callHost(Authentication.EXT_HOST, Authentication.DID_CHANGE_SESSIONS,
                    Authentication.didChangeSessions(provider.id, provider.label, extensionIds?.map { it.lowercase() }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not report ${provider.id}'s sessions changing", failure)
            }
        }
    }

    private fun unregistered(providerId: String) =
        MainThreadException("Error", "No authentication provider '$providerId' is currently registered.")

    override fun close() {
        scope.cancel()
        registrations.values.forEach { it.cancel() }
        registrations.clear()
        providers.clear()
        requests.clear()
    }

    private companion object {
        val LOG = Logger.getInstance(HostedAuthentication::class.java)
    }
}
