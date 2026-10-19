package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarkdownTest {

    @Test
    fun `keeps the label of a command link`() {
        // As rust-analyzer writes them, with a title after the URI.
        val text = "[117 implementations](command:rust-analyzer.hoverRefCommandProxy?0 'Go to implementations') | " +
                "Go to [Allocator](command:rust-analyzer.hoverRefCommandProxy?1 'core::alloc::Allocator')"
        assertEquals("117 implementations | Go to Allocator", Markdown.withoutCommandLinks(text))
    }

    @Test
    fun `leaves other links alone`() {
        val text =
            "See the [module-level documentation](https://doc.rust-lang.org/stable/std/boxed/index.html) for more."
        assertEquals(text, Markdown.withoutCommandLinks(text))
    }

    @Test
    fun `keeps escaped brackets in a label`() {
        assertEquals("""a \[b\]""", Markdown.withoutCommandLinks("""[a \[b\]](command:run)"""))
    }

    @Test
    fun `keeps command links only where the string is trusted`() {
        val link = "[run](command:ext.run?0)"
        assertEquals(link, Markdown.trustedText(markdown(link, "true")))
        assertEquals("run", Markdown.trustedText(markdown(link, "false")))
        assertEquals("run", Markdown.trustedText(markdown(link, null)))
        assertEquals("run", Markdown.trustedText(JsonPrimitive(link)))
    }

    @Test
    fun `keeps only the commands a trusted string enables`() {
        val text = "[a](command:ext.allowed) [b](command:ext.other)"
        val trust = """{"enabledCommands":["ext.allowed"]}"""
        assertEquals("[a](command:ext.allowed) b", Markdown.trustedText(markdown(text, trust)))
    }

    @Test
    fun `reads a lone argument as a list of one`() {
        val command = assertNotNull(Markdown.command("command:rust-analyzer.hoverRefCommandProxy?0"))
        assertEquals("rust-analyzer.hoverRefCommandProxy", command.id)
        assertEquals(Json.parseToJsonElement("[0]"), command.arguments)
    }

    @Test
    fun `reads encoded JSON arguments`() {
        val command = assertNotNull(Markdown.command("command:ext.open?%5B%22a+b%22%2C1%5D"))
        assertEquals(Json.parseToJsonElement("""["a+b",1]"""), command.arguments)
    }

    @Test
    fun `reads a command without arguments`() {
        assertEquals(JsonArray(emptyList()), assertNotNull(Markdown.command("command:ext.run")).arguments)
        assertNull(Markdown.command("https://example.com"))
        assertNull(Markdown.command("command:"))
    }

    private fun markdown(value: String, trust: String?) = Json.parseToJsonElement(
        """{"value":${JsonPrimitive(value)}${trust?.let { ""","isTrusted":$it""" }.orEmpty()}}""",
    )
}
