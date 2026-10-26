package me.helvert.codebridge.ide

import com.intellij.openapi.components.serviceIfCreated
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.TreeViews
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.rpc.TreeEventDto
import me.helvert.codebridge.rpc.TreeViewDto
import me.helvert.codebridge.rpc.WelcomeLineDto
import me.helvert.codebridge.rpc.WelcomePartDto
import me.helvert.codebridge.views.TreeIcons
import me.helvert.codebridge.workbench.HostedTreeViews
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * The views extensions contribute, as the backend keeps them for the frontend to draw.
 *
 * Which views exist comes from the manifests; what state they are in comes from the host's
 * `MainThreadTreeViews` calls. Both are checked here without a host: a test cannot start one, and
 * everything that does not ask the provider for items is the backend's alone. The `TreeViews`
 * payload tests cover reading those calls; this covers what is done with them.
 */
class TreeViewsTest : EditorFixture() {

    @BeforeEach
    fun installViews() {
        onEdt { install() }
        settle()
    }

    @AfterEach
    fun forget() {
        onEdt {
            // A light fixture can share its project, and with it this service, between tests.
            treeViews.hostStopped()
            ExtensionInstallationService.getInstance().uninstall(EXTENSION)
        }
        settle()
    }

    /**
     * Lets the host stop that installing or removing an extension queues run out first. A test
     * class before this one may have created the project's language servers, and their stop clears
     * every tree view: landing mid-test, it bumped a generation the test had just read.
     */
    private fun settle() {
        fixture.project.serviceIfCreated<HostedLanguageServers>()?.awaitWorkerForTest()
    }

    @Test
    fun `views are drawn from the manifest before any host runs`() = onEdt {
        val views = views()
        assertEquals(listOf(DEPS, HIDDEN, OUTLINE, PAGE, DEBUG_VIEW, ORPHAN), views.map { it.id })
        assertTrue(views.none { it.registered }, "Nothing provides data yet")

        val deps = view(DEPS)
        assertEquals(EXTENSION, deps.extensionId)
        assertEquals("probe", deps.containerId)
        assertFalse(deps.panel)

        assertEquals("Probe Panel", view(PAGE).containerTitle)
        assertTrue(view(PAGE).panel, "A `panel` container goes below the editor")
        assertTrue(view(PAGE).webview)
        assertFalse(deps.webview)

        // Containers VS Code has of its own need not be declared.
        assertEquals("Explorer", view(OUTLINE).containerTitle)
        assertEquals("$(files)", view(OUTLINE).containerIcon)
        assertEquals("$(list-tree)", view(OUTLINE).icon)
        // Nor does one nobody declares stop the view from being shown.
        assertEquals("nowhere", view(ORPHAN).containerTitle)
        assertEquals(ORPHAN, view(ORPHAN).name, "A view without a name is called by its id")

        assertEquals(EXTENSION, treeViews.extensionOf(DEPS))
        assertNull(treeViews.extensionOf("someone.else"))
    }

    @Test
    fun `names and titles are taken from package nls json`() = onEdt {
        assertEquals("Dependencies", view(DEPS).name)
        assertEquals("Dependencies", treeViews.nameOf(DEPS))
        assertEquals("Probe Container", view(DEPS).containerTitle)
        assertEquals(listOf("Refresh Crates"), view(DEPS).actions.map { it.title })
    }

    @Test
    fun `a view's declared visibility is passed on for the frontend to start it by`() = onEdt {
        assertTrue(view(HIDDEN).hiddenByDefault)
        assertFalse(view(HIDDEN).collapsedByDefault)
        assertTrue(view(DEPS).collapsedByDefault)
        assertFalse(view(DEPS).hiddenByDefault)
    }

    @Test
    fun `a view whose when clause does not hold is not shown`() = onEdt {
        assertFalse(view(HIDDEN).visible)
        assertTrue(view(DEPS).visible)
        assertFalse(view(DEBUG_VIEW).visible, "There is no debug session to be of type `probe`")
    }

