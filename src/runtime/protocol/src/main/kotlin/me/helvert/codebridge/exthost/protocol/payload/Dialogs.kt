package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadDiaglogs` -- VS Code's spelling, which is the id the host asks for -- behind
 * `window.showOpenDialog` and `window.showSaveDialog`.
 *
 * The host passes the extension's options through as they are, `defaultUri` revived into
 * `UriComponents`, and revives what comes back: a list of resources for an open dialog, one for a
 * save dialog, and `undefined` for either when the user cancelled.
 */
object Dialogs {
    const val SERVICE: String = "MainThreadDiaglogs"

    const val SHOW_OPEN: String = $$"$showOpenDialog"
    const val SHOW_SAVE: String = $$"$showSaveDialog"

    /**
     * What a dialog may offer: files of these [extensions], listed under [name]. An empty list, or
     * VS Code's `*`, is every file.
     */
    data class Filter(val name: String, val extensions: List<String>)

    /**
     * `$showOpenDialog(options)`. As in VS Code, a dialog asked to choose neither files nor
     * folders chooses files.
     */
    data class Open(
        val title: String? = null,
        val label: String? = null,
        val defaultUri: UriComponents? = null,
        val files: Boolean = true,
        val folders: Boolean = false,
        val many: Boolean = false,
        val filters: List<Filter> = emptyList(),
    )

    /** `$showSaveDialog(options)`. */
    data class Save(
        val title: String? = null,
        val label: String? = null,
        val defaultUri: UriComponents? = null,
        val filters: List<Filter> = emptyList(),
    )

    fun open(argsJson: String): Open {
        val options = Args.of(SHOW_OPEN, argsJson).objOrNull(0)
        val files = options.flag("canSelectFiles") ?: true
        val folders = options.flag("canSelectFolders") ?: false
        return Open(
            title = options.text("title"),
            label = options.text("openLabel"),
            defaultUri = UriComponents.from(options?.get("defaultUri")),
            files = files || !folders,
            folders = folders,
            many = options.flag("canSelectMany") ?: false,
            filters = filters(options),
        )
    }

    fun save(argsJson: String): Save {
        val options = Args.of(SHOW_SAVE, argsJson).objOrNull(0)
        return Save(
            title = options.text("title"),
            label = options.text("saveLabel"),
            defaultUri = UriComponents.from(options?.get("defaultUri")),
            filters = filters(options),
        )
    }

    /** The reply to `$showOpenDialog`: the resources chosen, or `undefined` for none. */
    fun opened(chosen: List<UriComponents>?): String =
        if (chosen.isNullOrEmpty()) JsonNull.toString() else JsonArray(chosen.map { it.toJson() }).toString()

    /** The reply to `$showSaveDialog`. */
    fun saved(chosen: UriComponents?): String = chosen?.toJson()?.toString() ?: JsonNull.toString()

    /** `filters`, `{ "Images": ["png", "jpg"] }`, in the order the extension wrote them. */
    private fun filters(options: JsonObject?): List<Filter> =
        (options?.get("filters") as? JsonObject).orEmpty().map { (name, extensions) ->
            Filter(
                name,
                (extensions as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trimStart('.') }
                    .filter { it.isNotEmpty() })
        }

    private fun JsonObject?.text(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.flag(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
}
