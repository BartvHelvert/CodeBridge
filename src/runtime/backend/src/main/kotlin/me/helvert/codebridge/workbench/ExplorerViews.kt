package me.helvert.codebridge.workbench

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.*
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.projectView.impl.nodes.ProjectViewProjectNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.IconUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.views.TreeIcons
import me.helvert.codebridge.views.TreeMenus
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * The tree views extensions add to VS Code's Explorer, in the Project view: one node each beside
 * External Libraries, as VS Code shows them as sections below its file tree.
 *
 * The Project view asks for a node's children synchronously, which an extension cannot answer. So
 * a level not fetched yet shows as loading, is fetched, and the view is redrawn once it is here;
 * what the extension says changed is fetched again the same way.
 *
 * Webview views are not here, but in a tool window.
 */
@Service(Service.Level.PROJECT)
internal class ExplorerViews(private val project: Project, private val scope: CoroutineScope) {

    /** A level of a view: its roots for a null [handle], else the items under that one. */
    data class Key(val viewId: String, val handle: String?)

    /** The views shown, in manifest order. */
    @Volatile
    var views: List<TreeViewDto> = emptyList()
        private set

    private val hosted get() = HostedTreeViews.getInstance(project)
    private val levels = ConcurrentHashMap<Key, TreeChildrenDto>()
    private val items = ConcurrentHashMap<Key, TreeItemDto>()

    /** The levels being fetched; each answers null when its tree was replaced while it was. */
    private val fetching = ConcurrentHashMap<Key, Deferred<TreeChildrenDto?>>()

    /** The path to the item each resource was last revealed as, by [UriComponents.key]: the view's node first. */
    private val revealed = ConcurrentHashMap<String, List<Key>>()

    /** [revealed], by the resource without its query or fragment; see [pathTo]. */
    private val revealedResources = ConcurrentHashMap<String, List<Key>>()

    /** Each view's generation as last seen; a new one is a new tree, and every handle of the old one is gone. */
    private val generations = ConcurrentHashMap<String, Int>()
    private val redrawing = AtomicBoolean()

    init {
        scope.launch { hosted.views().collect(::update) }
        scope.launch { hosted.changes.collect(::changed) }
    }

    fun view(viewId: String): TreeViewDto? = views.firstOrNull { it.id == viewId }

    fun item(key: Key): TreeItemDto? = items[key]

    private fun update(all: List<TreeViewDto>) {
        val shown = all.filter(::belongsHere)
        for (view in shown) {
            if (generations.put(view.id, view.generation) != view.generation) forget(view.id)
        }
        val before = views
        views = shown
        if (before != shown) redraw()
    }

    private fun changed(event: TreeEventDto) {
        if (view(event.viewId) == null) return
        when (event.kind) {
            TreeEventDto.REFRESH -> refreshed(event)
            TreeEventDto.REVEAL -> scope.launch { reveal(event) }
        }
    }

    private fun refreshed(event: TreeEventDto) {
        var any = false
        for (item in event.items) {
            val key = Key(event.viewId, item.handle)
            if (items.replace(key, item) == null) continue
            levels.remove(key)
            any = true
        }
        if (any) redraw()
    }

    /**
     * Learns where the item at the end of [event]'s path is, as `TreeView.reveal` tells VS Code: every
     * level down to it fetched, and the path kept for the resource the item stands for. Selecting it
     * is left to the Project view -- Select Opened File, or Always Select Opened File -- which finds a
     * file through the nodes that say they hold it ([holds]) and the one that stands for it
     * ([represents]); in split mode the tree on screen is the client's, and nothing done to the
     * backend's would show.
     *
     * Java Projects reveals the class in the active editor whenever the editor changes -- a class in
     * a library jar too, which no other node in the Project view stands for.
     */
    private suspend fun reveal(event: TreeEventDto) {
        val viewId = event.viewId
        event.items.forEach { items[Key(viewId, it.handle)] = it }
        val path = listOf(Key(viewId, null)) + event.path.map { Key(viewId, it) }
        for (level in path.dropLast(1)) {
            val next = path[path.indexOf(level) + 1].handle
            val held = load(level)?.items?.map { it.handle }
            if (held == null || next !in held) {
                LOG.info("Cannot reveal ${event.path} in view $viewId: ${level.handle ?: "its roots"} hold $held, not $next")
                return
            }
        }
        val resource = event.path.lastOrNull()?.let { hosted.resourceOf(viewId, it) }
        if (resource != null) {
            revealed[UriComponents.key(resource.uri)] = path
            revealedResources[withoutQuery(resource)] = path
        }
        LOG.info("View $viewId revealed ${event.path.lastOrNull()}, standing for ${resource?.uri}")
        // Not redrawn here: a level fetched on the way is redrawn already, and redrawing the whole
        // Project view on every editor switch -- which is when Java Projects reveals -- held up
        // Select Opened File behind it.
        if (event.focus) asClient {
            ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate(null)
        }
    }

