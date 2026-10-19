package me.helvert.codebridge.exthost.protocol.payload

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Folding ranges, in the shapes the host sends. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class FoldingRangesTest {

    private val resource = UriComponents.of(Path.of("C:\\Projects\\main.rs"))

    @Test
    fun `reads a registration with and without an event handle, and builds the request`() {
        val registration = FoldingRanges.registration(
            $$"""[7,[{"$serialized":true,"language":"rust","scheme":"file"}],{"value":"rust-lang.rust-analyzer"},8]""",
        )
        assertEquals(7, registration.handle)
        assertEquals(8, registration.eventHandle)
        assertTrue(registration.selector.toString().contains("rust"))
        assertNull(FoldingRanges.registration("""[7,[{"language":"rust"}],{"value":"x"}]""").eventHandle)
        assertEquals(8, FoldingRanges.emittedEventHandle("[8]"))

        val request = FoldingRanges.provide(7, resource)
        assertTrue(request.startsWith("""[7,{"scheme":"file","""))
        assertTrue(request.endsWith(",{}]"), "An empty folding context, as VS Code's folding model passes")
    }

    @Test
    fun `reads a reply with kinds, and nothing as no ranges`() {
        val ranges = FoldingRanges.ranges(
            """[{"start":1,"end":4},{"start":2,"end":3,"kind":{"value":"comment"}},{"start":6,"end":9,"kind":{"value":"imports"}},{"end":2}]""",
        )
        assertEquals(
            listOf(
                FoldingRanges.Range(1, 4),
                FoldingRanges.Range(2, 3, FoldingRanges.KIND_COMMENT),
                FoldingRanges.Range(6, 9, FoldingRanges.KIND_IMPORTS),
            ),
            ranges,
        )
        assertEquals(emptyList(), FoldingRanges.ranges(""))
        assertEquals(emptyList(), FoldingRanges.ranges(null))
    }
}
