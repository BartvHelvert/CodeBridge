package me.helvert.codebridge.textmate

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaScriptRegexTest {
    private fun regex(pattern: String) = Regex(JavaScriptRegex.toJava(pattern))

    @Test
    fun `go's increase pattern compiles and matches as in VS Code`() {
        val go = regex("""^.*(\bcase\b.*:|\bdefault\b:|(\b(func|if|else|switch|select|for|struct)\b.*)?{[^}"'`]*|\([^)"'`]*)$""")
        assertTrue(go.matches("func main() {"))
        assertTrue(go.matches("\tswitch x {"))
        assertTrue(go.matches("fmt.Println("))
        assertFalse(go.matches("x := 1"))
    }

    @Test
    fun `quantifiers stay quantifiers and other braces are literals`() {
        assertEquals("""a{2}b{1,}c{1,3}""", JavaScriptRegex.toJava("""a{2}b{1,}c{1,3}"""))
        assertEquals("""\{x\}""", JavaScriptRegex.toJava("""{x}"""))
        assertEquals("""\{""", JavaScriptRegex.toJava("""\{"""))
        assertTrue(regex("a{,2}").matches("a{,2}"))
    }

    @Test
    fun `class contents java reads as syntax are literals`() {
        assertTrue(regex("""[[]""").matches("["))
        assertTrue(regex("""[a&&b]""").matches("&"))
        assertTrue(regex("""[^]]""").matches("x]"))
        assertTrue(regex("""[{}]""").matches("}"))
    }

    @Test
    fun `empty classes match nothing or anything`() {
        assertFalse(regex("""a[]""").containsMatchIn("a"))
        assertTrue(regex("""a[^]b""").matches("a\nb"))
    }

    @Test
    fun `escapes javascript reads differently`() {
        assertTrue(regex("""\v""").matches("\u000B"))
        assertFalse(regex("""\v""").matches("\n"))
        assertTrue(regex("""\0""").matches("\u0000"))
        assertTrue(regex("""\y\/""").matches("y/"))
        assertTrue(regex("""\d\w\s\.""").matches("1a ."))
    }
}
