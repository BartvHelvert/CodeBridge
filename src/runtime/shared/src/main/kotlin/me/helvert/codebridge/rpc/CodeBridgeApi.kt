package me.helvert.codebridge.rpc

import com.intellij.ide.ui.icons.IconId
import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** All paths in this API describe the backend machine, unless contents are explicitly streamed. */
@Rpc
interface CodeBridgeApi : RemoteApi<Unit> {
    suspend fun extensionChanges(): Flow<Long>

    /** The icons the enabled extensions contribute (`contributes.icons`), with the fonts that draw them. */
    suspend fun contributedIcons(): ContributedIconsDto
    suspend fun runtimeStatus(): RuntimeStatusDto

    /**
     * The user's VS Code `settings.json` on the backend, for the frontend to open in an editor.
     *
     * Made if this is the first time anything needed it, from VS Code's own when there is one.
     */
    suspend fun userSettings(): UserSettingsDto

    /** Every status bar item the project's extensions show; each emission replaces the last. */
    suspend fun statusItems(projectId: ProjectId): Flow<List<StatusItemDto>>

    /** Runs what clicking the item would run in VS Code. Nothing, if it has since gone. */
    suspend fun runStatusItem(projectId: ProjectId, entryId: String)

    /**
     * Runs a command link of the item's [StatusItemDto.tooltip], `codebridge-status:[index]`, as clicking
     * it in VS Code's hover would.
     *
     * By position rather than by command id, so the frontend can only ask for a command the
     * extension itself offered in the tooltip, never an arbitrary one.
     */
    suspend fun runStatusAction(projectId: ProjectId, entryId: String, index: Int)

    /**
     * Runs the command of the completion item chosen, named by the id the backend gave it, once the
     * backend's copy of the document reads as the client left it: [length] characters, whose text
     * hashes to [hash]. The client inserts the item itself, so its edit and this call travel apart.
     */
    suspend fun runCompletionCommand(projectId: ProjectId, id: Long, length: Int, hash: Int)

    /**
     * Opens [uri], a document an extension makes up for a scheme of its own, as following a link to
     * it does in VS Code: rust-analyzer's "full compiler diagnostic". False when no extension
     * provides that scheme, or its provider had nothing to show.
     */
    suspend fun openProvidedDocument(projectId: ProjectId, uri: String): Boolean

    /**
     * Messages extensions want shown, each until it is answered.
     *
     * A list rather than a stream of events, so a client that connects late -- or reconnects --
     * still sees the questions an extension is waiting on.
     */
    suspend fun messages(projectId: ProjectId): Flow<List<MessageDto>>

    /** The user's reply to message [id]: the index of the [MessageDto.items] entry, or null for none. */
    suspend fun answerMessage(projectId: ProjectId, id: Long, choice: Int?)

    /**
     * Quick picks and input boxes extensions want answered, each until it is: a list, as
     * [messages] are, so a client that connects late still sees the question the extension waits on.
     */
    suspend fun quickInputs(projectId: ProjectId): Flow<List<QuickInputDto>>

    /**
     * The user's answer to quick input [id]: the positions in [QuickInputDto.items] chosen, or the
     * value typed; both null when it was dismissed.
     */
    suspend fun answerQuickInput(projectId: ProjectId, id: Long, chosen: List<Int>?, value: String?)

    /**
     * What the user did in [QuickInputDto.live] picker [id], which stays open until the extension
     * hides it or the user dismisses it.
     */
    suspend fun changeQuickInput(projectId: ProjectId, id: Long, event: QuickInputEventDto)

    /**
     * Files an extension asked to show (`window.showTextDocument`), for the frontend to open.
     *
     * Opened there because in split mode that is where the user's editors are: an editor the
     * backend opens for itself is not one the user sees, and closing it again reads as the file
     * being closed. Events rather than state: a request nobody is connected to see has no one to
     * open it for, and the backend falls back to opening the file itself.
     */
    suspend fun openRequests(projectId: ProjectId): Flow<OpenFileDto>

    /**
     * What `setDecorations` draws, for the frontend to draw: each emission replaces one decoration
     * type's ranges, paint and before/after text in one file. A new subscriber first gets
     * everything drawn now.
     *
     * Drawn there, paint and text together, so they stay together as VS Code draws them. The text
     * as a backend inlay was redrawn only by a highlighting pass, which typing kept restarting,
     * while the paint followed each call at once -- Error Lens resets its decorations on nearly
     * every keystroke. While anyone subscribes, the backend draws neither, and its highlighters
     * only carry the ranges for hovers.
     */
    suspend fun decorations(projectId: ProjectId): Flow<DecorationsDto>

