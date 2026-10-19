package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.*

/** `MainThreadDiaglogs` calls as the host sends them, and the replies it revives. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class DialogsTest {

    @Test
    fun `reads open dialog options`() {
        val open = Dialogs.open(
            $$"""[{"title":"Pick a crate","openLabel":"Use","canSelectFolders":true,"canSelectFiles":false,
            "canSelectMany":true,"defaultUri":{"$mid":1,"scheme":"file","path":"/w"},
            "filters":{"Rust":["rs",".toml"],"All":["*"]}}]"""
        )
        assertEquals("Pick a crate", open.title)
        assertEquals("Use", open.label)
        assertFalse(open.files)
        assertTrue(open.folders)
        assertTrue(open.many)
        assertEquals("/w", open.defaultUri?.path)
        assertEquals(
            listOf(Dialogs.Filter("Rust", listOf("rs", "toml")), Dialogs.Filter("All", listOf("*"))),
            open.filters
        )
    }

    @Test
    fun `an open dialog with no options chooses one file`() {
        val open = Dialogs.open("[null]")
        assertTrue(open.files)
        assertFalse(open.folders)
        assertFalse(open.many)
        assertNull(open.defaultUri)
        // Neither files nor folders is files, as in VS Code.
        assertTrue(Dialogs.open("""[{"canSelectFiles":false}]""").files)
    }

    @Test
    fun `reads save dialog options`() {
        val save =
            Dialogs.save("""[{"saveLabel":"Export","defaultUri":{"scheme":"file","path":"/w/out.json"},"filters":{"JSON":["json"]}}]""")
        assertEquals("Export", save.label)
        assertEquals("/w/out.json", save.defaultUri?.path)
        assertEquals(listOf(Dialogs.Filter("JSON", listOf("json"))), save.filters)
    }

    @Test
    fun `cancelling answers undefined`() {
        assertEquals("null", Dialogs.opened(null))
        assertEquals("null", Dialogs.opened(emptyList()))
        assertEquals("null", Dialogs.saved(null))
        val file = UriComponents("file", "", "/w/a.rs", "", "")
        assertEquals("[${file.toJson()}]", Dialogs.opened(listOf(file)))
        assertEquals(file.toJson().toString(), Dialogs.saved(file))
    }
}
