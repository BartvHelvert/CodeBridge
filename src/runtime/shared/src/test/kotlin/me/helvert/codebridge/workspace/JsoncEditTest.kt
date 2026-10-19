package me.helvert.codebridge.workspace

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One setting changed in a hand-edited file, and nothing else touched.
 *
 * `.vscode/settings.json` is committed and commented; an extension flipping one value -- as
 * rust-analyzer's check-on-save toggle does -- must leave every other byte exactly where it was.
 */
class JsoncEditTest {

    @Test
    fun `replacing a value keeps comments, order and formatting`() {
        val before = """
            {
                // Why this project checks on save.
                "rust-analyzer.checkOnSave": true,
                /* and why it does not format */ "editor.formatOnSave": false
            }
        """.trimIndent()

        val after = JsoncEdit.set(before, listOf("rust-analyzer.checkOnSave"), JsonPrimitive(false))

        assertEquals(before.replace("\"rust-analyzer.checkOnSave\": true", "\"rust-analyzer.checkOnSave\": false"), after)
    }

    @Test
    fun `a new key is added after the last, in the file's own indentation`() {
        val before = "{\n\t\"a\": 1\n}\n"

        val after = JsoncEdit.set(before, listOf("b"), JsonPrimitive("two"))

        assertEquals("{\n\t\"a\": 1,\n\t\"b\": \"two\"\n}\n", after)
    }

    @Test
    fun `a trailing comma stays where it was`() {
        val after = JsoncEdit.set("{\n  \"a\": 1,\n}", listOf("b"), JsonPrimitive(2))

        assertEquals("{\n  \"a\": 1,\n  \"b\": 2,\n}", after)
        assertEquals(2, JsoncEdit.parse(after).size)
    }

    @Test
    fun `a missing or empty file becomes a small object`() {
        assertEquals("{\n  \"a\": true\n}\n", JsoncEdit.set("", listOf("a"), JsonPrimitive(true)))
        assertEquals("{\n  \"a\": true\n}", JsoncEdit.set("{}", listOf("a"), JsonPrimitive(true)))
    }

    @Test
    fun `removing a key takes its line and the comma that joined it`() {
        val before = "{\n  \"a\": 1,\n  \"b\": 2,\n  \"c\": 3\n}"

        assertEquals("{\n  \"a\": 1,\n  \"c\": 3\n}", JsoncEdit.set(before, listOf("b"), null))
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}", JsoncEdit.set(before, listOf("c"), null))
        assertEquals("{\n  \"b\": 2,\n  \"c\": 3\n}", JsoncEdit.set(before, listOf("a"), null))
    }

    @Test
    fun `removing a key that is not there changes nothing`() {
        val before = "{ \"a\": 1 } // done"
        assertEquals(before, JsoncEdit.set(before, listOf("missing"), null))
    }

    @Test
    fun `a workspace file's language-scoped setting goes three keys deep`() {
        val before = "{\n  \"folders\": [],\n  \"settings\": {\n    \"[rust]\": {\n      \"editor.tabSize\": 4\n    }\n  }\n}"

        val edited = JsoncEdit.set(before, listOf("settings", "[rust]", "editor.formatOnSave"), JsonPrimitive(true))
        val created = JsoncEdit.set("{\n  \"folders\": []\n}", listOf("settings", "[toml]", "editor.tabSize"), JsonPrimitive(2))

        val rust = JsoncEdit.parse(edited)["settings"]!!.jsonObject["[rust]"]!!.jsonObject
        assertEquals(JsonPrimitive(4), rust["editor.tabSize"])
        assertEquals(JsonPrimitive(true), rust["editor.formatOnSave"])
        assertEquals(JsonPrimitive(2), JsoncEdit.parse(created)["settings"]!!.jsonObject["[toml]"]!!.jsonObject["editor.tabSize"])
    }

    @Test
    fun `a language-scoped key goes inside its section`() {
        val before = "{\n  \"[rust]\": {\n    \"editor.tabSize\": 4\n  }\n}"

        val edited = JsoncEdit.set(before, listOf("[rust]", "editor.formatOnSave"), JsonPrimitive(true))
        val created = JsoncEdit.set("{\n  \"a\": 1\n}", listOf("[toml]", "editor.tabSize"), JsonPrimitive(2))

        assertEquals("{\n  \"[rust]\": {\n    \"editor.tabSize\": 4,\n    \"editor.formatOnSave\": true\n  }\n}", edited)
        assertEquals(JsonPrimitive(2), JsoncEdit.parse(created)["[toml]"]!!.jsonObject["editor.tabSize"])
    }

    @Test
    fun `windows line endings are kept`() {
        val after = JsoncEdit.set("{\r\n  \"a\": 1\r\n}\r\n", listOf("b"), JsonPrimitive(2))

        assertEquals("{\r\n  \"a\": 1,\r\n  \"b\": 2\r\n}\r\n", after)
    }

    @Test
    fun `keys and strings containing braces and quotes do not confuse it`() {
        val before = """{ "a": "} \" {", "b": [1, {"c": "]"}] }"""

        val after = JsoncEdit.set(before, listOf("b"), JsonPrimitive(0))

        assertEquals("""{ "a": "} \" {", "b": 0 }""", after)
    }

    private val launch = """
        {
            "configurations": [
                // The one people run.
                {
                    "name": "Run",
                    "program": "a.out", // built by make
                    "args": ["--fast"]
                },
                { "name": "Empty" }
            ]
        }
    """.trimIndent()

    private fun at(vararg steps: Any): List<JsoncEdit.Step> =
        steps.map { if (it is Int) JsoncEdit.Step.Index(it) else JsoncEdit.Step.Key(it as String) }

    @Test
    fun `a key of an array element is replaced in place`() {
        val after = JsoncEdit.set(launch, at("configurations", 0, "program"), JsonPrimitive("b.out"))

        assertEquals(launch.replace("\"a.out\"", "\"b.out\""), after)
    }

    @Test
    fun `a key is added to an array element in its indentation`() {
        val after = JsoncEdit.set(launch, at("configurations", 0, "cwd"), JsonPrimitive("src"))

        assertEquals(launch.replace("\"args\": [\"--fast\"]", "\"args\": [\"--fast\"],\n            \"cwd\": \"src\""), after)
    }

    @Test
    fun `the last key of an array element is removed with the comma before it, keeping the comment between`() {
        val after = JsoncEdit.set(launch, at("configurations", 0, "args"), null)

        assertEquals(launch.replace("\"a.out\", // built by make\n            \"args\": [\"--fast\"]\n", "\"a.out\" // built by make\n"), after)
    }

    @Test
    fun `an array element itself is replaced`() {
        val after = JsoncEdit.set(launch, at("configurations", 0, "args", 0), JsonPrimitive("--slow"))

        assertEquals(launch.replace("--fast", "--slow"), after)
    }

    @Test
    fun `objects and arrays are written on one line, spaced`() {
        val env = kotlinx.serialization.json.buildJsonObject { put("A", JsonPrimitive("1")); put("B", JsonPrimitive("2")) }

        val after = JsoncEdit.set(launch, at("configurations", 1, "env"), env)

        assertEquals(launch.replace("{ \"name\": \"Empty\" }", "{ \"name\": \"Empty\", \"env\": { \"A\": \"1\", \"B\": \"2\" } }"), after)
    }

    @Test
    fun `an element that is not there is refused`() {
        kotlin.test.assertFailsWith<IllegalStateException> { JsoncEdit.set(launch, at("configurations", 2, "name"), JsonPrimitive("x")) }
    }
}