    /**
     * The image behind [DecorationsDto.gutterIcon], or null when it is gone or too large. An opaque
     * key rather than a path, so the frontend can only read images an extension named.
     */
    suspend fun decorationIcon(projectId: ProjectId, key: String): ByteArray?

    /**
     * Writes the colour at [offset] in the file at [path] -- a colour swatch's place -- as
     * [rgba] instead, the way the extension that found it writes colours. Answers whether it did.
     */
    suspend fun pickDocumentColor(projectId: ProjectId, path: String, offset: Int, rgba: Int): Boolean

    /**
     * Shell terminals an extension opened (`window.createTerminal`), and what it then does with
     * them, for the frontend to carry out in the Terminal tool window.
     *
     * Events rather than state, as [openRequests] are: the backend only sends these while someone
     * is listening, and keeps a terminal in a Run tool window tab of its own when nobody is -- an
     * IDE without the Terminal plugin.
     */
    suspend fun terminalRequests(projectId: ProjectId): Flow<TerminalRequestDto>

    /** What became of a terminal [terminalRequests] opened: it ended, or the user closed it. */
    suspend fun terminalEvent(projectId: ProjectId, event: TerminalEventDto)

    /**
     * The tree views enabled extensions contribute, and what their providers last said about each;
     * each emission replaces the last.
     *
     * State rather than events, as [statusItems] are: the views are known from the manifests before
     * any host runs, and a client that connects late must still be able to draw them.
     */
    suspend fun treeViews(projectId: ProjectId): Flow<List<TreeViewDto>>

    /**
     * Changes within a tree the frontend already drew: items the provider refreshed, and items it
     * asked to reveal. A refresh of the whole tree is not one of these; it bumps
     * [TreeViewDto.generation] instead, so a client that missed it still catches up.
     */
    suspend fun treeEvents(projectId: ProjectId): Flow<TreeEventDto>

    /**
     * The items under [handle] in view [viewId], or its roots for null.
     *
     * Asking for the roots of a view whose extension is not running starts the host and activates
     * the extension (`onView:`), as showing the view does in VS Code.
     */
    suspend fun treeChildren(projectId: ProjectId, viewId: String, handle: String?): TreeChildrenDto

    /** Whether view [viewId] is on screen; extensions often do nothing until theirs is. */
    suspend fun treeViewVisible(projectId: ProjectId, viewId: String, visible: Boolean)

    suspend fun treeItemExpanded(projectId: ProjectId, viewId: String, handle: String, expanded: Boolean)

    suspend fun treeSelection(projectId: ProjectId, viewId: String, handles: List<String>, focus: String?)

    /**
     * Runs the command of item [handle], as selecting it does in VS Code.
     *
     * By handle rather than by command, so the frontend can only run what the extension attached
     * to the item, never an arbitrary command.
     */
    suspend fun runTreeItem(projectId: ProjectId, viewId: String, handle: String)

    /**
     * The image behind [TreeItemDto.icon] or [TreeViewDto.containerIcon], or null when it is gone or
     * too large. An opaque key rather than a path, so the frontend can only read images an
     * extension named.
     */
    suspend fun treeIcon(projectId: ProjectId, key: String): ByteArray?

    /** The `view/item/context` menu of item [handle], as it stands now: for its right-click menu. */
    suspend fun treeItemActions(projectId: ProjectId, viewId: String, handle: String): List<TreeActionDto>

    /**
     * Runs menu entry [actionId] of view [viewId]: a title one for no [handle], else one of that
     * item's, with [selection] the items selected with it. Nothing, unless the entry is on offer
     * there now, so the frontend can only run what the extension's menus offer.
     */
    suspend fun runTreeAction(
        projectId: ProjectId,
        viewId: String,
        actionId: Int,
        handle: String?,
        selection: List<String>
    )

    /** The user checked or unchecked items of view [viewId]: each one's new state, by handle. */
    suspend fun treeCheckboxes(projectId: ProjectId, viewId: String, states: Map<String, Boolean>)

