package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `MainThreadTreeViews` and the `ExtHostTreeViews` calls that answer it: the trees an extension
 * draws in a view it contributed.
 *
 * The host owns the data. The workbench only learns that a view has a provider, and then asks for
 * one level of items at a time by the handle of the item above them; a handle is the host's own
 * name for an element and stays valid until the tree is refreshed past it.
 */
object TreeViews {

    const val SERVICE: String = "MainThreadTreeViews"
    const val HOST: String = "ExtHostTreeViews"

    const val REGISTER: String = $$"$registerTreeViewDataProvider"
    const val REFRESH: String = $$"$refresh"
    const val REVEAL: String = $$"$reveal"
    const val SET_MESSAGE: String = $$"$setMessage"
    const val SET_TITLE: String = $$"$setTitle"
    const val SET_BADGE: String = $$"$setBadge"
    const val DISPOSE: String = $$"$disposeTree"

    const val GET_CHILDREN: String = $$"$getChildren"
    const val HAS_RESOLVE: String = $$"$hasResolve"
    const val RESOLVE: String = $$"$resolve"
    const val SET_EXPANDED: String = $$"$setExpanded"
    const val SET_SELECTION_AND_FOCUS: String = $$"$setSelectionAndFocus"
    const val SET_VISIBLE: String = $$"$setVisible"
    const val CHANGE_CHECKBOX_STATE: String = $$"$changeCheckboxState"

    /** `TreeItemCollapsibleState`. */
    const val LEAF: Int = 0
    const val COLLAPSED: Int = 1
    const val EXPANDED: Int = 2

    /**
     * One `ITreeItem`, as the host converted the extension's `TreeItem`.
     *
     * [label] is null when the extension gave none and the item is named by its [resource] instead.
     * [icon] and [iconDark] are image files; [themeIcon] is a codicon name. [command] is what
     * selecting the item runs. [markdownTooltip] says the [tooltip] arrived as a `MarkdownString`.
     */
    data class Item(
        val handle: String,
        val parentHandle: String?,
        val label: String?,
        val description: String?,
        val tooltip: String?,
        val command: StatusBar.Command?,
        val contextValue: String?,
        val icon: UriComponents?,
        val iconDark: UriComponents?,
        val themeIcon: String?,
        val resource: UriComponents?,
        val collapsible: Int,
        val checked: Boolean?,
        val markdownTooltip: Boolean = false,
    )

    /**
     * `$registerTreeViewDataProvider(treeViewId, options)`. Unless [manualCheckboxes], checking an
     * item checks what is under it, and the item above follows its children, as VS Code does it.
     */
    data class Registration(
        val viewId: String,
        val showCollapseAll: Boolean,
        val canSelectMany: Boolean,
        val manualCheckboxes: Boolean = false,
    )

    /** `$refresh(treeViewId, itemsToRefresh?)`: null [items] means the whole tree. */
    data class Refresh(val viewId: String, val items: List<Item>?)

    /**
     * `$reveal(treeViewId, itemInfo?, options)`: [item] under [parentChain], root first; no item
     * means the view itself. [expand] is how many levels below the item to open.
     */
    data class Reveal(
        val viewId: String,
        val item: Item?,
        val parentChain: List<Item>,
        val select: Boolean,
        val focus: Boolean,
        val expand: Int,
    )

    /** `$setTitle(treeViewId, title, description?)`. */
    data class Title(val viewId: String, val title: String, val description: String?)

