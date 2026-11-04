package me.helvert.codebridge.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading a file of VS Code snippets, in the shapes extensions actually write them. */
class SnippetFileTest {

    @Test
    fun `prefixes, bodies and descriptions are a string or a list`() {
        val snippets = SnippetFile.parse(
            """
            {
              // A comment, as JSONC allows.
              "Log": { "prefix": ["log", "cl", "log"], "body": ["console.log($1);", "$0"], "description": ["Log", "it"] },
              "Return": { "prefix": "ret", "body": "return $0;" },
            }
            """.trimIndent(),
            "javascript",
        )

        assertEquals(
            listOf(
                SnippetFile.Snippet("Log", listOf("log", "cl"), "console.log($1);\n$0", "Log\nit", setOf("javascript")),
                SnippetFile.Snippet("Return", listOf("ret"), "return $0;", null, setOf("javascript")),
            ),
            snippets,
        )
    }

    @Test
    fun `a file for a language puts every snippet there, whatever its scope says`() {
        val snippet = SnippetFile.parse("""{ "S": { "prefix": "s", "body": "s", "scope": "python" } }""", "javascript").single()
        assertEquals(setOf("javascript"), snippet.scopes)
    }

    @Test
    fun `a global file's snippets go where their scope says, or everywhere`() {
        val snippets = SnippetFile.parse(
            """
            {
              "Scoped": { "prefix": "s", "body": "s", "scope": "javascript, typescript" },
              "Everywhere": { "prefix": "e", "body": "e" }
            }
            """.trimIndent(),
            null,
        )
        assertEquals(listOf(setOf("javascript", "typescript"), emptySet()), snippets.map { it.scopes })
    }

    @Test
    fun `a snippet without a prefix or a body is left out`() {
        val snippets = SnippetFile.parse(
            """{ "No prefix": { "body": "x" }, "Empty prefix": { "prefix": "", "body": "x" }, "No body": { "prefix": "n" } }""",
            "javascript",
        )
        assertEquals(emptyList(), snippets)
    }

    @Test
    fun `include and exclude are globs over the name, or the path when they have a slash`() {
        val snippet = SnippetFile.Snippet(
            "Test", listOf("t"), "t", null, emptySet(),
            include = listOf("*.test.ts", "**/spec/**"), exclude = listOf("skip.test.ts"),
        )
        assertTrue(SnippetFile.applies(snippet, "a.test.ts", "/p/src/a.test.ts"))
        assertTrue(SnippetFile.applies(snippet, "a.ts", "/p/spec/a.ts"))
        assertFalse(SnippetFile.applies(snippet, "a.ts", "/p/src/a.ts"))
        assertFalse(SnippetFile.applies(snippet, "skip.test.ts", "/p/src/skip.test.ts"))
    }
}
