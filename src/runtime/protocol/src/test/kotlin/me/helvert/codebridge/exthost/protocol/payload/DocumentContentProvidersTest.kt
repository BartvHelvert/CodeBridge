package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `MainThreadDocumentContentProviders`, and the URIs its documents are opened by. */
class DocumentContentProvidersTest {

    @Test
    fun `reads a registration`() {
        assertEquals(
            DocumentContentProviders.Registration(3, "rust-analyzer-diagnostics-view"),
            DocumentContentProviders.registration("""[3,"rust-analyzer-diagnostics-view"]""")
        )
        assertEquals(3, DocumentContentProviders.unregistered("[3]"))
    }

    @Test
    fun `a uri survives being written into a link and read back`() {
        // The tooltip link carries the URI as text; the provider reads the query and the fragment,
        // and rust-analyzer's fragment is a file URI with an escape of its own in it.
        val original = UriComponents(
            "rust-analyzer-diagnostics-view", "", "/diagnostic message [0]", "0",
            "file:///c%3A/x/src/main.rs"
        )
        assertEquals(original, DocumentContentProviders.resource(original.uri))
    }

    @Test
    fun `not a uri is null`() {
        assertNull(DocumentContentProviders.resource("no scheme here"))
    }

    @Test
    fun `reads the provided text, and nothing as nothing`() {
        assertEquals("error: x\n", DocumentContentProviders.content("\"error: x\\n\""))
        assertNull(DocumentContentProviders.content("null"))
        assertNull(DocumentContentProviders.content(null))
    }
}
