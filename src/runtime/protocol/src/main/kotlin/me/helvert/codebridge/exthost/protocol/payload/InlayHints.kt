package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Inlay hints: the type after a `let`, the parameter name in front of an argument.
 *
 * Read off the 1.135 bundle's `ExtHostLanguageFeatures` and its `InlayHintsAdapter`. A reply is a
 * list the host keeps cached under an id until told to release it, because a hint may be resolved,
 * or one of its label parts clicked, long after it was offered.
 */
object InlayHints {

    /** `$registerInlayHintsProvider(handle, selector, supportsResolve, eventHandle, displayName)`. */
    const val REGISTER: String = $$"$registerInlayHintsProvider"
    const val PROVIDE: String = $$"$provideInlayHints"
    const val RESOLVE: String = $$"$resolveInlayHint"
    const val RELEASE: String = $$"$releaseInlayHints"
    const val EMIT_EVENT: String = $$"$emitInlayHintsEvent"

    /** `InlayHintKind`: what the hint says, which an extension may leave out. */
    const val KIND_TYPE: Int = 1
    const val KIND_PARAMETER: Int = 2

    /**
     * A provider's registration.
     *
     * [supportsResolve] is whether the extension fills hints in once asked about one of them. A
     * language client does whenever its server supports `inlayHint/resolve` -- and VS Code's client
     * tells a server it may leave a label part's place, command and tooltip out until then, which
     * rust-analyzer does. [eventHandle] is present when the extension offered
     * `onDidChangeInlayHints`; it is what it names in [EMIT_EVENT] once its hints have changed
     * without the document changing -- a language server that has finished loading, typically.
     */
    data class Registration(
        val handle: Int,
        val selector: JsonElement,
        val supportsResolve: Boolean,
        val eventHandle: Int?,
        val displayName: String?,
    )

    /**
     * One hint, at a **1-based** position in the text the provider was asked about.
     *
     * [parts] are the hint's text as the extension split it -- so that each part can carry its own
     * tooltip, place and command -- and [label] is them joined, which is what is read. [tooltip] is
     * the hint's own, or failing that its parts', as plain text. [split] is whether the label came
     * in parts at all: only a part can lead anywhere. [cacheId] is `[listId, index]`, which the hint
     * is resolved by.
     */
    data class Hint(
        val position: Position,
        val parts: List<Part>,
        val tooltip: String?,
        val kind: Int?,
        val paddingLeft: Boolean,
        val paddingRight: Boolean,
        val split: Boolean = false,
        val cacheId: Pair<Int, Int>? = null,
    ) {
        val label: String get() = parts.joinToString("") { it.text }
    }

    /**
     * One piece of a hint's label.
     *
     * [location] is where the piece leads -- a type hint's type, say -- and [command] what clicking
     * it runs, converted by the host into a command it keeps with the list the hint came in: it can
     * only be run while that list has not been released.
     */
    data class Part(
        val text: String,
        val tooltip: String? = null,
        val location: LanguageFeatures.Location? = null,
        val command: CodeActions.Command? = null,
    ) {
        /**
         * What clicking the piece does as it stands.
         *
         * A piece with both leads to its place: the click that reaches it is a Ctrl+click, which in
         * VS Code too goes to the place, leaving the command to a plain click.
         */
        val action: Click?
            get() = location?.let(Click::GoTo) ?: command?.let(Click::Run)
    }

    /** What clicking a piece of a hint does: open a place, run a command, or first ask what it does. */
    sealed interface Click {
        data class GoTo(val location: LanguageFeatures.Location) : Click
        data class Run(val command: CodeActions.Command) : Click

        /** Resolve hint [cacheId] of provider [handle], then do what its part [part] then does. */
        data class Resolve(val handle: Int, val cacheId: Pair<Int, Int>, val part: Int) : Click
    }

    /**
     * What clicking part [part] of [hint] does, encoded for [click]; null for a part that does nothing.
     *
     * A string, because that is what a click's payload has to be to reach a split-mode frontend and
     * come back. [resolvableBy] is the provider's handle when it fills hints in on request: a part
     * of a split label that says nothing yet may still lead somewhere once resolved, which is only
     * asked when it is clicked -- VS Code asks on hover, which this side has no way to hear about.
     */
    fun click(hint: Hint, part: Int, resolvableBy: Int?): String? {
        val action = hint.parts.getOrNull(part)?.action
            ?: if (resolvableBy != null && hint.split) hint.cacheId?.let { Click.Resolve(resolvableBy, it, part) } else null
        return when (action) {
            is Click.GoTo -> buildJsonObject {
                put("uri", action.location.resource.toJson())
                put("range", action.location.range.toJson())
            }
            is Click.Run -> buildJsonObject {
                put("command", buildJsonObject {
                    put("id", action.command.id)
                    put("title", action.command.title)
                    put("arguments", action.command.arguments)
                })
            }
            is Click.Resolve -> buildJsonObject {
                put("resolve", buildJsonObject {
                    put("handle", action.handle)
                    put("list", action.cacheId.first)
                    put("index", action.cacheId.second)
                    put("part", action.part)
                })
            }
            null -> null
        }?.toString()
    }

