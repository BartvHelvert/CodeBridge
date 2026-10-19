package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A view's welcome content as extensions write it. */
class ViewsWelcomeTest {

    @Test
    fun `a line that is one link is a button, other links are inline`() {
        val lines = ViewsWelcome.lines(
            """
            No crates found. Read [the docs](https://example.com/docs) to set one up.

            [Open Cargo.toml](command:rust.open?%5B%22Cargo.toml%22%5D)
        """.trimIndent()
        )
        assertEquals(2, lines.size)
        val (text, button) = lines
        assertEquals(false, text.button)
        assertEquals(
            listOf(
                ViewsWelcome.Part("No crates found. Read "),
                ViewsWelcome.Part("the docs", "https://example.com/docs"),
                ViewsWelcome.Part(" to set one up."),
            ), text.parts
        )
        assertTrue(button.button)
        assertEquals(
            ViewsWelcome.Part("Open Cargo.toml", "command:rust.open?%5B%22Cargo.toml%22%5D"),
            button.parts.single()
        )
    }

    @Test
    fun `a command link names its command and arguments`() {
        val (id, arguments) = ViewsWelcome.command("command:rust.open?%5B%22Cargo.toml%22%5D")!!
        assertEquals("rust.open", id)
        assertEquals("""["Cargo.toml"]""", arguments.toString())
        assertEquals("one" to "[]", ViewsWelcome.command("command:one")!!.let { it.first to it.second.toString() })
        assertEquals("""[{"a":1}]""", ViewsWelcome.command("command:x?%7B%22a%22%3A1%7D")!!.second.toString())
        assertNull(ViewsWelcome.command("https://example.com"))
    }

    @Test
    fun `only the entries for the view are read, with their when clauses`() {
        val manifest = Json.parseToJsonElement(
            """{"contributes":{"viewsWelcome":[
            {"view":"deps","contents":"Nothing yet.","when":"!inRustProject"},
            {"view":"other","contents":"Elsewhere."}
        ]}}"""
        ) as JsonObject
        val entries = ViewsWelcome.of(manifest, "deps")
        assertEquals(1, entries.size)
        assertEquals("!inRustProject", entries.single().whenClause)
        assertEquals("Nothing yet.", entries.single().lines.single().parts.single().text)
    }
}
