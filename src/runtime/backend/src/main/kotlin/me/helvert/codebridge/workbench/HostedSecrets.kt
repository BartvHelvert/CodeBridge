package me.helvert.codebridge.workbench

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.SecretState
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `MainThreadSecretState`: extensions' `context.secrets`, kept in the IDE's password safe.
 *
 * VS Code keeps these in the OS keychain; the password safe is the IDE's equivalent and whatever
 * the user chose for it -- the keychain on macOS and Linux, KeePass on Windows, or memory alone.
 * One service per secret, and one more per extension
 * listing its keys: a credential store cannot be enumerated, and `secrets.keys()` must be answered.
 * The list lives in the same store, so a store that forgets on restart forgets both together.
 *
 * Secrets are the user's, not the project's, so every project's host hears of a change.
 */
internal class HostedSecrets(
    private val store: Store = PasswordSafeStore,
    private val notify: (String, String, String) -> Unit,
) : AutoCloseable {

    /** Where values are kept. A seam for tests: the password safe needs a running application. */
    interface Store {
        fun read(service: String, account: String): String?
        /** Removes the entry when [value] is null. */
        fun write(service: String, account: String, value: String?)
    }

    /**
     * The password safe, remembering what it last wrote or read for this run of the IDE.
     *
     * In split mode the backend's password safe is the client's, reached over the connection, and a
     * read just after a write can come back without it. GitLens re-reads its session the moment it is
     * told the secret changed, and took the missing value for a sign-out: the user was signed in and out
     * again within two seconds. Every extension secret on this backend goes through here, so what it
     * remembers cannot go stale; the safe still keeps each value for the next run.
     *
     * The split-mode safe blocks until the client answers and wants a progress indicator or job to
     * tie that wait to, warning on every call without one; a call here has neither, so each
     * call gets a job of its own.
     */
    object PasswordSafeStore : Store {
        private val safe = CredentialSecretStore { PasswordSafe.instance }

        override fun read(service: String, account: String): String? = withJob { safe.read(service, account) }

        override fun write(service: String, account: String, value: String?) =
            withJob { safe.write(service, account, value) }

        private fun <T> withJob(action: () -> T): T = runBlockingMaybeCancellable { action() }
    }

    internal class CredentialSecretStore(private val credentials: () -> CredentialStore) : Store {
        private val known = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, java.util.Optional<String>>()

        override fun read(service: String, account: String): String? =
            known.computeIfAbsent(service to account) {
                val safe = credentials()
                val attributes = attributes(service, account)
                val value = safe[attributes]?.getPasswordAsString() ?: run {
                    // Recover the entry left by older versions, without touching another key's value.
                    val legacy = CredentialAttributes(service, account)
                    safe[legacy]?.takeIf { it.userName == account }?.getPasswordAsString()?.also {
                        safe[attributes] = Credentials(account, it)
                        safe[legacy] = null
                    }
                }
                java.util.Optional.ofNullable(value)
            }.orElse(null)

        override fun write(service: String, account: String, value: String?) {
            val safe = credentials()
            safe[attributes(service, account)] = value?.let { Credentials(account, it) }
            val legacy = CredentialAttributes(service, account)
            if (safe[legacy]?.userName == account) safe[legacy] = null
            known[service to account] = java.util.Optional.ofNullable(value)
        }

        // KeePass replaces entries by service name alone, regardless of the account. Hash arbitrary
        // extension keys to give each a bounded, distinct service name on every credential backend.
        private fun attributes(service: String, account: String): CredentialAttributes {
            val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return CredentialAttributes("$service secret $key", account)
        }
    }

    init { instances.add(this) }

    /**
     * Answered off the workbench thread: the password safe may block on the keychain, or ask the
     * user for KeePass's master password, and every host call would queue behind it.
     */
    suspend fun handle(call: MainThreadCall): MainThreadResult = withContext(dispatcher) {
        val request = SecretState.request(call.method, call.argsJson)
        val extension = request.extensionId
        when (request.method) {
            SecretState.GET -> MainThreadResult(store.read(service(extension), request.key!!)?.let { JsonPrimitive(it).toString() })
            SecretState.KEYS -> MainThreadResult(JsonArray(keys(extension).map(::JsonPrimitive)).toString())
            SecretState.SET -> {
                val key = request.key!!
                store.write(service(extension), key, request.value)
                val keys = keys(extension)
                if (key !in keys) writeKeys(extension, keys + key)
                changed(extension, key)
                MainThreadResult()
            }
            SecretState.DELETE -> {
                val key = request.key!!
                store.write(service(extension), key, null)
                val keys = keys(extension)
                if (key in keys) writeKeys(extension, keys - key)
                changed(extension, key)
                MainThreadResult()
            }
            else -> error("Unreachable")
        }
    }

    private fun keys(extension: String): List<String> {
        val text = store.read(indexService(extension), INDEX_ACCOUNT) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
    }

    private fun writeKeys(extension: String, keys: List<String>) = store.write(indexService(extension), INDEX_ACCOUNT,
        keys.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)).toString() })

    /** VS Code tells every host, the writer's included, as its secret storage service does. */
    private fun changed(extension: String, key: String) {
        val args = SecretState.changed(extension, key)
        instances.forEach { it.notify(SecretState.EXT_HOST, SecretState.CHANGED, args) }
    }

    override fun close() { instances.remove(this) }

    companion object {
        private val instances = CopyOnWriteArrayList<HostedSecrets>()

        /** One call at a time for every project, so a write and the read after it are in order. */
        private val dispatcher = Dispatchers.IO.limitedParallelism(1)

        private const val INDEX_ACCOUNT = "keys"

        /** The extension id is the host's lower-cased key, so the spelling is stable. */
        internal fun service(extension: String) = generateServiceName("CodeBridge", extension)

        /** Ids contain no spaces, so this names no extension's own service. */
        private fun indexService(extension: String) = generateServiceName("CodeBridge", "$extension keys")
    }
}