    /**
     * What extensions add to VS Code's Call Stack menu (`debug/callstack/context`) for the current
     * debug session's current frame: the one right-clicked, since right-clicking a frame selects it.
     */
    suspend fun callStackActions(projectId: ProjectId): List<TreeActionDto>

    /**
     * Runs the command link numbered [link] in view [viewId]'s welcome content
     * ([WelcomePartDto.link]); nothing, unless the content offers it now.
     */
    suspend fun runWelcomeLink(projectId: ProjectId, viewId: String, link: Int)

    /** Runs Call Stack menu entry [actionId] on that frame; nothing, unless it is offered there now. */
    suspend fun runCallStackAction(projectId: ProjectId, actionId: Int)

    /**
     * Every webview extensions show -- panels, and the views they have resolved -- with the page
     * each shows now; each emission replaces the last. State rather than events, so a client that
     * connects late still draws the panels already open.
     */
    suspend fun webviews(projectId: ProjectId): Flow<List<WebviewDto>>

    /** Messages extensions post to their pages, and panels they ask to bring forward. */
    suspend fun webviewEvents(projectId: ProjectId): Flow<WebviewEventDto>

    /**
     * The webview behind webview view [viewId], made if it has none: its extension woken
     * (`onView:`), starting the host if it has to, and its provider asked to fill it.
     */
    suspend fun resolveWebviewView(projectId: ProjectId, viewId: String): WebviewResolutionDto

    /**
     * A message page [handle] posted (`acquireVsCodeApi().postMessage`), as JSON; with
     * [WebviewDto.binaryMessages], the `ArrayBuffer`s in it are [buffers], which its
     * `$$vscode_array_buffer_reference$$` entries name by position.
     */
    suspend fun webviewMessage(projectId: ProjectId, handle: String, json: String, buffers: List<ByteArray>)

    /**
     * What page [handle] saved with `setState`, as JSON: kept so a panel its extension can restore
     * comes back with it after a restart.
     */
    suspend fun webviewState(projectId: ProjectId, handle: String, state: String?)

    /**
     * What page [handle] asked for at [url], an `asWebviewUri` address; null when it names nothing
     * the webview's `localResourceRoots` allow, so a page can only read what its extension let it.
     */
    suspend fun webviewResource(projectId: ProjectId, handle: String, url: String): WebviewResourceDto?

    /**
     * Whether webview [handle] is on screen, and for a panel whether it is the editor in front and
     * the 0-based index of the editor group it is in. A [WebviewDto.dormant] panel on screen is
     * restored.
     */
    suspend fun webviewShown(projectId: ProjectId, handle: String, visible: Boolean, active: Boolean, position: Int)

    /** The user closed panel [handle]. */
    suspend fun closeWebview(projectId: ProjectId, handle: String)

    /**
     * Follows link [uri] page [handle] followed: a `command:` link is run, if its options allow that
     * command, and a link into the editor (`vscode://...`) goes to the extension it names.
     */
    suspend fun runWebviewLink(projectId: ProjectId, handle: String, uri: String)

    /**
     * What the project's extensions do on the user's machine -- open a link in the browser, read or
     * write the clipboard -- for this client to do; each emission replaces the last. A request stays
     * until [answerClientRequest] answers it.
     */
    suspend fun clientRequests(projectId: ProjectId): Flow<List<ClientRequestDto>>

    /** Whether request [id] was done, and for a clipboard read the [text] read; the extension is waiting on it. */
    suspend fun answerClientRequest(projectId: ProjectId, id: Long, done: Boolean, text: String?)

    /**
     * Gives out links into the editor -- an extension's `env.asExternalUri` for its sign-in callback
     * -- on this client's built-in web server at [port], for as long as the flow is collected. The
     * browser that opens them is this client's, and in split mode the backend's server is out of its
     * reach -- or under one of [schemes], the editor's own that this client's machine hands to
     * CodeBridge's launcher. Emits nothing.
     */
    suspend fun appLinkPort(port: Int, schemes: List<String>): Flow<Unit>

    /**
     * The scheme extensions are told is the editor's own (`vscodium`), which links into it are given
     * out under; null when no extension host can run.
     */
    suspend fun appUriScheme(): String?

    /**
     * Delivers a link into the editor the browser opened, [requestUri] being its path and query, to
     * the extension it names. A link the backend did not give out goes to [focused]'s extensions,
     * once the user allows it. Emits one outcome, once the extension has it or will not: a stream,
     * because a call is given a minute to answer and the user may take longer than that.
     */
    suspend fun openAppLink(requestUri: String, focused: ProjectId?): Flow<AppLinkOutcomeDto>

