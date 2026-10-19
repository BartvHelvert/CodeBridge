package me.helvert.codebridge.workbench

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Globs as extensions write them for `findFiles`, `files.exclude` and file watchers.
 *
 * `HostedServicesTest` and `HostedFileWatchersTest` reach this only through `**` and `{a,b}`; the
 * rest of the syntax is here, since a pattern that compiles to the wrong regex fails silently, by
 * finding nothing or excluding everything.
 */
class WorkspaceGlobTest {

    private fun assertMatches(pattern: String, vararg paths: String, ignoreCase: Boolean = false) {
        val glob = WorkspaceGlob(pattern, ignoreCase)
        for (path in paths) assertTrue(glob.matches(path), "'$pattern' should match '$path'")
    }

    private fun assertMisses(pattern: String, vararg paths: String, ignoreCase: Boolean = false) {
        val glob = WorkspaceGlob(pattern, ignoreCase)
        for (path in paths) assertFalse(glob.matches(path), "'$pattern' should not match '$path'")
    }

    @Test
    fun `a star stays within one segment`() {
        assertMatches("*.kt", "a.kt", ".kt")
        assertMisses("*.kt", "dir/a.kt", "a.kts")
        assertMatches("src/*/main.kt", "src/app/main.kt")
        assertMisses("src/*/main.kt", "src/main.kt", "src/a/b/main.kt")
    }

    @Test
    fun `a globstar spans any number of directories, none included`() {
        assertMatches("**/*.kt", "a.kt", "x/a.kt", "x/y/z/a.kt")
        assertMatches("src/**/test", "src/test", "src/a/test", "src/a/b/test")
        assertMisses("src/**/test", "srctest", "src/atest", "lib/src/test")
        assertMatches("src/**", "src/a", "src/a/b/c")
        assertMatches("**", "a", "a/b/c")
    }

    @Test
    fun `a question mark is one character other than a separator`() {
        assertMatches("a?c", "abc", "a.c")
        assertMisses("a?c", "ac", "abbc", "a/c")
    }

    @Test
    fun `braces offer alternatives, nested ones too, and a comma outside them is literal`() {
        assertMatches("*.{kt,java}", "a.kt", "a.java")
        assertMisses("*.{kt,java}", "a.kts", "a.{kt,java}")
        assertMatches("{src,test}/{a,{b,c}}.txt", "src/a.txt", "test/c.txt")
        assertMisses("{src,test}/{a,{b,c}}.txt", "lib/a.txt", "src/d.txt")
        assertMatches("a,b", "a,b")
        assertMatches("a}", "a}")
    }

    @Test
    fun `an unclosed brace is refused rather than matching nothing`() {
        assertFailsWith<IllegalArgumentException> { WorkspaceGlob("*.{kt,java") }
    }

    @Test
    fun `brackets are a character class, negated by a bang`() {
        assertMatches("file[0-9].txt", "file1.txt")
        assertMisses("file[0-9].txt", "filex.txt", "file10.txt")
        assertMatches("[abc].md", "b.md")
        assertMatches("[!abc].md", "d.md")
        assertMisses("[!abc].md", "a.md")
        assertMatches("[]x].md", "].md", "x.md")
        // A bracket that never closes is the character itself.
        assertMatches("a[b", "a[b")
    }

    @Test
    fun `regex syntax in a pattern is literal`() {
        assertMatches("a.b", "a.b")
        assertMisses("a.b", "axb")
        assertMatches("(x)+$", "(x)+$")
        assertMatches("[a&&b]", "&")
        assertMisses("[a&&b]", "c")
    }

    @Test
    fun `a path is matched with forward slashes whatever the platform wrote`() {
        assertMatches("src/**/*.kt", "src\\main\\a.kt")
    }

    @Test
    fun `case is ignored only when asked`() {
        assertMisses("**/*.KT", "src/a.kt")
        assertMatches("**/*.KT", "src/a.kt", ignoreCase = true)
    }
}
