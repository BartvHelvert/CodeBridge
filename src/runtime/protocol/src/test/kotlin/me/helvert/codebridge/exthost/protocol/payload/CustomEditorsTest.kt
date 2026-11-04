package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `MainThreadCustomEditors` calls as the pinned host sends them, and the `ExtHostCustomEditors` calls that answer them. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class CustomEditorsTest {

    private val extension = $$"""{"id":{"value":"me.hex","_lower":"me.hex"},"location":{"$mid":1,"scheme":"file","path":"/ext/hex"}}"""

    @Test
    fun `reads a text editor provider, whose last argument says how it takes buffers`() {
        val provider = CustomEditors.provider(
            CustomEditors.REGISTER_TEXT_PROVIDER,
            """[$extension,"me.drawio",{"enableFindWidget":true},{"supportsMove":false},true]""",
        )
        assertEquals("me.hex", provider.extension.id)
        assertEquals("me.drawio", provider.viewType)
        assertTrue(provider.text)
        assertTrue(Webviews.findWidget(provider.panelOptions))
        assertFalse(provider.multipleEditors)
        assertTrue(provider.binaryMessages)
    }

    @Test
    fun `reads a custom editor provider, which says whether a document may have several editors`() {
        val provider = CustomEditors.provider(
            CustomEditors.REGISTER_CUSTOM_PROVIDER,
            """[$extension,"hexEditor.hexedit",{},{"supportsInlineDiff":false},true,false]""",
        )
        assertFalse(provider.text)
        assertTrue(provider.multipleEditors)
        assertFalse(provider.binaryMessages)
    }

    @Test
    fun `reads an edit by its id, and a change that cannot be undone`() {
        val resource = """{"scheme":"file","authority":"","path":"/p/a.bin","query":"","fragment":""}"""
        val edit = CustomEditors.edit(CustomEditors.ON_DID_EDIT, """[$resource,"hex",7,"Replace"]""")
        assertEquals("/p/a.bin", edit.resource.path)
        assertEquals(7, edit.editId)
        assertEquals("Replace", edit.label)
        assertNull(CustomEditors.edit(CustomEditors.ON_CONTENT_CHANGE, """[$resource,"hex"]""").editId)
    }

    @Test
    fun `asks to resolve an editor with empty content options and the provider's panel options`() {
        val resource = UriComponents.of("file:///p/a.bin")
        val args = Json.parseToJsonElement(
            CustomEditors.resolveEditor(resource, "h1", "hex", "a.bin", Json.parseToJsonElement("""{"retainContextWhenHidden":true}""").jsonObject, true, 2)
        ).jsonArray
        assertEquals("/p/a.bin", args[0].jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("h1", args[1].jsonPrimitive.content)
        val init = args[3].jsonObject
        assertEquals("a.bin", init["title"]?.jsonPrimitive?.content)
        assertEquals(0, init["contentOptions"]?.jsonObject?.size)
        assertEquals("true", init["options"]?.jsonObject?.get("retainContextWhenHidden")?.toString())
        assertEquals(2, args[4].jsonPrimitive.int)
    }

    @Test
    fun `reads whether a made document is editable`() {
        assertTrue(CustomEditors.editable("""{"editable":true}"""))
        assertFalse(CustomEditors.editable("""{"editable":false}"""))
        assertFalse(CustomEditors.editable("null"))
    }
}
