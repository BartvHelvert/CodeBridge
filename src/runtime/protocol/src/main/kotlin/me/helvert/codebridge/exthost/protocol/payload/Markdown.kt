package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import java.net.URLDecoder

/**
 * VS Code's `MarkdownString`, which reaches the workbench as `{value, isTrusted, ...}`.
 *
 * Anywhere an extension can write prose -- hover contents, completion documentation -- the value
 * arrives either as a plain string or wrapped in one of these, depending on which overload the
 * extension used. Both spellings mean the same thing, so both are read here rather than at each
 * of the places that display one.
 */
object Markdown {

    /** The text of a plain string or a `MarkdownString`, or `null` for anything else. */
    fun text(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["value"]?.jsonPrimitive?.contentOrNull
        else -> null
    }

    /**
     * [text], keeping only the command links the extension trusted it with.
     *
     * The workbench runs a `command:` link only from a `MarkdownString` whose `isTrusted` is `true`,
     * or names the command in `enabledCommands`; any other is inert. Here the inert ones are
     * reduced to their labels, so that whatever displays the text can run every link left in it.
     */
    fun trustedText(element: JsonElement?): String? {
        val text = text(element) ?: return null
        val trust = (element as? JsonObject)?.get("isTrusted")
        return withoutCommandLinks(text) { id -> trusts(trust, id) }
    }

    private fun trusts(trust: JsonElement?, command: String): Boolean = when (trust) {
        is JsonPrimitive -> trust.booleanOrNull == true
        is JsonObject -> (trust["enabledCommands"] as? JsonArray).orEmpty()
            .any { (it as? JsonPrimitive)?.contentOrNull == command }

        else -> false
    }

    /**
     * Undoes the escaping the host applies to plaintext contributions.
     *
     * Plaintext hovers arrive with their spaces escaped to `&nbsp;` so that a markdown renderer
     * will not collapse them. Nothing renders markdown here yet, so the escape is simply undone.
     */
    fun unescape(text: String): String = text.replace("&nbsp;", " ")

    /**
     * Replaces each link to a `command:` URI with its label, except those whose command [keep]s.
     *
     * The workbench runs the command when such a link is clicked; anything that cannot would only
     * try to open it as an address, so the label is kept as text instead of offering a dead link.
     */
    fun withoutCommandLinks(text: String, keep: (command: String) -> Boolean = { false }): String =
        COMMAND_LINK.replace(text) { if (keep(decode(it.groupValues[2]))) it.value else it.groupValues[1] }

    /**
     * The command a `command:` link runs, or null if [href] is not one.
     *
     * As the workbench's command opener reads it: the id is the path, and the query is the
     * arguments as JSON, URI-encoded or not, with a lone value standing for a one-element list --
     * rust-analyzer's `command:rust-analyzer.hoverRefCommandProxy?0` passes `[0]`.
     */
    fun command(href: String): CodeActions.Command? {
        if (!href.startsWith(COMMAND_SCHEME)) return null
        val target = href.removePrefix(COMMAND_SCHEME)
        val id = decode(target.substringBefore('?')).takeIf { it.isNotEmpty() } ?: return null
        val query = target.substringAfter('?', "")
        val parsed = if (query.isEmpty()) null else {
            runCatching { Json.parseToJsonElement(decode(query)) }.getOrNull()
                ?: runCatching { Json.parseToJsonElement(query) }.getOrNull()
        }
        val arguments = when (parsed) {
            null -> JsonArray(emptyList())
            is JsonArray -> parsed
            else -> JsonArray(listOf(parsed))
        }
        return CodeActions.Command(id, "", arguments)
    }

    /** `decodeURIComponent`: percent escapes only, where `URLDecoder` would also turn `+` into a space. */
    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(text)

    private const val COMMAND_SCHEME = "command:"

    private val COMMAND_LINK =
        Regex("""\[((?:\\.|[^\\\]])*)]\(command:([^)\s?]*)[^)\s]*(?:\s+'[^']*'|\s+"[^"]*")?\)""")
}
