package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `MainThreadTreeViews` calls and `$getChildren` replies, as the host sends them. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class TreeViewsTest {

    @Test
    fun `reads the items of one level`() {
        val items = TreeViews.children(
            $$"""[[0,
            {"handle":"0/0:src","label":{"label":"src"},"collapsibleState":1,"themeIcon":{"id":"folder"},
             "resourceUri":{"$mid":1,"scheme":"file","path":"/w/src"}},
            {"handle":"1/main","parentHandle":"0/0:src","label":{"label":{"value":"**main**"}},"description":"entry",
             "tooltip":{"value":"The *entry* point"},"contextValue":"file","collapsibleState":0,
             "command":{"id":"vscode.open","title":"","arguments":[{"scheme":"file","path":"/w/src/main.rs"}],"originalId":"vscode.open"},
             "icon":{"scheme":"file","path":"/ext/light.svg"},"iconDark":{"scheme":"file","path":"/ext/dark.svg"},
             "checkbox":{"isChecked":true}}
        ]]"""
        )
        assertEquals(2, items.size)
        val (folder, main) = items
        assertEquals("0/0:src", folder.handle)
        assertEquals("src", folder.label)
        assertEquals(TreeViews.COLLAPSED, folder.collapsible)
        assertEquals("folder", folder.themeIcon)
        assertEquals("/w/src", folder.resource?.path)
        assertNull(folder.command)

        assertEquals("0/0:src", main.parentHandle)
        assertEquals("**main**", main.label)
        assertEquals("entry", main.description)
        assertEquals("The *entry* point", main.tooltip)
        assertEquals("vscode.open", main.command?.id)
        assertEquals(1, main.command?.arguments?.size)
        assertEquals("/ext/light.svg", main.icon?.path)
        assertEquals("/ext/dark.svg", main.iconDark?.path)
        assertEquals(true, main.checked)
        assertEquals(TreeViews.LEAF, main.collapsible)
        assertTrue(main.markdownTooltip)
        assertTrue(!folder.markdownTooltip)
    }

    @Test
    fun `no reply is no items`() {
        assertEquals(emptyList(), TreeViews.children(""))
        assertEquals(emptyList(), TreeViews.children("[]"))
    }

    @Test
    fun `asks for the roots without a handle and for one parent with it`() {
        assertEquals("""["deps"]""", TreeViews.getChildren("deps", null))
        assertEquals("""["deps",["1/x"]]""", TreeViews.getChildren("deps", "1/x"))
    }

    @Test
    fun `reads registration and a refresh of the whole tree or of some items`() {
        val registration =
            TreeViews.registration("""["deps",{"showCollapseAll":true,"canSelectMany":false,"dropMimeTypes":[]}]""")
        assertEquals("deps", registration.viewId)
        assertTrue(registration.showCollapseAll)
        assertTrue(!registration.manualCheckboxes)
        assertTrue(TreeViews.registration("""["deps",{"manageCheckboxStateManually":true}]""").manualCheckboxes)

        assertNull(TreeViews.refresh("""["deps"]""").items)
        val partial =
            TreeViews.refresh("""["deps",{"1/a":{"handle":"1/a","label":{"label":"A"},"collapsibleState":2}}]""")
        assertEquals(listOf("1/a"), partial.items?.map { it.handle })
        assertEquals(TreeViews.EXPANDED, partial.items?.single()?.collapsible)
    }

    @Test
    fun `reads a reveal with its parents, root first`() {
        val reveal = TreeViews.reveal(
            """["deps",{"item":{"handle":"1/c","label":{"label":"c"}},
            "parentChain":[{"handle":"1/a","label":{"label":"a"}},{"handle":"1/b","label":{"label":"b"}}]},
            {"select":false,"focus":true,"expand":2}]"""
        )
        assertEquals(listOf("1/a", "1/b"), reveal.parentChain.map { it.handle })
        assertEquals("1/c", reveal.item?.handle)
        assertEquals(false, reveal.select)
        assertEquals(true, reveal.focus)
        assertEquals(2, reveal.expand)
        assertEquals(1, TreeViews.reveal("""["deps",null,{"expand":true}]""").expand)
    }

    @Test
    fun `reads title, message and badge`() {
        assertEquals(TreeViews.Title("deps", "Crates", "3 found"), TreeViews.title("""["deps","Crates","3 found"]"""))
        assertEquals(
            TreeViews.Message("deps", "Loading", markdown = true),
            TreeViews.message("""["deps",{"value":"Loading"}]""")
        )
        assertEquals(TreeViews.Message("deps", null, markdown = false), TreeViews.message("""["deps",""]"""))
        assertEquals(
            """["deps",[{"treeItemHandle":"0/a","newState":true}]]""",
            TreeViews.checkboxes("deps", mapOf("0/a" to true))
        )
        assertEquals(
            TreeViews.Badge("deps", 4, "4 updates"),
            TreeViews.badge("""["deps",{"value":4,"tooltip":"4 updates"}]""")
        )
        assertNull(TreeViews.badge("""["deps",null]""").value)
    }
}
