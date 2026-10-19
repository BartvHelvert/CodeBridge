package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `MainThreadWebview*` calls as the host sends them, and the calls that answer them. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class WebviewsTest {

    @Test
    fun `reads a new panel`() {
        val panel = Webviews.panel(
            $$"""[
            {"id":{"value":"me.cats","_lower":"me.cats"},"location":{"$mid":1,"scheme":"file","path":"/ext/cats"}},
            "6f1c","catCoding",
            {"title":"Cat Coding","panelOptions":{"retainContextWhenHidden":true},
             "webviewOptions":{"enableScripts":true,"enableCommandUris":["a.b"],
               "localResourceRoots":[{"scheme":"file","path":"/ext/cats/media"}]},
             "serializeBuffersForPostMessage":true},
            {"viewColumn":-1,"preserveFocus":true}]"""
        )
        assertEquals("me.cats", panel.extension.id)
        assertEquals("/ext/cats", panel.extension.location?.path)
        assertEquals("6f1c", panel.handle)
        assertEquals("catCoding", panel.viewType)
        assertEquals("Cat Coding", panel.title)
        assertTrue(panel.options.scripts)
        assertEquals(listOf("a.b"), panel.options.commandUris)
        assertEquals(listOf("/ext/cats/media"), panel.options.localResourceRoots?.map { it.path })
        assertTrue(panel.preserveFocus)
    }

    @Test
    fun `reads options, where true allows every command and absent roots are left to the host`() {
        val (handle, options) = Webviews.options("""["h",{"enableScripts":false,"enableCommandUris":true}]""")
        assertEquals("h", handle)
        assertEquals(false, options.scripts)
        assertEquals(emptyList(), options.commandUris)
        assertNull(options.localResourceRoots)
        assertNull(Webviews.options("""["h",{}]""").second.commandUris)
    }

    @Test
    fun `keeps a message's buffers in the order its references count them`() {
        val message =
            Webviews.message("""["h","{\"a\":1}",null,null]""", mapOf(3 to byteArrayOf(2), 2 to byteArrayOf(1)))
        assertEquals("h", message.handle)
        assertEquals("""{"a":1}""", message.json)
        assertEquals(listOf(1, 2), message.buffers.map { it.single().toInt() })
    }

    @Test
    fun `reads a view provider, its badge and its show`() {
        val provider =
            Webviews.viewProvider("""[{"id":"me.cats","location":{"scheme":"file","path":"/ext"}},"cats.view",{}]""")
        assertEquals("me.cats", provider.extension.id)
        assertEquals("cats.view", provider.viewType)
        assertEquals("h" to 3, Webviews.badge("""["h",{"value":3,"tooltip":"three"}]"""))
        assertEquals("h" to null, Webviews.badge("""["h",null]"""))
        assertEquals("h" to true, Webviews.preserveFocus(Webviews.SHOW_VIEW, """["h",true]"""))
        assertEquals("h" to false, Webviews.preserveFocus(Webviews.REVEAL, """["h",{"preserveFocus":false}]"""))
    }

    @Test
    fun `sends a page's message with the empty buffer holder the host reads`() {
        val args = Json.parseToJsonElement(Webviews.onMessage("h", """{"x":1}""")).jsonArray
        assertEquals("h", args[0].jsonPrimitive.content)
        assertEquals("""{"x":1}""", args[1].jsonPrimitive.content)
        assertEquals(0, args[2].jsonObject.getValue("value").jsonArray.size)
    }

    @Test
    fun `describes a panel's view state by its handle`() {
        val state =
            Json.parseToJsonElement(Webviews.panelViewState("h", active = true, visible = true)).jsonArray[0].jsonObject
        assertEquals("true", state.getValue("h").jsonObject.getValue("active").jsonPrimitive.content)
    }

    @Test
    fun `reads the resource an asWebviewUri address names`() {
        val resource = Webviews.resource("https://file+.vscode-resource.vscode-cdn.net/c%3A/ext/media/main.js?v=1")
        assertEquals(UriComponents("file", "", "/c:/ext/media/main.js", "v=1", ""), resource)
        assertEquals(
            "host:8080",
            Webviews.resource("https://vscode-remote+host-003a8080.vscode-resource.vscode-cdn.net/x")?.authority
        )
        assertNull(Webviews.resource("https://example.com/main.js"))
        assertNull(Webviews.resource("https://abc.vscode-webview.vscode-cdn.net/index.html"))
    }

    @Test
    fun `reads a resource address whose plus VS Code's Uri escaped`() {
        assertEquals(
            UriComponents("file", "", "/c:/ext/dist/shared.js", "", ""),
            Webviews.resource("https://file%2B.vscode-resource.vscode-cdn.net/c%3A/ext/dist/shared.js"),
        )
    }

    @Test
    fun `reads where a panel opens, its find widget and whether it takes buffers`() {
        val panel = Webviews.panel(
            """[{"id":"me.cats"},"h","cats",
            {"title":"T","panelOptions":{"enableFindWidget":true},"webviewOptions":{"enableScripts":true,"portMapping":[]},
             "serializeBuffersForPostMessage":true},
            {"viewColumn":-2,"preserveFocus":false}]"""
        )
        assertEquals(Webviews.SIDE_GROUP, panel.column)
        assertTrue(panel.findWidget)
        assertTrue(panel.binaryMessages)
        // Kept as sent, to hand back on restoring, options the workbench does not read included.
        assertTrue("portMapping" in panel.options.json)
        assertEquals(Webviews.ACTIVE_GROUP, Webviews.panel("""[{"id":"x"},"h","v",{},{}]""").column)
        assertEquals(Webviews.Reveal("h", 1, true), Webviews.reveal("""["h",{"viewColumn":1,"preserveFocus":true}]"""))
        assertTrue(Webviews.viewProvider("""[{"id":"x"},"v",{"serializeBuffersForPostMessage":true}]""").binaryMessages)
    }

    @Test
    fun `reads a panel's icon for either theme, or none`() {
        val icon =
            Webviews.iconPath("""["h",{"light":{"scheme":"file","path":"/ext/l.svg"},"dark":{"scheme":"file","path":"/ext/d.svg"}}]""")
        assertEquals("/ext/l.svg", icon.light?.path)
        assertEquals("/ext/d.svg", icon.dark?.path)
        assertNull(Webviews.iconPath("""["h",null]""").light)
    }

    @Test
    fun `sends a page's buffers as a SerializableObjectWithBuffers of references`() {
        val args = Json.parseToJsonElement(Webviews.onMessage("h", """{"b":{}}""", buffers = 2)).jsonArray
        val refs = args[Webviews.ON_MESSAGE_BUFFERS].jsonArray
        assertEquals(listOf("0", "1"), refs.map { it.jsonObject.getValue($$$"$$ref$$").jsonPrimitive.content })
    }

    @Test
    fun `asks for a panel to be restored with its page's state as a value`() {
        val args = Json.parseToJsonElement(
            Webviews.deserializePanel(
                "h",
                "cats",
                "Cats",
                """{"count":3}""",
                Json.parseToJsonElement("""{"enableFindWidget":true}""").jsonObject,
                Json.parseToJsonElement("""{"enableScripts":true}""").jsonObject,
                active = true,
                position = 1,
            )
        ).jsonArray
        assertEquals("h", args[0].jsonPrimitive.content)
        assertEquals("cats", args[1].jsonPrimitive.content)
        val init = args[2].jsonObject
        assertEquals("3", init.getValue("state").jsonObject.getValue("count").jsonPrimitive.content)
        assertEquals("true", init.getValue("webviewOptions").jsonObject.getValue("enableScripts").jsonPrimitive.content)
        assertEquals("true", init.getValue("active").jsonPrimitive.content)
        assertEquals("1", args[3].jsonPrimitive.content)
    }
}
