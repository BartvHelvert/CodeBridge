package me.helvert.codebridge.workbench

import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.ViewsWelcome
import me.helvert.codebridge.exthost.protocol.payload.Webviews
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto
import me.helvert.codebridge.rpc.WebviewResolutionDto
import me.helvert.codebridge.rpc.WebviewResourceDto
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadWebviews`, `MainThreadWebviewPanels` and `MainThreadWebviewViews`: pages extensions
 * draw with HTML of their own, in an editor tab (a panel) or a view they contributed.
 *
 * Drawn by the frontend, which has the browser; this keeps what the extension said about each
 * webview -- its page, its options, its title -- and is the frontend's only way to the extension:
 * a page's messages, the files it asks for and the `command:` links it follows all come through
 * here, by the webview's handle, so a page can only reach what its extension allowed it.
 *
 * A webview view is made when the frontend first shows the view ([resolveView]), which is what wakes
 * its extension (`onView:`) in VS Code too.
 *
 * A panel whose extension registered a serializer for its view type outlives its host, as it
 * outlives a window reload in VS Code: when the host stops, and when the project is closed -- it is
 * saved in the workspace file -- it stays as a dormant tab, and once that tab is on screen its
 * extension is woken (`onWebviewPanel:`) and asked to fill it again (`$deserializeWebviewPanel`),
 * with the state its page last saved. Panels without a serializer go with their host, as in VS Code.
 */
@Service(Service.Level.PROJECT)
@State(name = "CodeBridgeWebviews", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HostedWebviews(private val project: Project, private val scope: CoroutineScope) :
    PersistentStateComponent<HostedWebviews.SavedPanels> {

    /** What the extension said about one webview. [viewType] is a view's id for a [WebviewDto.VIEW]. */
    private data class Webview(
        val handle: String,
        val kind: String,
        val viewType: String,
        val extension: Webviews.Extension,
        val title: String,
        val html: String = "",
        val htmlVersion: Int = 0,
        val options: Webviews.Options = Webviews.Options(),
        val binaryMessages: Boolean = false,
        val panelOptions: JsonObject = JsonObject(emptyMap()),
        val column: Int = Webviews.ACTIVE_GROUP,
        val preserveFocus: Boolean = false,
        val iconPath: Pair<UriComponents?, UriComponents?>? = null,
        val dormant: Boolean = false,
        val problem: String? = null,
        val restoredState: String? = null,
    )

    private val webviews = MutableStateFlow<Map<String, Webview>>(emptyMap())

    /**
     * Messages and reveals for the frontend, kept until it takes them: while it reconnects, or is
     * busy drawing, they wait rather than being lost. A message there is no room for is not
     * delivered, and its extension is told so.
     */
    private val events = Channel<WebviewEventDto>(MAX_PENDING_EVENTS)
    /** The extension providing each webview view, by view id, while the host runs. */
    private val providers = MutableStateFlow<Map<String, Webviews.ViewProvider>>(emptyMap())
    /** The panel view types the running host's extensions can restore (`registerWebviewPanelSerializer`). */
    private val serializers = MutableStateFlow<Set<String>>(emptySet())
    /** What each page last saved with `setState`, as JSON; apart from [webviews], which it would churn. */
    private val states = ConcurrentHashMap<String, String>()
    /** Where each panel last was: whether it was the editor in front, and its editor group's index. */
    private val placements = ConcurrentHashMap<String, Pair<Boolean, Int>>()
    /** Panels on screen now, so a dormant one is restored once its serializer registers. */
    private val onScreen = ConcurrentHashMap.newKeySet<String>()
    /** Dormant panels being restored, so showing one twice does not restore it twice. */
    private val restoring = ConcurrentHashMap.newKeySet<String>()
    /** One view resolved at a time, so showing a view twice quickly does not fill it twice. */
    private val resolving = Mutex()
    @Volatile private var session: ExtensionHostSession? = null
    private val treeViews get() = HostedTreeViews.getInstance(project)

    fun hostStarted(session: ExtensionHostSession) {
        this.session = session
    }

    /**
     * The webviews belonged to the host that has gone. A panel its extension can restore stays,
     * dormant, to be filled again when it is next on screen; the frontend closes the rest.
     */
    fun hostStopped() {
        session = null
        val restorable = serializers.value
        webviews.update { all ->
            all.values.filter { it.kind == WebviewDto.PANEL && (it.dormant || it.viewType in restorable) }
                .associate { it.handle to it.copy(dormant = true, html = "", problem = null, restoredState = states[it.handle]) }
        }
        states.keys.retainAll(webviews.value.keys)
        placements.keys.retainAll(webviews.value.keys)
        restoring.clear()
        providers.value = emptyMap()
        serializers.value = emptySet()
    }

    fun views(): Flow<List<WebviewDto>> = webviews.map { all ->
        val themeColors = ThemeColors.installed().webviewDefaults()
        all.values.map { it.toDto(themeColors) }
    }

    /** The events for the frontend, each taken once: by whichever frontend connection collects it. */
    val changes: Flow<WebviewEventDto> = events.receiveAsFlow()

    /**
     * Handles one call to any of the three services; only `$postMessage` answers with a value.
     * Arrives on the RPC reader, where an exception would end the session, so none escapes.
     */
    fun handle(call: MainThreadCall): String? = try {
        when (call.service) {
            Webviews.SERVICE -> content(call)
            Webviews.PANELS -> { panels(call); null }
            Webviews.VIEWS -> { views(call); null }
            else -> null
        }
    } catch (failure: Exception) {
        LOG.warn("Could not read ${call.service}.${call.method}", failure)
        null
    }

    private fun content(call: MainThreadCall): String? {
        when (call.method) {
            Webviews.SET_HTML -> {
                val (handle, html) = Webviews.html(call.argsJson)
                change(handle) { it.copy(html = html, htmlVersion = it.htmlVersion + 1) }
            }
            Webviews.SET_OPTIONS -> {
                val (handle, options) = Webviews.options(call.argsJson)
                change(handle) { it.copy(options = options) }
            }
            Webviews.POST_MESSAGE -> {
                val message = Webviews.message(call.argsJson, call.buffers)
                val webview = webviews.value[message.handle]
                if (webview == null || webview.dormant) return "false"
                val sent = events.trySend(
                    WebviewEventDto(
                        WebviewEventDto.MESSAGE,
                        message.handle,
                        message.json,
                        message.buffers
                    )
                )
                return if (sent.isSuccess) Webviews.DELIVERED else "false"
            }
        }
        return null
    }

    private fun panels(call: MainThreadCall) {
        when (call.method) {
            Webviews.CREATE_PANEL -> {
                val panel = Webviews.panel(call.argsJson)
                webviews.update {
                    it + (panel.handle to Webview(
                        panel.handle, WebviewDto.PANEL, panel.viewType, panel.extension, panel.title, options = panel.options,
                        binaryMessages = panel.binaryMessages, panelOptions = panel.panelOptions, column = panel.column,
                        preserveFocus = panel.preserveFocus,
                    ))
                }
            }
            Webviews.DISPOSE_PANEL -> {
                val handle = Webviews.handle(call.method, call.argsJson)
                forget(handle)
            }
            Webviews.SET_TITLE -> {
                val (handle, title) = Webviews.text(call.method, call.argsJson)
                change(handle) { it.copy(title = title.orEmpty()) }
            }
            Webviews.SET_ICON_PATH -> {
                val icon = Webviews.iconPath(call.argsJson)
                change(icon.handle) { it.copy(iconPath = (icon.light to icon.dark).takeIf { icon.light != null || icon.dark != null }) }
            }
            Webviews.REVEAL -> {
                val reveal = Webviews.reveal(call.argsJson)
                events.trySend(
                    WebviewEventDto(
                        WebviewEventDto.REVEAL,
                        reveal.handle,
                        preserveFocus = reveal.preserveFocus,
                        column = reveal.column
                    )
                )
            }
            Webviews.REGISTER_SERIALIZER -> {
                val viewType = Webviews.handle(call.method, call.argsJson)
                serializers.update { it + viewType }
                // A dormant panel already on screen was waiting for this.
                webviews.value.values.filter { it.dormant && it.viewType == viewType && it.handle in onScreen }.forEach { restore(it.handle) }
            }
            Webviews.UNREGISTER_SERIALIZER -> {
                val viewType = Webviews.handle(call.method, call.argsJson)
                serializers.update { it - viewType }
            }
        }
    }

    private fun views(call: MainThreadCall) {
        when (call.method) {
            Webviews.REGISTER_VIEW_PROVIDER -> {
                val provider = Webviews.viewProvider(call.argsJson)
                providers.update { it + (provider.viewType to provider) }
                treeViews.webviewProvided(provider.viewType, true)
            }
            Webviews.UNREGISTER_VIEW_PROVIDER -> {
                val viewType = Webviews.handle(call.method, call.argsJson)
                providers.update { it - viewType }
                treeViews.webviewProvided(viewType, false)
            }
            Webviews.SET_VIEW_TITLE -> {
                val (handle, title) = Webviews.text(call.method, call.argsJson)
                viewOf(handle)?.let { treeViews.retitle(it, title) }
            }
            Webviews.SET_VIEW_DESCRIPTION -> {
                val (handle, description) = Webviews.text(call.method, call.argsJson)
                viewOf(handle)?.let { treeViews.describe(it, description) }
            }
            Webviews.SET_VIEW_BADGE -> {
                val (handle, badge) = Webviews.badge(call.argsJson)
                viewOf(handle)?.let { treeViews.rebadge(it, badge) }
            }
            Webviews.SHOW_VIEW -> {
                val handle = Webviews.handle(call.method, call.argsJson)
                viewOf(handle)?.let { treeViews.viewCommand("$it.focus") }
            }
        }
    }

    private fun viewOf(handle: String): String? = webviews.value[handle]?.takeIf { it.kind == WebviewDto.VIEW }?.viewType

    /**
     * The webview filling view [viewId], made if there is none: the view's extension woken and its
     * provider asked to fill a new one, whose page then arrives through `$setHtml`.
     */
    suspend fun resolveView(viewId: String): WebviewResolutionDto = resolving.withLock {
        webviews.value.values.firstOrNull { it.kind == WebviewDto.VIEW && it.viewType == viewId }
            ?.let { return WebviewResolutionDto(it.handle) }
        if (viewId !in providers.value) {
            val extensionId = treeViews.extensionOf(viewId) ?: return WebviewResolutionDto(problem = NO_PROVIDER)
            try {
                project.service<HostedLanguageServers>().activateView(extensionId, viewId)
            } catch (failure: Exception) {
                LOG.info("Could not activate $extensionId for webview view $viewId", failure)
                return WebviewResolutionDto(problem = "Could not start $extensionId: ${HostedStatusBar.reason(failure)}")
            }
            withTimeoutOrNull(REGISTRATION_WAIT_MILLIS.milliseconds) { providers.first { viewId in it } }
                ?: return WebviewResolutionDto(problem = NO_PROVIDER)
        }
        val host = session ?: return WebviewResolutionDto(problem = NO_PROVIDER)
        val provider = providers.value[viewId] ?: return WebviewResolutionDto(problem = NO_PROVIDER)
        val handle = UUID.randomUUID().toString()
        val title = treeViews.nameOf(viewId)
        webviews.update {
            it + (handle to Webview(handle, WebviewDto.VIEW, viewId, provider.extension, title.orEmpty(), binaryMessages = provider.binaryMessages))
        }
        try {
            host.call(Webviews.HOST_VIEWS, Webviews.RESOLVE_VIEW, Webviews.resolveView(handle, viewId, title, null), cancellable = true)
        } catch (failure: Exception) {
            LOG.info("Could not resolve webview view $viewId", failure)
            webviews.update { it - handle }
            return WebviewResolutionDto(problem = HostedStatusBar.reason(failure))
        }
        WebviewResolutionDto(handle)
    }

    /**
     * Hands a page's message to its extension (`onDidReceiveMessage`): its `ArrayBuffer`s as buffers,
     * when the extension is one that expects them so, which the page then sent apart.
     */
    fun message(handle: String, json: String, buffers: List<ByteArray> = emptyList()) {
        val webview = webviews.value[handle]?.takeIf { !it.dormant } ?: return
        val binary = buffers.takeIf { webview.binaryMessages }.orEmpty()
        notifyHost(
            Webviews.HOST, Webviews.ON_MESSAGE, Webviews.onMessage(handle, json, binary.size),
            if (binary.isEmpty()) emptyMap() else mapOf(Webviews.ON_MESSAGE_BUFFERS to binary),
        )
    }

    /** What page [handle] saved with `setState`, kept for restoring its panel. */
    fun saveState(handle: String, state: String?) {
        if (handle !in webviews.value) return
        if (state == null || state == "null") states.remove(handle) else states[handle] = state
    }

    fun shown(handle: String, visible: Boolean, active: Boolean, position: Int = 0) {
        val webview = webviews.value[handle] ?: return
        if (visible) onScreen.add(handle) else onScreen.remove(handle)
        if (webview.kind == WebviewDto.PANEL) {
            placements[handle] = active to position
            if (webview.dormant) {
                if (visible) restore(handle)
                return
            }
            notifyHost(Webviews.HOST_PANELS, Webviews.ON_PANEL_VIEW_STATES, Webviews.panelViewState(handle, active, visible, position))
        } else notifyHost(Webviews.HOST_VIEWS, Webviews.ON_VIEW_VISIBILITY, Webviews.visibility(handle, visible))
    }

    /**
     * Has dormant panel [handle] filled again by its extension's serializer: the extension woken for
     * its view type, as VS Code wakes it to restore a panel, and given the state the page saved.
     * Should that fail the panel stays dormant, saying why, and is tried again when next shown.
     */
    private fun restore(handle: String) {
        val panel = webviews.value[handle]?.takeIf { it.dormant } ?: return
        if (!restoring.add(handle)) return
        scope.launch {
            try {
                val problem = deserialize(panel)
                if (problem != null) {
                    LOG.info("Could not restore webview panel ${panel.viewType}: $problem")
                    change(handle) { it.copy(dormant = true, html = "", problem = problem) }
                }
            } finally {
                restoring.remove(handle)
            }
        }
    }

    /** Restores [panel]; null once its extension has it again, else why not. */
    private suspend fun deserialize(panel: Webview): String? {
        if (panel.viewType !in serializers.value) {
            try {
                project.service<HostedLanguageServers>().activate(panel.extension.id, Webviews.ON_WEBVIEW_PANEL + panel.viewType)
            } catch (failure: Exception) {
                return "Could not start ${panel.extension.id}: ${HostedStatusBar.reason(failure)}"
            }
            withTimeoutOrNull(REGISTRATION_WAIT_MILLIS.milliseconds) { serializers.first { panel.viewType in it } }
                ?: return "${panel.extension.id} no longer restores '${panel.title.ifEmpty { panel.viewType }}'."
        }
        val host = session ?: return "The extension host is not running."
        val state = states[panel.handle]
        val (active, position) = placements[panel.handle] ?: (false to 0)
        // Awake before the host is asked: the page it sets while restoring must reach the frontend.
        change(panel.handle) { it.copy(dormant = false, problem = null, restoredState = state) }
        return try {
            host.call(
                Webviews.HOST_PANELS, Webviews.DESERIALIZE_PANEL,
                Webviews.deserializePanel(panel.handle, panel.viewType, panel.title, state, panel.panelOptions, panel.options.json, active, position),
            )
            null
        } catch (failure: Exception) {
            HostedStatusBar.reason(failure)
        }
    }

    /** The user closed panel [handle]: it is gone, and its extension hears `onDidDispose`. */
    fun close(handle: String) {
        val webview = webviews.value[handle]?.takeIf { it.kind == WebviewDto.PANEL } ?: return
        forget(handle)
        if (!webview.dormant) notifyHost(Webviews.HOST_PANELS, Webviews.ON_PANEL_DISPOSED, Webviews.handleArgs(handle))
    }

    private fun forget(handle: String) {
        webviews.update { it - handle }
        states.remove(handle)
        placements.remove(handle)
        onScreen.remove(handle)
    }

    /**
     * The file behind [url], an `asWebviewUri` address, if it lies under one of the webview's
     * `localResourceRoots`. Both sides are resolved to real paths first, so neither `..` nor a link
     * leads a page out of what its extension allowed.
     */
    fun resource(handle: String, url: String): WebviewResourceDto? {
        val webview = webviews.value[handle] ?: return null
        return read(url, webview.options.localResourceRoots ?: defaultRoots(webview))
    }

    /** What the host allows when the extension names no roots: the workspace and the extension's own directory. */
    private fun defaultRoots(webview: Webview): List<UriComponents> =
        listOfNotNull(project.basePath?.let { UriComponents.of(Path.of(it)) }, webview.extension.location)

    /**
     * Follows the link [uri] page [handle] followed out of itself: runs a `command:` link, when its
     * `enableCommandUris` allows that command -- all of them for `true`, else those it lists -- and
     * hands a link into the editor (`vscode://publisher.name/...`) to the extension it names.
     */
    suspend fun runLink(handle: String, uri: String) {
        val webview = webviews.value[handle] ?: return
        if (uri.substringBefore(':').lowercase() in EDITOR_SCHEMES) return openEditorLink(webview, uri)
        val allowed = webview.options.commandUris ?: return
        val (command, arguments) = ViewsWelcome.command(uri) ?: return
        if (allowed.isNotEmpty() && command !in allowed) return
        try {
            project.service<HostedLanguageServers>().executeCommand(command, arguments)
        } catch (failure: Exception) {
            LOG.warn("Webview command $command failed", failure)
            if (!project.isDisposed) HostedMessages.getInstance(project).notify(
                Messages.Severity.ERROR, "'$command' failed: ${HostedStatusBar.reason(failure)}", source = webview.title.ifEmpty { webview.viewType },
            )
        }
    }

    /** Asks the user first, as VS Code does for a link into the editor that it did not give out. */
    private suspend fun openEditorLink(webview: Webview, uri: String) {
        val link = runCatching { UriComponents.of(uri) }.getOrNull() ?: return
        val opening = HostedUrls.open(project, link) ?: return
        try {
            opening.await()
        } catch (_: HostedUrls.Declined) {
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.warn("Webview link into the editor failed", failure)
            if (!project.isDisposed) HostedMessages.getInstance(project).notify(
                Messages.Severity.ERROR, "Could not open the link: ${HostedStatusBar.reason(failure)}",
                source = webview.title.ifEmpty { webview.viewType },
            )
        }
    }

    private fun change(handle: String, transform: (Webview) -> Webview) {
        webviews.update { all -> all[handle]?.let { all + (handle to transform(it)) } ?: all }
    }

    private fun notifyHost(service: String, method: String, args: String, objectBuffers: Map<Int, List<ByteArray>> = emptyMap()) {
        val host = session ?: return
        // Sent now, in order; the answer is only looked at for a failure.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                host.call(service, method, args, objectBuffers = objectBuffers)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("$method failed", failure)
            }
        }
    }

    private fun Webview.toDto(themeColors: Map<String, Map<String, String>>) = WebviewDto(
        handle = handle, kind = kind, viewType = viewType, extensionId = extension.id, title = title, html = html,
        htmlVersion = htmlVersion,
        scripts = options.scripts,
        forms = options.forms,
        commandLinks = options.commandUris != null,
        binaryMessages = binaryMessages, findWidget = Webviews.findWidget(panelOptions),
        icon = iconPath?.first?.let(::imageKey), iconDark = iconPath?.second?.let(::imageKey),
        column = column, preserveFocus = preserveFocus, dormant = dormant, problem = problem, restoredState = restoredState,
        themeColors = themeColors,
    )

    private fun imageKey(image: UriComponents): String? =
        image.takeIf { it.scheme == UriComponents.FILE }?.let { runCatching { treeViews.imageKey(it.toPath()) }.getOrNull() }

    /** A panel as the workspace file keeps it, to be restored when the project opens again. */
    class SavedPanel {
        var handle: String = ""
        var viewType: String = ""
        var extensionId: String = ""
        /** The extension's directory, as `UriComponents` JSON. */
        var extensionLocation: String? = null
        var title: String = ""
        /** What the page last saved with `setState`, as JSON. */
        var state: String? = null
        /** `webviewOptions` and `panelOptions`, as JSON. */
        var options: String = "{}"
        var panelOptions: String = "{}"
        /** `iconPath`'s images, as `UriComponents` JSON. */
        var iconLight: String? = null
        var iconDark: String? = null
        var active: Boolean = false
        var position: Int = 0
    }

    class SavedPanels {
        var panels: MutableList<SavedPanel> = mutableListOf()
    }

    /** The panels that can be restored: their extensions' serializers would fill them again. */
    override fun getState(): SavedPanels {
        val restorable = serializers.value
        return SavedPanels().apply {
            panels = webviews.value.values.filter { it.kind == WebviewDto.PANEL && (it.dormant || it.viewType in restorable) }.map { panel ->
                val (active, position) = placements[panel.handle] ?: (false to 0)
                SavedPanel().apply {
                    handle = panel.handle
                    viewType = panel.viewType
                    extensionId = panel.extension.id
                    extensionLocation = panel.extension.location?.toJson()?.toString()
                    title = panel.title
                    state = states[panel.handle]
                    options = panel.options.json.toString()
                    panelOptions = panel.panelOptions.toString()
                    iconLight = panel.iconPath?.first?.toJson()?.toString()
                    iconDark = panel.iconPath?.second?.toJson()?.toString()
                    this.active = active
                    this.position = position
                }
            }.toMutableList()
        }
    }

    /** The panels open when the project was closed, dormant until each is next on screen. */
    override fun loadState(state: SavedPanels) {
        val restored = state.panels.filter { it.handle.isNotEmpty() && it.viewType.isNotEmpty() }.mapNotNull { saved ->
            runCatching {
                val options = json(saved.options)
                saved.state?.let { states[saved.handle] = it }
                placements[saved.handle] = saved.active to saved.position
                Webview(
                    saved.handle, WebviewDto.PANEL, saved.viewType,
                    Webviews.Extension(saved.extensionId, saved.extensionLocation?.let(::uri)), saved.title,
                    options = Webviews.options(options), panelOptions = json(saved.panelOptions),
                    // Brought back where they were, not where new panels go, and without taking the focus.
                    column = saved.position, preserveFocus = !saved.active,
                    iconPath = (saved.iconLight?.let(::uri) to saved.iconDark?.let(::uri)).takeIf { it.first != null || it.second != null },
                    dormant = true, restoredState = saved.state,
                )
            }.onFailure { LOG.info("Could not restore a webview panel of ${saved.viewType}", it) }.getOrNull()
        }
        // The one in front last, so it is the one selected.
        val ordered = restored.sortedBy { placements[it.handle]?.first == true }
        webviews.update { live -> ordered.associateBy { it.handle } + live }
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    private fun uri(text: String): UriComponents? = UriComponents.from(Json.parseToJsonElement(text))

    companion object {
        private val LOG = Logger.getInstance(HostedWebviews::class.java)
        private const val REGISTRATION_WAIT_MILLIS = 10_000L
        private const val MAX_PENDING_EVENTS = 4_096

        /** The schemes of links into the editor, as VS Code's builds name themselves. */
        private val EDITOR_SCHEMES = setOf("vscode", "vscode-insiders")
        private const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024
        private const val NO_PROVIDER = "There is no webview provider registered for this view."

        /** The media types a page's resources are most often of; a browser sniffs the rest. */
        private val MIME_TYPES = mapOf(
            "html" to "text/html", "htm" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript",
            "cjs" to "text/javascript", "css" to "text/css", "json" to "application/json", "map" to "application/json",
            "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon", "bmp" to "image/bmp",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "wasm" to "application/wasm", "txt" to "text/plain", "md" to "text/markdown", "xml" to "application/xml",
            "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        )

        /** The file behind [url] if it lies under one of [roots]; see [resource]. */
        internal fun read(url: String, roots: List<UriComponents>): WebviewResourceDto? {
            val resource = Webviews.resource(url)?.takeIf { it.scheme == UriComponents.FILE } ?: return null
            val file = runCatching { resource.copy(query = "", fragment = "").toPath().toRealPath() }.getOrNull() ?: return null
            val real = roots.mapNotNull { root ->
                root.takeIf { it.scheme == UriComponents.FILE }?.let { runCatching { it.toPath().toRealPath() }.getOrNull() }
            }
            if (real.none(file::startsWith)) return null
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_RESOURCE_BYTES) return null
            return WebviewResourceDto(Files.readAllBytes(file), mimeType(file))
        }

        internal fun mimeType(file: Path): String =
            MIME_TYPES[file.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

        fun getInstance(project: Project): HostedWebviews = project.service()
    }
}