    companion object {
        suspend fun getInstance(): CodeBridgeApi = RemoteApiProviderService.resolve(remoteApiDescriptor<CodeBridgeApi>())
    }
}

/**
 * One `window.createStatusBarItem`, as the extension last described it.
 *
 * [text] keeps VS Code's `$(icon)` references: turning them into something IntelliJ draws is the
 * frontend's job. [tooltip] is the extension's Markdown rendered as hovers are, in blocks; see
 * [StatusTooltipBlockDto]. [severity] is "warning" or "error" when the extension asked for that
 * background, which is how it says something is wrong.
 */
@Serializable
data class StatusItemDto(
    val entryId: String,
    val extensionId: String? = null,
    val name: String = "",
    val text: String = "",
    val tooltip: List<StatusTooltipBlockDto>? = null,
    val clickable: Boolean = false,
    val severity: String? = null,
    val alignLeft: Boolean = true,
    val priority: Double? = null,
) {
    companion object {
        /** What a command link in [tooltip] points at, followed by its index; `StatusBar.LINK_SCHEME`. */
        const val LINK_SCHEME: String = "codebridge-status:"
    }
}

/**
 * A part of a status bar item's tooltip, from top to bottom: [html], a list of [actions], or a
 * [separator] -- the Markdown's `---`.
 *
 * [html] is HTML: a command link the extension trusted points at `codebridge-status:N`, which
 * [CodeBridgeApi.runStatusAction] runs, and a `$(icon)` it asked to be drawn is `<icon src="codicon:name">`.
 * [actions] are consecutive paragraphs that each hold only such a link -- rust-analyzer's Open Logs,
 * Restart server and the rest -- which the frontend draws as the rows of a popup list.
 */
@Serializable
data class StatusTooltipBlockDto(
    val html: String? = null,
    val actions: List<StatusTooltipActionDto> = emptyList(),
    val separator: Boolean = false,
)

/**
 * One row of [StatusTooltipBlockDto.actions]: runs `codebridge-status:[index]`. [icon] is the `codicon:name`
 * the link's text started with, and [title] is the link's own title, its tooltip in VS Code.
 */
@Serializable
data class StatusTooltipActionDto(
    val index: Int,
    val label: String,
    val icon: String? = null,
    val title: String? = null,
)

/**
 * `window.showInformationMessage` and its siblings, for the frontend to show.
 *
 * [severity] is "info", "warning" or "error". [items] are the buttons; [cancelIndex] is the one
 * VS Code treats as "close" (`isCloseAffordance`), which a modal dialog's Cancel answers with.
 */
@Serializable
data class MessageDto(
    val id: Long,
    val severity: String,
    val text: String,
    val detail: String? = null,
    val modal: Boolean = false,
    val source: String? = null,
    val items: List<String> = emptyList(),
    val cancelIndex: Int? = null,
)

/**
 * A question for the user: a list to choose from when [kind] is [PICK], a value to type when it is
 * [INPUT]. [problem] is why the value last typed was refused, to show while asking again.
 *
 * A [live] pick is a picker the extension built itself (`createQuickPick`): it has a box to type
 * in, answers through [CodeBridgeApi.changeQuickInput] rather than once, and comes again under the same id
 * whenever the extension changes it. [valueRevision] and [itemsRevision] count the extension's
 * changes to [value] and [items], so that a client knows a [value] it has not typed over yet, and
 * names the [items] its events refer to.
 */
@Serializable
data class QuickInputDto(
    val id: Long,
    val kind: String,
    val title: String? = null,
    val placeholder: String? = null,
    val prompt: String? = null,
    val items: List<QuickItemDto> = emptyList(),
    val canPickMany: Boolean = false,
    val value: String = "",
    val password: Boolean = false,
    val problem: String? = null,
    val live: Boolean = false,
    /** Still working out its items: VS Code's progress bar. */
    val busy: Boolean = false,
    /** Whether typing filters on [QuickItemDto.description] and [QuickItemDto.detail] too, not just the label. */
    val matchOnDescription: Boolean = false,
    val matchOnDetail: Boolean = false,
    /** Kept open when the user clicks elsewhere. */
    val ignoreFocusOut: Boolean = false,
    /** The position in [items] the extension made the active one, if it did. */
    val active: Int? = null,
    val valueRevision: Int = 0,
    val itemsRevision: Int = 0,
) {
    companion object {
        const val PICK: String = "pick"
        const val INPUT: String = "input"
    }
}

