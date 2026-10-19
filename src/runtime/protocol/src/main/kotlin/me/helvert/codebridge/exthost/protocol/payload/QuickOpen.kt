package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadQuickOpen` / `ExtHostQuickOpen`: `window.showQuickPick`, `window.showInputBox`, and
 * the `createQuickPick` / `createInputBox` objects an extension drives itself.
 *
 * A debugger's `${command:pickProcess}` ends here, and so does any extension that asks the user to
 * choose. `$show` is answered only once the items follow in `$setItems`: the extension may still be
 * computing them when it opens the picker. A created picker is a stream of partial updates by id,
 * and the user's actions go back to the extension as `$onDid*` calls naming it.
 */
object QuickOpen {
    const val SERVICE: String = "MainThreadQuickOpen"
    const val EXT_HOST: String = "ExtHostQuickOpen"

    const val SHOW: String = $$"$show"
    const val SET_ITEMS: String = $$"$setItems"
    const val SET_ERROR: String = $$"$setError"
    const val INPUT: String = $$"$input"
    const val CREATE_OR_UPDATE: String = $$"$createOrUpdate"
    const val DISPOSE: String = $$"$dispose"

    const val VALIDATE_INPUT: String = $$"$validateInput"
    const val ON_DID_ACCEPT: String = $$"$onDidAccept"
    const val ON_DID_CHANGE_VALUE: String = $$"$onDidChangeValue"
    const val ON_DID_CHANGE_ACTIVE: String = $$"$onDidChangeActive"
    const val ON_DID_CHANGE_SELECTION: String = $$"$onDidChangeSelection"
    const val ON_DID_HIDE: String = $$"$onDidHide"

    /** One entry; a [separator] has no handle and cannot be chosen. */
    data class Item(
        val handle: Int?,
        val label: String,
        val description: String? = null,
        val detail: String? = null,
        val picked: Boolean = false,
        val separator: Boolean = false,
        /** Shown whatever the user types. */
        val alwaysShow: Boolean = false,
    )

    /** `$show`'s options, which are also the shape of a created picker's state. */
    data class PickOptions(
        val title: String? = null,
        val placeholder: String? = null,
        val prompt: String? = null,
        val canPickMany: Boolean = false,
        val matchOnDescription: Boolean = false,
        val matchOnDetail: Boolean = false,
        val ignoreFocusOut: Boolean = false,
        /** A created picker's alone, from here on. */
        val busy: Boolean = false,
        val value: String = "",
    )

    data class InputOptions(
        val title: String? = null,
        val prompt: String? = null,
        val placeholder: String? = null,
        val value: String? = null,
        val password: Boolean = false,
    )

    /** `$show(instance, options)`. */
    fun show(argsJson: String): Pair<Int, PickOptions> {
        val args = Args.of(SHOW, argsJson)
        val options = args.objOrNull(1)
        return args.int(0) to PickOptions(
            title = options.text("title"),
            placeholder = options.text("placeHolder"),
            prompt = options.text("prompt"),
            canPickMany = options.flag("canPickMany"),
            matchOnDescription = options.flag("matchOnDescription"),
            matchOnDetail = options.flag("matchOnDetail"),
            ignoreFocusOut = options.flag("ignoreFocusOut"),
        )
    }

    /** `$setItems(instance, items)`. */
    fun setItems(argsJson: String): Pair<Int, List<Item>> {
        val args = Args.of(SET_ITEMS, argsJson)
        return args.int(0) to items(args.element(1))
    }

    /** The instance `$setError(instance, error)` fails. */
    fun failedInstance(argsJson: String): Int = Args.of(SET_ERROR, argsJson).int(0)

    /** `$input(options, validateInput)`: the box, and whether the extension checks what is typed. */
    fun input(argsJson: String): Pair<InputOptions, Boolean> {
        val args = Args.of(INPUT, argsJson)
        val options = args.objOrNull(0)
        return InputOptions(
            title = options.text("title"),
            prompt = options.text("prompt"),
            placeholder = options.text("placeHolder"),
            value = options.text("value"),
            password = options.flag("password"),
        ) to (args.boolOrNull(1) == true)
    }

    /** `$createOrUpdate(params)`: the id, and the fields that changed -- a null field was cleared. */
    fun update(argsJson: String): Pair<Int, JsonObject> {
        val params = Args.of(CREATE_OR_UPDATE, argsJson).obj(0)
        val id = (params["id"] as? JsonPrimitive)?.intOrNull ?: throw PayloadException("$CREATE_OR_UPDATE has no id: $params")
        return id to params
    }

    fun disposed(argsJson: String): Int = Args.of(DISPOSE, argsJson).int(0)

    /** A created picker's state, read as [PickOptions]. */
    fun pickOptions(state: JsonObject) = PickOptions(
        title = state.text("title"),
        placeholder = state.text("placeholder"),
        prompt = state.text("prompt"),
        canPickMany = state.flag("canSelectMany"),
        matchOnDescription = state.flag("matchOnDescription"),
        matchOnDetail = state.flag("matchOnDetail"),
        ignoreFocusOut = state.flag("ignoreFocusOut"),
        busy = state.flag("busy"),
        value = state.text("value").orEmpty(),
    )

    /** The handles of the items a created picker's extension made active (`activeItems`). */
    fun activeHandles(state: JsonObject): List<Int> =
        (state["activeItems"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }

    /** A created input box's state, read as [InputOptions]. */
    fun inputOptions(state: JsonObject) = InputOptions(
        title = state.text("title"),
        prompt = state.text("prompt"),
        placeholder = state.text("placeholder"),
        value = state.text("value"),
        password = state.flag("password"),
    )

    fun items(element: JsonElement?): List<Item> = (element as? JsonArray).orEmpty().mapNotNull { entry ->
        val item = entry as? JsonObject ?: return@mapNotNull null
        val label = withoutIcons(item.text("label").orEmpty())
        if (item.text("type") == "separator") return@mapNotNull Item(null, label, separator = true)
        val handle = (item["handle"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        Item(
            handle, label, item.text("description")?.let(::withoutIcons), item.text("detail")?.let(::withoutIcons),
            item.flag("picked"), alwaysShow = item.flag("alwaysShow"),
        )
    }

    /** What `$show` answers: the chosen item's handle, or every chosen one for a multiple choice. */
    fun chosen(handles: List<Int>?, many: Boolean): String? = when {
        handles == null -> null
        many -> JsonArray(handles.map(::JsonPrimitive)).toString()
        else -> handles.firstOrNull()?.toString()
    }

    /**
     * What `$validateInput` answered, as the message that keeps the box open: a string, or an
     * error-severity `{content, severity}`. Info and warnings let the value through, as in VS Code.
     */
    fun validationError(reply: String?): String? {
        if (reply.isNullOrBlank()) return null
        return when (val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull()) {
            is JsonPrimitive -> parsed.contentOrNull?.takeIf { parsed.isString && it.isNotEmpty() }
            is JsonObject -> parsed.text("content")?.takeIf { (parsed["severity"] as? JsonPrimitive)?.intOrNull == SEVERITY_ERROR }
            else -> null
        }
    }

    /** `$validateInput(value)`. */
    fun validateInput(value: String): String = JsonArray(listOf(JsonPrimitive(value))).toString()

    /** `$onDidAccept(id)`, `$onDidHide(id)`. */
    fun session(id: Int): String = "[$id]"

    /** `$onDidChangeValue(id, value)`. */
    fun changedValue(id: Int, value: String): String = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(value))).toString()

    /** `$onDidChangeActive(id, handles)`, `$onDidChangeSelection(id, handles)`. */
    fun changedItems(id: Int, handles: List<Int>): String = "[$id,${JsonArray(handles.map(::JsonPrimitive))}]"

    /** Text without the `$(name)` icons a label may carry, which only VS Code can draw. */
    fun withoutIcons(text: String): String = ICON.replace(text, "").trim()

    private val ICON = Regex("""\$\([a-z0-9-~]+\)\s?""", RegexOption.IGNORE_CASE)

    /** `Severity.Error` as the workbench numbers it: Ignore, Info, Warning, Error. */
    private const val SEVERITY_ERROR = 3

    private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject?.flag(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true
}
