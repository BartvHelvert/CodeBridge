package me.helvert.codebridge.notebook

import kotlin.test.Test
import kotlin.test.assertEquals

class NotebookOutlineTest {
    private fun markdown(handle: Int, text: String) = NotebookOutline.Cell(handle, CellKinds.MARKUP, text)
    private fun code(handle: Int, text: String) = NotebookOutline.Cell(handle, CellKinds.CODE, text)

    /** Each entry as `text@handle:index:line`, indented by depth. */
    private fun render(entries: List<NotebookOutline.Entry>, depth: Int = 0): List<String> = entries.flatMap { entry ->
        listOf("  ".repeat(depth) + "${entry.text}@${entry.handle}:${entry.index}:${entry.line}") + render(entry.children, depth + 1)
    }

    @Test
    fun `nests headings by level and code cells under the heading before them`() {
        val outline = NotebookOutline.of(
            listOf(
                markdown(1, "# Intro\n\nSome text.\n\n## Setup"),
                code(2, "\nimport os\nprint(os.name)"),
                markdown(3, "## Results\n### Detail"),
                code(4, "x = 1"),
                markdown(5, "# Next"),
            )
        )
        assertEquals(
            listOf(
                "Intro@1:0:0",
                "  Setup@1:1:4",
                "    import os@2:0:1",
                "  Results@3:0:0",
                "    Detail@3:1:1",
                "      x = 1@4:0:0",
                "Next@5:0:0",
            ),
            render(outline),
        )
    }

    @Test
    fun `reads headings as the plain text they show`() {
        val outline = NotebookOutline.of(
            listOf(markdown(1, "Title with **bold** and `code`\n===\n\n```\n# not a heading\n```\n\nA [link](https://x.org) &amp; more\n---"))
        )
        assertEquals(listOf("Title with bold and code@1:0:0", "  A link & more@1:1:7"), render(outline))
    }

    @Test
    fun `code cells before any heading are at the top, and an empty one says so`() {
        val outline = NotebookOutline.of(listOf(code(1, "  "), markdown(2, "no heading here"), code(3, "y = 2")))
        assertEquals(listOf("Empty code cell@1:0:0", "y = 2@3:0:0"), render(outline))
    }
}