/** One entry of a quick pick; a [separator] starts a group named [label] and cannot be chosen. */
@Serializable
data class QuickItemDto(
    val label: String,
    val description: String? = null,
    val detail: String? = null,
    val picked: Boolean = false,
    val separator: Boolean = false,
    /** Shown whatever is typed in a [QuickInputDto.live] pick. */
    val alwaysShow: Boolean = false,
)

/**
 * What the user did in a [QuickInputDto.live] pick: typed [value] ([VALUE]), moved to the item at
 * [chosen] ([ACTIVE]), chose it -- or chose nothing, with no item matching -- ([ACCEPT]), or closed
 * it ([HIDE]). [chosen] are positions in the items of [itemsRevision].
 */
@Serializable
data class QuickInputEventDto(
    val kind: String,
    val value: String? = null,
    val chosen: List<Int> = emptyList(),
    val itemsRevision: Int = 0,
) {
    companion object {
        const val VALUE: String = "value"
        const val ACTIVE: String = "active"
        const val ACCEPT: String = "accept"
        const val HIDE: String = "hide"
    }
}

/**
 * What the settings page reports. Read-only: the runtime is not a preference.
 *
 * [directory] is where the bundled runtime lives on the backend machine, or empty when the plugin
 * cannot find its own installation. [version] is the bundled runtime's, empty when there is none.
 */
@Serializable
data class RuntimeStatusDto(
    val name: String,
    val version: String,
    val installed: Boolean,
    val directory: String,
    val supported: Boolean,
    val unsupportedMessage: String?,
    val diagnosis: String,
    val usable: Boolean,
    val source: String,
)

/** A file to open for the user, at a 0-based line and column, taking focus if [focus]. */
@Serializable
data class OpenFileDto(val file: VirtualFileId, val line: Int, val column: Int, val focus: Boolean)

/**
 * Decoration type [key]'s ranges in the file at [path], as document offsets, replacing what it had
 * there; no [look] means nothing to paint on the text. [wholeLine] widens each range to its full
 * lines, for the paint and for where its before and after text go.
 *
 * A path rather than a [VirtualFileId]: a split-mode backend sends a local file's id without a
 * serialized value, which a client cannot resolve. The client's editors have the file under the
 * backend's path, in its own file system.
 */
@Serializable
data class DecorationsDto(
    val path: String,
    val key: String,
    val ranges: List<DecorationRangeDto> = emptyList(),
    val look: DecorationLookDto? = null,
    val wholeLine: Boolean = false,
    /** The key of the type's gutter icon in the theme now in use, for [CodeBridgeApi.decorationIcon]. */
    val gutterIcon: String? = null,
) {
    companion object {
        /**
         * The key of the colour swatches CodeBridge draws before each colour an extension finds;
         * clicking one picks a colour for [CodeBridgeApi.pickDocumentColor]. Not a number, so never the key
         * of an extension's own decoration type.
         */
        const val DOCUMENT_COLORS: String = "codebridge.documentColors"
    }
}

/** A decorated range, with the text drawn before and after it in the theme now in use. */
@Serializable
data class DecorationRangeDto(
    val start: Int,
    val end: Int,
    val before: DecorationTextDto? = null,
    val after: DecorationTextDto? = null,
    /** The tooltip of the gutter icon: the range's first hover message, as Markdown. */
    val gutterTooltip: String? = null,
)

/**
 * Text drawn next to a decorated range, in [color] as ARGB when the extension gave one, in a box at
 * least [width] wide, filled with [background] as ARGB, [margin] from what is around it. The box
 * has a border [borderWidth] wide on each side in [borderColor], and a line along its top in
 * [overline]. [width], [margin] and [borderWidth] are CSS, as the extension wrote them.
 */
@Serializable
data class DecorationTextDto(
    val text: String,
    val color: Int? = null,
    val width: String? = null,
    val background: Int? = null,
    val margin: String? = null,
    val borderWidth: String? = null,
    val borderColor: Int? = null,
    val overline: Int? = null,
)

/**
 * How a decoration type paints, in each theme. The greedy flags are VS Code's `rangeBehavior`,
 * whether typing at an edge grows the range.
 */
