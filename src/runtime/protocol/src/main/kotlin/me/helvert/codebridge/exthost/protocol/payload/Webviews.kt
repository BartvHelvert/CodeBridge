package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import java.net.URI

/**
 * `MainThreadWebviews`, `MainThreadWebviewPanels` and `MainThreadWebviewViews`, and the
 * `ExtHostWebview*` calls that answer them: pages an extension draws with HTML of its own.
 *
 * A webview is named by a handle -- a UUID -- that whoever made it chose: the host for a panel
 * (`createWebviewPanel`), the workbench for a view, which it hands over in `$resolveWebviewView`.
 * Its content is the extension's: HTML, options and messages are all addressed by that handle on
 * `MainThreadWebviews`, whichever kind of webview it is.
 */
object Webviews {

    const val SERVICE: String = "MainThreadWebviews"
    const val PANELS: String = "MainThreadWebviewPanels"
    const val VIEWS: String = "MainThreadWebviewViews"

    const val HOST: String = "ExtHostWebviews"
    const val HOST_PANELS: String = "ExtHostWebviewPanels"
    const val HOST_VIEWS: String = "ExtHostWebviewViews"

    const val SET_HTML: String = $$"$setHtml"
    const val SET_OPTIONS: String = $$"$setOptions"
    const val POST_MESSAGE: String = $$"$postMessage"

    const val CREATE_PANEL: String = $$"$createWebviewPanel"
    const val DISPOSE_PANEL: String = $$"$disposeWebview"
    const val REVEAL: String = $$"$reveal"
    const val SET_TITLE: String = $$"$setTitle"
    const val SET_ICON_PATH: String = $$"$setIconPath"
    const val REGISTER_SERIALIZER: String = $$"$registerSerializer"
    const val UNREGISTER_SERIALIZER: String = $$"$unregisterSerializer"

    const val REGISTER_VIEW_PROVIDER: String = $$"$registerWebviewViewProvider"
    const val UNREGISTER_VIEW_PROVIDER: String = $$"$unregisterWebviewViewProvider"
    const val SET_VIEW_TITLE: String = $$"$setWebviewViewTitle"
    const val SET_VIEW_DESCRIPTION: String = $$"$setWebviewViewDescription"
    const val SET_VIEW_BADGE: String = $$"$setWebviewViewBadge"
    const val SHOW_VIEW: String = $$"$show"

    const val ON_MESSAGE: String = $$"$onMessage"
    const val ON_PANEL_VIEW_STATES: String = $$"$onDidChangeWebviewPanelViewStates"
    const val ON_PANEL_DISPOSED: String = $$"$onDidDisposeWebviewPanel"
    const val RESOLVE_VIEW: String = $$"$resolveWebviewView"
    const val ON_VIEW_VISIBILITY: String = $$"$onDidChangeWebviewViewVisibility"
    @Suppress("unused") // part of the protocol, not called yet
    const val DISPOSE_VIEW: String = $$"$disposeWebviewView"
    const val DESERIALIZE_PANEL: String = $$"$deserializeWebviewPanel"

    /** The activation event that wakes an extension to restore its panels of a view type. */
    const val ON_WEBVIEW_PANEL: String = "onWebviewPanel:"

    /** `EditorGroupColumn`s with a meaning of their own: the group in front, and one beside it. */
    const val ACTIVE_GROUP: Int = -1
    const val SIDE_GROUP: Int = -2

    /** The extension a webview belongs to: `{id, location}`, the host's `WebviewExtensionDescription`. */
    data class Extension(val id: String, val location: UriComponents?)

    /**
     * `WebviewContentOptions`. [commandUris] is null when `command:` links may not run anything, an
     * empty list when any command may, and otherwise the commands that may.
     * [localResourceRoots] is null when the extension named none -- the host then fills in the
     * workspace folders and the extension's own directory -- and empty when it allows none.
     */
    data class Options(
        val scripts: Boolean = false,
        val forms: Boolean = false,
        val commandUris: List<String>? = null,
        val localResourceRoots: List<UriComponents>? = null,
        /** The options as the host sent them, to hand back when the panel is restored. */
        val json: JsonObject = JsonObject(emptyMap()),
    )