    @Test
    fun `the debug views of a debugger are its extension's, shown for that type`() = onEdt {
        assertEquals(listOf(DEBUG_VIEW), treeViews.debugViews("probe").map { it.id })
        assertTrue(treeViews.debugViews("probe").single().visible)
        assertEquals(emptyList(), treeViews.debugViews("node"), "No enabled extension contributes that debugger")
    }

    @Test
    fun `title buttons are the view's own`() = onEdt {
        val refresh = view(DEPS).actions.single()
        assertEquals("$(refresh)", refresh.icon)
        assertEquals(emptyList(), view(OUTLINE).actions, "The entry's `when` names another view")
    }

    @Test
    fun `welcome links are numbered across the content as running them counts them`() = onEdt {
        assertEquals(
            listOf(
                WelcomeLineDto(listOf(WelcomePartDto("No crates yet."))),
                WelcomeLineDto(listOf(WelcomePartDto("Add Crate", link = 0)), button = true),
                WelcomeLineDto(
                    listOf(
                        WelcomePartDto("See "), WelcomePartDto("the docs", url = "https://example.com/docs"),
                        WelcomePartDto(" or "), WelcomePartDto("refresh", link = 2),
                    )
                ),
            ),
            view(DEPS).welcome,
        )
        assertEquals(emptyList(), view(OUTLINE).welcome, "That content's `when` does not hold")
    }

    @Test
    fun `what the provider says about its view reaches the frontend`() = onEdt {
        val before = view(DEPS).generation
        send(
            TreeViews.REGISTER,
            """["$DEPS",{"showCollapseAll":true,"canSelectMany":true,"manageCheckboxStateManually":true}]"""
        )
        view(DEPS).let {
            assertTrue(it.registered)
            assertTrue(it.showCollapseAll)
            assertTrue(it.canSelectMany)
            assertTrue(it.manualCheckboxes)
            assertEquals(before + 1, it.generation, "A new provider is a new tree")
        }

        send(TreeViews.SET_TITLE, """["$DEPS","Crates","3 found"]""")
        assertEquals("Crates", view(DEPS).title)
        assertEquals("3 found", view(DEPS).description)
        send(TreeViews.SET_TITLE, """["$DEPS","",null]""")
        assertNull(view(DEPS).title, "An empty title gives the view back its name")

        send(TreeViews.SET_BADGE, """["$DEPS",{"value":4,"tooltip":"4 updates"}]""")
        assertEquals(4, view(DEPS).badge)
        send(TreeViews.SET_BADGE, """["$DEPS",{"value":0}]""")
        assertNull(view(DEPS).badge, "VS Code draws no badge for zero")

        send(TreeViews.SET_MESSAGE, """["$DEPS","a < b\nloading"]""")
        assertEquals("a &lt; b<br>loading", view(DEPS).message, "Plain text is escaped, its breaks kept")
        send(TreeViews.SET_MESSAGE, """["$DEPS",""]""")
        assertNull(view(DEPS).message)

        send(TreeViews.DISPOSE, """["$DEPS"]""")
        view(DEPS).let {
            assertFalse(it.registered)
            assertFalse(it.showCollapseAll)
            assertNull(it.title)
            assertEquals(before + 2, it.generation, "The tree the frontend holds is gone")
        }
    }

    @Test
    fun `a webview view's own title, description and badge are kept as a tree view's are`() = onEdt {
        treeViews.retitle(PAGE, "Preview")
        treeViews.describe(PAGE, "draft")
        treeViews.rebadge(PAGE, 2)
        view(PAGE).let {
            assertEquals("Preview", it.title)
            assertEquals("draft", it.description)
            assertEquals(2, it.badge)
        }
        treeViews.retitle(PAGE, " ")
        treeViews.rebadge(PAGE, -1)
        assertNull(view(PAGE).title)
        assertNull(view(PAGE).badge)
    }

