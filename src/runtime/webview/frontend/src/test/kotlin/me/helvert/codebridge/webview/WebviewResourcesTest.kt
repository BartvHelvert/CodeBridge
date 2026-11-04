package me.helvert.codebridge.webview

import kotlinx.coroutines.runBlocking
import me.helvert.codebridge.rpc.WebviewResourceDto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pages' files, sent by the backend once for as long as they do not change. */
class WebviewResourcesTest {

    /** A backend with one file per address, answering without bytes when asked with the version it is at. */
    private class Backend {
        val files = mutableMapOf<String, Pair<String, String>>()
        val asked = mutableListOf<String?>()

        fun fetch(url: String): suspend (String?) -> WebviewResourceDto? = { cachedVersion ->
            asked += cachedVersion
            files[url]?.let { (text, version) ->
                WebviewResourceDto(text.encodeToByteArray().takeUnless { version == cachedVersion }, "text/javascript", version)
            }
        }
    }

    @Test
    fun `a file read again is not sent again until it changes`() = runBlocking {
        val backend = Backend()
        val resources = WebviewResources(capacity = 1024)
        backend.files["a"] = "one" to "1"
        assertEquals("one", resources.read("a", backend.fetch("a"))?.bytes?.decodeToString())
        assertEquals("one", resources.read("a", backend.fetch("a"))?.bytes?.decodeToString())
        backend.files["a"] = "two" to "2"
        assertEquals("two", resources.read("a", backend.fetch("a"))?.bytes?.decodeToString())
        assertEquals(listOf(null, "1", "1"), backend.asked)
    }

    @Test
    fun `a file the backend refuses is forgotten`() = runBlocking {
        val backend = Backend()
        val resources = WebviewResources(capacity = 1024)
        backend.files["a"] = "one" to "1"
        resources.read("a", backend.fetch("a"))
        backend.files.remove("a")
        assertNull(resources.read("a", backend.fetch("a")))
        assertEquals(0, resources.keptBytes)
    }

    @Test
    fun `the files read longest ago go first, and one larger than everything is not kept`() = runBlocking {
        val backend = Backend()
        val resources = WebviewResources(capacity = 10)
        backend.files["a"] = "aaaa" to "1"
        backend.files["b"] = "bbbb" to "1"
        backend.files["c"] = "cccc" to "1"
        backend.files["huge"] = "h".repeat(11) to "1"
        resources.read("a", backend.fetch("a"))
        resources.read("b", backend.fetch("b"))
        resources.read("a", backend.fetch("a"))
        resources.read("c", backend.fetch("c"))
        assertEquals(8, resources.keptBytes)
        backend.asked.clear()
        resources.read("a", backend.fetch("a"))
        resources.read("b", backend.fetch("b"))
        assertEquals(listOf("1", null), backend.asked)

        val huge = resources.read("huge", backend.fetch("huge"))
        assertContentEquals("h".repeat(11).encodeToByteArray(), huge?.bytes)
        assertEquals(8, resources.keptBytes)
    }
}
