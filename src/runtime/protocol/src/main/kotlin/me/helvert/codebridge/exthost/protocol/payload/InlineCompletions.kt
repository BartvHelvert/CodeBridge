package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import java.util.*

/**
 * Inline completions: text an extension proposes to type next, shown as ghost text after the caret.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `InlineCompletionAdapter`. The host keeps
 * each reply as a list, under the `pid` it answers with, until `$freeInlineCompletionsList`: the
 * items' commands are converted against it, so a list is freed only once a command it carries has
 * run. The other lifecycle calls -- shown, partially accepted, rejected -- reach only extensions
 * using the proposed `inlineCompletionsAdditions` API, and are not made.
 */
object InlineCompletions {

    /**
     * `$registerInlineCompletionsSupport(handle, selector, supportsHandleEvents, extensionId, version,
     * groupId, yieldTo, displayName, debounceDelayMs, excludes, ...)`.
     */
    const val REGISTER: String = $$"$registerInlineCompletionsSupport"
    const val PROVIDE: String = $$"$provideInlineCompletions"
    const val FREE: String = $$"$freeInlineCompletionsList"

    /** VS Code's setting that turns this off, on unless the user says otherwise. */
    const val SETTING: String = "editor.inlineSuggest.enabled"

    /** [debounceMillis] is how long the extension asked to wait after typing before being asked. */
    data class Registration(val handle: Int, val selector: JsonElement, val debounceMillis: Long?)

    /**
     * One proposal. [text] replaces [range] -- which ends at the caret or beyond it on its line, and
     * where absent is VS Code's default, the word at the caret to the end of the line -- and
     * [command] runs once it has been accepted.
     */
    data class Item(
        val index: Int,
        val text: String,
        val snippet: Boolean,
        val range: Range?,
        val command: CodeActions.Command?
    )

    /** A reply, kept in the host as list [id] until freed. */
    data class Answer(val id: Int, val items: List<Item>)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.longOrNull(8))
    }

    /**
     * Arguments for `$provideInlineCompletions(handle, resource, position, context)`; the token is
     * the host's. [explicit] is whether the user asked, rather than typed: the wire's trigger kind is
     * 0 for automatic and 1 for explicit, which the host turns into the API's own numbering. The
     * request's id and times are passed on to the extension as the workbench fills them in.
     */
    fun provide(handle: Int, resource: UriComponents, position: Position, explicit: Boolean): String {
        val now = System.currentTimeMillis()
        return JsonArray(
            listOf(
                JsonPrimitive(handle),
                resource.toJson(),
                position.toJson(),
                buildJsonObject {
                    put("triggerKind", if (explicit) 1 else 0)
                    put("selectedSuggestionInfo", JsonNull)
                    put("requestUuid", UUID.randomUUID().toString())
                    put("requestIssuedDateTime", now)
                    put("earliestShownDateTime", now)
                },
            )
        ).toString()
    }

    /**
     * Reads a reply, `undefined` from a provider with nothing to say.
     *
     * An item's `insertText` is a string, or `{snippet}` for a `SnippetString`; an item without one
     * is an inline edit of the proposed API, which has no text to type and is left out.
     */
    fun answer(reply: String?): Answer? {
        if (reply.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val id = (parsed["pid"] as? JsonPrimitive)?.intOrNull ?: return null
        val items = (parsed["items"] as? JsonArray).orEmpty().mapIndexedNotNull { position, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val (text, snippet) = when (val insert = item["insertText"]) {
                is JsonPrimitive -> (insert.takeIf { it.isString }?.content ?: return@mapIndexedNotNull null) to false
                is JsonObject -> ((insert["snippet"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapIndexedNotNull null) to true

                else -> return@mapIndexedNotNull null
            }
            Item(
                index = (item["idx"] as? JsonPrimitive)?.intOrNull ?: position,
                text = text,
                snippet = snippet,
                range = Range.from(item["range"]),
                command = CodeActions.Command.from(item["command"]),
            )
        }
        return Answer(id, items)
    }

    /**
     * Arguments for `$freeInlineCompletionsList(handle, pid, reason)`. The reason reaches only the
     * proposed API's `handleListEndOfLifetime`, so it is always "other".
     */
    fun free(handle: Int, id: Int): String =
        JsonArray(listOf(JsonPrimitive(handle), JsonPrimitive(id), buildJsonObject { put("kind", "other") })).toString()
}