    /** Reads what [click] wrote, or null for anything else. */
    fun click(payload: String): Click? {
        val parsed = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        (parsed["resolve"] as? JsonObject)?.let { resolve ->
            fun int(name: String) = (resolve[name] as? JsonPrimitive)?.intOrNull
            val handle = int("handle") ?: return null
            val list = int("list") ?: return null
            val index = int("index") ?: return null
            return Click.Resolve(handle, list to index, int("part") ?: return null)
        }
        CodeActions.Command.from(parsed["command"])?.let { return Click.Run(it) }
        return locationOf(parsed)?.let(Click::GoTo)
    }

    /** A reply: the list id to release, and the hints in the order the extension gave them. */
    data class Offered(val cacheId: Int?, val hints: List<Hint>)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.boolOrNull(2) == true, args.intOrNull(3), args.strOrNull(4))
    }

    /** `$emitInlayHintsEvent(eventHandle)`. */
    fun emittedEventHandle(argsJson: String): Int? = Args.ofOrNull(EMIT_EVENT, argsJson)?.intOrNull(0)

    /**
     * Arguments for `$provideInlayHints(handle, resource, range)`.
     *
     * The host drops any hint outside [range], so a caller wanting a whole document's hints asks for
     * the whole document. The token is left for the host to append.
     */
    fun provide(handle: Int, resource: UriComponents, range: Range): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), range.toJson())).toString()

    /** Arguments for `$resolveInlayHint(handle, [listId, index])`; the token is left for the host to append. */
    fun resolve(handle: Int, cacheId: Pair<Int, Int>): String = "[$handle,[${cacheId.first},${cacheId.second}]]"

    /** Arguments for `$releaseInlayHints(handle, cacheId)`. */
    fun release(handle: Int, cacheId: Int): String = "[$handle,$cacheId]"

    /**
     * Reads a `$provideInlayHints` reply, which is `undefined` when the provider had nothing.
     *
     * A label is either a string or a list of `{label, tooltip?, location?, command?}` parts. A hint
     * whose label comes out empty is dropped, as the host itself drops one.
     */
    fun offered(reply: String?): Offered {
        if (reply.isNullOrBlank()) return Offered(null, emptyList())
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject
            ?: return Offered(null, emptyList())
        val hints = (parsed["hints"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::hintOf) }
        return Offered((parsed["cacheId"] as? JsonPrimitive)?.intOrNull, hints)
    }

    /** Reads a `$resolveInlayHint` reply: the hint filled in, or null when the provider added nothing. */
    fun resolved(reply: String?): Hint? {
        if (reply.isNullOrBlank()) return null
        return (runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject)?.let(::hintOf)
    }

    private fun hintOf(hint: JsonObject): Hint? {
        val position = (hint["position"] as? JsonObject)?.let { position ->
            val line = (position["lineNumber"] as? JsonPrimitive)?.intOrNull
            val column = (position["column"] as? JsonPrimitive)?.intOrNull
            if (line != null && column != null) Position(line, column) else null
        } ?: return null
        val label = hint["label"]
        val parts = when (label) {
            is JsonPrimitive -> if (label.isString) listOf(Part(label.content)) else emptyList()
            is JsonArray -> label.mapNotNull { element ->
                val part = element as? JsonObject ?: return@mapNotNull null
                Part(
                    text = Markdown.text(part["label"]) ?: return@mapNotNull null,
                    tooltip = tooltipOf(part["tooltip"]),
                    location = (part["location"] as? JsonObject)?.let(::locationOf),
                    command = CodeActions.Command.from(part["command"]),
                )
            }
            else -> emptyList()
        }.filter { it.text.isNotEmpty() }
        if (parts.isEmpty()) return null
        val cacheId = (hint["cacheId"] as? JsonArray)?.let { id ->
            val list = (id.getOrNull(0) as? JsonPrimitive)?.intOrNull
            val index = (id.getOrNull(1) as? JsonPrimitive)?.intOrNull
            if (list != null && index != null) list to index else null
        }
        return Hint(
            position = position,
            parts = parts,
            tooltip = tooltipOf(hint["tooltip"]) ?: parts.mapNotNull { it.tooltip }.joinToString(NEWLINE).ifEmpty { null },
            kind = (hint["kind"] as? JsonPrimitive)?.intOrNull,
            paddingLeft = (hint["paddingLeft"] as? JsonPrimitive)?.booleanOrNull == true,
            paddingRight = (hint["paddingRight"] as? JsonPrimitive)?.booleanOrNull == true,
            split = label is JsonArray,
            cacheId = cacheId,
        )
    }

    /** `{uri, range}`, as a reference is. */
    private fun locationOf(location: JsonObject): LanguageFeatures.Location? {
        val resource = UriComponents.from(location["uri"]) ?: return null
        return LanguageFeatures.Location(resource, Range.from(location["range"]) ?: return null)
    }

    /** A plain string or a `MarkdownString`, shown as it is written; blank is no tooltip. */
    private fun tooltipOf(element: JsonElement?): String? =
        Markdown.text(element)?.let(Markdown::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    private const val NEWLINE = "\n"
}
