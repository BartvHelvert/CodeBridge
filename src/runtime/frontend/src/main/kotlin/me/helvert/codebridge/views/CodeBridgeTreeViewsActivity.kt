package me.helvert.codebridge.views

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.platform.project.projectId
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.messages.CodeBridgeQuickInputs
import me.helvert.codebridge.rpc.TreeEventDto
import me.helvert.codebridge.rpc.TreeViewDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.lang.Runnable
import javax.swing.Icon
import kotlin.time.Duration.Companion.milliseconds

/**
 * The tree views VS Code extensions contribute, as IntelliJ tool windows.
 *
 * One tool window per view container -- an extension's activity bar or panel entry, or one of VS
 * Code's own such as the Explorer -- with its views stacked in it as VS Code does; see [ViewStack]. The windows come and go with the extensions that contribute them, so they
 * are registered here rather than declared in the plugin descriptor.
 *
 * Lives in the frontend because that is where tool windows are in split mode; the trees' contents
 * are the backend's, fetched a level at a time over RPC.
 */
internal class CodeBridgeTreeViewsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val windows = TreeViewWindows(project)
        CodeBridgeTreeViewsScope.getInstance(project).windows = windows
        coroutineScope {
            launch { watchViews(project, windows) }
            launch { watchEvents(project, windows) }
        }
    }

    /** Reconnects, as the status bar does: a backend that restarts ends the flow. */
    private suspend fun watchViews(project: Project, windows: TreeViewWindows) = retrying("tree views") {
        CodeBridgeApi.getInstance().treeViews(project.projectId()).collect { views ->
            withContext(Dispatchers.EDT) { drawing("tree views") { windows.update(views) } }
        }
    }

    private suspend fun watchEvents(project: Project, windows: TreeViewWindows) = retrying("tree view changes") {
        CodeBridgeApi.getInstance().treeEvents(project.projectId()).collect { event ->
            withContext(Dispatchers.EDT) { drawing("a tree view change") { windows.changed(event) } }
        }
    }

    private suspend fun retrying(what: String, block: suspend () -> Unit) = coroutineScope {
        while (isActive) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.debug("Lost the $what; retrying", error)
            }
            delay(RETRY_MILLIS.milliseconds)
        }
    }

    /**
     * Runs [block], logging rather than rethrowing what it throws.
     *
     * A failure to draw is not a lost connection. Rethrown, it reached [retrying], which collected
     * the stream again and so drew it again -- registering another tool window every two seconds.
     */
    private fun drawing(what: String, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LOG.warn("Could not draw $what", error)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeTreeViewsActivity::class.java)
        private const val RETRY_MILLIS = 2_000L
    }
}

/**
 * The tool windows [update] has registered, the stack of views in each, and each view's panel. Used on the EDT only,
 * but for [mayHold].
 */
internal class TreeViewWindows(private val project: Project) {
    private val log = Logger.getInstance(TreeViewWindows::class.java)
    private val windows = mutableMapOf<String, ToolWindow>()
    private val containerIcons = mutableMapOf<String, Icon>()
    private var containerBadges: Map<String, Int> = emptyMap()
    private val stacks = mutableMapOf<String, ViewStack>()
    private val panels = mutableMapOf<String, CodeBridgeViewTab>()

    /** Containers whose registration failed, which are not tried again: a failed attempt may still leave a button. */
    private val failed = mutableSetOf<String>()
    private val scope: CoroutineScope get() = CodeBridgeTreeViewsScope.getInstance(project).scope
    private val source = RpcTreeViewSource(project)

    /** The tree views' tabs as [update] last left them, for [mayHold] off the EDT. */
    @Volatile
    private var trees: List<TreeViewTab> = emptyList()

    /** Every view [update] was last given, hidden ones too, for the containers' menus and to draw again. */
    @Volatile
    private var latest: List<TreeViewDto> = emptyList()

    private val properties = PropertiesComponent.getInstance(project)

