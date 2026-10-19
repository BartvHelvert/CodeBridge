package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Semantic tokens: the half of VS Code's colouring that is not TextMate.
 *
 * A grammar colours what a regular expression can recognise. Everything that needs the program --
 * whether `x` is a parameter or a property, whether `Foo` is a class or a namespace, whether a name
 * resolves into the standard library -- comes from the extension instead, as a flat list of spans
 * the editor paints *over* the grammar's result. It is most of what makes VS Code's TypeScript and
 * Rust look better informed than the same grammars do elsewhere.
 *
 * Two things here are unlike every other payload on this protocol.
 *
 * **The reply is binary.** Not JSON: a `VSBuffer` of little-endian `uint32`s, because a large file
 * produces tens of thousands of numbers and encoding those as text costs more than the request
 * does. It arrives as [me.helvert.codebridge.exthost.protocol.RpcMessage.ReplyOkBuffer], which is why the
 * session grew a way to ask for bytes.
 *
 * **The coordinates are 0-based**, and relative. Everything else crossing this protocol is in the
 * workbench's 1-based editor model, because the workbench has already converted it; these are
 * passed through untouched from what the extension built with `vscode.SemanticTokensBuilder`, which
 * is LSP's own encoding. [Token] keeps them 0-based rather than quietly shifting them, so the one
 * conversion stays visible at the place that draws them.
 */
object SemanticTokens {

    const val REGISTER: String = $$"$registerDocumentSemanticTokensProvider"
    const val REGISTER_RANGE: String = $$"$registerDocumentRangeSemanticTokensProvider"
    const val PROVIDE: String = $$"$provideDocumentSemanticTokens"
    const val PROVIDE_RANGE: String = $$"$provideDocumentRangeSemanticTokens"
    const val RELEASE: String = $$"$releaseDocumentSemanticTokens"
    const val EMIT_EVENT: String = $$"$emitDocumentSemanticTokensEvent"

    /**
     * What the numbers in a reply mean, declared once at registration.
     *
     * Every provider chooses its own vocabulary and its own order, so a token's type is an index
     * into [types] and its modifiers a bit set over [modifiers] -- meaningless without the legend
     * the same handle registered. LSP's standard names (`function`, `parameter`, `readonly`, ...)
     * are a convention providers mostly follow, not a fixed table, which is why these are resolved
     * to strings here and matched by name later.
     */
    data class Legend(val types: List<String>, val modifiers: List<String>)

    /**
     * A provider's registration.
     *
     * [eventHandle] is present when the extension offered an `onDidChangeSemanticTokens`; it is the
     * handle it will name in [EMIT_EVENT] once its answer has changed, which for a language server
     * is "the project finished loading" -- the moment a file first colours properly.
     */
    data class Registration(
        val handle: Int,
        val selector: JsonElement,
        val legend: Legend,
        val eventHandle: Int? = null,
    )

    /**
     * One span to colour, with its legend indices already resolved.
     *
     * [line] and [character] are **0-based**, and [length] is in UTF-16 code units -- the same unit
     * IntelliJ measures documents in, so nothing has to be re-encoded on the way to an offset.
     */
    data class Token(
        val line: Int,
        val character: Int,
        val length: Int,
        val type: String,
        val modifiers: Set<String>,
    )

    /**
     * A decoded reply.
     *
     * [resultId] is the host's cache key, not an index: a non-zero one means the host kept a copy
     * of this answer to diff the next request against, and is waiting either to be asked for that
     * diff or to be told to let go of it.
     *
     * [data] is the answer in the host's own encoding -- five numbers a token, relative positions --
     * which is what the next delta is expressed against. Null when this reply cannot serve as that
     * base: a truncated one, or a delta that could not be applied, after which the only safe next
     * question is one for the whole answer.
     */
    class Tokens(val resultId: Int, val tokens: List<Token>, val data: IntArray?)