@Serializable
data class DecorationLookDto(
    val light: DecorationPaintDto?,
    val dark: DecorationPaintDto?,
    val greedyToLeft: Boolean = false,
    val greedyToRight: Boolean = false,
)

/**
 * Text attributes, with colours as ARGB and effects by `EffectType` name: the main effect first,
 * when there is one, then the additional ones.
 */
@Serializable
data class DecorationPaintDto(
    val foreground: Int? = null,
    val background: Int? = null,
    val errorStripe: Int? = null,
    val fontType: Int = 0,
    val effects: List<DecorationEffectDto> = emptyList(),
)

@Serializable
data class DecorationEffectDto(val type: String, val color: Int)

/**
 * One thing to do to terminal [id]. [kind] says which, and which of the other fields it reads:
 * [OPEN] all of [name], [command] (empty for the user's own shell), [cwd] and [env]; [TYPE] [text];
 * [SHOW] [preserveFocus]; [DISPOSE] nothing else.
 */
@Serializable
data class TerminalRequestDto(
    val kind: String,
    val id: Int,
    val name: String = "",
    val command: List<String> = emptyList(),
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val text: String = "",
    val preserveFocus: Boolean = false,
) {
    companion object {
        const val OPEN: String = "open"
        const val TYPE: String = "type"
        const val SHOW: String = "show"
        const val DISPOSE: String = "dispose"
    }
}

@Serializable
data class UserSettingsDto(val file: VirtualFileId)

/** Terminal [id] has ended: its process exited, or [byUser] the user closed its tab. */
@Serializable
data class TerminalEventDto(val id: Int, val byUser: Boolean)

/**
 * One view from `contributes.views`, in the view container it belongs to.
 *
 * [containerId] is the `viewsContainers` entry, or one of VS Code's own (`explorer`, `scm`,
 * `debug`, `test`), titled [containerTitle]. [panel] says it lives in VS Code's bottom panel rather
 * than its side bar. [containerIcon] and [icon] are a `$(codicon)` or a [CodeBridgeApi.treeIcon] key.
 *
 * [registered] is whether a provider is answering for it now; [generation] changes whenever the
 * whole tree has to be fetched again. [title], [description], [message] and [badge] are what the
 * extension set at runtime; [message] is HTML, made from the extension's text or Markdown.
 */
@Serializable
data class TreeViewDto(
    val id: String,
    val name: String,
    val extensionId: String,
    val containerId: String,
    val containerTitle: String,
    val containerIcon: String? = null,
    val panel: Boolean = false,
    val icon: String? = null,
    val registered: Boolean = false,
    val generation: Int = 0,
    val title: String? = null,
    val description: String? = null,
    val message: String? = null,
    val badge: Int? = null,
    val showCollapseAll: Boolean = false,
    val canSelectMany: Boolean = false,
    /** Whether checking an item leaves the items under and above it alone; see `manageCheckboxStateManually`. */
    val manualCheckboxes: Boolean = false,
    /** The view's `view/title` menu as it stands now: [TreeActionDto.NAVIGATION] ones as buttons. */
    val actions: List<TreeActionDto> = emptyList(),
    /** Whether the view's `when` clause holds now; VS Code does not show it otherwise. */
    val visible: Boolean = true,
    /** `"visibility": "hidden"`: left out until the user shows it from its container's menu. */
    val hiddenByDefault: Boolean = false,
    /** `"visibility": "collapsed"`: shown collapsed until the user opens it. */
    val collapsedByDefault: Boolean = false,
    /** What the view shows while it has no items: `contributes.viewsWelcome` whose `when` holds now. */
    val welcome: List<WelcomeLineDto> = emptyList(),
    /** A webview view (`"type": "webview"`): drawn by its extension's page, see [CodeBridgeApi.resolveWebviewView], rather than as a tree. */
    val webview: Boolean = false,
) {
    /** What the view's tab reads: its title, and its [badge] as VS Code counts it beside the title. */
    val tabTitle: String get() = (title ?: name) + (badge?.let { " ($it)" } ?: "")
}

/** One line of a view's welcome content; a [button] is a line that is a single link. */
@Serializable
data class WelcomeLineDto(val parts: List<WelcomePartDto>, val button: Boolean = false)