    /** Whether [key] is on the path to the item that stands for [file], as the extension last revealed it. */
    fun holds(key: Key, file: VirtualFile): Boolean = pathTo(file)?.contains(key) == true

    /** Whether [key] is the item that stands for [file], as the extension last revealed it. */
    fun represents(key: Key, file: VirtualFile): Boolean = pathTo(file)?.lastOrNull() == key

    /** Whether an item of a view stands for [file], so that the Project view can select it there. */
    fun revealed(file: VirtualFile): Boolean = pathTo(file) != null

    /**
     * The path last revealed for [file]'s URI, or else for the same resource under another query. Java
     * names a JDK class after the project it was reached from -- `String.class?=greeter/...` in Java
     * Projects, `?=java-sandbox/...` when followed from a file of that project -- and either is the
     * one class.
     */
    private fun pathTo(file: VirtualFile): List<Key>? {
        val uri = uriOf(file) ?: return null
        return revealed[UriComponents.key(uri)]
            ?: runCatching { revealedResources[withoutQuery(UriComponents.of(uri))] }.getOrNull()
    }

    private fun withoutQuery(resource: UriComponents): String =
        UriComponents.key(resource.copy(query = "", fragment = "").uri)

    /** What an extension knows [file] by: a document it provided by its URI, a local file by its path's. */
    private fun uriOf(file: VirtualFile): String? =
        HostedContentProviders.getInstance(project).uriOf(file)
            ?: file.takeIf { it.isInLocalFileSystem }
                ?.let { runCatching { UriComponents.of(it.toNioPath()).uri }.getOrNull() }

    private fun forget(viewId: String) {
        levels.keys.removeIf { it.viewId == viewId }
        items.keys.removeIf { it.viewId == viewId }
        revealed.values.removeIf { path -> path.first().viewId == viewId }
        revealedResources.values.removeIf { path -> path.first().viewId == viewId }
    }

    /**
     * The level [key], or null while it is fetched; the view is redrawn once it is here. Fetching a
     * view's roots tells the extension the view is visible, and fetching an item's children that it
     * is expanded, as opening either does in VS Code.
     */
    @Suppress("DeferredResultUnused") // The level arrives in [levels], and the redraw shows it.
    fun children(key: Key): TreeChildrenDto? = levels[key] ?: run { fetch(key); null }

    /**
     * The level [key], fetched if it has not been. Asked twice when the tree is replaced meanwhile,
     * as it is when the first fetch is what wakes the extension; null if it is again.
     */
    private suspend fun load(key: Key): TreeChildrenDto? =
        levels[key] ?: fetch(key).await() ?: levels[key] ?: fetch(key).await()