    @Test
    fun `losing the host forgets every provider and the trees they gave`() = onEdt {
        send(TreeViews.REGISTER, """["$DEPS",{"showCollapseAll":true}]""")
        send(TreeViews.SET_BADGE, """["$DEPS",{"value":1}]""")
        refresh(crate("0/serde"))
        val generation = view(DEPS).generation

        treeViews.hostStopped()

        view(DEPS).let {
            assertFalse(it.registered)
            assertNull(it.badge)
            assertTrue(it.generation > generation, "The frontend must drop the old host's handles")
        }
        assertEquals(emptyList(), treeViews.itemActions(DEPS, "0/serde"))
    }

    @Test
    fun `a refresh of some items sends them, and of the whole tree starts it again`() = onEdt {
        send(TreeViews.REGISTER, """["$DEPS",{}]""")
        val generation = view(DEPS).generation

        val event = events(1) { refresh(crate("0/serde"), folder("0/dev")) }.single()
        assertEquals(TreeEventDto.REFRESH, event.kind)
        assertEquals(DEPS, event.viewId)
        assertEquals(listOf("0/serde", "0/dev"), event.items.map { it.handle })
        assertEquals(generation, view(DEPS).generation, "Only those items change")

        send(TreeViews.REFRESH, """["$DEPS"]""")
        assertEquals(generation + 1, view(DEPS).generation)
        assertEquals(emptyList(), treeViews.itemActions(DEPS, "0/serde"), "Its handles went with the tree")
    }

    @Test
    fun `items are described for the frontend as the host gave them`() = onEdt {
        val file = Files.createTempFile("codebridge-tree", ".rs")
        val icon = Files.writeString(Files.createTempFile("codebridge-tree", ".svg"), "<svg/>")
        val items = events(1) {
            refresh(
                crate("0/serde"),
                folder("0/dev"),
                """{"handle":"0/main","resourceUri":${
                    UriComponents.of(file).toJson()
                },"themeIcon":{"id":"file"},"collapsibleState":0}""",
                """{"handle":"0/build","resourceUri":${
                    UriComponents.of(file).toJson()
                },"themeIcon":{"id":"file"},"collapsibleState":2}""",
                """{"handle":"0/logo","label":{"label":"logo"},"icon":${
                    UriComponents.of(icon).toJson()
                },"checkbox":{"isChecked":true}}""",
                """{"handle":"0/tip","label":{"label":"tip"},"tooltip":"x < y"}""",
            )
        }.single().items.associateBy { it.handle }

        items.getValue("0/serde").let {
            assertEquals("serde", it.label)
            assertEquals("1.0", it.description)
            assertTrue(it.expandable)
            assertFalse(it.expanded)
            assertTrue(it.folder, "Expandable, and no icon of its own")
            assertTrue(it.hasCommand)
        }
        assertTrue(items.getValue("0/dev").expanded)
        items.getValue("0/main").let {
            assertEquals(file.fileName.toString(), it.label, "No label: named by its file")
            assertNull(it.icon, "A `file` theme icon with a resource is drawn from the file's type")
            assertEquals(file.toString(), it.resourcePath)
            assertNotNull(it.fileIcon, "The backend's icon for the file, which the frontend may not know the type of")
            assertFalse(it.folder)
            assertFalse(it.expandable)
        }
        items.getValue("0/build").let {
            assertNull(it.icon)
            assertFalse(it.folder, "A `file` theme icon is a file's even when the item expands")
            assertTrue(it.expandable)
        }
        items.getValue("0/logo").let {
            assertNull(it.fileIcon, "No resource")
            assertEquals(true, it.checked)
            val key = assertNotNull(it.icon)
            assertContentEquals("<svg/>".toByteArray(), treeViews.icon(key))
        }
        assertEquals("x &lt; y", items.getValue("0/tip").tooltip)
    }

