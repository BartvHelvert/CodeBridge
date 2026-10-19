package me.helvert.codebridge.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

class JsoncTest {

    @Test
    fun `strips line comments`() {
        val comment = "// trailing note"
        val stripped = Jsonc.stripToJson("""{ "a": 1 } $comment""")
        assertEquals("""{ "a": 1 } """ + " ".repeat(comment.length), stripped)
    }

    @Test
    fun `strips block comments but keeps newlines`() {
        val source = "{\n/* one\n   two */\n\"a\": 1\n}"
        val stripped = Jsonc.stripToJson(source)
        assertEquals(source.lines().size, stripped.lines().size)
        assertEquals("{\n      \n         \n\"a\": 1\n}", stripped)
    }

    @Test
    fun `preserves offsets so error positions stay valid`() {
        val source = """{ // note
            |  "a": 1
            |}""".trimMargin()
        assertEquals(source.length, Jsonc.stripToJson(source).length)
    }

    @Test
    fun `leaves comment-like text inside strings alone`() {
        val source = """{ "url": "https://example.com", "glob": "/*.kt" }"""
        assertEquals(source, Jsonc.stripToJson(source))
    }

    @Test
    fun `handles escaped quotes inside strings`() {
        val source = """{ "a": "he said \"hi\" // not a comment" }"""
        assertEquals(source, Jsonc.stripToJson(source))
    }

    @Test
    fun `blanks trailing commas in objects and arrays`() {
        val stripped = Jsonc.stripToJson("""{ "a": [1, 2, ], }""")
        assertEquals("""{ "a": [1, 2  ]  }""", stripped)
    }

    @Test
    fun `keeps commas that are not trailing`() {
        val source = """{ "a": 1, "b": 2 }"""
        assertEquals(source, Jsonc.stripToJson(source))
    }
}