    private fun fetch(key: Key): Deferred<TreeChildrenDto?> {
        fetching[key]?.let { return it }
        val fetch = scope.async(start = CoroutineStart.LAZY) {
            val generation = generations[key.viewId]
            try {
                if (key.handle == null) hosted.setVisible(key.viewId, true)
                else hosted.setExpanded(key.viewId, key.handle, true)
                val answer = try {
                    hosted.children(key.viewId, key.handle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.info("Could not read the items of view ${key.viewId}", failure)
                    TreeChildrenDto(problem = HostedStatusBar.reason(failure))
                }
                // Fetched from a tree that has since been replaced -- as the first fetch is, when it
                // is what wakes the extension and its provider registers -- so asked for again on
                // the redraw.
                if (generations[key.viewId] != generation) return@async null
                // The host forgets every handle it gave out when a view's roots are fetched again.
                if (key.handle == null) forget(key.viewId)
                answer.items.forEach { items[Key(key.viewId, it.handle)] = it }
                levels[key] = answer
                answer
            } finally {
                fetching -= key
                redraw()
            }
        }
        val running = fetching.putIfAbsent(key, fetch)
        if (running != null) {
            fetch.cancel()
            return running
        }
        fetch.start()
        return fetch
    }

    /** Redraws the Project view, once for whatever arrives together. */
    fun redraw() {
        if (!redrawing.compareAndSet(false, true)) return
        scope.launch {
            delay(REDRAW_MILLIS.milliseconds)
            redrawing.set(false)
            asClient { pane()?.updateFromRoot(true) }
        }
    }

    /**
     * Runs [action] on the EDT as the split-mode frontend: the Project view is one per client, and
     * the host's own, which a coroutine here gets without this, is on nobody's screen.
     */
    private fun asClient(action: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            CodeBridgeTextEditors.getInstance(project).asClient { if (!project.isDisposed) action() }
        }
    }

    private fun pane() = ProjectView.getInstance(project).getProjectViewPaneById(ProjectViewPane.ID)

    fun run(key: Key) {
        val handle = key.handle ?: return
        scope.launch { hosted.run(key.viewId, handle) }
    }

    fun runWelcomeLink(viewId: String, link: Int) {
        scope.launch { hosted.runWelcomeLink(viewId, link) }
    }

    /**
     * The menu of the nodes in [selected]: the `view/item/context` one of the first item, run on it
     * and the other items of its view selected beside it; or a view's `view/title` one.
     */
    fun menu(selected: List<Node<*>>): List<AnAction> {
        val first = selected.firstOrNull() ?: return emptyList()
        val viewId = first.viewId
        val handle = (first as? ItemNode)?.value?.handle
        val entries = if (handle != null) hosted.itemActions(viewId, handle)
        else if (first is ViewNode) view(viewId)?.actions.orEmpty()
        else return emptyList()
        val selection =
            selected.filterIsInstance<ItemNode>().filter { it.viewId == viewId }.mapNotNull { it.value.handle }
        return TreeMenus.grouped(entries, ::icon) { entry ->
            scope.launch { hosted.runAction(viewId, entry.id, handle, if (handle == null) emptyList() else selection) }
        }
    }

    private fun icon(entry: TreeActionDto) = icon(entry.icon, entry.iconDark)

    /** The icon an extension named, or null while its image is fetched; drawn once it is here. */
    fun icon(light: String?, dark: String?) = TreeIcons.icon(hosted.source, scope, light, dark, ::redraw)

    companion object {
        private val LOG = Logger.getInstance(ExplorerViews::class.java)
        private const val REDRAW_MILLIS = 50L

        /** VS Code's Explorer, whose views extensions add below its file tree. */
        const val EXPLORER: String = "explorer"

        fun belongsHere(view: TreeViewDto): Boolean =
            view.containerId == EXPLORER && !view.panel && !view.webview && view.visible

        fun getInstance(project: Project): ExplorerViews = project.service()
    }
}

/** Adds [ExplorerViews] to the root of the Project view, beside External Libraries. */
internal class ExplorerViewsStructure : TreeStructureProvider, DumbAware {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        if (parent !is ProjectViewProjectNode) return children
        val project = parent.project ?: return children
        val views = ExplorerViews.getInstance(project).views
        if (views.isEmpty()) return children
        return children + views.map { ViewNode(project, it.id, settings) }
    }
}

