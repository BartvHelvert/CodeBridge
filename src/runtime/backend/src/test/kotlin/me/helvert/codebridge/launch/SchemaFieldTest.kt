package me.helvert.codebridge.launch

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import kotlinx.serialization.json.*
import me.helvert.codebridge.ide.EditorFixture
import me.helvert.codebridge.launch.SchemaField.Kind
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * The fields of a `launch.json` entry's form, chosen by the shape the debugger's schema gives each
 * key, what each does to its key -- nothing until it is changed -- and the `${...}` they complete.
 */
class SchemaFieldTest : EditorFixture() {

    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun field(json: String?, value: JsonElement? = null, tasks: (() -> List<String>)? = null): SchemaField =
        SchemaField.of("key", json?.let(::schema), value, Variables { emptyList() }, tasks).also { it.show(value) }

    private fun kind(json: String?, value: JsonElement? = null) = field(json, value).kind

    private var SchemaField.text: String
        get() = (this as TypedField).text
        set(value) {
            (this as TypedField).text = value
        }

    @Test
    fun `each shape gets the field that edits it`() = onEdt {
        assertEquals(Kind.CHECK, kind("""{ "type": "boolean" }"""))
        assertEquals(Kind.CHOICE, kind("""{ "enum": ["a", "b"] }"""))
        assertEquals(Kind.TEXT, kind("""{ "type": "string" }"""))
        assertEquals(Kind.NUMBER, kind("""{ "type": ["integer", "null"] }"""))
        assertEquals(Kind.LIST, kind("""{ "type": "array", "items": { "type": "string" } }"""))
        assertEquals(Kind.MAP, kind("""{ "type": "object", "additionalProperties": { "type": "string" } }"""))
        // Anything else is edited as the JSON it is.
        assertEquals(Kind.JSON, kind("""{ "type": "object", "properties": { "a": { "type": "string" } } }"""))
    }

    @Test
    fun `a value the shape cannot hold gets a field that can`() = onEdt {
        // A `${input:...}` where a boolean belongs is text.
        assertEquals(Kind.TEXT, kind("""{ "type": "boolean" }""", JsonPrimitive($$"${input:debug}")))
        // An environment variable removed with null.
        assertEquals(
            Kind.JSON,
            kind("""{ "type": "object", "additionalProperties": { "type": "string" } }""", schema("""{ "A": null }"""))
        )
    }

    @Test
    fun `of alternatives the first that holds the value is taken`() = onEdt {
        val args = """{ "anyOf": [{ "type": "array", "items": { "type": "string" } }, { "type": "string" }] }"""

        assertEquals(Kind.LIST, kind(args, JsonArray(listOf(JsonPrimitive("-v")))))
        assertEquals(Kind.TEXT, kind(args, JsonPrimitive("-v --fast")))
    }

    @Test
    fun `a key no schema describes is edited by the value it has`() = onEdt {
        assertEquals(Kind.CHECK, kind(null, JsonPrimitive(true)))
        assertEquals(Kind.LIST, kind(null, JsonArray(listOf(JsonPrimitive("a")))))
        assertEquals(Kind.JSON, kind(null, schema("""{ "a": 1 }""")))
    }

    @Test
    fun `an untouched field leaves its key as it is, however it would write it`() = onEdt {
        // 1.0 would be written back as 1, and the list with other quoting.
        assertEquals(FieldChange.Unchanged, field("""{ "type": "number" }""", JsonPrimitive(1.0)).change())
        assertEquals(
            FieldChange.Unchanged, field(
                """{ "type": "array", "items": { "type": "string" } }""",
                JsonArray(listOf(JsonPrimitive("a b"), JsonPrimitive("c")))
            ).change()
        )
        assertEquals(FieldChange.Unchanged, field("""{ "type": "boolean", "default": true }""").change())
    }

    @Test
    fun `a changed field sets its key, and an emptied one removes it`() = onEdt {
        val text = field("""{ "type": "string" }""", JsonPrimitive("a.out"))
        text.text = "b.out"
        assertEquals(FieldChange.Set(JsonPrimitive("b.out")), text.change())
        text.text = ""
        assertEquals(FieldChange.Remove, text.change())

        val check = field("""{ "type": "boolean" }""")
        (check.component as JBCheckBox).isSelected = true
        assertEquals(FieldChange.Set(JsonPrimitive(true)), check.change())

        val list = field("""{ "type": "array", "items": { "type": "string" } }""")
        list.text = "--port 80 \"a b\""
        assertEquals(FieldChange.Set(JsonArray(listOf("--port", "80", "a b").map(::JsonPrimitive))), list.change())
    }

    @Test
    fun `a field holds what it was given or what it was changed to`() = onEdt {
        val text = field("""{ "type": "string" }""", JsonPrimitive("a.out"))
        assertEquals(JsonPrimitive("a.out"), text.current())
        text.text = "b.out"
        assertEquals(JsonPrimitive("b.out"), text.current())
        text.text = ""
        assertNull(text.current())
    }

