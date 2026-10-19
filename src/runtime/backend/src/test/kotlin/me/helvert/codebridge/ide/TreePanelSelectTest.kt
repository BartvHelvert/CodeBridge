package me.helvert.codebridge.ide

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.views.TreeViewSource
import me.helvert.codebridge.views.CodeBridgeTreePanel
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Finding a file in an extension's tree view by its items' `resourceUri`s, as Select In does for
 * library files only such a view lists: rust-analyzer's dependencies, say.
 */
class TreePanelSelectTest : EditorFixture() {

    @Test
    fun `a file is found through the folders that hold it, fetching only those`() = withPanel { panel, tree, source ->
        assertFalse(panel.mayHold(str.toString()), "Nothing fetched yet")

        assertTrue(panel.select(str.toString(), focus = false))

        assertEquals("str", selected(tree))
        assertEquals(
            listOf(null, "core", "core/src"),
            source.fetched,
            "Neither the group nor the other crate is opened"
        )
        assertTrue(panel.mayHold(str.toString()))
        assertTrue(panel.mayHold(other.resolve("lib.rs").toString()), "That crate's root was fetched, and holds it")
        assertFalse(panel.mayHold(root.resolve("elsewhere.rs").toString()))
    }

    @Test
    fun `a hidden view fetches nothing`() = withPanel { _, _, source ->
        delay(200.milliseconds)

        assertEquals(emptyList(), source.fetched)
    }

    @Test
    fun `an item the provider refreshes forgets the files that were under it`() = withPanel { panel, _, _ ->
        panel.select(str.toString(), focus = false)
        assertTrue(panel.mayHold(str.toString()))

        // The crate now stands for another folder, and what was fetched under it is gone.
        panel.changed(TreeEventDto(TreeEventDto.REFRESH, "deps", items = listOf(item("core", other))))

        assertFalse(panel.mayHold(str.toString()))
        assertTrue(panel.mayHold(other.resolve("lib.rs").toString()))
    }

    @Test
    fun `a file the tree does not list selects the deepest folder holding it`() = withPanel { panel, tree, _ ->
        assertTrue(panel.select(core.resolve("src/unlisted.rs").toString(), focus = false))

        assertEquals("core/src", selected(tree))
    }

    @Test
    fun `a file outside every item is not selected`() = withPanel { panel, tree, _ ->
        val elsewhere = withContext(Dispatchers.IO) { Files.createTempFile("elsewhere", ".rs") }
        assertFalse(panel.select(elsewhere.toString(), focus = false))

        assertEquals(null, selected(tree))
    }

    @Test
    fun `selected items stand for their files to the IDE's actions`() = withPanel { panel, tree, _ ->
        // Library files are known to the IDE by the time they are open; these were only just written.
        val expected = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(str)
        panel.select(str.toString(), focus = false)

        // A test's data manager answers nothing from components unless told to.
        HeadlessDataManager.fallbackToProductionDataManager(panel)
        val context = DataManager.getInstance().getDataContext(tree)

        // Looked up off the EDT, as an action's update does, since finding a file may read the disk.
        assertEquals(
            expected,
            withContext(Dispatchers.Default) { readAction { context.getData(CommonDataKeys.VIRTUAL_FILE) } })
    }

    private val root: Path = Files.createTempDirectory("deps")
    private val core: Path = root.resolve("core").also { it.resolve("src").createDirectories() }
    private val str: Path = core.resolve("src/str.rs").also { it.writeText("") }

    /** A crate whose folder starts as `core`'s does, which must not be mistaken for holding it. */
    private val other: Path = root.resolve("core2").also { it.createDirectories() }

    private class Source(private val children: Map<String?, List<TreeItemDto>>) : TreeViewSource {
        val fetched = mutableListOf<String?>()
        override suspend fun children(viewId: String, handle: String?): TreeChildrenDto {
            fetched += handle
            return TreeChildrenDto(children[handle].orEmpty())
        }

        override suspend fun visible(viewId: String, visible: Boolean) = Unit
        override suspend fun expanded(viewId: String, handle: String, expanded: Boolean) = Unit
        override suspend fun selection(viewId: String, handles: List<String>, focus: String?) = Unit
        override suspend fun run(viewId: String, handle: String) = Unit
        override suspend fun icon(key: String): ByteArray? = null
        override suspend fun itemActions(viewId: String, handle: String): List<TreeActionDto> = emptyList()
        override suspend fun runAction(viewId: String, actionId: Int, handle: String?, selection: List<String>) = Unit
        override suspend fun runWelcomeLink(viewId: String, link: Int) = Unit
        override suspend fun checkboxes(viewId: String, states: Map<String, Boolean>) = Unit
    }

    private fun item(handle: String, path: Path?, expandable: Boolean = true) =
        TreeItemDto(handle, handle, resourcePath = path?.toString(), expandable = expandable)

    private fun source() = Source(
        mapOf(
            null to listOf(item("group", null), item("core2", other), item("core", core)),
            "group" to listOf(item("group/inner", core)),
            "core" to listOf(
                item("core/Cargo.toml", core.resolve("Cargo.toml"), expandable = false),
                item("core/src", core.resolve("src"))
            ),
            "core/src" to listOf(item("core/src/str.rs", str, expandable = false).copy(label = "str")),
        )
    )

    private fun selected(tree: Tree): String? =
        ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeItemDto)?.label

    private fun withPanel(test: suspend (CodeBridgeTreePanel, Tree, Source) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val source = source()
        try {
            withContext(Dispatchers.EDT) {
                val panel = CodeBridgeTreePanel(
                    source,
                    scope,
                    TreeViewDto(
                        "deps",
                        "Dependencies",
                        "probe.ext",
                        "explorer",
                        "Explorer",
                        registered = true,
                    )
                )
                try {
                    test(panel, UIUtil.findComponentOfType(panel, Tree::class.java)!!, source)
                } finally {
                    Disposer.dispose(panel)
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
