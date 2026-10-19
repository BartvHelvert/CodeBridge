package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonPrimitive

/** `MainThreadClipboard`: `vscode.env.clipboard`, plain text only. */
object Clipboard {

    const val SERVICE: String = "MainThreadClipboard"

    /** `$readText()`, answered with the clipboard's text, or `""` when it holds none. */
    const val READ: String = $$"$readText"

    /** `$writeText(value)`. */
    const val WRITE: String = $$"$writeText"

    /** The text `$writeText` is to put on the clipboard. */
    fun written(argsJson: String): String = Args.of(WRITE, argsJson).str(0)

    /** The reply to `$readText`: the host hands it to the extension as is, so never `null`. */
    fun text(value: String?): String = JsonPrimitive(value.orEmpty()).toString()
}
