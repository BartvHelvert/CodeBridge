package me.helvert.codebridge.exthost.protocol.payload

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call and type hierarchy requests, and the items and calls that come back. */
class HierarchiesTest {

    private val uri = UriComponents.of(Path.of("source.rs").toAbsolutePath())
    private val range = Range(2, 1, 4, 2)
    private val name = Range(2, 4, 2, 8)

    private fun item(id: String, extra: String = "") =
        """{"_sessionId":"s1","_itemId":"$id","name":"area","detail":"fn()","kind":11,"uri":${uri.toJson()},"range":${range.toJson()},"selectionRange":${name.toJson()}$extra}"""

    @Test
    fun `registrations of either kind are read as a selector`() {
        for (kind in Hierarchies.Kind.entries) {
            val registration = Hierarchies.registration(kind, """[4,[{"language":"rust"}]]""")
            assertEquals(4, registration.handle, kind.name)
            assertTrue(registration.selector.toString().contains("rust"), kind.name)
        }
    }

    @Test
    fun `items keep their session and id, which is how the host finds them again`() {
        val items = Hierarchies.items("[${item("0")},${item("1", ""","tags":[1]""")}]")

        assertEquals(listOf("0", "1"), items.map { it.itemId })
        val first = items.first()
        assertEquals("s1", first.sessionId)
        assertEquals("area", first.name)
        assertEquals("fn()", first.detail)
        assertEquals(11, first.kind)
        assertEquals(uri, first.resource)
        assertEquals(range, first.range)
        assertEquals(name, first.selectionRange)
        assertTrue(items.last().deprecated)
        assertEquals("""[3,"s1","0"]""", Hierarchies.ask(3, first))
        assertEquals("""[3,"s1"]""", Hierarchies.release(3, "s1"))
    }

    @Test
    fun `nothing, or something malformed, is no items`() {
        assertTrue(Hierarchies.items(null).isEmpty())
        assertTrue(Hierarchies.items("null").isEmpty())
        assertTrue(Hierarchies.items("""[{"name":"no session"},7]""").isEmpty())
    }

    @Test
    fun `a blank detail is none, and a missing name range is the whole symbol`() {
        val item = Hierarchies.items("""[{"_sessionId":"s","_itemId":"0","name":"x","detail":"","kind":4,"uri":${uri.toJson()},"range":${range.toJson()}}]""").single()

        assertNull(item.detail)
        assertEquals(range, item.selectionRange)
    }

    @Test
    fun `incoming calls name the caller and outgoing calls the callee`() {
        val site = Range(3, 5, 3, 9)
        val incoming = Hierarchies.incomingCalls("""[{"from":${item("2")},"fromRanges":[${site.toJson()}]}]""").single()
        val outgoing = Hierarchies.outgoingCalls("""[{"to":${item("3")},"fromRanges":[${site.toJson()},${site.toJson()}]}]""").single()

        assertEquals("2", incoming.item.itemId)
        assertEquals(listOf(site), incoming.ranges)
        assertEquals("3", outgoing.item.itemId)
        assertEquals(2, outgoing.ranges.size)
        assertTrue(Hierarchies.incomingCalls("""[{"to":${item("4")},"fromRanges":[]}]""").isEmpty())
    }
}
