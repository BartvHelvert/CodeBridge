package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadCustomEditors` and the `ExtHostCustomEditors` calls that answer it: editors an
 * extension draws in a webview for the files its `contributes.customEditors` claim.
 *
 * The workbench decides when one opens -- the user opened such a file -- and makes the webview,
 * which the extension fills in `$resolveCustomEditor` as it fills a panel: its page, options and
 * messages then go through `MainThreadWebviews` by the handle the workbench chose.
 *
 * A provider is one of two kinds. A text editor provider edits a text document the host already
 * holds, so the file is mirrored into the host before it is asked. A custom editor provider keeps
 * a document of its own, made with `$createCustomDocument`; when it is editable, it reports each
 * edit with `$onDidEdit` -- an id the workbench undoes and redoes it by -- or `$onContentChange`
 * for one that cannot be undone, and is asked to save and revert.
 */
object CustomEditors {

    const val SERVICE: String = "MainThreadCustomEditors"
    const val HOST: String = "ExtHostCustomEditors"

    const val REGISTER_TEXT_PROVIDER: String = $$"$registerTextEditorProvider"
    const val REGISTER_CUSTOM_PROVIDER: String = $$"$registerCustomEditorProvider"
    const val UNREGISTER_PROVIDER: String = $$"$unregisterEditorProvider"
    const val ON_DID_EDIT: String = $$"$onDidEdit"
    const val ON_CONTENT_CHANGE: String = $$"$onContentChange"

    const val CREATE_DOCUMENT: String = $$"$createCustomDocument"
    const val DISPOSE_DOCUMENT: String = $$"$disposeCustomDocument"
    const val RESOLVE_EDITOR: String = $$"$resolveCustomEditor"
    const val UNDO: String = $$"$undo"
    const val REDO: String = $$"$redo"
    const val REVERT: String = $$"$revert"
    const val SAVE: String = $$"$onSave"
    const val DISPOSE_EDITS: String = $$"$disposeEdits"

    /** The activation event that wakes an extension to open one of its editors. */
    const val ON_CUSTOM_EDITOR: String = "onCustomEditor:"

    /**
     * A provider an extension registered for [viewType]: a [text] one, or one keeping custom
     * documents of its own. [panelOptions] are its `webviewOptions` -- `enableFindWidget`,
     * `retainContextWhenHidden` -- as the host sent them.
     */
    data class Provider(
        val extension: Webviews.Extension,
        val viewType: String,
        val text: Boolean,
        val panelOptions: JsonObject,
        /** Whether one document may have several editors; only custom document providers say. */
        val multipleEditors: Boolean,
        val binaryMessages: Boolean,
    )

    /** An edit of a custom document: [editId], which the workbench undoes and redoes it by, or none for one it cannot undo. */
    data class Edit(val resource: UriComponents, val viewType: String, val editId: Int?, val label: String?)

    /**
     * `$registerTextEditorProvider(extension, viewType, options, capabilities, serializeBuffersForPostMessage)`
     * and `$registerCustomEditorProvider(extension, viewType, options, capabilities, supportsMultipleEditorsPerDocument, serializeBuffersForPostMessage)`.
     */
    fun provider(method: String, argsJson: String): Provider {
        val args = Args.of(method, argsJson)
        val text = method == REGISTER_TEXT_PROVIDER
        return Provider(
            extension = Webviews.extension(args.objOrNull(0)),
            viewType = args.str(1),
            text = text,
            panelOptions = args.objOrNull(2) ?: JsonObject(emptyMap()),
            multipleEditors = !text && args.boolOrNull(4) == true,
            binaryMessages = args.boolOrNull(if (text) 4 else 5) == true,
        )
    }

    /** `$unregisterEditorProvider(viewType)`. */
    fun viewType(argsJson: String): String = Args.of(UNREGISTER_PROVIDER, argsJson).str(0)

    /** `$onDidEdit(resource, viewType, editId, label)` and `$onContentChange(resource, viewType)`. */
    fun edit(method: String, argsJson: String): Edit {
        val args = Args.of(method, argsJson)
        return Edit(args.uri(0), args.str(1), if (method == ON_DID_EDIT) args.int(2) else null, args.strOrNull(3))
    }

    /**
     * `$createCustomDocument(resource, viewType, backupId, untitledDocumentData)`, the cancellation
     * token the RPC layer's. Neither a backup nor untitled content: a document is always read from
     * its file.
     */
    fun createDocument(resource: UriComponents, viewType: String): String = buildJsonArray {
        add(resource.toJson()); add(viewType); add(JsonNull); add(JsonNull)
    }.toString()

    /** Whether the provider can edit the document `$createCustomDocument` made: its `{editable}` answer. */
    fun editable(reply: String): Boolean =
        ((runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject)?.get("editable") as? JsonPrimitive)?.booleanOrNull == true

    /**
     * `$resolveCustomEditor(resource, handle, viewType, {title, contentOptions, options, active}, position)`,
     * the cancellation token the RPC layer's. The page's content options start empty, as a new
     * webview's do: the extension sets them while resolving. [position] is the editor group's 0-based index.
     */
    fun resolveEditor(
        resource: UriComponents, handle: String, viewType: String, title: String, panelOptions: JsonObject, active: Boolean, position: Int,
    ): String = buildJsonArray {
        add(resource.toJson()); add(handle); add(viewType)
        add(buildJsonObject {
            put("title", title)
            put("contentOptions", JsonObject(emptyMap()))
            put("options", panelOptions)
            put("active", active)
        })
        add(position.coerceAtLeast(0))
    }.toString()

    /** The resource and view type alone, as `$disposeCustomDocument`, `$onSave` and `$revert` take them. */
    fun document(resource: UriComponents, viewType: String): String = buildJsonArray {
        add(resource.toJson()); add(viewType)
    }.toString()

    /** `$undo(resource, viewType, editId, isDirty)` and `$redo`, with whether the document is unsaved once it is done. */
    fun step(resource: UriComponents, viewType: String, editId: Int, dirty: Boolean): String = buildJsonArray {
        add(resource.toJson()); add(viewType); add(editId); add(dirty)
    }.toString()

    /** `$disposeEdits(resource, viewType, editIds)`: edits that can no longer be undone or redone. */
    fun disposeEdits(resource: UriComponents, viewType: String, editIds: List<Int>): String = buildJsonArray {
        add(resource.toJson()); add(viewType); add(JsonArray(editIds.map(::JsonPrimitive)))
    }.toString()

    /** The view type a provider registered for an `onCustomEditor:` activation event. */
    fun activationEvent(viewType: String): String = ON_CUSTOM_EDITOR + viewType
}
