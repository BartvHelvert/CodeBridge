package me.helvert.codebridge.ide

import com.intellij.lexer.EmptyLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import me.helvert.codebridge.language.HoverMarkdown
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** A hover's markdown becoming the HTML Quick Documentation shows. */
class HoverMarkdownTest : EditorFixture() {

    @Test
    fun `renders the markdown rather than showing it`() = onEdt {
        val html = render(
            """
            ```rust
            pub struct Box<T>
            ```

            ---

            A pointer type for `T`. See the [docs](https://doc.rust-lang.org/std/boxed/index.html).
            """.trimIndent(),
        )
        assertFalse(html.contains("```"), html)
        assertContains(html, "<hr")
        assertContains(html, "<code>T</code>")
        assertContains(html, """<a href="https://doc.rust-lang.org/std/boxed/index.html">docs</a>""")
    }

    @Test
    fun `keeps the code of a fence in a language the IDE does not know`() = onEdt {
        val html = render("```nosuchlanguage\nlet a = 1;\nlet b = 2;\n```")
        assertContains(html, "let a = 1;")
        assertContains(html, "let b = 2;")
        assertFalse(html.contains("nosuchlanguage"), html)
    }

    @Test
    fun `leaves a command link for the link handler`() = onEdt {
        // Behind the prefix a split-mode frontend forwards, and not swapped out by the safe-link filter.
        val html = render("[117 implementations](command:rust-analyzer.hoverRefCommandProxy?0 'Go to implementations')")
        assertContains(html, """href="psi_element://command:rust-analyzer.hoverRefCommandProxy?0"""")
        assertContains(html, ">117 implementations</a>")
    }

    @Test
    fun `escapes code in a fence`() = onEdt {
        assertContains(render("```\nVec<T>\n```"), "Vec&lt;T&gt;")
    }

    @Test
    fun `colours code by an editor highlighter`() = onEdt {
        // What a contributed language's code gets: no `Language`, only the highlighter TextMate
        // gives its files. A highlighter calling everything a keyword stands in for it.
        val scheme = EditorColorsManager.getInstance().globalScheme
        val keyword = object : SyntaxHighlighterBase() {
            override fun getHighlightingLexer() = EmptyLexer()
            override fun getTokenHighlights(tokenType: IElementType) = arrayOf(DefaultLanguageHighlighterColors.KEYWORD)
        }
        val html = HoverMarkdown.styledCodeBlock(LexerEditorHighlighter(keyword, scheme), "Vec<T>")
        assertContains(html, "Vec&lt;T&gt;")
        assertFalse(html.contains("&amp;"), html)
        assertContains(html, "color:")
    }

    @Test
    fun `keeps the lines and indentation of highlighted code`() = onEdt {
        // The pane collapses whitespace even in `<pre>`; a where clause folded onto one line is
        // what that looked like.
        val scheme = EditorColorsManager.getInstance().globalScheme
        val plain = object : SyntaxHighlighterBase() {
            override fun getHighlightingLexer() = EmptyLexer()
            override fun getTokenHighlights(tokenType: IElementType) = emptyArray<TextAttributesKey>()
        }
        val html = HoverMarkdown.styledCodeBlock(LexerEditorHighlighter(plain, scheme), "where\n    T: ?Sized,\n\tA")
        assertContains(html, "where<br>&#32;&#32;&#32;&#32;T:&#32;?Sized,<br>&#32;&#32;&#32;&#32;A")
    }

    private fun render(markdown: String) = HoverMarkdown.toHtml(fixture.project, markdown)
}
