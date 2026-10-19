package me.helvert.codebridge.views

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DataManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.RowIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.*
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.views.CodeBridgeTreePanel.Companion.BUTTON_WIDTH
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * One extension tree view: the items its provider gives, fetched a level at a time as they are
 * opened, as VS Code fetches them.
 *
 * Nothing is fetched until the view is on screen, because the first fetch is what wakes the
 * extension. Selecting an item with the mouse or Enter runs the command the extension attached to
 * it, as a single click does in VS Code -- which is usually how such a view opens a file.
 *
 * An item whose `resourceUri` is a file stands for that file to the IDE's own actions -- Copy
 * Path, Select In and the like -- and [select] finds a file in the tree by those, as a dependency
 * view such as rust-analyzer's holds the library files that nothing else in the IDE lists.
 *
 * Used on the EDT only, but for [mayHold]; the fetches themselves run on the backend.
 */
class CodeBridgeTreePanel(
    private val source: TreeViewSource,
    parentScope: CoroutineScope,
    view: TreeViewDto,
    /**
     * Whether the view's title actions are a toolbar of the panel's own. Not in a tool window, whose
     * header has room for them on the row of its tabs -- see [titleActions] -- but a debug session's
     * tab has no such header.
     */
    private val ownToolbar: Boolean = true,
) : SimpleToolWindowPanel(true, true), Disposable {

    /** Told when [titleActions] changes, for whatever shows them outside the panel. */
    var titleActionsChanged: () -> Unit = {}

    /**
     * Told of the component a welcome link or button was clicked on, before its command runs, so a
     * picker the command opens can open beside it.
     */
    var clicked: (JComponent) -> Unit = {}

    /** The view's title actions as they stand now; see [titleActionsFor]. */
    var titleActions: List<AnAction> = emptyList()
        private set

    var view: TreeViewDto = view
        private set

    /** An item and whether its children have been fetched. */
    private class ItemNode(var item: TreeItemDto) : DefaultMutableTreeNode(item) {
        var loaded = false
        var loading: Job? = null
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = ItemTree(model)
    private val message = JBLabel().apply { border = JBUI.Borders.empty(6, 8); isVisible = false }

    /** The tree, or in its place the view's welcome content while it has no items. */
    private val cards = JPanel(CardLayout())
    private val welcome = Welcome()
    private var shown = false
    /** The generation the roots on screen were fetched for, or null before the first fetch. */
    private var loadedGeneration: Int? = null
    private var rootLoad: Job? = null
    /** Items open in the tree, by handle, so a fetch of the whole tree can open them again. */
    private val expanded = mutableSetOf<String>()

    /**
     * The files of the items in the tree now, for [mayHold] to read off the EDT. Made again from the
     * tree whenever it changes, so that an item the provider replaced or dropped does not linger.
     */
    @Volatile
    private var resources: Set<String> = emptySet()
    /** What the toolbar was last built from, so it is rebuilt only when that changes. */
    private var toolbarFor: Pair<Boolean, List<TreeActionDto>>? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        ToolTipManager.sharedInstance().registerComponent(tree)
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? ItemNode ?: return
                if (!node.loaded && node.loading == null) scope.launch(Dispatchers.EDT) { load(node) }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = expansion(event, true)
            override fun treeCollapsed(event: TreeExpansionEvent) = expansion(event, false)
        })
        tree.addTreeSelectionListener {
            val handles = tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? ItemNode)?.item?.handle }
            val focus = (tree.leadSelectionPath?.lastPathComponent as? ItemNode)?.item?.handle
            call { selection(this@CodeBridgeTreePanel.view.id, handles, focus) }
        }
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = if (event.isPopupTrigger) showMenu(event) else Unit
            override fun mouseReleased(event: MouseEvent) = if (event.isPopupTrigger) showMenu(event) else Unit

            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 1) return
                tree.inlineAt(event.x, event.y)?.let { (node, entry) -> return runInline(node, entry) }
                // Null over the expand handle, which only opens the item.
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                if (tree.onCheckbox(path, event.x)) return toggle(path.lastPathComponent as ItemNode)
                run(path)
            }

            override fun mouseMoved(event: MouseEvent) = tree.hover(event.point)

            override fun mouseExited(event: MouseEvent) = tree.hover(null)
        }
        tree.addMouseListener(mouse)
        tree.addMouseMotionListener(mouse)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when (event.keyCode) {
                    KeyEvent.VK_ENTER -> tree.selectionPath?.let(::run)
                    // Space ticks a checkbox, as it does in VS Code.
                    KeyEvent.VK_SPACE -> (tree.selectionPath?.lastPathComponent as? ItemNode)?.takeIf { it.item.checked != null }?.let(::toggle)
                }
            }
        })
        cards.add(ScrollPaneFactory.createScrollPane(tree, true), TREE)
        cards.add(ScrollPaneFactory.createScrollPane(welcome.wrapper, true), WELCOME)
        val body = JPanel(BorderLayout())
        body.add(message, BorderLayout.NORTH)
        body.add(cards, BorderLayout.CENTER)
        @Suppress("UsePropertyAccessSyntax") // `content` reads as nullable, so it cannot be assigned
        setContent(body)
        update(view)
    }

    fun update(view: TreeViewDto) {
        val previous = this.view
        this.view = view
        tree.selectionModel.selectionMode =
            if (view.canSelectMany) TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION else TreeSelectionModel.SINGLE_TREE_SELECTION
        message.text = view.message?.let(::html)
        message.isVisible = view.message != null
        val wanted = view.showCollapseAll to view.actions
        if (wanted != toolbarFor) {
            toolbarFor = wanted
            titleActions = titleActionsFor(view)
            if (ownToolbar) toolbar = titleActions.takeIf { it.isNotEmpty() }?.let { actions ->
                ActionManager.getInstance().createActionToolbar("CodeBridge.TreeView", DefaultActionGroup(actions), true)
                    .apply { targetComponent = tree }.component
            }
            else titleActionsChanged()
        }
        if (shown && (loadedGeneration != view.generation || previous.registered != view.registered)) reload()
        else if (loadedGeneration != null && view.welcome != previous.welcome) showEmpty()
    }

    fun setShown(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        call { visible(view.id, shown) }
        if (shown && loadedGeneration != view.generation) reload()
    }

    /** Applies a change the provider made inside the tree. */
    fun changed(event: TreeEventDto) {
        when (event.kind) {
            TreeEventDto.REFRESH -> {
                event.items.forEach(::refresh)
                rememberResources()
            }

            TreeEventDto.REVEAL -> scope.launch(Dispatchers.EDT) { reveal(event) }
            TreeEventDto.COLLAPSE_ALL -> collapseAll()
            TreeEventDto.FOCUS -> tree.requestFocusInWindow()
        }
    }

    /** Puts [item] in place of the item by its handle, to fetch its children again when it is opened. */
    private fun refresh(item: TreeItemDto) {
        val node = find(item.handle) ?: return
        node.item = item
        node.userObject = item
        node.loading?.cancel()
        node.loading = null
        node.loaded = false
        val open = tree.isExpanded(TreePath(node.path))
        node.removeAllChildren()
        if (item.expandable) node.add(DefaultMutableTreeNode(LOADING))
        model.nodeStructureChanged(node)
        if (open && item.expandable) tree.expandPath(TreePath(node.path))
    }

    private fun rememberResources() {
        resources = TreeUtil.treeNodeTraverser(root).traverse().filterIsInstance<ItemNode>()
            .mapNotNull { it.item.resourcePath }.toSet()
    }

    /**
     * The view's `view/title` menu: its `navigation` entries as buttons, as VS Code shows them in the
     * view's title, and the rest behind a "more" button; and Collapse All when the extension asked.
     */
    private fun titleActionsFor(view: TreeViewDto): List<AnAction> {
        val (buttons, more) = view.actions.partition { it.group == TreeActionDto.NAVIGATION }
        val actions = mutableListOf<AnAction>()
        buttons.forEach { actions += action(it) }
        if (view.showCollapseAll) actions += CommonActionsManager.getInstance().createCollapseAllAction(DefaultTreeExpander(tree), tree)
        if (more.isNotEmpty()) actions += DefaultActionGroup("More Actions", grouped(more, null)).apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.More
            templatePresentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)
        }
        return actions
    }

    /**
     * The item's `view/item/context` menu, at the mouse. Asked for when it opens, because which
     * entries it has depends on the extension's context keys as they are now.
     */
    private fun showMenu(event: MouseEvent) {
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        val item = (path.lastPathComponent as? ItemNode)?.item ?: return
        // As in VS Code, right-clicking outside the selection acts on that item alone.
        if (!tree.isPathSelected(path)) tree.selectionPath = path
        val point = RelativePoint(event)
        scope.launch(Dispatchers.EDT) {
            val actions = try {
                withContext(Dispatchers.Default) { source.itemActions(view.id, item.handle) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.debug("Could not read the menu of ${item.handle}", error)
                return@launch
            }
            if (actions.isEmpty()) return@launch
            JBPopupFactory.getInstance().createActionGroupPopup(
                null, DefaultActionGroup(grouped(actions, item.handle)), DataManager.getInstance().getDataContext(tree),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
            ).show(point)
        }
    }

    /** [actions] in menu order, each run for the view, or for item [handle] and whatever is selected with it. */
    private fun grouped(actions: List<TreeActionDto>, handle: String?): List<AnAction> =
        TreeMenus.grouped(actions, ::menuIcon) { entry -> runEntry(entry, handle) }

    /** [entry] as a button, run for the view. */
    private fun action(entry: TreeActionDto): AnAction =
        TreeMenus.action(entry, ::menuIcon) { runEntry(it, null) }

    private fun menuIcon(entry: TreeActionDto) = TreeIcons.icon(source, scope, entry.icon, entry.iconDark) {}

    private fun runEntry(entry: TreeActionDto, handle: String?) {
        val selection = if (handle == null) emptyList()
        else tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? ItemNode)?.item?.handle }
        call { runAction(view.id, entry.id, handle, selection) }
    }

    /** An item's inline button: on the item and the rest of the selection when it is selected, else on it alone. */
    private fun runInline(node: ItemNode, entry: TreeActionDto) {
        if (!entry.enabled) return
        val path = TreePath(node.path)
        val selection = if (tree.isPathSelected(path)) tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? ItemNode)?.item?.handle }
            else listOf(node.item.handle)
        call { runAction(view.id, entry.id, node.item.handle, selection) }
    }

    /**
     * Ticks or unticks [node]'s checkbox and tells the extension. Unless the view manages them
     * itself, the items under it that have been fetched follow it, and each item above it is ticked
     * exactly when every item under it is, as VS Code does it.
     */
    private fun toggle(node: ItemNode) {
        val checked = node.item.checked != true
        val changed = linkedMapOf<String, Boolean>()
        fun set(target: ItemNode, state: Boolean) {
            if (target.item.checked == null || target.item.checked == state) return
            target.item = target.item.copy(checked = state)
            target.userObject = target.item
            changed[target.item.handle] = state
        }
        set(node, checked)
        if (!view.manualCheckboxes) {
            TreeUtil.treeNodeTraverser(node).traverse().filterIsInstance<ItemNode>().forEach { set(it, checked) }
            var parent = node.parent
            while (parent is ItemNode) {
                val boxes = children(parent).filter { it.item.checked != null }
                if (boxes.isNotEmpty()) set(parent, boxes.all { it.item.checked == true })
                parent = parent.parent
            }
        }
        if (changed.isEmpty()) return
        tree.repaint()
        call { checkboxes(view.id, changed) }
    }

    private fun reload() {
        rootLoad?.cancel()
        rootLoad = scope.launch(Dispatchers.EDT) { loadRoots() }
    }

    private suspend fun loadRoots() {
        val generation = view.generation
        tree.emptyText.text = "Loading..."
        val answer = fetch(null)
        loadedGeneration = generation
        problem = answer.problem
        unregistered = answer.unregistered
        fill(root, answer.items)
        showEmpty()
    }

    /** Why the tree has no items, when fetching them failed; null when it simply has none. */
    private var problem: String? = null

    /** Whether [problem] is only that no provider is registered for the view. */
    private var unregistered = false

    /**
     * What the view shows with no items: the problem if there was one, in the empty tree; otherwise
     * the view's welcome content in the tree's place, laid out as VS Code lays it out. A view with no
     * provider registered shows its welcome content too, as in VS Code, as some extensions only
     * register once a link in it is clicked.
     */
    private fun showEmpty() {
        tree.emptyText.text = problem.orEmpty()
        val showWelcome = (problem == null || unregistered) && root.childCount == 0 && view.welcome.isNotEmpty()
        if (showWelcome) welcome.show(view.welcome)
        (cards.layout as CardLayout).show(cards, if (showWelcome) WELCOME else TREE)
    }

    private fun openWelcomeLink(part: WelcomePartDto, from: JComponent) {
        val link = part.link
        if (link != null) {
            clicked(from)
            call { runWelcomeLink(view.id, link) }
        } else part.url?.let(BrowserUtil::browse)
    }

    /**
     * A view's welcome content as VS Code draws it: paragraphs down the left, their links clickable,
     * and a line that is a single link a full-width button, up to [BUTTON_WIDTH].
     */
    private inner class Welcome {
        private val lines = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 20, 12, 20)
        }

        /** Holds [lines] at the top, at their own height. */
        val wrapper = JPanel(BorderLayout()).apply {
            background = UIUtil.getTreeBackground()
            add(lines, BorderLayout.NORTH)
        }
        private var shown: List<WelcomeLineDto>? = null

        fun show(content: List<WelcomeLineDto>) {
            if (content == shown) return
            shown = content
            lines.removeAll()
            for ((parts, isButton) in content) {
                lines.add(Box.createVerticalStrut(JBUI.scale(if (isButton) 6 else 10)))
                lines.add(if (isButton) button(parts.single()) else paragraph(parts))
            }
            lines.revalidate()
            lines.repaint()
        }

        private fun button(part: WelcomePartDto): JComponent = object : JButton(Codicons.strip(part.text).trim()) {
            override fun getMaximumSize() = Dimension(JBUI.scale(BUTTON_WIDTH), preferredSize.height)
        }.apply {
            icon = Codicons.references(part.text).firstOrNull()?.let(Codicons::icon)
            alignmentX = LEFT_ALIGNMENT
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            addActionListener { openWelcomeLink(part, this) }
        }

        private fun paragraph(parts: List<WelcomePartDto>): JComponent {
            val targets = mutableListOf<WelcomePartDto>()
            val body = parts.joinToString("") { part ->
                // `$(name)` is a codicon, as everywhere VS Code shows an extension's text.
                val text = Codicons.htmlIcons(StringUtil.escapeXmlEntities(part.text))
                if (part.link == null && part.url == null) text
                else "<a href=\"${targets.size}\">$text</a>".also { targets += part }
            }
            val pane = JBHtmlPane(
                JBHtmlPaneStyleConfiguration(),
                JBHtmlPaneConfiguration { iconResolver { src -> Codicons.iconOf(src) } },
            )
            pane.isEditable = false
            pane.isOpaque = false
            pane.alignmentX = LEFT_ALIGNMENT
            pane.text = "<html><body>$body</body></html>"
            pane.addHyperlinkListener { event ->
                if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
                event.description.toIntOrNull()?.let(targets::getOrNull)?.let { openWelcomeLink(it, pane) }
            }
            return pane
        }
    }

    /** `workbench.actions.treeView.<view>.collapseAll`, and the toolbar's Collapse All. */
    fun collapseAll() = TreeUtil.collapseAll(tree, 0)

    private suspend fun load(node: ItemNode) {
        node.loading?.join()
        if (node.loaded) return
        val load = scope.launch(Dispatchers.EDT) {
            val answer = fetch(node.item.handle)
            // Gone from the tree meanwhile: a refresh replaced it.
            if (node.root !== root) return@launch
            node.loaded = true
            if (answer.problem != null && answer.items.isEmpty()) {
                node.removeAllChildren()
                node.add(DefaultMutableTreeNode(answer.problem))
                model.nodeStructureChanged(node)
            } else fill(node, answer.items)
            if (node.item.handle in expanded) tree.expandPath(TreePath(node.path))
        }
        node.loading = load
        load.join()
        if (node.loading === load) node.loading = null
    }

    /** Puts [items] under [parent], opening those the extension or the user had open. */
    private fun fill(parent: DefaultMutableTreeNode, items: List<TreeItemDto>) {
        parent.removeAllChildren()
        val nodes = items.map { item ->
            ItemNode(item).also { node ->
                if (item.expandable) node.add(DefaultMutableTreeNode(LOADING))
                parent.add(node)
            }
        }
        model.nodeStructureChanged(parent)
        rememberResources()
        for (node in nodes) {
            if (node.item.expandable && (node.item.expanded || node.item.handle in expanded)) tree.expandPath(TreePath(node.path))
        }
    }

    /** Opens the path to the item the extension asked to reveal, fetching each level as needed. */
    private suspend fun reveal(event: TreeEventDto) {
        if (loadedGeneration != view.generation) {
            rootLoad?.cancel()
            loadRoots()
        }
        var parent: DefaultMutableTreeNode = root
        for (handle in event.path) {
            if (parent is ItemNode && !parent.loaded) load(parent)
            val node = children(parent).firstOrNull { it.item.handle == handle } ?: return
            if (parent is ItemNode) tree.expandPath(TreePath(parent.path))
            parent = node
        }
        val target = parent as? ItemNode ?: return
        val path = TreePath(target.path)
        if (event.select) tree.selectionPath = path
        TreeUtil.scrollToVisible(tree, path, false)
        if (event.focus) tree.requestFocusInWindow()
        // `expand` opens that many levels below the item, and VS Code stops at three.
        var level = listOf(target)
        repeat(event.expand.coerceAtMost(3)) {
            level = level.filter { it.item.expandable }.flatMap { node ->
                load(node)
                tree.expandPath(TreePath(node.path))
                children(node)
            }
        }
    }

    /**
     * Whether the items fetched so far lead to file [path]: one of them is it or a folder holding it.
     * Only a guess at what [select] will find, but one that needs no fetch. Callable on any thread.
     */
    fun mayHold(path: String): Boolean = resources.any { OSAgnosticPathUtil.startsWith(path, it) }

    /**
     * Selects file [path]: from the roots down through the items whose file is it or a folder holding
     * it, fetching each level as needed, as VS Code's Explorer reveals a file. Stops at the deepest
     * such item when the file itself is not in the tree; an item without a file is not looked into,
     * since only fetching all of it could say what it holds. Whether any item was selected.
     */
    suspend fun select(path: String, focus: Boolean): Boolean {
        if (loadedGeneration != view.generation) {
            rootLoad?.cancel()
            loadRoots()
        }
        var parent: DefaultMutableTreeNode = root
        var found: ItemNode? = null
        while (true) {
            if (parent is ItemNode && !parent.loaded) load(parent)
            // Of nested folders that are each an item, the innermost.
            val next = children(parent)
                .filter { child -> child.item.resourcePath?.let { OSAgnosticPathUtil.startsWith(path, it) } == true }
                .maxByOrNull { it.item.resourcePath!!.length } ?: break
            found = next
            if (!next.item.expandable || OSAgnosticPathUtil.COMPARATOR.compare(next.item.resourcePath, path) == 0) break
            parent = next
        }
        val target = found ?: return false
        val treePath = TreePath(target.path)
        (target.parent as? ItemNode)?.let { tree.expandPath(TreePath(it.path)) }
        tree.selectionPath = treePath
        TreeUtil.scrollToVisible(tree, treePath, false)
        if (focus) tree.requestFocusInWindow()
        return true
    }

    private fun children(parent: DefaultMutableTreeNode): List<ItemNode> =
        (0 until parent.childCount).mapNotNull { parent.getChildAt(it) as? ItemNode }

    private fun find(handle: String): ItemNode? =
        TreeUtil.treeNodeTraverser(root).traverse().filterIsInstance<ItemNode>().firstOrNull { it.item.handle == handle }

    private suspend fun fetch(handle: String?): TreeChildrenDto = try {
        withContext(Dispatchers.Default) { source.children(view.id, handle) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LOG.warn("Could not read the items of view ${view.id}", error)
        TreeChildrenDto(problem = "Could not reach the extension host")
    }

    private fun expansion(event: TreeExpansionEvent, open: Boolean) {
        val handle = (event.path.lastPathComponent as? ItemNode)?.item?.handle ?: return
        if (open) expanded.add(handle) else expanded.remove(handle)
        call { expanded(view.id, handle, open) }
    }

    private fun run(path: TreePath) {
        val item = (path.lastPathComponent as? ItemNode)?.item ?: return
        call { run(view.id, item.handle) }
    }

    private fun call(work: suspend TreeViewSource.() -> Unit) {
        scope.launch {
            runCatching { source.work() }
                .onFailure { if (it is CancellationException) throw it else LOG.debug("Tree view call failed", it) }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val item = (value as? ItemNode)?.item
            if (item == null) {
                append((value as? DefaultMutableTreeNode)?.userObject?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                return
            }
            val own = TreeIcons.item(source, scope, item) { tree.repaint() }
            val checked = item.checked
            icon = if (checked == null) own else RowIcon(2).apply {
                setIcon(CheckboxIcon.of(checked), 0)
                own?.let { setIcon(it, 1) }
            }
            append(item.label)
            item.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            toolTipText = item.tooltip?.let(::html)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeTreePanel::class.java)
        private const val LOADING = "Loading..."
        private const val TREE = "tree"
        private const val WELCOME = "welcome"

        /** The widest a welcome button grows, as in VS Code. */
        private const val BUTTON_WIDTH = 300

        /** HTML the backend made of what the extension wrote; see [TreeItemDto.tooltip]. */
        private fun html(body: String) = "<html>$body</html>"
    }

    /**
     * The tree, with each hovered or selected item's inline menu entries drawn as buttons at the
     * right of its row, over its text, as VS Code draws them.
     */
    private inner class ItemTree(model: DefaultTreeModel) : Tree(model), UiDataProvider {
        private var hovered = -1
        private var hoveredButton: Rectangle? = null

        /** The selected items' files, for the IDE's actions on files. */
        override fun uiDataSnapshot(sink: DataSink) {
            val paths = selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? ItemNode)?.item?.resourcePath }
            if (paths.isEmpty()) return
            sink.lazy(CommonDataKeys.VIRTUAL_FILE_ARRAY) {
                paths.mapNotNull { LocalFileSystem.getInstance().findFileByPath(it.replace('\\', '/')) }
                    .toTypedArray().takeIf { it.isNotEmpty() }
            }
            sink.lazy(CommonDataKeys.VIRTUAL_FILE) {
                LocalFileSystem.getInstance().findFileByPath(paths.first().replace('\\', '/'))
            }
        }

        fun hover(point: Point?) {
            val row = point?.let { rowAt(it.x, it.y) } ?: -1
            val button = point?.let { at ->
                buttons(row).firstOrNull { (box, entry) -> entry.enabled && box.contains(at) }?.first
            }
            if (row == hovered && button == hoveredButton) return
            hovered = row
            hoveredButton = button
            repaint()
        }

        /** The row under [x], [y], or -1 below the last one. */
        fun rowAt(x: Int, y: Int): Int {
            val row = getClosestRowForLocation(x, y)
            val bounds = getRowBounds(row) ?: return -1
            return if (y >= bounds.y && y < bounds.y + bounds.height) row else -1
        }

        /** The inline buttons drawn on [row], each with where it is drawn; entries without an icon stay in the menu. */
        private fun buttons(row: Int): List<Pair<Rectangle, TreeActionDto>> {
            val node = getPathForRow(row)?.lastPathComponent as? ItemNode ?: return emptyList()
            val bounds = getRowBounds(row) ?: return emptyList()
            val size = JBUI.scale(20)
            val gap = JBUI.scale(4)
            var right = visibleRight() - gap
            return node.item.inline.filter { it.icon != null }.reversed().map { entry ->
                right -= size
                val box = Rectangle(right, bounds.y + (bounds.height - size) / 2, size, size)
                right -= gap
                box to entry
            }
        }

        /**
         * The right edge of what is seen of the tree, short of a vertical scrollbar: the new UI draws
         * it over the tree rather than beside it, and a button under it could not be clicked.
         */
        private fun visibleRight(): Int {
            val right = visibleRect.x + visibleRect.width
            val bar = (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane)
                ?.verticalScrollBar?.takeIf { it.isVisible && it.width > 0 } ?: return right
            return minOf(right, SwingUtilities.convertPoint(bar, 0, 0, this).x)
        }

        private fun shownRows(): Set<Int> = (selectionRows ?: IntArray(0)).toSet() + setOfNotNull(hovered.takeIf { it >= 0 })

        fun inlineAt(x: Int, y: Int): Pair<ItemNode, TreeActionDto>? {
            val row = rowAt(x, y).takeIf { it in shownRows() } ?: return null
            val entry = buttons(row).firstOrNull { (box, _) -> box.contains(x, y) }?.second ?: return null
            return (getPathForRow(row).lastPathComponent as ItemNode) to entry
        }

        /** Whether [x] falls on the checkbox drawn at the start of [path]'s row. */
        fun onCheckbox(path: TreePath, x: Int): Boolean {
            val item = (path.lastPathComponent as? ItemNode)?.item ?: return false
            if (item.checked == null) return false
            val bounds = getPathBounds(path) ?: return false
            return x >= bounds.x && x < bounds.x + CheckboxIcon.of(false).iconWidth + JBUI.scale(2)
        }

        override fun getToolTipText(event: MouseEvent): String? =
            inlineAt(event.x, event.y)?.second?.title ?: super.getToolTipText(event)

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            for (row in shownRows()) {
                val buttons = buttons(row)
                if (buttons.isEmpty()) continue
                val bounds = getRowBounds(row) ?: continue
                val left = buttons.minOf { it.first.x } - JBUI.scale(4)
                // Over the row's own text, in the row's own background.
                g.color = if (isRowSelected(row)) UIUtil.getTreeSelectionBackground(hasFocus()) else background
                g.fillRect(left, bounds.y, visibleRight() - left, bounds.height)
                for ((box, entry) in buttons) {
                    val icon = TreeIcons.icon(source, scope, entry.icon, entry.iconDark) { repaint() } ?: continue
                    if (entry.enabled && row == hovered && box == hoveredButton) {
                        val highlight = g.create() as Graphics2D
                        try {
                            GraphicsUtil.setupAAPainting(highlight)
                            highlight.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                            val arc = DarculaUIUtil.BUTTON_ARC.get()
                            highlight.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc)
                        } finally {
                            highlight.dispose()
                        }
                    }
                    val shown = if (entry.enabled) icon else IconLoader.getDisabledIcon(icon)
                    shown.paintIcon(this, g, box.x + (box.width - shown.iconWidth) / 2, box.y + (box.height - shown.iconHeight) / 2)
                }
            }
        }
    }

    /** A checkbox as an icon, painted by the look and feel's own checkbox so that it matches the theme. */
    private class CheckboxIcon private constructor(private val checked: Boolean) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            box.isSelected = checked
            box.setSize(iconWidth, iconHeight)
            g.translate(x, y)
            try { box.paint(g) } finally { g.translate(-x, -y) }
        }

        override fun getIconWidth(): Int = box.preferredSize.width

        override fun getIconHeight(): Int = box.preferredSize.height

        companion object {
            private val box = JBCheckBox().apply { isOpaque = false; border = JBUI.Borders.empty() }
            private val on = CheckboxIcon(true)
            private val off = CheckboxIcon(false)

            fun of(checked: Boolean): Icon = if (checked) on else off
        }
    }
}