    @Test
    fun `history resources retain file icons without becoming local files`() = onEdt {
        val items = events(1) {
            refresh(
                """{"handle":"history","resourceUri":{"scheme":"gitlens","path":"/repo/settings.json","query":"ref=HEAD"},"collapsibleState":0}""",
                """{"handle":"themed","resourceUri":{"scheme":"git","path":"/repo/misc.xml"},"themeIcon":{"id":"file"},"collapsibleState":0}""",
                """{"handle":"folder","resourceUri":{"scheme":"gitlens","path":"/repo/.idea"},"themeIcon":{"id":"folder"},"collapsibleState":1}""",
                """{"handle":"custom","resourceUri":{"scheme":"gitlens","path":"/repo/settings.json"},"themeIcon":{"id":"git-commit"},"collapsibleState":0}""",
            )
        }.single().items.associateBy { it.handle }
        for ((handle, name) in listOf("history" to "settings.json", "themed" to "misc.xml")) {
            val item = items.getValue(handle)
            assertEquals(name, item.resourceName)
            assertEquals(name, item.label)
            assertNull(item.resourcePath, "A historical resource must not enable local file actions")
            assertNull(item.icon)
            val expected = if (name.endsWith(".json")) com.intellij.icons.AllIcons.FileTypes.Json
            else com.intellij.icons.AllIcons.FileTypes.Xml
            assertEquals(expected, TreeIcons.item(treeViews.source, treeViews.scope, item) {})
        }
        val folder = items.getValue("folder")
        assertTrue(folder.folder)
        assertEquals(
            com.intellij.icons.AllIcons.Nodes.Folder,
            TreeIcons.item(treeViews.source, treeViews.scope, folder) {})
        assertEquals("$(git-commit)", items.getValue("custom").icon)
    }

    @Test
    fun `an item's menu is what its context value offers`() = onEdt {
        val items = events(1) { refresh(crate("0/serde"), folder("0/dev")) }.single().items.associateBy { it.handle }

        assertEquals(listOf("Remove"), items.getValue("0/serde").inline.map { it.title })
        assertEquals(emptyList(), items.getValue("0/dev").inline.map { it.title })

        assertEquals(listOf("Remove", "Open"), treeViews.itemActions(DEPS, "0/serde").map { it.title }, "Inline first")
        assertEquals(listOf("Open"), treeViews.itemActions(DEPS, "0/dev").map { it.title })
        assertEquals(emptyList(), treeViews.itemActions(DEPS, "0/never-given"))
    }

    @Test
    fun `a reveal opens the chain above the item, root first`() = onEdt {
        val event = events(1) {
            send(
                TreeViews.REVEAL, """["$DEPS",{"item":${crate("0/serde/0/derive")},""" +
                        """"parentChain":[${folder("0/dev")},${crate("0/serde")}]},{"select":false,"focus":true,"expand":2}]"""
            )
        }.single()

        assertEquals(TreeEventDto.REVEAL, event.kind)
        assertEquals(listOf("0/dev", "0/serde", "0/serde/0/derive"), event.path)
        assertFalse(event.select)
        assertTrue(event.focus)
        assertEquals(2, event.expand)
        assertEquals(
            listOf("Remove", "Open"), treeViews.itemActions(DEPS, "0/serde/0/derive").map { it.title },
            "A revealed item is one the frontend now holds"
        )
    }

    @Test
    fun `the views' workbench commands focus or collapse them`() = onEdt {
        assertFalse(treeViews.viewCommand("probe.unknown.focus"))
        assertFalse(treeViews.viewCommand("workbench.view.extension.unknown"))
        assertFalse(treeViews.viewCommand("editor.action.formatDocument"))

        val events = events(3) {
            assertTrue(treeViews.viewCommand("$DEPS.focus"))
            assertTrue(treeViews.viewCommand("workbench.view.extension.probePanel"))
            assertTrue(treeViews.viewCommand("workbench.actions.treeView.$OUTLINE.collapseAll"))
        }
        assertEquals(
            listOf(TreeEventDto.FOCUS to DEPS, TreeEventDto.FOCUS to PAGE, TreeEventDto.COLLAPSE_ALL to OUTLINE),
            events.map { it.kind to it.viewId },
        )
    }

    @Test
    fun `icons are served only from inside the extension`() = onEdt {
        val key = assertNotNull(view(DEPS).containerIcon)
        assertContentEquals(SVG.toByteArray(), treeViews.icon(key))
        assertNull(view(HIDDEN).icon, "`../` out of the extension is not followed")
        assertNull(treeViews.icon("icon-unknown"))
    }

