package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Formatting as a character is typed: rust-analyzer adds the `;` after `let x =`, clangd re-indents
 * a line on `}`.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `OnTypeFormattingAdapter`. The reply is a
 * bare array of [TextEdit], as a formatter's is.
 */
object OnTypeFormatting {

    /** `$registerOnTypeFormattingSupport(handle, selector, autoFormatTriggerCharacters, extensionId)`. */
    const val REGISTER: String = $$"$registerOnTypeFormattingSupport"
    const val PROVIDE: String = $$"$provideOnTypeFormattingEdits"

    /** VS Code's setting that turns this on, off unless the user says otherwise. */
    const val SETTING: String = "editor.formatOnType"

    /** A provider, and the characters it formats after: its first trigger and any more it named. */
    data class Registration(val handle: Int, val selector: JsonElement, val triggers: List<String>)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.stringsOrEmpty(2))
    }

    /**
     * Arguments for `$provideOnTypeFormattingEdits(handle, resource, position, ch, options)`: the
     * position just after [ch], which is `"\n"` for Enter. The token is left for the host to append.
     */
    fun provide(
        handle: Int,
        resource: UriComponents,
        position: Position,
        ch: String,
        options: Formatting.Options
    ): String =
        JsonArray(
            listOf(
                JsonPrimitive(handle),
                resource.toJson(),
                position.toJson(),
                JsonPrimitive(ch),
                options.toJson()
            )
        ).toString()
}
