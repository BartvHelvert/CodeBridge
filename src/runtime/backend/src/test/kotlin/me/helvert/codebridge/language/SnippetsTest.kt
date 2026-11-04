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

    @Test
    fun `without values a variable is its default, as for a language server's snippet`() {
        assertEquals("class Name", Snippets.plain($$"class ${TM_FILENAME_BASE:Name}"))
        assertEquals("class ", Snippets.plain($$"class $TM_FILENAME_BASE"))
    }

    @Test
    fun `a variable takes its value`() {
        val expansion = Snippets.expand($$"class $TM_FILENAME_BASE {}", values("TM_FILENAME_BASE" to "Widget"))
        assertEquals("class Widget {}", expansion.text)
        assertEquals(emptyList(), expansion.stops)
    }

    @Test
    fun `a variable without a value here takes its default`() {
        assertEquals("x = y", Snippets.expand($$"x = ${TM_SELECTED_TEXT:y}", values()).text)
    }

    @Test
    fun `a value with snippet syntax in it is inserted as it is`() {
        val expansion = Snippets.expand("$" + "CLIPBOARD", values("CLIPBOARD" to $$"${1:a} $b"))
        assertEquals($$"${1:a} $b", expansion.text)
        assertEquals($$$"$${1:a} $$b", expansion.template)
    }

    @Test
    fun `a variable in a placeholder's default becomes the stop's value`() {
        val expansion = Snippets.expand($$"class ${1:$TM_FILENAME_BASE}", values("TM_FILENAME_BASE" to "Widget"))
        assertEquals("class Widget", expansion.text)
        assertEquals(listOf(stop("TAB_1", "Widget")), expansion.stops)
    }

    @Test
    fun `a variable VS Code does not know becomes a placeholder holding its name, after every stop`() {
        val expansion = Snippets.expand($$"$1 $unknown $2", values())
        assertEquals(" unknown ", expansion.text)
        assertEquals(listOf("TAB_1", "TAB_2", "TAB_3"), expansion.stops.map { it.name })
        assertEquals("unknown", expansion.stops.last().value)
    }

    @Test
    fun `a variable written twice has one value, though the snippet is read twice`() {
        var next = 0
        val expansion = Snippets.expand($$"$UUID ${1:a} $1", { (++next).toString() })
        assertEquals("1 a a", expansion.text)
    }

    @Test
    fun `a transform rewrites the value`() {
        val name = values("TM_FILENAME" to "my-widget.test.ts")
        assertEquals("my-widget", Snippets.expand($$"${TM_FILENAME/\\..*$//}", name).text)
        assertEquals("MyWidget", Snippets.expand($$"${TM_FILENAME/(.*?)\\..*/${1:/pascalcase}/}", name).text)
        assertEquals("myWidget", Snippets.expand($$"${TM_FILENAME/(.*?)\\..*/${1:/camelcase}/}", name).text)
        assertEquals("MY-WIDGET.TEST.TS", Snippets.expand($$"${TM_FILENAME/(.*)/${1:/upcase}/}", name).text)
        assertEquals("my_widget_test_ts", Snippets.expand($$"${TM_FILENAME/[-.]/_/g}", name).text)
        assertEquals("my_widget.test.ts", Snippets.expand($$"${TM_FILENAME/[-.]/_/}", name).text)
    }

    @Test
    fun `a transform's conditions pick by whether the group matched`() {
        val transform = $$"${TM_SELECTED_TEXT/(a)?.*/${1:?yes:no}/}"
        assertEquals("yes", Snippets.expand(transform, values("TM_SELECTED_TEXT" to "abc")).text)
        assertEquals("no", Snippets.expand(transform, values("TM_SELECTED_TEXT" to "xyz")).text)
        assertEquals("no", Snippets.expand(transform, values()).text)
        assertEquals("[x]", Snippets.expand($$"${TM_SELECTED_TEXT/(x)/[${1:+x}]/}", values("TM_SELECTED_TEXT" to "x")).text)
        assertEquals("none", Snippets.expand($$"${TM_SELECTED_TEXT/(x)?.*/${1:-none}/}", values("TM_SELECTED_TEXT" to "y")).text)
    }

    @Test
    fun `a transform that is not one leaves the value as it is`() {
        assertEquals("abc", Snippets.expand($$"${TM_SELECTED_TEXT/(/x/}", values("TM_SELECTED_TEXT" to "abc")).text)
    }

    private fun values(vararg values: Pair<String, String>) = Snippets.Variables { values.toMap()[it] }

    private fun stop(name: String, value: String) = Snippets.TabStop(name, value, emptyList())
}