    init {
        project.messageBus.connect(CodeBridgeTreeViewsScope.getInstance(project)).subscribe(ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) = shownChanged()
            })
    }

    fun update(all: List<TreeViewDto>) {
        if (project.isDisposed) return
        latest = all
        containerBadges = containerBadgeCounts(all)
        // A view the user or its extension hid goes from its stack, and its tool window with the last of them.
        val views = offered(all).filterNot(::hidden)
        val byContainer = views.groupBy { it.containerId }
        for (gone in panels.keys - views.map { it.id }.toSet()) {
            panels.remove(gone)?.let { panel ->
                stacks[panel.view.containerId]?.remove(panel)
                Disposer.dispose(panel)
            }
        }
        for ((containerId, window) in windows.toList()) {
            if (containerId in byContainer) continue
            windows.remove(containerId)
            containerIcons.remove(containerId)
            stacks.remove(containerId)
            window.remove()
        }
        for ((containerId, members) in byContainer) {
            if (containerId in failed) continue
            val window = windows[containerId] ?: register(members.first()) ?: continue
            val stack = stacks[containerId] ?: continue
            for (view in members) {
                val panel = panels[view.id]
                if (panel == null) add(window, stack, view)
                else {
                    panel.update(view)
                    stack.refresh(panel)
                }
            }
            stack.arrange(members.mapNotNull { panels[it.id] })
            showTitle(window, stack)
            updateIcon(containerId, window)
        }
        trees = panels.values.filterIsInstance<TreeViewTab>()
        shownChanged()
    }

    /**
     * The views a tool window may show, hidden or not. Not those of VS Code's Run and Debug: they are
     * shown in the debug sessions of the extension's debugger instead, see `CodeBridgeDebugViewTabs` -- but
     * for a webview view, since a debug session's tabs are the backend's, and the browser drawing it
     * is here. Not one whose `when` clause does not hold, as in VS Code. And not the tree views of VS
     * Code's Explorer, which are in the Project view beside External Libraries; the backend puts them there.
     */
    private fun offered(all: List<TreeViewDto>): List<TreeViewDto> =
        all.filter { it.visible && (it.panel || it.containerId != DEBUG || it.webview) }
            .filterNot { it.containerId == EXPLORER && !it.panel && !it.webview }

    /** Whether [view] is left out of its stack: as the user last chose, else as its extension declared. */
    private fun hidden(view: TreeViewDto): Boolean =
        properties.getValue(hiddenKey(view.id))?.toBoolean() ?: view.hiddenByDefault

    /**
     * A checkable entry per view of [containerId], as VS Code lists them in its container's "..."
     * menu: unchecking one takes it out of the stack, checking it puts it back. The last one shown
     * cannot be unchecked, or the window would go and its menu with it.
     */
    private inner class ViewsMenu(private val containerId: String) : ActionGroup(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val members = offered(latest).filter { it.containerId == containerId }
            if (members.size < 2) return EMPTY_ARRAY
            val actions = members.map { view ->
                object : ToggleAction(view.name), DumbAware {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT

                    override fun isSelected(e: AnActionEvent) = !hidden(view)

                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        properties.setValue(hiddenKey(view.id), (!state).toString())
                        this@TreeViewWindows.update(latest)
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        val last = members.count { !hidden(it) } == 1 && !hidden(view)
                        e.presentation.isEnabled = !last
                    }
                }
            }
            return arrayOf(Separator.create(), *actions.toTypedArray())
        }
    }

    /** Whether a tree view may hold file [path]; see [CodeBridgeTreePanel.mayHold]. Callable on any thread. */
    fun mayHold(path: String): Boolean = trees.any { it.panel.mayHold(path) }

    /**
     * Shows file [path] in a tree view that holds it: the one on screen if it does, else the first
     * that may, opened in its stack and its window opened.
     */
    fun select(path: String, focus: Boolean) {
        val candidates = trees.filter { it.panel.mayHold(path) }
        val tab = candidates.firstOrNull { shown(it) } ?: candidates.firstOrNull() ?: return
        val window = windows[tab.view.containerId]?.takeUnless { it.isDisposed } ?: return
        stacks[tab.view.containerId]?.open(tab)
        val reveal = Runnable {
            scope.launch(Dispatchers.EDT) {
                if (!tab.panel.select(path, focus)) log.info("No item of view ${tab.view.id} leads to $path")
            }
        }
        if (focus) window.activate(reveal) else window.show(reveal)
    }

    private fun shown(tab: CodeBridgeViewTab): Boolean {
        val window = windows[tab.view.containerId] ?: return false
        return !window.isDisposed && window.isVisible && stacks[tab.view.containerId]?.isOpen(tab) == true
    }

    fun changed(event: TreeEventDto) {
        val panel = panels[event.viewId] ?: return
        if (event.kind == TreeEventDto.FOCUS || event.kind == TreeEventDto.REVEAL && event.focus) {
            windows[panel.view.containerId]?.let { window ->
                stacks[panel.view.containerId]?.open(panel)
                window.activate(null)
            }
        }
        panel.changed(event)
    }

    /**
     * The tool window for [view]'s container, registered unless the manager already has one by its
     * id, and remembered before anything else is done with it: whatever fails after this must not
     * lead to registering it a second time.
     */
    private fun register(view: TreeViewDto): ToolWindow? {
        val id = "CodeBridge.${view.containerId}"
        val manager = ToolWindowManager.getInstance(project)
        manager.getToolWindow(id)?.let { existing ->
            windows[view.containerId] = existing
            return existing
        }
        val window = try {
            val (anchor, split) = placement(view)
            // Not the `registerToolWindow(id) { ... }` builder: it is inline, so its call to the
            // override-only `registerToolWindow(task)` lands in this class, and the Plugin Verifier
            // reports it. This overload is deprecated in favour of `ToolWindowFactory`, which cannot
            // declare windows that come from whatever extensions are installed. Its disposable is
            // unused by the platform.
            @Suppress("DEPRECATION")
            manager.registerToolWindow(
                id,
                false,
                anchor,
                CodeBridgeTreeViewsScope.getInstance(project),
                true,
                split,
            ).apply {
                setToHideOnEmptyContent(false)
                @Suppress("UsePropertyAccessSyntax") // `icon` reads as nullable, so it cannot be assigned
                setIcon(TreeIcons.placeholder)
                stripeTitle = view.containerTitle
            }
        } catch (error: Exception) {
            log.warn("Could not register the tool window for ${view.containerId}", error)
            // Registered as far as the manager is concerned, or not at all; either way, not again.
            val registered = manager.getToolWindow(id)
            if (registered != null) windows[view.containerId] = registered else failed += view.containerId
            return registered
        }
        windows[view.containerId] = window
        containerIcons[view.containerId] = TreeIcons.placeholder
        updateIcon(view.containerId, window)
        TreeIcons.load(source, scope, view.containerIcon) { icon ->
            if (!window.isDisposed && windows[view.containerId] === window) {
                containerIcons[view.containerId] = icon
                updateIcon(view.containerId, window)
            }
        }
        window.setAdditionalGearActions(ViewsMenu(view.containerId))
        val stack = ViewStack(project, ::shownChanged)
        stacks[view.containerId] = stack
        val content = ContentFactory.getInstance().createContent(stack, "", false)
        content.isCloseable = false
        window.contentManager.addContent(content)
        return window
    }

    @Suppress("UsePropertyAccessSyntax") // `icon` reads as nullable, so it cannot be assigned
    private fun updateIcon(containerId: String, window: ToolWindow) {
        if (window.isDisposed) return
        val base = containerIcons[containerId] ?: return
        window.setIcon(CountBadgeIcon.withCount(base, containerBadges[containerId] ?: 0))
    }

    /**
     * Where a container's tool window starts out, by what the container is for; the user can move it,
     * and IntelliJ remembers where.
     *
     * VS Code shows the views extensions add to its Explorer and Source Control as sections below
     * the file tree, so those go below the Project view: left, split. Its Testing views matter
     * during a run, which IntelliJ shows at the bottom, so they go beside the Run window there; Run
     * and Debug views never get here, being tabs of the debug session itself. A container of an extension's own is an auxiliary tool, which IntelliJ
     * keeps on the right, leaving the left to the IDE's navigation; a panel one goes in the bottom
     * panel, where VS Code has it.
     */
    private fun placement(view: TreeViewDto): Pair<ToolWindowAnchor, Boolean> = when {
        view.panel -> ToolWindowAnchor.BOTTOM to false
        view.containerId in SIDE_BAR_SECTIONS -> ToolWindowAnchor.LEFT to true
        view.containerId in SESSION_VIEWS -> ToolWindowAnchor.BOTTOM to true
        else -> ToolWindowAnchor.RIGHT to false
    }

    private fun add(window: ToolWindow, stack: ViewStack, view: TreeViewDto) {
        // Its title actions go in its header in the stack, or the tool window's if it is alone; see [showTitle].
        val panel = tab(view)
        panel.titleActionsChanged = {
            stack.refresh(panel)
            showTitle(window, stack)
        }
        panels[view.id] = panel
        // Disposed with the window's content, if the window goes first.
        window.contentManager.contents.firstOrNull()?.let { Disposer.register(it, panel) }
        stack.add(panel)
    }

    /** A tree, or for a webview view the page the webview module draws, if it is loaded. */
    private fun tab(view: TreeViewDto): CodeBridgeViewTab =
        if (!view.webview) TreeViewTab(CodeBridgeTreePanel(source, scope, view, ownToolbar = false).apply {
            clicked = { button -> project.service<CodeBridgeQuickInputs>().anchorNextPick(above = false) { button } }
        })
        else CodeBridgeWebviewViewFactory.EP.extensionList.firstOrNull()?.create(project, scope, view) ?: UndrawableWebviewTab(view)

    /**
     * A view alone in its window in the window's header, as VS Code shows one: its title beside the
     * window's, and its title actions on the right. Views stacked have their own headers instead.
     */
    private fun showTitle(window: ToolWindow, stack: ViewStack) {
        if (window.isDisposed) return
        val single = stack.single
        window.contentManager.contents.firstOrNull()?.let { content ->
            content.displayName = single?.view?.tabTitle.orEmpty()
            content.description = single?.view?.description
        }
        window.setTitleActions(single?.titleActions.orEmpty())
    }

    /** Tells each panel whether it is on screen: its window open and it not collapsed in its stack. */
    private fun shownChanged() {
        for (panel in panels.values) {
            if (panel.view.containerId in windows) panel.setShown(shown(panel))
        }
    }
}