    @Test
    fun `nothing reaches a view nobody contributes, or a host that is not there`() = onEdt {
        val unregistered = runBlocking { treeViews.children("someone.else", null) }
        assertEquals(HostedTreeViews.NO_PROVIDER, unregistered.problem)
        assertTrue(unregistered.unregistered, "The panel shows welcome content in its place")

        refresh(crate("0/serde"))
        // These race the host going away; with none, they must do nothing rather than throw.
        treeViews.setVisible(DEPS, true)
        treeViews.setExpanded(DEPS, "0/serde", true)
        treeViews.setSelection(DEPS, listOf("0/serde"), "0/serde")
        treeViews.setCheckboxes(DEPS, mapOf("0/serde" to true))
        runBlocking {
            treeViews.run(DEPS, "0/serde")
            treeViews.runAction(DEPS, 0, "0/serde", emptyList())
        }
        treeViews.setVisible(DEPS, false)
        assertTrue(runBlocking { treeViews.execute(JsonArray(listOf(JsonPrimitive("probe.open")))) } is IllegalStateException)
    }

    @Test
    fun `a malformed call does not take the handler down with it`() = onEdt {
        // Arrives on the RPC reader, where an exception would end the session.
        for (method in listOf(
            TreeViews.REGISTER, TreeViews.REFRESH, TreeViews.REVEAL, TreeViews.SET_TITLE,
            TreeViews.SET_BADGE, TreeViews.SET_MESSAGE, TreeViews.DISPOSE
        )) {
            send(method, "not json")
            send(method, "[]")
        }
        send(TreeViews.REFRESH, """["$DEPS",{"0/x":{"label":{"label":"no handle"}}}]""")
        assertFalse(view(DEPS).registered)
    }

    private fun crate(handle: String) =
        """{"handle":"$handle","label":{"label":"${handle.substringAfterLast('/')}"},"description":"1.0",""" +
                """"collapsibleState":1,"contextValue":"crate","command":{"id":"probe.open","title":""}}"""

    @Suppress("SameParameterValue")
    private fun folder(handle: String) =
        """{"handle":"$handle","label":{"label":"${handle.substringAfterLast('/')}"},"collapsibleState":2,"contextValue":"folder"}"""

