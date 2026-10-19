package me.helvert.codebridge.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the user decided about extensions and accounts, kept as VS Code keeps it in application
 * storage: which extensions may use which account of a provider, and which account each extension
 * prefers. Global, as accounts are the user's and not the project's.
 *
 * Accounts are named by label, which is what VS Code keys both by. Read from disk on every call, so
 * a decision made in one project's window holds in the others without being told.
 */
internal class AuthenticationAccess(private val file: Path) {

    /** Whether [extensionId] may use [account] of [providerId]: null when the user was never asked. */
    fun allowed(providerId: String, account: String, extensionId: String): Boolean? = synchronized(lock) {
        (read()["access"]?.at(providerId, account) as? JsonObject)?.get(extensionId.lowercase())?.let { (it as? JsonPrimitive)?.booleanOrNull }
    }

    fun setAllowed(providerId: String, account: String, extensionId: String, allowed: Boolean) = edit {
        it.with("access", providerId, account, extensionId.lowercase(), value = JsonPrimitive(allowed))
    }

    /** The label of the account [extensionId] last used with [providerId]. */
    fun preference(extensionId: String, providerId: String): String? = synchronized(lock) {
        (read()["preferences"]?.at(extensionId.lowercase(), providerId) as? JsonPrimitive)?.contentOrNull
    }

    fun setPreference(extensionId: String, providerId: String, account: String?) = edit {
        it.with("preferences", extensionId.lowercase(), providerId, value = account?.let(::JsonPrimitive))
    }

    private fun edit(change: (JsonObject) -> JsonObject) = synchronized(lock) {
        HostedStorage.writeAtomically(file, change(read()).toString())
    }

    private fun read(): JsonObject =
        if (!Files.exists(file)) JsonObject(emptyMap())
        else runCatching { Json.parseToJsonElement(Files.readString(file)) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonElement.at(vararg path: String): JsonElement? =
        path.fold(this as JsonElement?) { node, name -> (node as? JsonObject)?.get(name) }

    /** This object with [value] at [path], made as it goes; a null value removes the last name. */
    private fun JsonObject.with(vararg path: String, value: JsonElement?): JsonObject {
        val name = path.first()
        val next = if (path.size == 1) value
        else ((this[name] as? JsonObject) ?: JsonObject(emptyMap())).with(*path.copyOfRange(1, path.size), value = value)
        return JsonObject(if (next == null) this - name else this + (name to next))
    }

    private companion object {
        /** Every project reads and writes the same file. */
        val lock = Any()
    }
}
