package me.helvert.codebridge.exthost.protocol.payload

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one payload on this protocol that is neither JSON nor 1-based.
 *
 * Both of those are the kind of mistake that produces colour in the wrong place rather than an
 * error, so the decoding is pinned against buffers built here by hand -- little-endian `uint32`s,
 * laid out the way `encodeSemanticTokensDto` lays them out -- rather than against anything this
 * object also writes.
 */
class SemanticTokensTest {

    private val legend = SemanticTokens.Legend(
        types = listOf("namespace", "class", "function", "parameter"),
        modifiers = listOf("declaration", "static", "deprecated"),
    )

    @Test
    fun `relative positions become absolute ones`() {
        // Three tokens: line 0 col 4, then col 10 of the same line, then line 2 col 2.
        // The second names a delta of 6 from the first's start, not an absolute column.
        val tokens = SemanticTokens.decode(
            dto(
                resultId = 0,
                0, 4, 3, 2, 0,
                0, 6, 5, 3, 0,
                2, 2, 4, 1, 0,
            ),
            legend,
        ).tokens
        assertEquals(listOf(0 to 4, 0 to 10, 2 to 2), tokens.map { it.line to it.character })
        assertEquals(listOf("function", "parameter", "class"), tokens.map { it.type })
        assertEquals(listOf(3, 5, 4), tokens.map { it.length })
    }

    @Test
    fun `a new line restarts the column rather than continuing it`() {
        // The trap: `character` accumulates only while `deltaLine` is 0. Carrying it across a line
        // break colours further and further right down the file, which looks plausible at the top.
        val tokens = SemanticTokens.decode(dto(0, 0, 40, 2, 1, 0, 1, 3, 2, 1, 0), legend).tokens
        assertEquals(listOf(0 to 40, 1 to 3), tokens.map { it.line to it.character })
    }

    @Test
    fun `modifiers are read as bits, in the legend's own order`() {
        val tokens = SemanticTokens.decode(dto(0, 0, 0, 4, 2, 0b101), legend).tokens
        assertEquals(setOf("declaration", "deprecated"), tokens.single().modifiers)
        // Order comes from the legend, not from a hash, so what is drawn does not depend on the
        // names an extension happened to choose.
        assertEquals(listOf("declaration", "deprecated"), tokens.single().modifiers.toList())
        assertEquals(emptySet(), SemanticTokens.decode(dto(0, 0, 0, 4, 2, 0), legend).tokens.single().modifiers)
    }

    @Test
    fun `a type outside the legend is dropped without shifting what follows`() {
        // A legend skew must not slide every later token leftwards: the position of an unreadable
        // token still has to be accumulated, only its colour is lost.
        val tokens = SemanticTokens.decode(dto(0, 0, 4, 3, 99, 0, 0, 6, 5, 3, 0), legend).tokens
        assertEquals(1, tokens.size)
        assertEquals(0 to 10, tokens.single().let { it.line to it.character })
        assertEquals("parameter", tokens.single().type)
    }

    @Test
    fun `a zero-length token is not drawn`() {
        assertTrue(SemanticTokens.decode(dto(0, 0, 4, 0, 2, 0), legend).tokens.isEmpty())
    }

    @Test
    fun `a truncated buffer yields the tokens that survived it`() {
        val whole = dto(0, 0, 4, 3, 2, 0, 0, 6, 5, 3, 0)
        // Cut two words off the end: the declared length now overruns the buffer, and the trailing
        // partial token has no type to be coloured by.
        val tokens = SemanticTokens.decode(whole.copyOf(whole.size - 2 * Int.SIZE_BYTES), legend).tokens
        assertEquals(1, tokens.size)
        assertEquals("function", tokens.single().type)
    }

