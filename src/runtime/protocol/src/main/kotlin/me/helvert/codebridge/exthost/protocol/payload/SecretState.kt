package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `MainThreadSecretState`: an extension's `context.secrets`, strings kept out of plain storage --
 * tokens, passwords, API keys. Every call names the extension by its lower-cased id, which the host
 * fills in itself, so one extension cannot reach another's secrets.
 */
object SecretState {

    const val SERVICE: String = "MainThreadSecretState"
    const val EXT_HOST: String = "ExtHostSecretState"

    /** `$getPassword(extensionId, key)`, answered with the value or nothing. */
    const val GET: String = $$"$getPassword"

    /** `$setPassword(extensionId, key, value)`. */
    const val SET: String = $$"$setPassword"

    /** `$deletePassword(extensionId, key)`. */
    const val DELETE: String = $$"$deletePassword"

    /** `$getKeys(extensionId)`, answered with every key the extension holds a value under. */
    const val KEYS: String = $$"$getKeys"

    /** `$onDidChangePassword({ extensionId, key })`, sent to every host when a value changes. */
    const val CHANGED: String = $$"$onDidChangePassword"

    /** One call, read: [value] only for [SET], [key] for all but [KEYS]. */
    data class Request(val method: String, val extensionId: String, val key: String?, val value: String?)

    fun request(method: String, argsJson: String): Request {
        val args = Args.of(method, argsJson)
        return when (method) {
            GET, DELETE -> Request(method, args.str(0), args.str(1), null)
            SET -> Request(method, args.str(0), args.str(1), args.str(2))
            KEYS -> Request(method, args.str(0), null, null)
            else -> throw PayloadException("Unknown $SERVICE method $method")
        }
    }

    fun changed(extensionId: String, key: String): String = buildJsonArray {
        add(buildJsonObject { put("extensionId", extensionId); put("key", key) })
    }.toString()
}