/** A node of [ExplorerViews], of view [viewId]. */
internal sealed class Node<T : Any>(project: Project, value: T, settings: ViewSettings?) :
    ProjectViewNode<T>(project, value, settings) {

    abstract val viewId: String
    protected val views get() = ExplorerViews.getInstance(myProject)

    override fun contains(file: VirtualFile): Boolean = false
    override fun getRoots(): Collection<VirtualFile> = emptyList()
    override fun isIncludedInExpandAll(): Boolean = false

    /** The level of [key] as nodes: its items, why there are none, or what the view says while it has none. */
    protected fun nodes(key: ExplorerViews.Key): Collection<AbstractTreeNode<*>> {
        val level = views.children(key) ?: return listOf(MessageNode(myProject, Message(key, 0, LOADING), settings))
        level.problem?.let { return listOf(MessageNode(myProject, Message(key, 0, it), settings)) }
        if (level.items.isEmpty() && key.handle == null) {
            return views.view(viewId)?.welcome.orEmpty().mapIndexed { index, line ->
                WelcomeNode(myProject, Welcome(viewId, index, line), settings)
            }
        }
        return level.items.mapIndexed { index, item ->
            ItemNode(myProject, ExplorerViews.Key(viewId, item.handle), settings, index)
        }
    }
}

/** A view, beside External Libraries. */
internal class ViewNode(project: Project, viewId: String, settings: ViewSettings?) :
    Node<ExplorerViews.Key>(project, ExplorerViews.Key(viewId, null), settings) {

    override val viewId: String get() = value.viewId

    override fun contains(file: VirtualFile): Boolean = views.holds(value, file)

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val message = views.view(viewId)?.message?.let { StringUtil.stripHtml(it, false).trim() }?.ifEmpty { null }
        val items = nodes(value)
        return if (message == null) items else listOf(
            MessageNode(
                myProject,
                Message(value, -1, message),
                settings
            )
        ) + items
    }

    override fun update(presentation: PresentationData) {
        val view = views.view(viewId) ?: return
        presentation.presentableText = view.title ?: view.name
        // One icon for every view, rather than the view's own: VS Code shows none on an Explorer
        // section, keeping it for a view moved to a container of its own, and Java Projects'
        // `$(project)` made the node look like the projects listed in it.
        presentation.setIcon(AllIcons.Actions.DependencyAnalyzer)
        presentation.locationString =
            listOfNotNull(view.description, view.badge?.toString()).joinToString("  ").ifEmpty { null }
        presentation.tooltip = view.message
    }

    override fun getSortOrder(settings: NodeSortSettings): NodeSortOrder = NodeSortOrder.LIBRARY_ROOT
}

/**
 * One item of a view. Kept in the order the extension gave, whatever the Project view sorts by:
 * a level of a tree view is a list, not a folder.
 */
internal class ItemNode(project: Project, key: ExplorerViews.Key, settings: ViewSettings?, private val index: Int) :
    Node<ExplorerViews.Key>(project, key, settings) {

    override val viewId: String get() = value.viewId
    private val item get() = views.item(value)

    /** On the way to the item the extension last revealed for [file]; how Select Opened File gets there. */
    override fun contains(file: VirtualFile): Boolean = views.holds(value, file)

    /** The file, or its PSI, which is what the Project view looks for when selecting an opened file. */
    override fun canRepresent(element: Any?): Boolean {
        val file = element as? VirtualFile ?: (element as? PsiFileSystemItem)?.virtualFile
        val represents = file != null && views.represents(value, file)
        if (represents) Logger.getInstance(ExplorerViews::class.java).info("Reached ${file.name} in view $viewId")
        return represents || super.canRepresent(element)
    }

    override fun getChildren(): Collection<AbstractTreeNode<*>> =
        if (item?.expandable == true) nodes(value) else emptyList()

    override fun isAlwaysLeaf(): Boolean = item?.expandable != true

    /** The item's file, so that the IDE's own actions -- Copy Path, Open In -- work on it, as VS Code's do. */
    override fun getVirtualFile(): VirtualFile? =
        item?.resourcePath?.let {
            runCatching {
                LocalFileSystem.getInstance().findFileByNioFile(Path.of(it))
            }.getOrNull()
        }

    override fun update(presentation: PresentationData) {
        val item = item ?: return
        presentation.presentableText = item.label
        presentation.locationString = item.description
        presentation.tooltip = item.tooltip
        presentation.setIcon(icon(item))
    }

    private fun icon(item: TreeItemDto) = views.icon(item.icon, item.iconDark)
        ?: virtualFile?.let { IconUtil.getIcon(it, Iconable.ICON_FLAG_READ_STATUS, myProject) }
        ?: if (item.folder) AllIcons.Nodes.Folder else null

    /** Selecting an item runs the command the extension attached to it; one without opens its file. */
    override fun canNavigate(): Boolean = item?.hasCommand == true || virtualFile?.isDirectory == false
    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        if (item?.hasCommand == true) views.run(value)
        else virtualFile?.takeUnless { it.isDirectory }
            ?.let { OpenFileDescriptor(myProject, it).navigate(requestFocus) }
    }

    override fun getTypeSortWeight(sortByType: Boolean): Int = 0
    override fun getSortKey(): Comparable<*> = index
    override fun getTypeSortKey(): Comparable<*> = index
    override fun getManualOrderKey(): Comparable<*> = index
    override fun getWeight(): Int = 0
}