    @Test
    fun `nothing is trusted to size an allocation`() {
        val buffer = ByteBuffer.allocate(4 * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        // A declared data length of two billion words, and one word of data.
        buffer.putInt(0).putInt(1).putInt(Int.MAX_VALUE).putInt(7)
        assertTrue(SemanticTokens.decode(buffer.array(), legend).tokens.isEmpty())
        // And a negative one, which is what an unsigned length over 2^31 arrives as.
        assertTrue(SemanticTokens.decode(dtoRaw(0, 1, -1), legend).tokens.isEmpty())
    }

    @Test
    fun `a reply too short to hold a header is no tokens at all`() {
        assertEquals(0, SemanticTokens.decode(ByteArray(4), legend).resultId)
        assertTrue(SemanticTokens.decode(ByteArray(0), legend).tokens.isEmpty())
        assertTrue(SemanticTokens.decode(null, legend).tokens.isEmpty())
    }

    @Test
    fun `a delta with nothing to apply it to draws nothing but still surrenders its result id`() {
        // The host is holding that answer either way, and only the id can release it.
        val decoded = SemanticTokens.decode(dtoRaw(11, 2, 0), legend)
        assertEquals(11, decoded.resultId)
        assertTrue(decoded.tokens.isEmpty())
        assertNull(decoded.data, "Nothing to diff the next answer against")
    }

    @Test
    fun `a full answer keeps its numbers for the next delta`() {
        val decoded = SemanticTokens.decode(dto(4, 0, 4, 3, 2, 0), legend)
        assertEquals(listOf(0, 4, 3, 2, 0), decoded.data?.toList())
    }

    @Test
    fun `a delta is applied to the previous answer's numbers`() {
        val previous = intArrayOf(
            0, 4, 3, 2, 0,
            0, 6, 5, 3, 0,
            2, 2, 4, 1, 0,
        )
        // Replace the second token's length and type (numbers 7 and 8), and append a fourth token.
        val decoded = SemanticTokens.decode(
            dtoRaw(12, 2, 2, 7, 2, 2, 6, 1, 15, 0, 5, 1, 0, 3, 0, 0),
            legend,
            previous,
        )
        assertEquals(12, decoded.resultId)
        assertEquals(
            listOf(0, 4, 3, 2, 0, 0, 6, 6, 1, 0, 2, 2, 4, 1, 0, 1, 0, 3, 0, 0),
            decoded.data?.toList(),
        )
        assertEquals(listOf(Triple(0, 4, 3), Triple(0, 10, 6), Triple(2, 2, 4), Triple(3, 0, 3)), decoded.tokens.map(::spans))
        assertEquals("class", decoded.tokens[1].type)
    }

    @Test
    fun `a delta that does not fit the previous answer is refused rather than half-applied`() {
        val previous = intArrayOf(0, 4, 3, 2, 0)
        // Deletes past the end of what it claims to be a diff of.
        assertNull(SemanticTokens.decode(dtoRaw(3, 2, 1, 4, 5, 0), legend, previous).data)
        // Out of order.
        assertNull(SemanticTokens.decode(dtoRaw(3, 2, 2, 3, 1, 0, 1, 1, 0), legend, previous).data)
        // More edits declared than the buffer holds.
        assertNull(SemanticTokens.decode(dtoRaw(3, 2, 1_000_000, 0, 0, 0), legend, previous).data)
    }

    @Test
    fun `a result id survives an answer with no tokens in it`() {
        assertEquals(9, SemanticTokens.decode(dto(resultId = 9), legend).resultId)
    }

    @Test
    fun `reads a registration and the legend its numbers mean nothing without`() {
        val registration = SemanticTokens.registration(
            """[6,[{"language":"rust"}],{"tokenTypes":["class","function"],"tokenModifiers":["static"]},12]"""
        )
        assertEquals(6, registration.handle)
        assertEquals(12, registration.eventHandle)
        assertEquals(listOf("class", "function"), registration.legend.types)
        assertEquals(listOf("static"), registration.legend.modifiers)
        assertTrue(registration.selector.toString().contains("rust"))
    }

    @Test
    fun `a provider that offers no change event is still registered`() {
        val registration = SemanticTokens.registration("""[6,[],{"tokenTypes":[],"tokenModifiers":[]}]""")
        assertNull(registration.eventHandle)
        assertEquals(emptyList(), registration.legend.types)
    }

    @Test
    fun `the range registration carries no event handle at all`() {
        // Three arguments, not the whole-document provider's four.
        val registration = SemanticTokens.rangeRegistration("""[8,[],{"tokenTypes":["class"],"tokenModifiers":[]}]""")
        assertEquals(8, registration.handle)
        assertNull(registration.eventHandle)
        assertEquals(listOf("class"), registration.legend.types)
    }

    @Test
    fun `reads the handle a change event names`() {
        assertEquals(12, SemanticTokens.emittedEventHandle("[12]"))
        assertNull(SemanticTokens.emittedEventHandle("[]"))
        assertNull(SemanticTokens.emittedEventHandle("not json"))
    }

    @Test
    fun `builds the request without a cancellation token, asking for the whole file`() {
        val resource = UriComponents.of(Path.of("C:\\Projects\\example.rs"))
        val args = SemanticTokens.provide(3, resource)
        assertTrue(args.startsWith("""[3,{"scheme":"file","""), args)
        // Trailing 0 is `previousResultId`: "I am holding nothing of yours", so answer in full.
        // The host appends its own token; sending one here would shift every argument after it.
        assertTrue(args.endsWith(",0]"), args)
    }

    @Test
    fun `builds the range request with an editor range`() {
        val resource = UriComponents.of(Path.of("C:\\Projects\\example.rs"))
        val args = SemanticTokens.provideRange(3, resource, Range(1, 1, 40, 1))
        assertTrue(args.endsWith("""{"startLineNumber":1,"startColumn":1,"endLineNumber":40,"endColumn":1}]"""), args)
    }

    @Test
    fun `builds the release request the host waits for`() {
        assertEquals("[3,17]", SemanticTokens.release(3, 17))
    }

    @Test
    fun `a new line moves every token below it`() {
        // The case that looked worst: drawn where they were, every line takes the colours of the one
        // that used to be there until the next answer arrives.
        val tokens = listOf(token(0, 4, 1), token(1, 0, 3), token(1, 4, 1))
        val moved = SemanticTokens.rebase(tokens, "let a = b\nfoo(a)", "\nlet a = b\nfoo(a)")
        assertEquals(listOf(Triple(1, 4, 1), Triple(2, 0, 3), Triple(2, 4, 1)), moved.map(::spans))
    }

    @Test
    fun `typing before a token on its line moves it along`() {
        val moved = SemanticTokens.rebase(listOf(token(0, 0, 3), token(0, 4, 3)), "foo bar", "foo  bar")
        assertEquals(listOf(Triple(0, 0, 3), Triple(0, 5, 3)), moved.map(::spans))
    }

    @Test
    fun `typing at the end of a word keeps its colour`() {
        val moved = SemanticTokens.rebase(listOf(token(0, 0, 3), token(0, 4, 3)), "foo bar", "fooo bar")
        assertEquals(listOf(Triple(0, 0, 4), Triple(0, 5, 3)), moved.map(::spans))
    }

    @Test
    fun `typing inside a word and deleting from its end keep its colour`() {
        assertEquals(Triple(0, 0, 4), SemanticTokens.rebase(listOf(token(0, 0, 3)), "foo", "fXoo").single().let(::spans))
        assertEquals(Triple(0, 0, 2), SemanticTokens.rebase(listOf(token(0, 0, 3)), "foo", "fo").single().let(::spans))
    }

    @Test
    fun `typing just before a word does not grow it`() {
        // The same position is the end of the word before, which is the one being typed.
        val moved = SemanticTokens.rebase(listOf(token(0, 0, 3), token(0, 4, 3)), "foo bar", "foo xbar")
        assertEquals(listOf(Triple(0, 0, 3), Triple(0, 5, 3)), moved.map(::spans))
    }

    @Test
    fun `joining two lines moves the lower one onto the upper`() {
        val moved = SemanticTokens.rebase(listOf(token(1, 0, 3), token(1, 4, 3)), "a\nfoo bar", "afoo bar")
        assertEquals(listOf(Triple(0, 1, 3), Triple(0, 5, 3)), moved.map(::spans))
    }

    @Test
    fun `a token an edit cuts through, or deletes, is dropped rather than guessed at`() {
        assertEquals(emptyList(), SemanticTokens.rebase(listOf(token(0, 0, 3), token(0, 4, 3)), "foo bar", "foX ar"))
        assertEquals(emptyList(), SemanticTokens.rebase(listOf(token(0, 0, 3)), "foo;", ";"))
    }

    @Test
    fun `an unchanged text leaves the tokens alone`() {
        val tokens = listOf(token(0, 0, 3))
        assertSame(tokens, SemanticTokens.rebase(tokens, "foo", "foo"))
    }

    private fun token(line: Int, character: Int, length: Int) =
        SemanticTokens.Token(line, character, length, "variable", emptySet())

    private fun spans(token: SemanticTokens.Token) = Triple(token.line, token.character, token.length)

    /** A full-encoding reply: result id, `EncodedSemanticTokensType.Full`, length, then the data. */
    private fun dto(resultId: Int, vararg data: Int): ByteArray =
        dtoRaw(resultId, 1, data.size, *data)

    /** The same with every header word spelled out, for the malformed cases. */
    private fun dtoRaw(vararg words: Int): ByteArray {
        val buffer = ByteBuffer.allocate(words.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        words.forEach(buffer::putInt)
        return buffer.array()
    }
}
