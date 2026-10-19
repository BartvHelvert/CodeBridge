package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * A replacement of one range with new text: VS Code's `TextEdit` as the workbench receives it.
 *
 * `{range, text, eol}`, in the editor's 1-based coordinates. [snippet] is set only for an edit a
 * workspace edit asked to insert as a snippet, whose text still carries tab stops. [keepWhitespace]
 * is the snippet's own say on indentation: unless it is set, a snippet inserted into an editor has its
 * lines indented as the line it starts on is.
 */
data class TextEdit(
    val range: Range,
    val text: String,
    val snippet: Boolean = false,
    val keepWhitespace: Boolean = false,
) {

    companion object {

        /** Reads one edit, or `null` when [element] is not one. */
        fun from(element: JsonElement?): TextEdit? {
            val edit = element as? JsonObject ?: return null
            // `Range.from` also accepts a completion's `{insert, replace}` pair, which an edit never is.
            val range = Range.from(edit["range"]) ?: return null
            val text = (edit["text"] as? JsonPrimitive)?.contentOrNull ?: return null
            fun flag(name: String) = (edit[name] as? JsonPrimitive)?.booleanOrNull == true
            return TextEdit(range, text, flag("insertAsSnippet"), flag("keepWhitespace"))
        }

        /**
         * A formatting reply: a bare array of edits, or nothing.
         *
         * `undefined` rather than an empty array is how a provider that declined is told apart from
         * one that found nothing to change, and both mean the same here.
         */
        fun list(reply: String?): List<TextEdit>? {
            if (reply.isNullOrBlank()) return null
            val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray ?: return null
            return parsed.mapNotNull(::from)
        }
    }
}

/**
 * What an extension asks the editor to change, across files: VS Code's `WorkspaceEditDto`.
 *
 * `{edits: [...]}`, where each entry is one of a text edit against a resource, or a file operation
 * with an old and a new resource. Notebook cell edits are the third kind and are skipped: there are
 * no notebooks here to apply them to.
 */
data class WorkspaceEdit(val entries: List<Entry>) {

    /**
     * In the order the extension gave them, which matters: an edit that creates a file and then
     * writes into it lists the creation first.
     */
    sealed interface Entry {
        /** What the extension said of this change, if anything; see [Metadata]. */
        val metadata: Metadata?
    }

    /**
     * VS Code's `WorkspaceEditEntryMetadata`: a [label] the preview groups changes under, and
     * whether the change [needsConfirmation] -- which makes VS Code show its preview, with this
     * change left unticked, before anything is applied.
     */
    data class Metadata(val needsConfirmation: Boolean, val label: String, val description: String?)

    /** Whether any change asks to be confirmed, so the edit is previewed rather than applied. */
    val needsConfirmation: Boolean get() = entries.any { it.metadata?.needsConfirmation == true }

    val textEdits: List<ResourceEdit> get() = entries.filterIsInstance<ResourceEdit>()

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * One text edit, and the document version it was computed against.
     *
     * [versionId] is absent for a file the same edit creates, and for an extension that did not know
     * the version; present, it is the host's own count, which the applier compares before trusting
     * the offsets.
     */
    data class ResourceEdit(
        val resource: UriComponents,
        val edit: TextEdit,
        val versionId: Int?,
        override val metadata: Metadata? = null,
    ) : Entry

    /**
     * A file created ([oldResource] absent), deleted ([newResource] absent) or renamed (both).
     *
     * [overwrite] and [ignoreIfExists] come from VS Code's options for the operation. A delete's
     * `recursive` is not read: deleting a directory here always takes its contents with it.
     */
    data class FileEdit(
        val oldResource: UriComponents?,
        val newResource: UriComponents?,
        val overwrite: Boolean = false,
        val ignoreIfExists: Boolean = false,
        override val metadata: Metadata? = null,
    ) : Entry

    companion object {

        const val SERVICE: String = "MainThreadBulkEdits"

        /** `$tryApplyWorkspaceEdit(dto, undoRedoGroupId, isRefactoring)`, answered with a boolean. */
        const val TRY_APPLY: String = $$"$tryApplyWorkspaceEdit"

        /** The edit an extension handed `workspace.applyEdit`, the first argument of [TRY_APPLY]. */
        fun applied(argsJson: String): WorkspaceEdit = from(Args.of(TRY_APPLY, argsJson).obj(0))
            ?: throw PayloadException("$TRY_APPLY argument 0 has no edits")

        /** Reads the DTO, or `null` when [element] is not one. */
        fun from(element: JsonElement?): WorkspaceEdit? {
            val edits = (element as? JsonObject)?.get("edits") as? JsonArray ?: return null
            val entries = mutableListOf<Entry>()
            for (entry in edits) {
                val edit = entry as? JsonObject ?: continue
                val textEdit = edit["textEdit"]
                if (textEdit != null) {
                    val resource = UriComponents.from(edit["resource"]) ?: continue
                    entries += ResourceEdit(
                        resource, TextEdit.from(textEdit) ?: continue,
                        (edit["versionId"] as? JsonPrimitive)?.intOrNull, metadata(edit["metadata"]),
                    )
                    continue
                }
                if ("oldResource" in edit || "newResource" in edit) {
                    val options = edit["options"] as? JsonObject
                    fun option(name: String) = (options?.get(name) as? JsonPrimitive)?.booleanOrNull == true
                    entries += FileEdit(
                        UriComponents.from(edit["oldResource"]), UriComponents.from(edit["newResource"]),
                        overwrite = option("overwrite"), ignoreIfExists = option("ignoreIfExists"),
                        metadata = metadata(edit["metadata"]),
                    )
                }
            }
            return WorkspaceEdit(entries)
        }

        private fun metadata(element: JsonElement?): Metadata? {
            val metadata = element as? JsonObject ?: return null
            fun string(name: String) = (metadata[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Metadata(
                (metadata["needsConfirmation"] as? JsonPrimitive)?.booleanOrNull == true,
                string("label") ?: "",
                string("description"),
            )
        }
    }
}