/** Text in place of a level's items: loading, a problem, or the view's message. [index] keeps two apart. */
internal data class Message(val key: ExplorerViews.Key, val index: Int, val text: String)

internal class MessageNode(project: Project, message: Message, settings: ViewSettings?) :
    Node<Message>(project, message, settings) {

    override val viewId: String get() = value.key.viewId
    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun isAlwaysLeaf(): Boolean = true

    override fun update(presentation: PresentationData) {
        presentation.addText(value.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun getTypeSortWeight(sortByType: Boolean): Int = 0
    override fun getSortKey(): Comparable<*> = value.index
    override fun getTypeSortKey(): Comparable<*> = value.index
    override fun getManualOrderKey(): Comparable<*> = value.index
}

/** Line [index] of a view's welcome content, which VS Code shows while it has no items. */
internal data class Welcome(val viewId: String, val index: Int, val line: WelcomeLineDto)

/** A welcome line; one with a link -- a button in VS Code -- follows the first when opened. */
internal class WelcomeNode(project: Project, welcome: Welcome, settings: ViewSettings?) :
    Node<Welcome>(project, welcome, settings) {

    override val viewId: String get() = value.viewId
    private val link get() = value.line.parts.firstOrNull { it.link != null || it.url != null }

    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun isAlwaysLeaf(): Boolean = true

    override fun update(presentation: PresentationData) {
        for ((text, link, url) in value.line.parts) {
            val linked = link != null || url != null
            presentation.addText(
                text,
                if (linked) SimpleTextAttributes.LINK_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
            )
        }
    }

    override fun canNavigate(): Boolean = link != null
    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val part = link ?: return
        part.link?.let { views.runWelcomeLink(viewId, it) } ?: part.url?.let(BrowserUtil::browse)
    }

    override fun getTypeSortWeight(sortByType: Boolean): Int = 0
    override fun getSortKey(): Comparable<*> = value.index
    override fun getTypeSortKey(): Comparable<*> = value.index
    override fun getManualOrderKey(): Comparable<*> = value.index
}

/**
 * Lets the Project view select a file only an [ExplorerViews] item stands for: a class in a library
 * jar that Java's language server provides as a `jdt:` document. Without it Select Opened File turns
 * such a file away before asking any node, as being neither in the project nor in a library.
 */
internal class ExplorerViewsSelectable : ProjectPaneSelectableFileContributor {
    override fun isSelectable(project: Project, virtualFile: VirtualFile): Boolean {
        val supported = project.serviceIfCreated<ExplorerViews>()?.revealed(virtualFile) == true
        if (supported) Logger.getInstance(ExplorerViews::class.java)
            .info("Selecting ${virtualFile.name} through an Explorer view")
        return supported
    }
}

/** The extension's menu for the [ExplorerViews] node selected, in the Project view's context menu. */
internal class ExplorerViewsMenu : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val project = event?.project ?: return EMPTY_ARRAY
        val views = project.serviceIfCreated<ExplorerViews>() ?: return EMPTY_ARRAY
        val selected = event.getData(PlatformCoreDataKeys.SELECTED_ITEMS).orEmpty().filterIsInstance<Node<*>>()
        val actions = views.menu(selected)
        if (actions.isEmpty()) return EMPTY_ARRAY
        return (actions + Separator.getInstance()).toTypedArray()
    }
}

private const val LOADING = "Loading…"
