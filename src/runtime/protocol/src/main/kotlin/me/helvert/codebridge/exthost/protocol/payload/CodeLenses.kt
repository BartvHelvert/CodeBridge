package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * Code lenses: "Run", "Debug", "3 references" above the line they are about.
 *
 * Read off the 1.143 bundle's `ExtHostLanguageFeatures` and its `CodeLensAdapter`. A reply is a list
 * the host keeps cached under an id until told to release it: a lens may arrive with only its range
 * and be resolved later, and the command a lens carries is converted into one the host keeps with
 * the list -- it can only be run while that list has not been released.
 */
object CodeLenses {

    /** `$registerCodeLensSupport(handle, selector, eventHandle)`. */
    const val REGISTER: String = $$"$registerCodeLensSupport"
    const val PROVIDE: String = $$"$provideCodeLenses"
    const val RESOLVE: String = $$"$resolveCodeLens"
    const val RELEASE: String = $$"$releaseCodeLenses"
    const val EMIT_EVENT: String = $$"$emitCodeLensEvent"

    /**
     * A provider's registration.
     *
     * [eventHandle] is present when the extension offered `onDidChangeCodeLenses`; it is what it
     * names in [EMIT_EVENT] once its lenses have changed without the document changing.
     */
    data class Registration(val handle: Int, val selector: JsonElement, val eventHandle: Int?)

    /**
     * One lens, over a **1-based** range in the text the provider was asked about.
     *
     * [title] is null for a lens not yet resolved: an extension may offer the range alone and fill
     * in what it says only when asked, which is what [RESOLVE] is for. [command] is what clicking it
     * runs, null for a lens that is only text -- one whose command names no command. [cacheId] is
     * `[listId, index]`, which the lens is resolved by.
     */
    data class Lens(
        val range: Range,
        val title: String? = null,
        val tooltip: String? = null,
        val command: CodeActions.Command? = null,
        val cacheId: Pair<Int, Int>? = null,
    )

    /** A reply: the list id to release, and the lenses in the order the extension gave them. */
    data class Offered(val cacheId: Int?, val lenses: List<Lens>)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.intOrNull(2))
    }

    /** `$emitCodeLensEvent(eventHandle)`. */
    fun emittedEventHandle(argsJson: String): Int? = Args.ofOrNull(EMIT_EVENT, argsJson)?.intOrNull(0)

    /** Arguments for `$provideCodeLenses(handle, resource)`; the token is left for the host to append. */
    fun provide(handle: Int, resource: UriComponents): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson())).toString()

    /**
     * Arguments for `$resolveCodeLens(handle, lens)`; the token is left for the host to append.
     *
     * The host finds the lens by its cache id alone, and answers with what it was sent, its command
     * filled in.
     */
    fun resolve(handle: Int, lens: Lens): String {
        val cacheId = requireNotNull(lens.cacheId) { "Only a lens the host cached can be resolved" }
        val dto = buildJsonObject {
            put("cacheId", JsonArray(listOf(JsonPrimitive(cacheId.first), JsonPrimitive(cacheId.second))))
            put("range", lens.range.toJson())
        }
        return JsonArray(listOf(JsonPrimitive(handle), dto)).toString()
    }

    /** Arguments for `$releaseCodeLenses(handle, cacheId)`. */
    fun release(handle: Int, cacheId: Int): String = "[$handle,$cacheId]"

    /** Reads a `$provideCodeLenses` reply, which is `undefined` when the provider had nothing. */
    fun offered(reply: String?): Offered {
        val parsed = parse(reply) ?: return Offered(null, emptyList())
        val lenses = (parsed["lenses"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::lensOf) }
        return Offered((parsed["cacheId"] as? JsonPrimitive)?.intOrNull, lenses)
    }

    /**
     * Reads a `$resolveCodeLens` reply: the lens with its command, or null when the provider gave
     * none -- or no longer holds the lens, its list having been released.
     */
    fun resolved(reply: String?): Lens? = parse(reply)?.let(::lensOf)?.takeIf { it.title != null }

    private fun parse(reply: String?): JsonObject? {
        if (reply.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject
    }

    private fun lensOf(lens: JsonObject): Lens? {
        val range = Range.from(lens["range"]) ?: return null
        val command = lens["command"] as? JsonObject
        val cacheId = cacheIdOf(lens["cacheId"])
        return Lens(
            range = range,
            title = command?.let { (it["title"] as? JsonPrimitive)?.contentOrNull.orEmpty() },
            tooltip = (command?.get("tooltip") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            // A command without an id is a lens that is only text, as VS Code draws it.
            command = CodeActions.Command.from(command)?.takeIf { it.id.isNotEmpty() },
            cacheId = cacheId,
        )
    }

    /**
     * [title] as it reads without icons.
     *
     * VS Code draws `$(name)` as the codicon of that name, inline; a code vision entry is text, so
     * the icon is left out rather than shown as its name.
     */
    fun plainTitle(title: String): String = title.replace(ICON, "").replace(SPACES, " ").trim()

    private val ICON = Regex("""\$\([A-Za-z0-9~-]+\)""")
    private val SPACES = Regex(""" {2,}""")
}
