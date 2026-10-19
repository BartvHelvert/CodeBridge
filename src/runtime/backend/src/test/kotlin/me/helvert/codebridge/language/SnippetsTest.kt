package me.helvert.codebridge.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a VS Code snippet becomes once IntelliJ has to insert it as plain text.
 *
 * Servers send these constantly -- a call with its arguments as tab stops, a tag with its closing
 * half -- and the failure mode is not subtle: get it wrong and `${1:name}` lands in the user's file.
 * Pure string handling, so it is worth pinning every form the grammar allows rather than the two
 * that happen to appear in the fixture.
 */
class SnippetsTest {

    @Test
    fun `leaves ordinary text alone`() {
        assertEquals("println", Snippets.plain("println"))
    }

    @Test
    fun `drops bare tab stops`() {
        assertEquals("print()", Snippets.plain("print($1)$0"))
    }

    @Test
    fun `keeps a placeholder's default text`() {
        assertEquals("print(value)", Snippets.plain($$"print(${1:value})"))
    }

    @Test
    fun `keeps nested placeholder text`() {
        assertEquals("for (item in items)", Snippets.plain($$"for (${1:item} in ${2:${3:items}})"))
    }

    @Test
    fun `takes the first choice, which is the one VS Code preselects`() {
        assertEquals("public class", Snippets.plain($$"${1|public,private,protected|} class"))
    }

    @Test
    fun `a transform contributes nothing, having no variable to transform`() {
        assertEquals("name: ", Snippets.plain($$"name: ${TM_FILENAME/(.*)\\..+$/$1/}"))
    }

    @Test
    fun `drops named variables`() {
        assertEquals("// ", Snippets.plain($$"// $TM_FILENAME"))
    }

    @Test
    fun `an escaped dollar is a dollar`() {
        assertEquals($$"$name and }", Snippets.plain($$"\\$name and \\}"))
    }

    @Test
    fun `an unclosed placeholder stays literal rather than swallowing the rest`() {
        // Malformed input from an extension must not silently delete the text after it.
        assertEquals($$"${1:oops", Snippets.plain($$"${1:oops"))
    }

    @Test
    fun `an empty placeholder leaves nothing behind`() {
        assertEquals("()", Snippets.plain($$"(${1})"))
    }

    @Test
    fun `the final tab stop is where the template ends`() {
        val expansion = Snippets.expand("println($0)")

        assertEquals($$"println($END$)", expansion.template)
        assertEquals(emptyList(), expansion.stops)
        assertEquals("println(".length, expansion.end)
    }

    @Test
    fun `stops are visited in number order, not text order`() {
        val expansion = Snippets.expand($$"f(${2:a}, ${1:value})$0")

        assertEquals($$"f($TAB_2$, $TAB_1$)$END$", expansion.template)
        assertEquals(listOf(stop("TAB_1", "value"), stop("TAB_2", "a")), expansion.stops)
    }

    @Test
    fun `a repeated stop is one variable, mirrored`() {
        val expansion = Snippets.expand("<$1>$0</$1>")

        assertEquals($$"<$TAB_1$>$END$</$TAB_1$>", expansion.template)
        assertEquals(listOf(stop("TAB_1", "")), expansion.stops)
    }

    @Test
    fun `a mirror takes the value of a later placeholder`() {
        assertEquals(listOf(stop("TAB_1", "x")), Snippets.expand($$"$1 = ${1:x}").stops)
    }

    @Test
    fun `a nested stop is part of the outer stop's value`() {
        val expansion = Snippets.expand($$"for (x in ${1:a.${2:items}})")

        assertEquals($$"for (x in $TAB_1$)", expansion.template)
        assertEquals(listOf(stop("TAB_1", "a.items")), expansion.stops)
    }

    @Test
    fun `a choice offers every option and starts on the first`() {
        val expansion = Snippets.expand($$"${1|public,private,a\\,b|} class")

        assertEquals(listOf(Snippets.TabStop("TAB_1", "public", listOf("public", "private", "a,b"))), expansion.stops)
    }

    @Test
    fun `a literal dollar is doubled in the template`() {
        assertEquals($$$"$$x $TAB_1$", Snippets.expand($$"\\$x $1").template)
    }

    @Test
    fun `a tab stop is only its digits`() {
        assertEquals($$"$TAB_1$abc", Snippets.expand("$1abc").template)
    }

    @Test
    fun `variables are text, not stops`() {
        val expansion = Snippets.expand($$"// $TM_FILENAME ${TM_LINE:1}")

        assertEquals("//  1", expansion.template)
        assertEquals(emptyList(), expansion.stops)
        assertNull(expansion.end)
    }

    @Test
    fun `a stop inside a variable's default is still a stop`() {
        assertEquals($$"[$TAB_1$]", Snippets.expand($$"[${TM_SELECTED_TEXT:${1:x}}]").template)
    }

    @Test
    fun `every final stop after the first is a variable never stopped at`() {
        // rust-analyzer's `pd`, typed into in both places at once in VS Code.
        val expansion = Snippets.expand($$"eprintln!(\"$0 = {:?}\", $0);")

        assertEquals("eprintln!(\" = {:?}\", );", expansion.text)
        assertEquals($$"eprintln!(\"$END$ = {:?}\", $FINAL_1$);", expansion.template)
        assertEquals(listOf(stop("FINAL_1", "")), expansion.finals)
        assertEquals(listOf(11, 21), expansion.ends.map { it.end })
        assertEquals(11, expansion.end)
    }

    @Test
    fun `a mirror shows the stop's value in the text, as VS Code inserts it`() {
        assertEquals("x = x", Snippets.plain($$"$1 = ${1:x}"))
        assertEquals("a: a", Snippets.plain($$"${1|a,b|}: $1"))
    }

    @Test
    fun `marks say where each stop is written in the text`() {
        val expansion = Snippets.expand($$"f(${2:ab}, $1) ${0:end} $2")

        assertEquals("f(ab, ) end ab", expansion.text)
        assertEquals(
            listOf(Snippets.Mark(2, 2, 4), Snippets.Mark(1, 6, 6), Snippets.Mark(0, 8, 11), Snippets.Mark(2, 12, 14)),
            expansion.marks,
        )
    }

    @Test
    fun `a nested stop has no mark of its own`() {
        assertEquals(listOf(Snippets.Mark(1, 0, 3)), Snippets.expand($$"${1:a${2:b}c}").marks)
    }

    @Test
    fun `lines after the first are indented as the line the snippet goes into`() {
        val spaces = Snippets.Indentation(4, spaces = true)
        assertEquals("a {\n        b\n    }", Snippets.indented("a {\r\n\tb\n}", "    ", spaces))
        // The editor's tabs, counting a tab as a whole indent and a remainder as spaces.
        assertEquals("a\n\t\t  b", Snippets.indented("a\n      b", "\t", Snippets.Indentation(4, spaces = false)))
        // The first line keeps what goes before it, and only its own leading whitespace is redone.
        assertEquals("    a", Snippets.indented("\ta", "  ", spaces))
    }

    private fun stop(name: String, value: String) = Snippets.TabStop(name, value, emptyList())
}

