package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * One completion, as `$provideCompletionItems` answers.
 *
 * Everything an extension can attach that is dropped here -- commit characters, additional text
 * edits -- is dropped because nothing draws it yet, not because it failed to arrive.
 */
data class CompletionItem(
    val label: String,
    val insertText: String,
    val kind: Int?,
    val detail: String?,
    val documentation: String?,
    /** Whether [documentation] is markdown, as a `MarkdownString`'s is, rather than plain text. */
    val documentationIsMarkdown: Boolean = false,
    val filterText: String?,
    val sortText: String?,
    /** VS Code snippet syntax, which IntelliJ does not speak. */
    val snippet: Boolean,
    /** The text the item replaces, or null when the result's default range applies. */
    val replace: Range?,
    /**
     * What to run once the item is inserted. Its arguments may name a command the host keeps with
     * the list, which then has to be run before the list is released.
     */
    val command: CodeActions.Command? = null,
    /** Where the item stood in the reply, which with the list's cache id names it to the host. */
    val index: Int = 0,
    /** How to ask for the rest of the item, or null when its provider does not resolve items. */
    val resolvable: Resolvable? = null,
)

/** A completion the host still holds, by its provider, its list's cache id and its place there. */
data class Resolvable(val handle: Int, val cacheId: Int, val index: Int)

/**
 * What `$resolveCompletionItem` fills in that is drawn: the detail and documentation of the item the
 * user settled on, which an extension may leave out of the list to answer it sooner.
 */
data class ResolvedCompletion(val detail: String?, val documentation: String?, val documentationIsMarkdown: Boolean)

/** A whole `$provideCompletionItems` reply: the items, and the cache handle to hand back. */
data class CompletionList(val items: List<CompletionItem>, val cacheId: Int?)

/**
 * The reply to `ExtHostLanguageFeatures.$provideCompletionItems`.
 *
 * The shape is `{x: cacheId, a: defaultRange, b: items, c: isIncomplete}`, and each item is keyed
 * the same way: `a` label, `b` kind, `c` detail, `d` documentation, `e` sortText, `f` filterText,
 * `h` insertText, `i` insertTextRules, `j` range, and `n`, `o`, `p` its command. These single letters
 * are not a guess and not an abbreviation anyone chose for readability -- they are what the minifier
 * left behind, read off the runtime bundle the same way the rpcIds are, and there is no published
 * description of any of it.
 *
 * Which makes this the most version-fragile shape on the protocol, and the reason it sits beside
 * the rpcId table rather than in the code that draws a completion popup: both are facts about one
 * build of VS Code, and both are checked by tests that replay captured traffic.
 */
object Completions {

    const val PROVIDE: String = $$"$provideCompletionItems"
    const val RESOLVE: String = $$"$resolveCompletionItem"

    /** `InsertTextRules.InsertAsSnippet`; bit 1 is KeepWhitespace, which changes nothing here. */
    private const val SNIPPET_RULE = 4

    fun parse(reply: String): CompletionList {
        val result = json(reply) ?: return CompletionList(emptyList(), null)
        val default = Range.from(result["a"])
        // Indexed before anything unreadable is dropped: the host names an item by its place in the
        // list it built, not in this one.
        val items = (result["b"] as? JsonArray).orEmpty().withIndex().mapNotNull { (index, entry) ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val label = labelOf(item["a"]) ?: return@mapNotNull null
            val rules = item["i"]?.jsonPrimitive?.intOrNull ?: 0
            CompletionItem(
                label = label,
                insertText = item["h"]?.jsonPrimitive?.contentOrNull ?: label,
                kind = item["b"]?.jsonPrimitive?.intOrNull,
                detail = item["c"]?.jsonPrimitive?.contentOrNull,
                documentation = Markdown.text(item["d"]),
                documentationIsMarkdown = item["d"] is JsonObject,
                filterText = item["f"]?.jsonPrimitive?.contentOrNull,
                sortText = item["e"]?.jsonPrimitive?.contentOrNull,
                snippet = rules and SNIPPET_RULE != 0,
                replace = Range.from(item["j"]) ?: default,
                command = commandOf(item),
                index = index,
            )
        }
        return CompletionList(items, result["x"]?.jsonPrimitive?.intOrNull)
    }

    /**
     * Arguments for `$resolveCompletionItem(handle, [cacheId, index])`; the token is the host's.
     *
     * The pair is VS Code's `ChainedCacheId`: the list's cache id and the item's place in it.
     */
    fun resolve(item: Resolvable): String = "[${item.handle},[${item.cacheId},${item.index}]]"

    /**
     * The reply to `$resolveCompletionItem`: the item again, in the shape of one in the list, or
     * nothing when the provider does not resolve or the list was already released.
     */
    fun parseResolved(reply: String): ResolvedCompletion? {
        val item = json(reply) ?: return null
        return ResolvedCompletion(
            detail = item["c"]?.jsonPrimitive?.contentOrNull,
            documentation = Markdown.text(item["d"]),
            documentationIsMarkdown = item["d"] is JsonObject,
        )
    }

    /**
     * `o` is the command's id. A command with arguments the host cannot send is kept there, `n`
     * naming it, and `o` is then the host's own command that runs it given `n`; otherwise `p` holds
     * the arguments. This is how the workbench runs it, too.
     */
    private fun commandOf(item: JsonObject): CodeActions.Command? {
        val id = item["o"]?.jsonPrimitive?.contentOrNull ?: return null
        val cached = item["n"]?.jsonPrimitive?.contentOrNull
        val arguments =
            if (cached != null) JsonArray(listOf(JsonPrimitive(cached))) else item["p"] as? JsonArray ?: JsonArray(
                emptyList()
            )
        return CodeActions.Command(id, "", arguments)
    }

    /** A label is a string, or a `{label, detail, description}` when the extension split it up. */
    private fun labelOf(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["label"]?.jsonPrimitive?.contentOrNull
        else -> null
    }

    private fun json(reply: String) =
        runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject
}