    /** `$refresh` of [items] in the dependencies view, as the host sends it: keyed by handle. */
    private fun refresh(vararg items: String) {
        val byHandle = items.joinToString(",") { item ->
            "\"${Regex("\"handle\":\"([^\"]+)\"").find(item)!!.groupValues[1]}\":$item"
        }
        send(TreeViews.REFRESH, """["$DEPS",{$byHandle}]""")
    }

    /** The first [count] events [trigger] sends the frontend. */
    private fun events(count: Int, trigger: () -> Unit): List<TreeEventDto> = runBlocking {
        val collected = async(start = CoroutineStart.UNDISPATCHED) { treeViews.changes.take(count).toList() }
        trigger()
        withTimeout(5.seconds) { collected.await() }
    }

    private fun views(): List<TreeViewDto> = runBlocking { treeViews.views().first() }

    private fun view(id: String): TreeViewDto = views().single { it.id == id }

    private fun send(method: String, args: String) = treeViews.handle(MainThreadCall(TreeViews.SERVICE, method, args))

    private val treeViews get() = HostedTreeViews.getInstance(fixture.project)

    private fun install() {
        val manifest = buildJsonObject {
            put("publisher", "codebridge"); put("name", "probe-views"); put("version", "1.0.0")
            put("engines", buildJsonObject { put("vscode", "^1.90.0") })
            put("contributes", buildJsonObject {
                put("viewsContainers", buildJsonObject {
                    put("activitybar", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "probe"); put("title", "%container.probe%"); put(
                            "icon",
                            "media/probe.svg"
                        )
                        })
                    })
                    put("panel", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "probePanel"); put("title", "Probe Panel"); put(
                            "icon",
                            "$(output)"
                        )
                        })
                    })
                })
                put("views", buildJsonObject {
                    put("probe", buildJsonArray {
                        add(buildJsonObject {
                            put("id", DEPS); put("name", "%view.deps%"); put(
                            "visibility",
                            "collapsed"
                        )
                        })
                        add(buildJsonObject {
                            put("id", HIDDEN); put("name", "Hidden"); put("when", "probe.never"); put(
                            "visibility",
                            "hidden"
                        ); put(
                            "icon",
                            "../../outside.svg"
                        )
                        })
                    })
                    put(
                        "explorer",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("id", OUTLINE); put("name", "Outline"); put(
                                "icon",
                                "$(list-tree)"
                            )
                            })
                        })
                    put(
                        "probePanel",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("id", PAGE); put("name", "Page"); put(
                                "type",
                                "webview"
                            )
                            })
                        })
                    put(
                        "debug",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("id", DEBUG_VIEW); put(
                                "name",
                                "Probe Things"
                            ); put("when", "debugType == probe")
                            })
                        })
                    put("nowhere", buildJsonArray { add(buildJsonObject { put("id", ORPHAN) }) })
                })
                put(
                    "debuggers",
                    buildJsonArray { add(buildJsonObject { put("type", "probe"); put("label", "Probe") }) })
                put("commands", buildJsonArray {
                    add(buildJsonObject {
                        put("command", "probe.refresh"); put(
                        "title",
                        "%command.refresh%"
                    ); put("icon", "$(refresh)")
                    })
                    add(buildJsonObject { put("command", "probe.remove"); put("title", "Remove") })
                    add(buildJsonObject { put("command", "probe.open"); put("title", "Open") })
                })
                put("menus", buildJsonObject {
                    put("view/title", buildJsonArray {
                        add(buildJsonObject {
                            put("command", "probe.refresh"); put(
                            "when",
                            "view == $DEPS"
                        ); put("group", "navigation")
                        })
                    })
                    put("view/item/context", buildJsonArray {
                        add(buildJsonObject { put("command", "probe.open"); put("when", "view == $DEPS") })
                        add(buildJsonObject {
                            put("command", "probe.remove"); put(
                            "when",
                            "view == $DEPS && viewItem == crate"
                        ); put("group", "inline")
                        })
                    })
                })
                put("viewsWelcome", buildJsonArray {
                    add(buildJsonObject {
                        put("view", DEPS)
                        put(
                            "contents", "No crates yet.\n[Add Crate](command:probe.add)\n" +
                                    "See [the docs](https://example.com/docs) or [refresh](command:probe.refresh)"
                        )
                    })
                    add(buildJsonObject {
                        put("view", OUTLINE); put("contents", "Never shown"); put(
                        "when",
                        "probe.never"
                    )
                    })
                })
            })
        }
        val nls =
            """{"view.deps":"Dependencies","container.probe":{"message":"Probe Container","comment":"side bar"},""" +
                    """"command.refresh":"Refresh Crates"}"""

        @Suppress("HttpUrlsUsage") // an XML namespace, not a link
        val xml = """<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">""" +
                """<Metadata><Identity Id="probe-views"/></Metadata></PackageManifest>"""
        val vsix = Files.createTempFile(directory, "probe-", ".vsix")
        ZipArchiveOutputStream(vsix).use { zip ->
            for ((name, text) in listOf(
                "extension/package.json" to manifest.toString(),
                "extension/package.nls.json" to nls,
                "extension/media/probe.svg" to SVG,
                "extension.vsixmanifest" to xml,
            )) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(text.toByteArray())
                zip.closeArchiveEntry()
            }
        }
        ExtensionInstallationService.getInstance().install(vsix) {}
    }

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-tree-views") }

    private companion object {
        const val EXTENSION = "codebridge.probe-views"
        const val DEPS = "probe.deps"
        const val HIDDEN = "probe.hidden"
        const val OUTLINE = "probe.outline"
        const val PAGE = "probe.page"
        const val DEBUG_VIEW = "probe.debugView"
        const val ORPHAN = "probe.orphan"
        const val SVG = """<svg xmlns="http://www.w3.org/2000/svg"/>"""
    }
}
