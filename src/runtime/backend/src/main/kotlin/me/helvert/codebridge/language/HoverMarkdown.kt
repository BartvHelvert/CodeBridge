package me.helvert.codebridge.language

import com.intellij.lang.documentation.QuickDocHighlightingHelper
import com.intellij.lang.documentation.QuickDocHighlightingHelper.appendStyledFragment
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI

/**
 * The markdown of a hover as HTML for the documentation pane.
 *
 * GitHub-flavoured, as the workbench renders it, with fenced code highlighted the way the IDE
 * highlights code in its own documentation -- in whatever language the fence names, where the IDE
 * or an installed extension knows it, and plain otherwise.
 */
internal object HoverMarkdown {

    fun toHtml(project: Project, markdown: String): String {
        val flavour = Flavour(project)
        val tree = MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(markdown as CharSequence)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
            .removeSurrounding("<body>", "</body>")
            .replace("""href="command:""", """href="$INTERNAL_LINK_PREFIX""" + "command:")
    }

    /**
     * What a link must start with for a click on it to reach the backend.
     *
     * A split-mode frontend forwards only `psi_element://` links, with the prefix taken off; it
     * hands any other link straight to the browser. A monolith passes the link on as it is, prefix
     * included, so [CodeBridgeHoverLinkHandler] accepts either.
     */
    const val INTERNAL_LINK_PREFIX = "psi_element://"

    /**
     * [code] as a documentation code block, coloured by [highlighter].
     *
     * The same block [QuickDocHighlightingHelper.getStyledCodeBlock] makes, for code it cannot
     * colour itself: it highlights by `Language`, and a language an extension contributes has none
     * -- the editor colours those files by file type, through TextMate.
     */
    fun styledCodeBlock(highlighter: EditorHighlighter, code: String): String {
        highlighter.setText(code)
        val html = StringBuilder(QuickDocHighlightingHelper.CODE_BLOCK_PREFIX)
        val tokens = highlighter.createIterator(0)
        var column = 0
        while (!tokens.atEnd()) {
            val token = StringBuilder()
            for (char in code.substring(tokens.start, tokens.end)) {
                column = encode(char, column, token)
            }
            html.appendStyledFragment(token.toString(), tokens.textAttributes)
            tokens.advance()
        }
        return html.append(QuickDocHighlightingHelper.CODE_BLOCK_SUFFIX).toString()
    }

    /**
     * Appends [char] as the platform's own code blocks spell it, returning the column after it.
     *
     * The documentation pane collapses whitespace even inside `<pre>`, so line breaks and spaces
     * are written as markup -- as `HtmlSyntaxInfoReader` does, tabs included.
     */
    private fun encode(char: Char, column: Int, html: StringBuilder): Int = when (char) {
        '\n' -> {
            html.append("<br>"); 0
        }

        '\r' -> column
        ' ' -> {
            html.append("&#32;"); column + 1
        }

        '\t' -> {
            val next = (column / TAB_SIZE + 1) * TAB_SIZE
            repeat(next - column) { html.append("&#32;") }
            next
        }

        '<' -> {
            html.append("&lt;"); column + 1
        }

        '>' -> {
            html.append("&gt;"); column + 1
        }

        '&' -> {
            html.append("&amp;"); column + 1
        }

        else -> {
            html.append(char); column + 1
        }
    }

    private const val TAB_SIZE = 4

    private fun codeBlock(project: Project, fenceLanguage: String?, code: String): String {
        if (fenceLanguage == null) return QuickDocHighlightingHelper.getStyledCodeBlock(project, null, code)
        // The IDE's own language first: it reads the code better than a grammar does.
        QuickDocHighlightingHelper.guessLanguage(fenceLanguage)?.let {
            return QuickDocHighlightingHelper.getStyledCodeBlock(project, it, code)
        }
        val highlighter = highlighterFor(project, fenceLanguage)
            ?: return QuickDocHighlightingHelper.getStyledCodeBlock(project, null, code)
        return styledCodeBlock(highlighter, code)
    }

    /**
     * The editor's highlighter for a file of the contributed language [fenceLanguage], if any.
     *
     * The type comes from [CodeBridgeLanguageFileTypes] as it does for a file opened in the editor, which
     * is also what gets TextMate's highlighter registered for it.
     */
    private fun highlighterFor(project: Project, fenceLanguage: String): EditorHighlighter? {
        val fileTypes = CodeBridgeLanguageFileTypes.getInstance()
        val name = fileTypes.nameFor(fenceLanguage) ?: return null
        val type = fileTypes.forName(name) ?: FileTypeManager.getInstance().getFileTypeByFileName(name)
        if (type === UnknownFileType.INSTANCE || type === PlainTextFileType.INSTANCE) return null
        val file = LightVirtualFile(name, type, "")
        return EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(file, EditorColorsManager.getInstance().globalScheme, project)
    }

    private class Flavour(private val project: Project) : GFMFlavourDescriptor() {
        override fun createHtmlGeneratingProviders(
            linkMap: LinkMap,
            baseURI: URI?
        ): Map<IElementType, GeneratingProvider> =
            super.createHtmlGeneratingProviders(linkMap, baseURI) + (MarkdownElementTypes.CODE_FENCE to CodeFence(
                project
            ))
    }

    private class CodeFence(private val project: Project) : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            val children = node.children
            val fenceLanguage = children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                ?.getTextInNode(text)?.trim()?.split(' ')?.first()?.takeIf { it.isNotEmpty() }
            // The opening line ends at the first line break; the closing fence may be missing.
            val start = children.indexOfFirst { it.type == MarkdownTokenTypes.EOL } + 1
            val end = children.indexOfLast { it.type == MarkdownTokenTypes.CODE_FENCE_END }.takeIf { it >= 0 }
                ?: children.size
            val code = if (start in 1..end) {
                children.subList(start, end).joinToString("") { it.getTextInNode(text) }.removeSuffix("\n")
            } else ""
            visitor.consumeHtml(ReadAction.computeBlocking<String, Throwable> {
                codeBlock(
                    project,
                    fenceLanguage,
                    code
                )
            })
        }
    }
}