    /** `$createWebviewPanel(extension, handle, viewType, initData, showOptions)`. */
    data class Panel(
        val extension: Extension,
        val handle: String,
        val viewType: String,
        val title: String,
        val options: Options,
        val preserveFocus: Boolean,
        /** The `EditorGroupColumn` to open in: [ACTIVE_GROUP], [SIDE_GROUP] or a group's 0-based index. */
        val column: Int = ACTIVE_GROUP,
        /** `panelOptions`, as the host sent them, to hand back when the panel is restored. */
        val panelOptions: JsonObject = JsonObject(emptyMap()),
        /** Whether the extension expects `ArrayBuffer`s in a page's messages to arrive as buffers. */
        val binaryMessages: Boolean = false,
    ) {
        /** `enableFindWidget`: whether the page can be searched, as VS Code's find widget does. */
        val findWidget: Boolean get() = findWidget(panelOptions)
    }

    /** `enableFindWidget` of a panel's `panelOptions`. */
    fun findWidget(panelOptions: JsonObject): Boolean = (panelOptions["enableFindWidget"] as? JsonPrimitive)?.booleanOrNull ?: false

    /** `$registerWebviewViewProvider(extension, viewType, options)`. */
    data class ViewProvider(val extension: Extension, val viewType: String, val binaryMessages: Boolean = false)

    /** `$reveal(handle, {viewColumn, preserveFocus})`. */
    data class Reveal(val handle: String, val column: Int, val preserveFocus: Boolean)

    /** `$setIconPath(handle, {light, dark}?)`: the images for light and dark themes, or none. */
    data class IconPath(val handle: String, val light: UriComponents?, val dark: UriComponents?)

    /**
     * `$postMessage(handle, message, ...buffers)`: [json] is the message as the host serialized it,
     * where an `ArrayBuffer` became `{"$$vscode_array_buffer_reference$$": true, "index": n}` naming
     * the n-th of [buffers].
     */
    data class Message(val handle: String, val json: String, val buffers: List<ByteArray>)

    fun handle(method: String, argsJson: String): String = Args.of(method, argsJson).str(0)

    /** `$setHtml(handle, html)`. */
    fun html(argsJson: String): Pair<String, String> {
        val args = Args.of(SET_HTML, argsJson)
        return args.str(0) to (args.strOrNull(1) ?: "")
    }

    /** `$setOptions(handle, options)`. */
    fun options(argsJson: String): Pair<String, Options> {
        val args = Args.of(SET_OPTIONS, argsJson)
        return args.str(0) to options(args.objOrNull(1))
    }

    /**
     * `$postMessage`, with the binary arguments the RPC layer took out of the JSON: by position, so
     * the buffers keep the order the host's references count them in.
     */
    fun message(argsJson: String, buffers: Map<Int, ByteArray>): Message {
        val args = Args.of(POST_MESSAGE, argsJson)
        return Message(args.str(0), args.strOrNull(1) ?: "null", buffers.toSortedMap().filterKeys { it >= 2 }.values.toList())
    }