    /** `$setBadge(treeViewId, badge?)`; no [value] takes the badge away. */
    data class Badge(val viewId: String, val value: Int?, val tooltip: String?)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        val options = args.objOrNull(1)
        fun flag(name: String) = options?.get(name)?.jsonPrimitive?.booleanOrNull ?: false
        return Registration(args.str(0), flag("showCollapseAll"), flag("canSelectMany"), flag("manageCheckboxStateManually"))
    }

    fun refresh(argsJson: String): Refresh {
        val args = Args.of(REFRESH, argsJson)
        return Refresh(args.str(0), args.objOrNull(1)?.values?.mapNotNull { (it as? JsonObject)?.let(::item) })
    }

    fun reveal(argsJson: String): Reveal {
        val args = Args.of(REVEAL, argsJson)
        val info = args.objOrNull(1)
        val options = args.objOrNull(2)
        fun flag(name: String, default: Boolean) = options?.get(name)?.jsonPrimitive?.booleanOrNull ?: default
        // `expand` is `true` for one level, or a number of levels up to three.
        val expand = options?.get("expand")?.jsonPrimitive?.let { it.intOrNull ?: if (it.booleanOrNull == true) 1 else 0 } ?: 0
        return Reveal(
            viewId = args.str(0),
            item = (info?.get("item") as? JsonObject)?.let(::item),
            parentChain = (info?.get("parentChain") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) },
            select = flag("select", true),
            focus = flag("focus", false),
            expand = expand,
        )
    }

    /** `$setMessage(treeViewId, message)`: plain text or, if [markdown], a `MarkdownString`; empty takes it away. */
    data class Message(val viewId: String, val text: String?, val markdown: Boolean)

    fun message(argsJson: String): Message {
        val args = Args.of(SET_MESSAGE, argsJson)
        return Message(args.str(0), Markdown.text(args.element(1))?.takeIf { it.isNotBlank() }, args.element(1) is JsonObject)
    }

    /** Arguments for `$changeCheckboxState(treeViewId, updates)`: each item's new state, by handle. */
    fun checkboxes(viewId: String, states: Map<String, Boolean>): String = JsonArray(listOf(
        JsonPrimitive(viewId),
        JsonArray(states.map { (handle, checked) ->
            JsonObject(mapOf("treeItemHandle" to JsonPrimitive(handle), "newState" to JsonPrimitive(checked)))
        }),
    )).toString()

    fun title(argsJson: String): Title {
        val args = Args.of(SET_TITLE, argsJson)
        return Title(args.str(0), args.strOrNull(1).orEmpty(), args.strOrNull(2)?.takeIf { it.isNotBlank() })
    }

    fun badge(argsJson: String): Badge {
        val args = Args.of(SET_BADGE, argsJson)
        val badge = args.objOrNull(1)
        return Badge(
            args.str(0),
            badge?.get("value")?.jsonPrimitive?.intOrNull,
            badge?.get("tooltip")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** `$disposeTree` and every other call that names only the view. */
    fun viewId(method: String, argsJson: String): String = Args.of(method, argsJson).str(0)

    /**
     * The items a `$getChildren` reply holds for the one parent it was asked about.
     *
     * The reply is a list of `[index, ...items]`, one per parent asked about, by its position in
     * the request; an absent reply means the provider has no tree yet.
     */
    fun children(replyJson: String): List<Item> {
        if (replyJson.isBlank()) return emptyList()
        val reply = Json.parseToJsonElement(replyJson) as? JsonArray ?: return emptyList()
        val first = reply.firstOrNull() as? JsonArray ?: return emptyList()
        return first.drop(1).mapNotNull { (it as? JsonObject)?.let(::item) }
    }

    /** The item a `$resolve` reply describes, or null when the provider resolved nothing. */
    fun resolved(replyJson: String): Item? {
        if (replyJson.isBlank()) return null
        return (Json.parseToJsonElement(replyJson) as? JsonObject)?.let(::item)
    }

    /** `$getChildren(treeViewId, treeItemHandles?)`, for one parent or, with none, the roots. */
    fun getChildren(viewId: String, handle: String?): String = JsonArray(
        listOfNotNull(JsonPrimitive(viewId), handle?.let { JsonArray(listOf(JsonPrimitive(it))) })
    ).toString()

    fun item(json: JsonObject): Item {
        fun str(name: String) = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val command = (json["command"] as? JsonObject)?.let { command ->
            command["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                StatusBar.Command(id, command["arguments"] as? JsonArray ?: JsonArray(emptyList()))
            }
        }
        val checkbox = json["checkbox"] as? JsonObject
        return Item(
            handle = str("handle") ?: throw PayloadException("A tree item has no handle: $json"),
            parentHandle = str("parentHandle"),
            // `{label, highlights}`, whose label is itself plain text or a `MarkdownString`.
            label = Markdown.text((json["label"] as? JsonObject)?.get("label"))?.takeIf { it.isNotEmpty() },
            description = str("description")?.takeIf { it.isNotEmpty() },
            tooltip = Markdown.text(json["tooltip"].takeUnless { it is JsonNull })?.takeIf { it.isNotBlank() },
            command = command,
            contextValue = str("contextValue"),
            icon = UriComponents.from(json["icon"]),
            iconDark = UriComponents.from(json["iconDark"]),
            themeIcon = (json["themeIcon"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull,
            resource = UriComponents.from(json["resourceUri"]),
            collapsible = json["collapsibleState"]?.jsonPrimitive?.intOrNull ?: LEAF,
            checked = checkbox?.get("isChecked")?.jsonPrimitive?.booleanOrNull,
            markdownTooltip = json["tooltip"] is JsonObject,
        )
    }
}