private fun hiddenKey(viewId: String) = "CodeBridge.view.hidden.$viewId"

/** VS Code's own containers whose views it shows as sections below the file tree. */
private val SIDE_BAR_SECTIONS = setOf("explorer", "scm")

/** VS Code's own containers whose views belong to a test run. */
private val SESSION_VIEWS = setOf("test")

/** VS Code's Run and Debug container, whose views are tabs of the debug session instead. */
private const val DEBUG = "debug"

/** VS Code's Explorer, whose tree views are in the Project view; see the backend's `ExplorerViews`. */
private const val EXPLORER = "explorer"

/**
 * The project's tree views over RPC: the backend has them, and this is the frontend. Public for the
 * webview module, whose views' title actions run as a tree view's do.
 */
class RpcTreeViewSource(private val project: Project) : TreeViewSource {
    override suspend fun children(viewId: String, handle: String?) =
        CodeBridgeApi.getInstance().treeChildren(project.projectId(), viewId, handle)
    override suspend fun visible(viewId: String, visible: Boolean) =
        CodeBridgeApi.getInstance().treeViewVisible(project.projectId(), viewId, visible)
    override suspend fun expanded(viewId: String, handle: String, expanded: Boolean) =
        CodeBridgeApi.getInstance().treeItemExpanded(project.projectId(), viewId, handle, expanded)
    override suspend fun selection(viewId: String, handles: List<String>, focus: String?) =
        CodeBridgeApi.getInstance().treeSelection(project.projectId(), viewId, handles, focus)
    override suspend fun run(viewId: String, handle: String) =
        CodeBridgeApi.getInstance().runTreeItem(project.projectId(), viewId, handle)
    override suspend fun icon(key: String): ByteArray? = CodeBridgeApi.getInstance().treeIcon(project.projectId(), key)
    override suspend fun itemActions(viewId: String, handle: String) =
        CodeBridgeApi.getInstance().treeItemActions(project.projectId(), viewId, handle)
    override suspend fun runAction(viewId: String, actionId: Int, handle: String?, selection: List<String>) =
        CodeBridgeApi.getInstance().runTreeAction(project.projectId(), viewId, actionId, handle, selection)
    override suspend fun runWelcomeLink(viewId: String, link: Int) =
        CodeBridgeApi.getInstance().runWelcomeLink(project.projectId(), viewId, link)
    override suspend fun checkboxes(viewId: String, states: Map<String, Boolean>) =
        CodeBridgeApi.getInstance().treeCheckboxes(project.projectId(), viewId, states)
}

/** The coroutines and listeners of a project's tree views, ended with the project. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
internal class CodeBridgeTreeViewsScope(val scope: CoroutineScope) : Disposable {
    /** The project's tree view windows, once the views are being watched. */
    @Volatile
    var windows: TreeViewWindows? = null

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): CodeBridgeTreeViewsScope =
            project.getService(CodeBridgeTreeViewsScope::class.java)
    }
}
