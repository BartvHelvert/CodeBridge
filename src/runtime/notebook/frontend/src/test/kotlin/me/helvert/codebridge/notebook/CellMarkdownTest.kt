package me.helvert.codebridge.notebook

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CellMarkdownTest {
    @Test
    fun `renders GitHub-flavoured markdown`() {
        val html = CellMarkdown.toHtml(
            """
            # Learning Java Basics

            Some **bold** text and `code`.

            | Type | Size |
            |------|------|
            | byte | 1    |
            """.trimIndent()
        )
        assertContains(html, "<h1>Learning Java Basics</h1>")
        assertContains(html, "<strong>bold</strong>")
        assertContains(html, "<code>code</code>")
        assertContains(html, """<table cellspacing="0">""")
        assertContains(html, "<td>byte</td>")
        assertFalse(html.startsWith("<body>"))
    }

    @Test
    fun `an empty cell says how to edit it`() {
        assertContains(CellMarkdown.toHtml("  \n"), "Double-click or press Enter to edit")
    }

    @Test
    fun `a cell that renders nothing visible says how to edit it`() {
        for (markdown in listOf("#", "- ", "<!-- a note -->", "&nbsp;")) {
            assertContains(CellMarkdown.toHtml(markdown), "Double-click or press Enter to edit", message = markdown)
        }
    }

    @Test
    fun `a rule or an image is shown`() {
        for (markdown in listOf("---", "![](a.png)")) {
            assertFalse("Double-click" in CellMarkdown.toHtml(markdown), markdown)
        }
    }
}