/**
 * Text, or a link reading [text]: a command the backend runs as [link] -- see
 * [CodeBridgeApi.runWelcomeLink] -- or a web address [url] to open.
 */
@Serializable
data class WelcomePartDto(val text: String, val link: Int? = null, val url: String? = null)

/**
 * One entry of an extension's menu for a view or its items: a command, by the [id] the backend runs
 * it by. [group] is its menu group, in order; [NAVIGATION] in a view's title and [INLINE] on an
 * item are the ones VS Code shows as buttons. [icon] and [iconDark] are as for [TreeItemDto].
 * An entry with [children] is a submenu, which runs nothing itself.
 */
@Serializable
data class TreeActionDto(
    val id: Int,
    val title: String,
    val icon: String? = null,
    val iconDark: String? = null,
    val group: String = "",
    val enabled: Boolean = true,
    val children: List<TreeActionDto> = emptyList(),
) {
    companion object {
        const val NAVIGATION: String = "navigation"
        const val INLINE: String = "inline"
    }
}

/**
 * One item of a tree view. [handle] names it to the backend; [label] already falls back to the
 * name of [resourcePath] when the extension gave none.
 *
 * [icon] is a `$(codicon)` or a [CodeBridgeApi.treeIcon] key; with no icon and a [resourceName], the
 * frontend draws the file's own icon, as VS Code does: [fileIcon], the backend's for the file it
 * has, else one for the file's name. The frontend alone may not know the file's type, nor have
 * the plugins that give it an icon of its own. [expandable] and [expanded] come from the
 * item's collapsible state. [tooltip] is HTML, made from the extension's text or Markdown.
 */
@Serializable
data class TreeItemDto(
    val handle: String,
    val label: String,
    val description: String? = null,
    val tooltip: String? = null,
    val icon: String? = null,
    /** The image to draw instead of [icon] under a dark theme, when the extension gave one. */
    val iconDark: String? = null,
    val resourcePath: String? = null,
    val fileIcon: IconId? = null,
    val folder: Boolean = false,
    val expandable: Boolean = false,
    val expanded: Boolean = false,
    val hasCommand: Boolean = false,
    val checked: Boolean? = null,
    /** The item's `inline` menu entries as they stand now, which VS Code shows as buttons on its row. */
    val inline: List<TreeActionDto> = emptyList(),
    /** Filename for icon lookup for any URI scheme; [resourcePath] is only for local file actions. */
    val resourceName: String? = null,
)

/**
 * A [CodeBridgeApi.treeChildren] answer: the items, or why there are none to show. [unregistered] marks the
 * [problem] of no extension having registered the view's provider, under which VS Code still shows the
 * view's welcome content -- the very content some extensions wait on before they register.
 */
@Serializable
data class TreeChildrenDto(
    val items: List<TreeItemDto> = emptyList(),
    val problem: String? = null,
    val unregistered: Boolean = false,
)

/**
 * A change inside view [viewId]. [REFRESH] replaces [items] where the tree has them and fetches
 * their children again. [REVEAL] opens [path], root first, to its last item, selecting it if
 * [select] and focusing the view if [focus], then opens [expand] levels below; an empty path
 * reveals the view alone.
 */
@Serializable
data class TreeEventDto(
    val kind: String,
    val viewId: String,
    val items: List<TreeItemDto> = emptyList(),
    val path: List<String> = emptyList(),
    val select: Boolean = false,
    val focus: Boolean = false,
    val expand: Int = 0,
) {
    companion object {
        const val REFRESH: String = "refresh"
        const val REVEAL: String = "reveal"

        /** `<view>.focus` and the like: show the view and put the focus in it. */
        const val FOCUS: String = "focus"

        /** `workbench.actions.treeView.<view>.collapseAll`. */
        const val COLLAPSE_ALL: String = "collapseAll"
    }
}

/**
 * One webview: a [PANEL] in an editor tab, or a [VIEW] in a tool window tab, whose [viewType] is
 * then the id of the view it fills. [html] is the page as the extension last set it. [scripts],
 * [forms] and [commandLinks] are what its options allow: running scripts, submitting forms and
 * following `command:` links.
 *
 * A panel that outlived its extension host -- the IDE restarted, or the host did -- is [dormant]
 * until its extension's serializer fills it again, which happens once it is on screen; [problem] is
 * why that failed, and [restoredState] what its page had saved, for the page to start from.
 */
