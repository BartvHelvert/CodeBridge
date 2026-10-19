package me.helvert.codebridge.workbench

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.keePass.InMemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostedSecretsTest {
    // Uses the platform's KeePass entry semantics, without an IDE or a user's password database.
    private val safe = InMemoryCredentialStore()
    private val service = HostedSecrets.service("eamodio.gitlens")
    private fun restarted() = HostedSecrets.CredentialSecretStore { safe }

    @Test
    fun `multiple secrets survive losing the process cache`() {
        val store = restarted()
        store.write(service, "session", "token")
        store.write(service, "refresh", "refresh-token")
        store.write(service, "", "empty-key")
        store.write(HostedSecrets.service("other.extension"), "session", "other-token")

        val fresh = restarted()
        assertEquals("token", fresh.read(service, "session"))
        assertEquals("refresh-token", fresh.read(service, "refresh"))
        assertEquals("empty-key", fresh.read(service, ""))
        assertEquals("other-token", fresh.read(HostedSecrets.service("other.extension"), "session"))
        fresh.write(service, "refresh", "updated")
        fresh.write(service, "session", null)

        assertNull(restarted().read(service, "session"))
        assertEquals("updated", restarted().read(service, "refresh"))
    }

    @Test
    fun `surviving legacy secret migrates without overwriting another key`() {
        safe[CredentialAttributes(service, "session")] = Credentials("session", "legacy-token")
        val store = restarted()
        assertNull(store.read(service, "refresh"))
        store.write(service, "refresh", "refresh-token")
        assertEquals("legacy-token", store.read(service, "session"))
        assertNull(safe[CredentialAttributes(service, "session")])
        assertEquals("legacy-token", restarted().read(service, "session"))
        assertEquals("refresh-token", restarted().read(service, "refresh"))
    }

    @Test
    fun `deleting or replacing a legacy secret cannot resurrect it after restart`() {
        val legacy = CredentialAttributes(service, "session")
        safe[legacy] = Credentials("session", "old")
        restarted().write(service, "session", "new")
        assertEquals("new", restarted().read(service, "session"))
        assertNull(safe[legacy])
        restarted().write(service, "session", null)
        assertNull(restarted().read(service, "session"))

        safe[legacy] = Credentials("session", "old")
        restarted().write(service, "session", null)
        assertNull(restarted().read(service, "session"))
    }
}