    /** `$registerDocumentSemanticTokensProvider(handle, selector, legend, eventHandle)`. */
    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, legend(args.obj(2)), args.intOrNull(3))
    }

    /** `$registerDocumentRangeSemanticTokensProvider(handle, selector, legend)`, which has no event. */
    fun rangeRegistration(argsJson: String): Registration {
        val args = Args.of(REGISTER_RANGE, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, legend(args.obj(2)))
    }

    /** `$emitDocumentSemanticTokensEvent(eventHandle)`, naming a provider's whole answer as stale. */
    fun emittedEventHandle(argsJson: String): Int? = Args.ofOrNull(EMIT_EVENT, argsJson)?.intOrNull(0)

    /**
     * Arguments for `$provideDocumentSemanticTokens(handle, resource, previousResultId)`.
     *
     * [previousResultId] names the last answer this provider gave for the document, and the reply is
     * then a delta against it: only the stretch of numbers that changed, which for a keystroke in a
     * large file is a handful instead of tens of thousands. 0 asks for the whole answer. The host
     * forgets the answer it was given once it has replied, so an id is only ever sent once.
     *
     * The cancellation token is absent on purpose: the host appends its own for a cancellable call.
     */
    fun provide(handle: Int, resource: UriComponents, previousResultId: Int = 0): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            JsonPrimitive(previousResultId),
        )).toString()

    /** Arguments for `$provideDocumentRangeSemanticTokens(handle, resource, range)`. */
    fun provideRange(handle: Int, resource: UriComponents, range: Range): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            range.toJson(),
        )).toString()

    /** Arguments for `$releaseDocumentSemanticTokens(handle, resultId)`, which the host waits for. */
    fun release(handle: Int, resultId: Int): String = "[$handle,$resultId]"

    /**
     * Reads the `VSBuffer` a provider replied with.
     *
     * The layout, as `encodeSemanticTokensDto` writes it -- a `Uint32Array` reinterpreted as bytes,
     * so little-endian on every platform VS Code runs on:
     *
     * ```
     * [0]   result id           the host's cache key, 0 when it kept nothing
     * [1]   1 = full, 2 = delta
     * full:  [2] how many uint32s of token data follow, then
     *        five per token: deltaLine, deltaStartChar, length, typeIndex, modifierBits
     * delta: [2] how many edits follow, then per edit
     *        start, deleteCount, how many uint32s follow, then those
     * ```
     *
     * A delta's positions count numbers in [previous] -- the `data` of the answer whose id was sent
     * -- not tokens or characters; applying it gives the whole new answer, which is then read as a
     * full one would be. Without [previous] a delta cannot be read, and comes back with no tokens
     * and no data.
     *
     * Nothing here trusts the buffer's own lengths. A truncated or mis-framed reply yields fewer
     * tokens -- never an exception, and never an allocation sized by the wire.
     */
    fun decode(bytes: ByteArray?, legend: Legend, previous: IntArray? = null): Tokens {
        if (bytes == null || bytes.size < HEADER_WORDS * Int.SIZE_BYTES) return EMPTY
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val resultId = words.get(0)
        val data = when (words.get(1)) {
            FULL -> {
                val declared = words.get(2).coerceAtLeast(0)
                val available = words.limit() - HEADER_WORDS
                val data = IntArray(minOf(declared, available)) { words.get(HEADER_WORDS + it) }
                // A truncated reply still colours what it has; it just cannot be diffed against.
                if (declared > available) return Tokens(resultId, tokensOf(data, legend), null)
                data
            }
            DELTA -> previous?.let { applyDelta(words, it) }
            else -> null
        } ?: return Tokens(resultId, emptyList(), null)
        return Tokens(resultId, tokensOf(data, legend), data)
    }

    /**
     * [previous] with a delta's edits applied, or null when they do not fit it.
     *
     * The edits are in ascending order and do not overlap, as the host produces them and as VS Code
     * applies them; anything else means the delta is against an answer other than [previous], and
     * applying it would colour the file with a mixture of two.
     */
    private fun applyDelta(words: IntBuffer, previous: IntArray): IntArray? {
        val count = words.get(2)
        val edits = ArrayList<Triple<Int, Int, IntArray>>()
        var at = HEADER_WORDS
        var end = 0
        repeat(count.coerceAtLeast(0)) {
            // Every edit takes at least three words, so a count bigger than the buffer runs out here.
            if (at + 3 > words.limit()) return null
            val start = words.get(at)
            val deleteCount = words.get(at + 1)
            val length = words.get(at + 2)
            at += 3
            if (start < end || deleteCount < 0 || start + deleteCount > previous.size) return null
            if (length < 0 || at + length > words.limit()) return null
            edits += Triple(start, deleteCount, IntArray(length) { words.get(at + it) })
            at += length
            end = start + deleteCount
        }
        val out = IntArray(previous.size + edits.sumOf { (_, deleteCount, data) -> data.size - deleteCount })
        var from = 0
        var to = 0
        for ((start, deleteCount, data) in edits) {
            previous.copyInto(out, to, from, start)
            to += start - from
            data.copyInto(out, to)
            to += data.size
            from = start + deleteCount
        }
        previous.copyInto(out, to, from, previous.size)
        return out
    }

    /** The tokens [data] encodes, rounded down to whole tokens: a partial one has no type to colour by. */
    private fun tokensOf(data: IntArray, legend: Legend): List<Token> {
        val fields = data.size / FIELDS_PER_TOKEN * FIELDS_PER_TOKEN
        val tokens = ArrayList<Token>(fields / FIELDS_PER_TOKEN)
        var line = 0
        var character = 0
        for (at in 0 until fields step FIELDS_PER_TOKEN) {
            val deltaLine = data[at]
            line += deltaLine
            // Relative to the previous token when they share a line, absolute on a new one.
            character = if (deltaLine == 0) character + data[at + 1] else data[at + 1]
            val length = data[at + 2]
            val type = legend.types.getOrNull(data[at + 3])
            if (type == null || length <= 0 || line < 0 || character < 0) continue
            tokens += Token(line, character, length, type, modifiers(data[at + 4], legend))
        }
        return tokens
    }

    /**
     * [tokens], computed against [previousText], moved to where the same words are in [text].
     *
     * An answer is always about text the user has since changed: it was asked for after the editor
     * went quiet, and every keystroke redraws the file before the next answer arrives. Drawn at
     * their old line and column, the tokens colour the wrong words -- after a new line, every line
     * below takes the colours of the one above it -- until the round trip completes, and that
     * wrong-then-right flicker is what made typing feel slow. So they move with the text, as VS
     * Code's own copy does: tokens before the edit stay, tokens after it move, and a token the edit
     * touched is dropped -- except a word typed into or at the end of, which is still that word and
     * keeps its colour while it grows or shrinks.
     */
    fun rebase(tokens: List<Token>, previousText: CharSequence, text: CharSequence): List<Token> {
        if (tokens.isEmpty()) return tokens
        val change = Documents.changeBetween(previousText, text)
        if (change.start == change.oldEnd && change.start == change.newEnd) return tokens
        // 0-based, like the tokens.
        val start = Documents.positionOf(previousText, change.start).let { it.lineNumber - 1 to it.column - 1 }
        val oldEnd = Documents.positionOf(previousText, change.oldEnd).let { it.lineNumber - 1 to it.column - 1 }
        val newEnd = Documents.positionOf(text, change.newEnd).let { it.lineNumber - 1 to it.column - 1 }
        val inserted = text.subSequence(change.start, change.newEnd)
        val withinWord = start.first == oldEnd.first && inserted.all(Character::isJavaIdentifierPart)
        val growth = (change.newEnd - change.start) - (change.oldEnd - change.start)
        val lines = newEnd.first - oldEnd.first
        return tokens.mapNotNull { token ->
            val end = token.character + token.length
            when {
                // Inside the token, or at its end -- but not typed just in front of it, which is
                // the previous word's end as often as this one's start.
                withinWord && token.line == start.first && token.character <= start.second &&
                    oldEnd.second <= end && !(token.character == start.second && change.start == change.oldEnd) ->
                    token.copy(length = token.length + growth).takeIf { it.length > 0 }
                token.line < start.first || (token.line == start.first && end <= start.second) -> token
                token.line > oldEnd.first -> token.copy(line = token.line + lines)
                token.line == oldEnd.first && token.character >= oldEnd.second ->
                    token.copy(line = newEnd.first, character = token.character - oldEnd.second + newEnd.second)
                else -> null
            }
        }
    }

    /** `{tokenTypes, tokenModifiers}`, each in the order its indices refer to. */
    private fun legend(value: JsonObject): Legend = Legend(
        types = strings(value["tokenTypes"]),
        modifiers = strings(value["tokenModifiers"]),
    )

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    /**
     * The set bits of a modifier mask, as names.
     *
     * Kept in the legend's own order rather than a hash order, so what is drawn -- and what a test
     * asserts -- does not depend on which names an extension happened to choose.
     */
    private fun modifiers(mask: Int, legend: Legend): Set<String> {
        if (mask == 0) return emptySet()
        val names = LinkedHashSet<String>()
        for ((index, name) in legend.modifiers.withIndex()) {
            if (index >= Int.SIZE_BITS) break
            if (mask and (1 shl index) != 0) names += name
        }
        return names
    }

    /** Result id, encoding type, data length. */
    private const val HEADER_WORDS = 3

    /** `EncodedSemanticTokensType.Full` and `.Delta`. */
    private const val FULL = 1
    private const val DELTA = 2

    /** deltaLine, deltaStartChar, length, typeIndex, modifierBits. */
    private const val FIELDS_PER_TOKEN = 5

    private val EMPTY = Tokens(0, emptyList(), null)
}