@Serializable
data class WebviewDto(
    val handle: String,
    val kind: String,
    val viewType: String,
    val extensionId: String,
    val title: String = "",
    val html: String = "",
    /**
     * Counts the extension's settings of [html]. Each one reloads the page, even to the same HTML:
     * extensions reload a page by clearing its HTML and setting it again, and the updates in between
     * may be merged on the way here.
     */
    val htmlVersion: Int = 0,
    val scripts: Boolean = false,
    val forms: Boolean = false,
    val commandLinks: Boolean = false,
    /** Whether `ArrayBuffer`s in the page's messages are to reach the extension as buffers rather than as JSON. */
    val binaryMessages: Boolean = false,
    /** A panel's `enableFindWidget`: whether the user can search its page. */
    val findWidget: Boolean = false,
    /** A panel's `iconPath`, as [CodeBridgeApi.treeIcon] keys: for light themes, and for dark ones. */
    val icon: String? = null,
    val iconDark: String? = null,
    /** Where a new panel opens: [ACTIVE_GROUP], [SIDE_GROUP] or an editor group's 0-based index. */
    val column: Int = ACTIVE_GROUP,
    /** Whether a new panel opens without taking the focus. */
    val preserveFocus: Boolean = false,
    val dormant: Boolean = false,
    val problem: String? = null,
    val restoredState: String? = null,
    /** Extension-contributed color defaults by id and theme kind (light/dark). */
    val themeColors: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        const val PANEL: String = "panel"
        const val VIEW: String = "view"

        /** The editor group in front. */
        const val ACTIVE_GROUP: Int = -1

        /** A group beside the one in front, made if there is none. */
        const val SIDE_GROUP: Int = -2
    }
}

/**
 * Something for webview [handle]: a [MESSAGE] [json] for its page, with the binary [buffers] its
 * `$$vscode_array_buffer_reference$$` entries name; or [REVEAL], to bring a panel forward in editor
 * group [column] (see [WebviewDto.column]), taking the focus unless [preserveFocus].
 */
@Serializable
data class WebviewEventDto(
    val kind: String,
    val handle: String,
    val json: String = "",
    val buffers: List<ByteArray> = emptyList(),
    val preserveFocus: Boolean = false,
    val column: Int = WebviewDto.ACTIVE_GROUP,
) {
    companion object {
        const val MESSAGE: String = "message"
        const val REVEAL: String = "reveal"
    }
}

/** A [CodeBridgeApi.resolveWebviewView] answer: the webview's handle, or why there is none. */
@Serializable
data class WebviewResolutionDto(val handle: String? = null, val problem: String? = null)

/** A file a page asked for, and its media type. */
@Serializable
data class WebviewResourceDto(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean =
        other is WebviewResourceDto && bytes.contentEquals(other.bytes) && mimeType == other.mimeType

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

/**
 * The icons enabled extensions contribute: glyphs of their icon fonts, and names that are another
 * icon's. Ids are those an extension writes as `$(id)`.
 */
@Serializable
data class ContributedIconsDto(
    val fonts: List<IconFontDto> = emptyList(),
    val aliases: Map<String, String> = emptyMap()
)

/** An icon font as the extension ships it, and the code point of each icon id it draws. */
@Serializable
data class IconFontDto(val font: ByteArray, val glyphs: Map<String, Int>) {
    override fun equals(other: Any?): Boolean =
        other is IconFontDto && font.contentEquals(other.font) && glyphs == other.glyphs

    override fun hashCode(): Int = 31 * font.contentHashCode() + glyphs.hashCode()
}

/**
 * Something an extension asked to do on the user's machine, numbered for the answer: a [kind] of
 * [BROWSE] with the link as [value], [READ_CLIPBOARD], or [WRITE_CLIPBOARD] with the text as [value].
 */
@Serializable
data class ClientRequestDto(val id: Long, val kind: String, val value: String? = null) {
    companion object {
        const val BROWSE: String = "browse"
        const val READ_CLIPBOARD: String = "readClipboard"
        const val WRITE_CLIPBOARD: String = "writeClipboard"
    }
}

/**
 * What became of a link into the editor: [extension] took it, in [projectId], unless the user
 * [declined] it or nothing could -- then [problem] says why.
 */
@Serializable
data class AppLinkOutcomeDto(
    val extension: String? = null,
    val projectId: ProjectId? = null,
    val declined: Boolean = false,
    val problem: String? = null,
)
