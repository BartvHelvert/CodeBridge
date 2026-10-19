package me.helvert.codebridge.workbench

import com.intellij.ide.ui.icons.IconId
import com.intellij.ide.ui.icons.rpcIdOrNull
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.IconUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.*
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.HoverMarkdown
import me.helvert.codebridge.manifest.ManifestStrings
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.views.TreeViewSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadTreeViews`: the trees extensions draw in the views they contribute.
 *
 * Which views exist is read from the manifests, so they can be drawn before any host runs, as VS
 * Code draws its side bar before activating anything. What is in them is the host's: the frontend
 * asks for one level at a time ([children]) and the provider answers through `$getChildren`.
 * Asking for a view's roots is what wakes its extension -- `onView:<id>`, starting the host if it
 * has to -- because in VS Code that is what showing the view does.
 *
 * Items are kept here by handle, as the host last described them, so that selecting one runs the
 * command the extension attached to it and nothing else.
 */
@Service(Service.Level.PROJECT)
class HostedTreeViews(private val project: Project, val scope: CoroutineScope) {

    /** These views, for a panel the backend shows itself: a tab of a debug session. */
    val source: TreeViewSource = object : TreeViewSource {
        override suspend fun children(viewId: String, handle: String?) = this@HostedTreeViews.children(viewId, handle)
        override suspend fun visible(viewId: String, visible: Boolean) = setVisible(viewId, visible)
        override suspend fun expanded(viewId: String, handle: String, expanded: Boolean) =
            setExpanded(viewId, handle, expanded)

        override suspend fun selection(viewId: String, handles: List<String>, focus: String?) =
            setSelection(viewId, handles, focus)

        override suspend fun run(viewId: String, handle: String) = this@HostedTreeViews.run(viewId, handle)
        override suspend fun icon(key: String): ByteArray? = this@HostedTreeViews.icon(key)
        override suspend fun itemActions(viewId: String, handle: String) =
            this@HostedTreeViews.itemActions(viewId, handle)

        override suspend fun runAction(viewId: String, actionId: Int, handle: String?, selection: List<String>) =
            this@HostedTreeViews.runAction(viewId, actionId, handle, selection)

        override suspend fun runWelcomeLink(viewId: String, link: Int) =
            this@HostedTreeViews.runWelcomeLink(viewId, link)

        override suspend fun checkboxes(viewId: String, states: Map<String, Boolean>) = setCheckboxes(viewId, states)
    }

    /**
     * The views an extension puts in VS Code's Run and Debug side bar for sessions of [debugType]:
     * those of the extension that contributes that debugger. VS Code shows them beside the
     * session's variables and call stack, so they are tabs of that session here.
     */
    fun debugViews(debugType: String): List<TreeViewDto> {
        val owners = ProjectExtensions.getInstance(project).enabled()
            .filter { extension -> extension.manifest.contributes.debuggers.any { it.type == debugType } }
            .map { it.id }.toSet()
        val state = runtime.value
        val menus = menus()
        return contributions().filter { it.container.id == DEBUG && !it.container.panel && !it.webview && it.extensionId in owners }
            .map { it.toDto(state[it.id] ?: Runtime(), menus, debugType) }
            // A view whose `when` does not hold for this debugger has no tab, as VS Code does not show it.
            .filter { it.visible }
    }

    /** The `view/item/context` menu of item [handle] as it stands now, in menu order. */
    fun itemActions(viewId: String, handle: String): List<TreeActionDto> {
        val item = items[viewId]?.get(handle) ?: return emptyList()
        return offered(menus(), viewId, item).map { it.toDto(context(viewId, item)) }
    }

    /**
     * Runs menu entry [actionId] for [viewId], or for item [handle] in it, with [selection] the items
     * selected beside it -- only when that entry is offered there now.
     *
     * An item crosses to the host as `{$treeViewId, $treeItemHandle}`, which the host turns back into
     * the extension's own element before its command sees it; with several selected, VS Code passes
     * the one acted on and then all of them.
     */
    suspend fun runAction(viewId: String, actionId: Int, handle: String?, selection: List<String>) {
        val item = handle?.let { items[viewId]?.get(it) ?: return }
        val entry =
            offered(menus(), viewId, item).flatMap { it.flatten() }.firstOrNull { it.id == actionId && !it.submenu }
                ?: return
        if (!entry.enabled(context(viewId, item))) return
        val args = buildJsonArray {
            add(entry.command)
            if (handle != null) {
                add(element(viewId, handle))
                val chosen = selection.filter { it != handle && items[viewId]?.containsKey(it) == true }
                if (chosen.isNotEmpty()) add(JsonArray((listOf(handle) + chosen).map { element(viewId, it) }))
            }
        }
        val host = session ?: return
        try {
            host.call("ExtHostCommands", $$"$executeContributedCommand", args.toString())
        } catch (failure: Exception) {
            LOG.warn("Menu command ${entry.command} failed", failure)
            if (!project.isDisposed) HostedMessages.getInstance(project).notify(
                Messages.Severity.ERROR, "'${entry.title}' failed: ${HostedStatusBar.reason(failure)}",
                source = contributions().firstOrNull { it.id == viewId }?.name ?: viewId,
            )
        }
    }

    private fun element(viewId: String, handle: String) = buildJsonObject {
        put($$"$treeViewId", viewId); put($$"$treeItemHandle", handle)
    }

    /** A view's `view/title` entries and its items' `view/item/context` ones. */
    private class Menus(val title: List<ExtensionMenus.Entry>, val item: List<ExtensionMenus.Entry>)

    private fun menus() = Menus(
        ExtensionMenus.entries(VIEW_TITLE, ::image, ProjectExtensions.getInstance(project).enabled()),
        ExtensionMenus.entries(VIEW_ITEM, ::image, ProjectExtensions.getInstance(project).enabled()),
    )

    /** The entries offered for [viewId], or for [item] in it, in menu order, buttons first. */
    private fun offered(menus: Menus, viewId: String, item: TreeViews.Item?): List<ExtensionMenus.Entry> =
        if (item == null) ExtensionMenus.offered(menus.title, context(viewId, null), TreeActionDto.NAVIGATION)
        else ExtensionMenus.offered(menus.item, context(viewId, item), TreeActionDto.INLINE)

    /** The keys a clause about [viewId] reads: what the workbench sets for it, then the rest. */
    private fun context(viewId: String, item: TreeViews.Item?): (String) -> JsonElement? = contextKeys.lookup { key ->
        when (key) {
            "view" -> JsonPrimitive(viewId)
            "viewItem" -> item?.contextValue?.let(::JsonPrimitive)
            else -> null
        }
    }

    /**
     * Runs the workbench command [command] if it is one of the views': `<view>.focus`,
     * `workbench.view.extension.<container>` or `workbench.actions.treeView.<view>.collapseAll`.
     * Whether it was one.
     */
    fun viewCommand(command: String): Boolean {
        val views = contributions()
        val event = when {
            command.endsWith(FOCUS) -> views.firstOrNull { it.id == command.removeSuffix(FOCUS) }
                ?.let { TreeEventDto(TreeEventDto.FOCUS, it.id) }

            command.startsWith(CONTAINER) -> views.firstOrNull { it.container.id == command.removePrefix(CONTAINER) }
                ?.let { TreeEventDto(TreeEventDto.FOCUS, it.id) }

            command.startsWith(TREE_ACTION) && command.endsWith(COLLAPSE_ALL) ->
                views.firstOrNull { it.id == command.removePrefix(TREE_ACTION).removeSuffix(COLLAPSE_ALL) }
                    ?.let { TreeEventDto(TreeEventDto.COLLAPSE_ALL, it.id) }

            else -> null
        } ?: return false
        events.tryEmit(event)
        return true
    }

    /**
     * Runs link [link] of [viewId]'s welcome content: a command, as `executeCommand` runs it. Only a
     * link the content offers now, so the frontend cannot name any other command.
     */
    suspend fun runWelcomeLink(viewId: String, link: Int) {
        val target = welcome(viewId).flatMap { it.parts }.mapNotNull { it.target }.getOrNull(link) ?: return
        val command = ViewsWelcome.command(target) ?: return
        try {
            project.service<HostedLanguageServers>().executeCommand(command.first, command.second)
        } catch (failure: Exception) {
            LOG.warn("Welcome command ${command.first} failed", failure)
            if (!project.isDisposed) HostedMessages.getInstance(project).notify(
                Messages.Severity.ERROR, "'${command.first}' failed: ${HostedStatusBar.reason(failure)}",
                source = contributions().firstOrNull { it.id == viewId }?.name ?: viewId,
            )
        }
    }

    /** The lines of [viewId]'s welcome content whose `when` clause holds now. */
    private fun welcome(viewId: String): List<ViewsWelcome.Line> {
        val context = context(viewId, null)
        return ProjectExtensions.getInstance(project).enabled().flatMap { extension ->
            ViewsWelcome.of(extension.manifest.raw, viewId)
                .filter { WhenClause.evaluate(it.whenClause, context) }
                .flatMap { it.lines }
        }
    }


    /** The resource item [handle] of [viewId] stands for, of any scheme, as the extension last described it. */
    fun resourceOf(viewId: String, handle: String): UriComponents? = items[viewId]?.get(handle)?.resource

    /** The extension contributing view [viewId], or null when no enabled one does. */
    fun extensionOf(viewId: String): String? = contributions().firstOrNull { it.id == viewId }?.extensionId

    /** The name view [viewId] is contributed with. */
    fun nameOf(viewId: String): String? = contributions().firstOrNull { it.id == viewId }?.name

    /** Whether a provider answers for webview view [viewId]; see [HostedWebviews]. */
    internal fun webviewProvided(viewId: String, provided: Boolean) =
        change(viewId) { if (provided) it.copy(registered = true) else Runtime(generation = it.generation + 1) }

    /** What a webview view's extension set: its title, description and badge, as a tree view's sets them. */
    internal fun retitle(viewId: String, title: String?) = change(viewId) { it.copy(title = title?.ifBlank { null }) }

    internal fun describe(viewId: String, description: String?) = change(viewId) { it.copy(description = description) }

    internal fun rebadge(viewId: String, badge: Int?) =
        change(viewId) { it.copy(badge = badge?.takeIf { value -> value > 0 }) }

    /** What the extension said about a view at runtime. [generation] only ever grows. */
    private data class Runtime(
        val registered: Boolean = false,
        val generation: Int = 0,
        val title: String? = null,
        val description: String? = null,
        val message: String? = null,
        val badge: Int? = null,
        val showCollapseAll: Boolean = false,
        val canSelectMany: Boolean = false,
        val manualCheckboxes: Boolean = false,
    )

    private val runtime = MutableStateFlow<Map<String, Runtime>>(emptyMap())
    private val events = MutableSharedFlow<TreeEventDto>(extraBufferCapacity = 64)
    @Volatile
    private var session: ExtensionHostSession? = null

    /** Runs a command as `commands.executeCommand` would, the workbench's own included. */
    @Volatile
    private var executeCommand: (suspend (String, JsonArray) -> Any?)? = null
    private val contextKeys get() = HostedContextKeys.getInstance(project)

    /** The items each view's frontend has been given, by handle; dropped whenever the tree is. */
    private val items = ConcurrentHashMap<String, ConcurrentHashMap<String, TreeViews.Item>>()

    /** Views the frontend says are on screen, to tell a provider that registers later. */
    private val visible = ConcurrentHashMap.newKeySet<String>()

    /** Image files extensions named, by the key the frontend asks for them by. */
    private val icons = ConcurrentHashMap<String, Path>()
    private val iconKeys = ConcurrentHashMap<Path, String>()
    private val nextIcon = AtomicInteger()

    /**
     * [executeCommand] runs an item's command. Not `$executeContributedCommand` on the host: an
     * item's command may be the workbench's own -- `vscode.open` reaches here as `_workbench.open`
     * -- which no extension registers, so the host answers that it does not exist.
     */
    fun hostStarted(session: ExtensionHostSession, executeCommand: suspend (String, JsonArray) -> Any?) {
        this.session = session
        this.executeCommand = executeCommand
    }

    /** Every provider belonged to the host that has gone, and so did every handle it gave out. */
    fun hostStopped() {
        session = null
        executeCommand = null
        items.clear()
        runtime.update { views -> views.mapValues { (_, view) -> Runtime(generation = view.generation + 1) } }
    }

    /** The views to draw, as [TreeViewDto]s; a new list whenever an extension or a provider changes. */
    fun views(): Flow<List<TreeViewDto>> =
        combine(
            ExtensionInstallationService.getInstance().changes, ProjectExtensions.getInstance(project).changes,
            runtime, contextKeys.values,
        ) { _, _, state, _ ->
            val menus = menus()
            contributions().map { view ->
                view.toDto(
                    state[view.id] ?: Runtime(),
                    menus,
                )
            }
        }

    val changes: SharedFlow<TreeEventDto> = events.asSharedFlow()

    /** Handles one `MainThreadTreeViews` call. Nothing here returns a value to the host. */
    fun handle(call: MainThreadCall) {
        try {
            when (call.method) {
                TreeViews.REGISTER -> {
                    val registration = TreeViews.registration(call.argsJson)
                    items.remove(registration.viewId)
                    change(registration.viewId) {
                        it.copy(
                            registered = true, generation = it.generation + 1,
                            showCollapseAll = registration.showCollapseAll, canSelectMany = registration.canSelectMany,
                            manualCheckboxes = registration.manualCheckboxes
                        )
                    }
                    if (registration.viewId in visible) tellVisible(registration.viewId, true)
                }

                TreeViews.REFRESH -> refreshed(TreeViews.refresh(call.argsJson))
                TreeViews.REVEAL -> revealed(TreeViews.reveal(call.argsJson))
                TreeViews.SET_MESSAGE -> {
                    val message = TreeViews.message(call.argsJson)
                    change(message.viewId) {
                        it.copy(message = message.text?.let { text ->
                            html(
                                text,
                                message.markdown
                            )
                        })
                    }
                }

                TreeViews.SET_TITLE -> {
                    val title = TreeViews.title(call.argsJson)
                    change(title.viewId) {
                        it.copy(
                            title = title.title.ifBlank { null },
                            description = title.description
                        )
                    }
                }

                TreeViews.SET_BADGE -> {
                    val badge = TreeViews.badge(call.argsJson)
                    change(badge.viewId) { it.copy(badge = badge.value?.takeIf { value -> value > 0 }) }
                }

                TreeViews.DISPOSE -> {
                    val viewId = TreeViews.viewId(call.method, call.argsJson)
                    items.remove(viewId)
                    change(viewId) { Runtime(generation = it.generation + 1) }
                }
            }
        } catch (failure: Exception) {
            // Arrives on the RPC reader, where an exception would end the session.
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
    }

    /**
     * The items under [handle] in [viewId], or its roots for null, waking the view's extension first
     * if nothing is answering for it yet.
     */
    suspend fun children(viewId: String, handle: String?): TreeChildrenDto {
        if (runtime.value[viewId]?.registered != true) {
            val view = contributions().firstOrNull { it.id == viewId }
                ?: return UNREGISTERED
            try {
                project.service<HostedLanguageServers>().activateView(view.extensionId, viewId)
            } catch (failure: Exception) {
                LOG.info("Could not activate ${view.extensionId} for view $viewId", failure)
                return TreeChildrenDto(problem = "Could not start ${view.extensionId}: ${HostedStatusBar.reason(failure)}")
            }
            // Most extensions register their provider in `activate`, but some only after work of
            // their own -- reading the workspace, say -- so the registration may follow activation.
            withTimeoutOrNull(REGISTRATION_WAIT_MILLIS.milliseconds) { runtime.first { it[viewId]?.registered == true } }
                ?: return UNREGISTERED
        }
        val host = session ?: return UNREGISTERED
        val reply = try {
            host.call(TreeViews.HOST, TreeViews.GET_CHILDREN, TreeViews.getChildren(viewId, handle))
        } catch (failure: Exception) {
            LOG.info("Could not read the items of view $viewId", failure)
            return TreeChildrenDto(problem = HostedStatusBar.reason(failure))
        }
        val children = TreeViews.children(reply)
        val known = items.computeIfAbsent(viewId) { ConcurrentHashMap() }
        children.forEach { known[it.handle] = it }
        val menus = menus()
        return TreeChildrenDto(children.map { toDto(viewId, menus, it) })
    }

    fun setVisible(viewId: String, shown: Boolean) {
        if (shown) visible.add(viewId) else visible.remove(viewId)
        if (runtime.value[viewId]?.registered == true) tellVisible(viewId, shown)
    }

    fun setExpanded(viewId: String, handle: String, expanded: Boolean) {
        if (items[viewId]?.containsKey(handle) != true) return
        notifyHost(TreeViews.SET_EXPANDED, buildJsonArray { add(viewId); add(handle); add(expanded) })
    }

    fun setSelection(viewId: String, handles: List<String>, focus: String?) {
        val known = items[viewId] ?: return
        notifyHost(TreeViews.SET_SELECTION_AND_FOCUS, buildJsonArray {
            add(viewId)
            add(JsonArray(handles.filter(known::containsKey).map(::JsonPrimitive)))
            add(focus?.takeIf(known::containsKey)?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    /**
     * The user checked or unchecked items of [viewId], as `onDidChangeCheckboxState` tells the
     * extension. Only items the frontend was given; the rest are not the extension's to hear about.
     */
    fun setCheckboxes(viewId: String, states: Map<String, Boolean>) {
        val known = items[viewId] ?: return
        val changed = states.filterKeys(known::containsKey)
        if (changed.isEmpty()) return
        changed.forEach { (handle, checked) -> known.computeIfPresent(handle) { _, item -> item.copy(checked = checked) } }
        notifyHost(TreeViews.CHANGE_CHECKBOX_STATE, TreeViews.checkboxes(viewId, changed))
    }

    /**
     * Runs the command attached to item [handle].
     *
     * An item without one may still get one from the provider's `resolveTreeItem`, which VS Code
     * asks only when the item is selected; that is done here too.
     */
    suspend fun run(viewId: String, handle: String) {
        val item = items[viewId]?.get(handle) ?: return
        val host = session ?: return
        val command = item.command ?: try {
            if (host.call(TreeViews.HOST, TreeViews.HAS_RESOLVE, "[${JsonPrimitive(viewId)}]") != "true") return
            host.call(
                TreeViews.HOST,
                TreeViews.RESOLVE,
                buildJsonArray { add(viewId); add(handle) }.toString(),
                cancellable = true
            ).let(TreeViews::resolved)?.command ?: return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("Could not resolve tree item $handle of view $viewId", failure)
            return
        }
        val args = buildJsonArray { add(JsonPrimitive(command.id)); command.arguments.forEach(::add) }
        val failure = execute(args) ?: return
        LOG.warn("Tree item command ${command.id} failed", failure)
        if (!project.isDisposed) HostedMessages.getInstance(project).notify(
            Messages.Severity.ERROR, "'${command.id}' failed: ${HostedStatusBar.reason(failure)}",
            source = contributions().firstOrNull { it.id == viewId }?.name ?: viewId,
        )
    }

    /** The image registered as [key], or null when it has gone or is too large to be an icon. */
    fun icon(key: String): ByteArray? {
        val file = icons[key] ?: return null
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_ICON_BYTES) return null
        return Files.readAllBytes(file)
    }

    private fun refreshed(refresh: TreeViews.Refresh) {
        val changed = refresh.items
        if (changed == null) {
            items.remove(refresh.viewId)
            change(refresh.viewId) { it.copy(generation = it.generation + 1) }
            return
        }
        val known = items.computeIfAbsent(refresh.viewId) { ConcurrentHashMap() }
        changed.forEach { known[it.handle] = it }
        val menus = menus()
        events.tryEmit(
            TreeEventDto(
                TreeEventDto.REFRESH,
                refresh.viewId,
                items = changed.map { toDto(refresh.viewId, menus, it) })
        )
    }

    private fun revealed(reveal: TreeViews.Reveal) {
        val chain = reveal.parentChain + listOfNotNull(reveal.item)
        LOG.info("View ${reveal.viewId} asks to reveal ${chain.map { it.handle }}")
        val known = items.computeIfAbsent(reveal.viewId) { ConcurrentHashMap() }
        chain.forEach { known[it.handle] = it }
        val menus = menus()
        events.tryEmit(
            TreeEventDto(
                TreeEventDto.REVEAL,
                reveal.viewId,
                items = chain.map { toDto(reveal.viewId, menus, it) },
                path = chain.map { it.handle },
                select = reveal.select,
                focus = reveal.focus,
                expand = reveal.expand,
            )
        )
    }

    private fun change(viewId: String, transform: (Runtime) -> Runtime) {
        runtime.update { it + (viewId to transform(it[viewId] ?: Runtime())) }
    }

    private fun tellVisible(viewId: String, shown: Boolean) {
        LOG.info("Telling the extension of view $viewId it is ${if (shown) "visible" else "hidden"}")
        notifyHost(TreeViews.SET_VISIBLE, buildJsonArray { add(viewId); add(shown) })
    }

    private fun notifyHost(method: String, args: JsonArray) = notifyHost(method, args.toString())

    private fun notifyHost(method: String, args: String) {
        val host = session ?: return
        // Sent now, in order; the answer is only looked at for a failure.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                host.call(TreeViews.HOST, method, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("$method failed", failure)
            }
        }
    }

    /**
     * Text the extension wrote, as HTML: Markdown rendered as hovers are, command links reduced to
     * their labels since nothing in a tooltip can be clicked; plain text escaped, its breaks kept.
     */
    private fun html(text: String, markdown: Boolean): String =
        if (markdown) runCatching {
            HoverMarkdown.toHtml(
                project,
                Markdown.withoutCommandLinks(text)
            )
        }.getOrElse { plain(text) }
        else plain(text)

    private fun plain(text: String) = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")

    private fun toDto(viewId: String, menus: Menus, item: TreeViews.Item): TreeItemDto {
        val resource =
            item.resource?.takeIf { it.scheme == UriComponents.FILE }?.let { runCatching { it.toPath() }.getOrNull() }
        // VS Code draws the file's own icon for these, from its icon theme; so does the frontend.
        val fileIcon = item.themeIcon == "file" || item.themeIcon == "folder"
        val icon = when {
            item.themeIcon != null && !(fileIcon && item.resource != null) -> "$(${item.themeIcon})"
            else -> item.icon?.takeIf { it.scheme == UriComponents.FILE }
                ?.let { runCatching { iconKey(it.toPath()) }.getOrNull() }
        }
        return TreeItemDto(
            handle = item.handle,
            label = item.label ?: resource?.name ?: item.resource?.path?.substringAfterLast('/').orEmpty(),
            description = item.description,
            tooltip = item.tooltip?.let { html(it, item.markdownTooltip) },
            icon = icon,
            iconDark = item.iconDark?.takeIf { it.scheme == UriComponents.FILE }
                ?.let { runCatching { iconKey(it.toPath()) }.getOrNull() },
            resourcePath = resource?.toString(),
            resourceName = item.resource?.path?.substringAfterLast('/')?.substringAfterLast('\\'),
            fileIcon = resource?.takeIf { icon == null }?.let(::fileIcon),
            // As in VS Code: a `file` or `folder` theme icon says which; without one, an expandable item is a folder.
            folder = item.themeIcon == "folder" || (item.themeIcon == null && icon == null && item.collapsible != TreeViews.LEAF),
            expandable = item.collapsible != TreeViews.LEAF,
            expanded = item.collapsible == TreeViews.EXPANDED,
            hasCommand = item.command != null,
            checked = item.checked,
            inline = offered(menus, viewId, item).filter { it.group == TreeActionDto.INLINE }
                .map { it.toDto(context(viewId, item)) },
        )
    }

    /**
     * The IDE's icon for [file], as the project view draws it: with what plugins say of it, which the
     * frontend, knowing only the file's name, cannot ask. Null for a file the IDE has not seen.
     */
    private fun fileIcon(file: Path): IconId? {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return null
        return runCatching { runReadActionBlocking { IconUtil.computeFileIcon(virtual, 0, project) }.rpcIdOrNull() }
            .onFailure { if (it is CancellationException) throw it else LOG.debug("No icon for $file", it) }
            .getOrNull()
    }

    /** The key [icon] serves image [file] by, for a webview panel's `iconPath`. */
    internal fun imageKey(file: Path): String = iconKey(file)

    private fun iconKey(file: Path): String {
        val normalized = file.toAbsolutePath().normalize()
        return iconKeys.computeIfAbsent(normalized) { path ->
            "icon-${nextIcon.incrementAndGet()}".also { icons[it] = path }
        }
    }

    /** One `contributes.views` entry, with the container it is in. */
    private data class View(
        val id: String,
        val name: String,
        val extensionId: String,
        val icon: String?,
        val container: Container,
        /** When VS Code shows the view at all; see [TreeViewDto.visible]. */
        val whenClause: String?,
        /** Drawn by a page of the extension's own; see [TreeViewDto.webview]. */
        val webview: Boolean = false,
        /** The manifest's `visibility`: `visible`, `hidden` or `collapsed`; see [TreeViewDto.hiddenByDefault]. */
        val visibility: String? = null,
    )

    private data class Container(val id: String, val title: String, val icon: String?, val panel: Boolean)

    private fun View.toDto(
        state: Runtime,
        menus: Menus,
        debugType: String? = null,
    ) = TreeViewDto(
        id = id,
        name = name,
        extensionId = extensionId,
        containerId = container.id,
        containerTitle = container.title,
        containerIcon = container.icon,
        panel = container.panel,
        icon = icon,
        registered = state.registered,
        generation = state.generation,
        title = state.title,
        description = state.description,
        message = state.message,
        badge = state.badge,
        showCollapseAll = state.showCollapseAll,
        canSelectMany = state.canSelectMany,
        manualCheckboxes = state.manualCheckboxes,
        actions = offered(menus, id, null).map { it.toDto(context(id, null)) },
        visible = WhenClause.evaluate(whenClause, contextKeys.lookup { key ->
            if (key == "debugType") debugType?.let(::JsonPrimitive) else null
        }),
        hiddenByDefault = visibility == "hidden",
        collapsedByDefault = visibility == "collapsed",
        welcome = welcomeDto(id),
        webview = webview,
    )

    /** [viewId]'s welcome content, its links numbered across the whole of it as [runWelcomeLink] names them. */
    private fun welcomeDto(viewId: String): List<WelcomeLineDto> {
        var next = 0
        return welcome(viewId).map { line ->
            WelcomeLineDto(line.parts.map { part ->
                val target = part.target
                when {
                    target == null -> WelcomePartDto(part.text)
                    ViewsWelcome.command(target) != null -> WelcomePartDto(part.text, link = next++)
                    else -> {
                        next++; WelcomePartDto(part.text, url = target)
                    }
                }
            }, line.button)
        }
    }

    /**
     * The tree views enabled extensions contribute, in manifest order.
     *
     * Webview views are among them, marked: they share `contributes.views` and the view containers,
     * and the frontend draws them in the same tool windows, by a page rather than a tree.
     */
    private fun contributions(): List<View> {
        val installed = ProjectExtensions.getInstance(project).enabled()
        val containers = installed.flatMap(::containers).associateBy { it.id }
        return installed.flatMap { extension ->
            val views = (extension.manifest.raw["contributes"] as? JsonObject)?.get("views") as? JsonObject
                ?: return@flatMap emptyList()
            val nls by lazy { ManifestStrings.nls(extension.directory) }
            views.flatMap { (containerId, entries) ->
                val container = containers[containerId] ?: BUILT_IN[containerId]
                ?: Container(containerId, containerId, null, panel = false)
                (entries as? JsonArray).orEmpty().mapNotNull { entry ->
                    val view = entry as? JsonObject ?: return@mapNotNull null
                    val id = view.string("id") ?: return@mapNotNull null
                    val name = view.string("name")?.let { ManifestStrings.localized(it, nls) } ?: id
                    View(
                        id, name, extension.id, image(extension, view.string("icon")), container, view.string("when"),
                        webview = view.string("type") == "webview", visibility = view.string("visibility"),
                    )
                }
            }
        }
    }

    /** `contributes.viewsContainers`: `activitybar` ones go in the side bar, `panel` ones below. */
    private fun containers(extension: InstalledExtension): List<Container> {
        val declared = (extension.manifest.raw["contributes"] as? JsonObject)?.get("viewsContainers") as? JsonObject
            ?: return emptyList()
        val nls by lazy { ManifestStrings.nls(extension.directory) }
        return declared.flatMap { (location, entries) ->
            (entries as? JsonArray).orEmpty().mapNotNull { entry ->
                val container = entry as? JsonObject ?: return@mapNotNull null
                val id = container.string("id") ?: return@mapNotNull null
                val title = container.string("title")?.let { ManifestStrings.localized(it, nls) } ?: id
                Container(id, title, image(extension, container.string("icon")), location == "panel")
            }
        }
    }

    /**
     * A manifest icon: a `$(codicon)` as it is, or a file in the extension, as a key for [icon].
     * The path is the manifest's, so it is untrusted and must stay inside the extension.
     */
    /** A manifest icon, as a `$(codicon)` or a key for [icon]; see [ExtensionMenus.image]. */
    internal fun image(extension: InstalledExtension, value: String?): String? =
        ExtensionMenus.image(extension, value, ::iconKey)

    /** Runs the command `[id, ...arguments]` in [args], answering what went wrong, or null when nothing did. */
    internal suspend fun execute(args: JsonArray): Throwable? {
        val execute = executeCommand ?: return IllegalStateException("The extension host is not running")
        return try {
            val id = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull
                ?: return IllegalArgumentException("No command to run")
            execute(id, JsonArray(args.drop(1)))
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val LOG = Logger.getInstance(HostedTreeViews::class.java)

        /** VS Code's words for a view nobody provides data for. */
        /** VS Code's Run and Debug container. */
        const val DEBUG: String = "debug"

        private const val VIEW_TITLE = "view/title"
        private const val VIEW_ITEM = "view/item/context"

        private const val FOCUS = ".focus"
        private const val CONTAINER = "workbench.view.extension."
        private const val TREE_ACTION = "workbench.actions.treeView."
        private const val COLLAPSE_ALL = ".collapseAll"

        const val NO_PROVIDER: String = "There is no data provider registered that can provide view data."
        private val UNREGISTERED = TreeChildrenDto(problem = NO_PROVIDER, unregistered = true)

        private const val REGISTRATION_WAIT_MILLIS = 10_000L
        private const val MAX_ICON_BYTES = 1024 * 1024

        /** VS Code's own view containers, which extensions add views to without declaring them. */
        private val BUILT_IN = listOf(
            Container("explorer", "Explorer", "$(files)", panel = false),
            Container("scm", "Source Control", "$(source-control)", panel = false),
            Container("debug", "Run and Debug", "$(debug-alt)", panel = false),
            Container("test", "Testing", "$(beaker)", panel = false),
            Container("remote", "Remote Explorer", "$(remote-explorer)", panel = false),
        ).associateBy { it.id }

        fun getInstance(project: Project): HostedTreeViews = project.service()
    }
}
