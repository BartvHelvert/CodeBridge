package me.helvert.codebridge.notebook

import com.intellij.openapi.util.text.StringUtil
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.accept
import org.intellij.markdown.ast.visitors.RecursiveVisitor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * A notebook's outline, as VS Code's Outline view draws one with its code cells shown: the headings
 * of its markdown cells, nested by level, and each code cell under the heading before it, named by
 * its first line.
 */
internal object NotebookOutline {

    /** A cell as the outline is drawn from it. */
    data class Cell(val handle: Int, val kind: Int, val text: String)

    /**
     * A heading or a code cell: the cell it is in, which of that cell's entries it is, and the line
     * of the cell it starts on.
     */
    data class Entry(val handle: Int, val index: Int, val line: Int, val code: Boolean, val text: String, val children: List<Entry>)

    fun of(cells: List<Cell>): List<Entry> {
        val top = mutableListOf<Node>()
        // The headings the next entry may go under, outermost first.
        val open = ArrayDeque<Node>()
        fun add(node: Node) = (open.lastOrNull()?.children ?: top).add(node)
        for (cell in cells) {
            if (cell.kind == CellKinds.CODE) {
                val lines = cell.text.lines()
                val line = lines.indexOfFirst { it.isNotBlank() }
                add(Node(cell.handle, 0, line.coerceAtLeast(0), code = true, lines.getOrNull(line)?.trim() ?: EMPTY_CODE, CODE_LEVEL))
            } else for (heading in headings(cell)) {
                while (open.isNotEmpty() && open.last().level >= heading.level) open.removeLast()
                add(heading)
                open.addLast(heading)
            }
        }
        return top.map(Node::entry)
    }

    /** A markdown cell's headings, `#` and underlined alike, as plain text. */
    private fun headings(cell: Cell): List<Node> {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(cell.text as CharSequence)
        val found = mutableListOf<Node>()
        tree.accept(object : RecursiveVisitor() {
            override fun visitNode(node: ASTNode) {
                val level = LEVELS[node.type]
                if (level == null) return super.visitNode(node)
                val text = plain(HtmlGenerator(cell.text, node, flavour).generateHtml())
                if (text.isNotEmpty()) {
                    val line = cell.text.subSequence(0, node.startOffset).count { it == '\n' }
                    found += Node(cell.handle, found.size, line, code = false, text, level)
                }
            }
        })
        return found
    }

    /** [html] as the text it shows. */
    private fun plain(html: String): String =
        StringUtil.unescapeXmlEntities(html.replace(TAG, "")).replace(WHITESPACE, " ").trim()

    private class Node(val handle: Int, val index: Int, val line: Int, val code: Boolean, val text: String, val level: Int) {
        val children = mutableListOf<Node>()
        fun entry(): Entry = Entry(handle, index, line, code, text, children.map(Node::entry))
    }

    private val LEVELS = mapOf(
        MarkdownElementTypes.ATX_1 to 1, MarkdownElementTypes.SETEXT_1 to 1,
        MarkdownElementTypes.ATX_2 to 2, MarkdownElementTypes.SETEXT_2 to 2,
        MarkdownElementTypes.ATX_3 to 3,
        MarkdownElementTypes.ATX_4 to 4,
        MarkdownElementTypes.ATX_5 to 5,
        MarkdownElementTypes.ATX_6 to 6,
    )

    /** Deeper than any heading: a code cell has nothing under it. */
    private const val CODE_LEVEL = 7
    private const val EMPTY_CODE = "Empty code cell"
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
}