    @Test
    fun `a value carried over from another debugger's field is written though untouched`() = onEdt {
        // Typed in the field of the debugger chosen before, and not yet in the file.
        val carried = SchemaField.of(
            "key",
            schema("""{ "type": "string" }"""),
            JsonPrimitive("b.out"),
            Variables { emptyList() },
            null
        )
        carried.show(JsonPrimitive("b.out"), changed = true)
        assertEquals(FieldChange.Set(JsonPrimitive("b.out")), carried.change())

        // Cleared there: the key goes.
        val cleared = SchemaField.of("key", schema("""{ "type": "string" }"""), null, Variables { emptyList() }, null)
        cleared.show(null, changed = true)
        assertEquals(FieldChange.Remove, cleared.change())
    }

    @Test
    fun `a number field writes a number, or the string it is given`() = onEdt {
        val port = field("""{ "type": "integer" }""")
        port.text = "9229"
        assertEquals(FieldChange.Set(JsonPrimitive(9229L)), port.change())
        port.text = $$"${input:port}"
        assertEquals(FieldChange.Set(JsonPrimitive($$"${input:port}")), port.change())
    }

    @Test
    fun `a choice keeps a value the schema does not list`() = onEdt {
        val choice =
            field("""{ "enum": ["internalConsole", "integratedTerminal"] }""", JsonPrimitive($$"${input:console}"))
        val combo = choice.component as ComboBox<*>
        assertEquals(JsonPrimitive($$"${input:console}"), combo.selectedItem)
        combo.selectedItem = JsonPrimitive("integratedTerminal")
        assertEquals(FieldChange.Set(JsonPrimitive("integratedTerminal")), choice.change())
    }

    @Test
    fun `a task key offers the tasks and takes any other`() = onEdt {
        val task = field("""{ "type": "string" }""", JsonPrimitive("build"), tasks = { listOf("build", "test") })
        val combo = task.component as ComboBox<*>
        assertEquals(listOf("build", "test"), (0 until combo.itemCount).map { combo.getItemAt(it) })
        combo.selectedItem = "lint"
        assertEquals(FieldChange.Set(JsonPrimitive("lint")), task.change())
    }

    @Test
    fun `JSON that does not parse is refused, and comments are dropped`() = onEdt {
        val json = field(null, schema("""{ "a": 1 }"""))
        json.text = """{ "a": 2 /* two */ }"""
        assertEquals(FieldChange.Set(schema("""{ "a": 2 }""")), json.change())
        json.text = """{ "a": """
        assertFailsWith<ConfigurationException> { json.change() }
    }

    @Test
    fun `text fields offer to insert variables, as run configurations offer macros`() = onEdt {
        for (json in listOf(
            """{ "type": "string" }""", """{ "type": "number" }""",
            """{ "type": "array", "items": { "type": "string" } }""", """{ "type": "object" }"""
        )) {
            val field = field(json) as TypedField
            assertEquals(
                listOf("Insert variable"),
                field.textField.extensions.mapNotNull { it.tooltip }.filter { it == "Insert variable" })
        }
    }

    @Test
    fun `a variable is inserted at the caret, in place of what is selected`() = onEdt {
        val field = field("""{ "type": "string" }""", JsonPrimitive("bin/app")) as TypedField
        field.textField.caretPosition = 0
        TypedField.insert(field.textField, $$"${workspaceFolder}/")
        assertEquals($$"${workspaceFolder}/bin/app", field.text)
        assertEquals($$"${workspaceFolder}/".length, field.textField.caretPosition)

        field.textField.select(field.text.indexOf("bin"), field.text.length)
        TypedField.insert(field.textField, $$"${input:program}")
        assertEquals($$"${workspaceFolder}/${input:program}", field.text)
        assertEquals(FieldChange.Set(JsonPrimitive($$"${workspaceFolder}/${input:program}")), field.change())
    }

    @Test
    fun `the variables offered are whole, the predefined first, then the inputs of the entry's file`() {
        val choices = LaunchVariables.choices { listOf("who", "port") }.map { it.text }

        assertEquals($$"${workspaceFolder}", choices.first())
        assertContains(choices, $$"${input:who}")
        assertContains(choices, $$"${env:$${LaunchVariables.environmentNames().first()}}")
        assertTrue(
            choices.indexOf($$"${input:who}") < choices.indexOf(
                $$"${env:$${
                    LaunchVariables.environmentNames().first()
                }}"
            )
        )
        // A namespace is not a variable of its own.
        assertEquals(emptyList(), choices.filter { it.endsWith(":}") })
    }

    @Test
    fun `the variable being typed is the last one opened and not yet closed`() {
        assertEquals(LaunchVariables.Typing(null, "work"), LaunchVariables.typing($$"a ${file} ${work"))
        assertEquals(LaunchVariables.Typing("config", "editor.tab"), LaunchVariables.typing($$"${config:editor.tab"))
        assertNull(LaunchVariables.typing($$"${file}/x"))
        assertNull(LaunchVariables.typing("no variable"))
    }
}