    fun panel(argsJson: String): Panel {
        val args = Args.of(CREATE_PANEL, argsJson)
        val init = args.objOrNull(3)
        val show = args.objOrNull(4)
        return Panel(
            extension = extension(args.objOrNull(0)),
            handle = args.str(1),
            viewType = args.str(2),
            title = init?.string("title").orEmpty(),
            options = options(init?.get("webviewOptions") as? JsonObject),
            preserveFocus = (show?.get("preserveFocus") as? JsonPrimitive)?.booleanOrNull ?: false,
            column = column(show),
            panelOptions = init?.get("panelOptions") as? JsonObject ?: JsonObject(emptyMap()),
            binaryMessages = (init?.get("serializeBuffersForPostMessage") as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    fun reveal(argsJson: String): Reveal {
        val args = Args.of(REVEAL, argsJson)
        val show = args.objOrNull(1)
        return Reveal(args.str(0), column(show), (show?.get("preserveFocus") as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    /** `viewColumn`, already an `EditorGroupColumn` as the host converts it; absent means the group in front. */
    private fun column(show: JsonObject?): Int = (show?.get("viewColumn") as? JsonPrimitive)?.intOrNull ?: ACTIVE_GROUP

    fun iconPath(argsJson: String): IconPath {
        val args = Args.of(SET_ICON_PATH, argsJson)
        val paths = args.objOrNull(1)
        return IconPath(args.str(0), UriComponents.from(paths?.get("light")), UriComponents.from(paths?.get("dark")))
    }

    /** `$setTitle(handle, title)` and the view's title and description setters. */
    fun text(method: String, argsJson: String): Pair<String, String?> {
        val args = Args.of(method, argsJson)
        return args.str(0) to args.strOrNull(1)
    }

    /** `$reveal(handle, {viewColumn, preserveFocus})`, and the view's `$show(handle, preserveFocus)`: whether to keep the focus. */
    fun preserveFocus(method: String, argsJson: String): Pair<String, Boolean> {
        val args = Args.of(method, argsJson)
        val preserve = if (method == SHOW_VIEW) args.boolOrNull(1)
            else (args.objOrNull(1)?.get("preserveFocus") as? JsonPrimitive)?.booleanOrNull
        return args.str(0) to (preserve ?: false)
    }

    /** `$setWebviewViewBadge(handle, badge?)`; no value takes the badge away. */
    fun badge(argsJson: String): Pair<String, Int?> {
        val args = Args.of(SET_VIEW_BADGE, argsJson)
        return args.str(0) to (args.objOrNull(1)?.get("value") as? JsonPrimitive)?.intOrNull
    }

    fun viewProvider(argsJson: String): ViewProvider {
        val args = Args.of(REGISTER_VIEW_PROVIDER, argsJson)
        val binary = (args.objOrNull(2)?.get("serializeBuffersForPostMessage") as? JsonPrimitive)?.booleanOrNull ?: false
        return ViewProvider(extension(args.objOrNull(0)), args.str(1), binary)
    }

    /**
     * `$onMessage(handle, message, buffers)`. The third argument is a `SerializableObjectWithBuffers`
     * whose value -- the buffers [message]'s `$$vscode_array_buffer_reference$$` entries name -- the
     * host reads. With no buffers it goes as plain JSON; with some, its slot holds the value with a
     * `$$ref$$` where each buffer goes, and the buffers themselves travel beside the call's JSON, at
     * [ON_MESSAGE_BUFFERS].
     */
    fun onMessage(handle: String, message: String, buffers: Int = 0): String = buildJsonArray {
        add(handle); add(message)
        if (buffers == 0) add(buildJsonObject { put("value", JsonArray(emptyList())) })
        else add(JsonArray(List(buffers) { index -> buildJsonObject { put(BUFFER_REF, index) } }))
    }.toString()

    /** How the RPC protocol marks where a buffer goes in a `SerializableObjectWithBuffers`: `{"$$ref$$": n}`. */
    private const val BUFFER_REF: String = $$$"$$ref$$"

    /** The position of [onMessage]'s buffers among its arguments. */
    const val ON_MESSAGE_BUFFERS: Int = 2

    /**
     * `$deserializeWebviewPanel(handle, viewType, {title, state, panelOptions, webviewOptions, active}, position)`:
     * a panel for the extension's serializer to fill again, with the [state] its page last saved, as JSON.
     */
    fun deserializePanel(
        handle: String, viewType: String, title: String, state: String?, panelOptions: JsonObject, webviewOptions: JsonObject,
        active: Boolean, position: Int,
    ): String = buildJsonArray {
        add(handle); add(viewType)
        add(buildJsonObject {
            put("title", title)
            // The host hands the page's state to `deserializeWebviewPanel` as a value, not as text.
            put("state", state?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
            put("panelOptions", panelOptions)
            put("webviewOptions", webviewOptions)
            put("active", active)
        })
        add(position.coerceAtLeast(0))
    }.toString()

    /** `$resolveWebviewView(handle, viewType, title, state)`; the cancellation token is the RPC layer's. */
    fun resolveView(handle: String, viewType: String, title: String?, state: String?): String = buildJsonArray {
        add(handle); add(viewType); add(title?.let(::JsonPrimitive) ?: JsonNull); add(state?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    /** `$onDidChangeWebviewPanelViewStates`, for one panel; [position] is its editor group's 0-based index. */
    fun panelViewState(handle: String, active: Boolean, visible: Boolean, position: Int = 0): String = buildJsonArray {
        add(buildJsonObject {
            putJsonObject(handle) { put("active", active); put("visible", visible); put("position", position.coerceAtLeast(0)) }
        })
    }.toString()

    /** The handle alone, as `$onDidDisposeWebviewPanel` and `$disposeWebviewView` take it. */
    fun handleArgs(handle: String): String = buildJsonArray { add(handle) }.toString()

    fun visibility(handle: String, visible: Boolean): String = buildJsonArray { add(handle); add(visible) }.toString()

    private fun extension(json: JsonObject?): Extension {
        val id = when (val value = json?.get("id")) {
            is JsonPrimitive -> value.contentOrNull
            // An `ExtensionIdentifier` crosses as `{value, _lower}`.
            is JsonObject -> value.string("value")
            else -> null
        }
        return Extension(id.orEmpty(), UriComponents.from(json?.get("location")))
    }

    /** `WebviewContentOptions` from its JSON. */
    fun options(json: JsonObject?): Options {
        if (json == null) return Options()
        fun flag(name: String) = (json[name] as? JsonPrimitive)?.booleanOrNull ?: false
        val commandUris = when (val value = json["enableCommandUris"]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> if (value.booleanOrNull == true) emptyList() else null
            else -> null
        }
        val roots = (json["localResourceRoots"] as? JsonArray)?.mapNotNull(UriComponents::from)
        return Options(flag("enableScripts"), flag("enableForms"), commandUris, roots, json)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    /** Where `asWebviewUri` puts every resource: a host under this one, which `webview.cspSource` allows. */
    const val CDN: String = "vscode-cdn.net"
    private const val RESOURCE_HOST: String = ".vscode-resource.$CDN"

    /**
     * The resource an `asWebviewUri` address names, or null for any other address.
     *
     * The host spells a resource `https://<scheme>+<authority>.vscode-resource.vscode-cdn.net<path>`,
     * the authority with every character but a letter or digit written `-` and four hex digits, so
     * that it fits in a host name. A page asks for that address and the workbench answers it.
     *
     * The `+` usually arrives as `%2B`: an extension that builds its page from `asWebviewUri(...)
     * .toString()`, as GitLens does, gets VS Code's `Uri` spelling, which escapes it.
     */
    fun resource(url: String): UriComponents? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val host = uri.rawAuthority?.let(::unescape)?.lowercase() ?: return null
        if (!host.endsWith(RESOURCE_HOST)) return null
        val label = host.removeSuffix(RESOURCE_HOST)
        val plus = label.indexOf('+')
        if (plus <= 0) return null
        val authority = decodeAuthority(label.substring(plus + 1)) ?: return null
        return UriComponents(label.substring(0, plus), authority, uri.path ?: "/", uri.query ?: "", uri.fragment ?: "")
    }

    /** [text] with its `%XX` escapes decoded; a `+` stays a `+`, unlike in a form's encoding. */
    private fun unescape(text: String): String? {
        if ('%' !in text) return text
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '%') {
                if (index + 3 > text.length) return null
                result.append(text.substring(index + 1, index + 3).toIntOrNull(16)?.toChar() ?: return null)
                index += 3
            } else {
                result.append(text[index++])
            }
        }
        return result.toString()
    }

    private fun decodeAuthority(encoded: String): String? {
        val result = StringBuilder()
        var index = 0
        while (index < encoded.length) {
            val char = encoded[index]
            if (char == '-') {
                if (index + 5 > encoded.length) return null
                result.append(encoded.substring(index + 1, index + 5).toIntOrNull(16)?.toChar() ?: return null)
                index += 5
            } else {
                result.append(char)
                index++
            }
        }
        return result.toString()
    }

    /** `true`, as `$postMessage` answers once a message is on its way. */
    const val DELIVERED: String = "true"
}
