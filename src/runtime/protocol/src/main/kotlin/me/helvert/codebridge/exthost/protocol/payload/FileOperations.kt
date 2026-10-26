package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * Files the user creates, deletes, renames, moves or copies, as extensions hear of them:
 * `workspace.onWill*Files` and `workspace.onDid*Files`.
 *
 * Read off the 1.143 bundle's `ExtHostFileSystemEventService`. Before the operation, the host fires
 * the will-event and answers with the edits the listeners handed `event.waitUntil` -- a language
 * client's `workspace/willRenameFiles`, say, which is how a server updates the imports of a moved
 * file. After it, the did-event, which answers nothing. Nothing is registered first: the host fires
 * to whichever listeners it has, and answers `undefined` when none of them had an edit.
 */
object FileOperations {

    const val SERVICE: String = "ExtHostFileSystemEventService"

    /** `$onWillRunFileOperation(operation, files, timeout, token)`. */
    const val WILL_RUN: String = $$"$onWillRunFileOperation"

    /** `$onDidRunFileOperation(operation, files)`. */
    const val DID_RUN: String = $$"$onDidRunFileOperation"

    /**
     * VS Code's `FileOperation`, numbered as it numbers them.
     *
     * A rename is a [MOVE]; a [COPY] reaches extensions as a creation of its targets.
     */
    enum class Operation(val code: Int, val noun: String, val participants: String) {
        CREATE(0, "creation", "File Create"),
        DELETE(1, "deletion", "File Delete"),
        MOVE(2, "move", "File Rename"),
        COPY(3, "copy", "File Copy"),
    }

    /**
     * One file or folder the operation is about: [target] is the one created or deleted, or where
     * one is moved or copied to, from [source].
     */
    data class Change(val source: UriComponents?, val target: UriComponents)

    /** What the listeners answered: the edit to apply, and who asked for it, for the user. */
    data class Participation(val edit: WorkspaceEdit, val extensionNames: List<String>)

    /**
     * Arguments for [WILL_RUN]; the token is left for the host to append.
     *
     * [timeoutMillis] is not a deadline the host enforces: it is how long a listener may take before
     * the host logs it as slow.
     */
    fun willRun(operation: Operation, changes: List<Change>, timeoutMillis: Long): String =
        JsonArray(listOf(JsonPrimitive(operation.code), pairs(changes), JsonPrimitive(timeoutMillis))).toString()

    /** Arguments for [DID_RUN]. */
    fun didRun(operation: Operation, changes: List<Change>): String =
        JsonArray(listOf(JsonPrimitive(operation.code), pairs(changes))).toString()

    /** The reply to [willRun], or null when no listener had an edit to make. */
    fun participation(reply: String?): Participation? {
        if (reply.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val edit = WorkspaceEdit.from(parsed["edit"]) ?: return null
        val names = (parsed["extensionNames"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        return Participation(edit, names)
    }

    private fun pairs(changes: List<Change>) = JsonArray(changes.map {
        buildJsonObject {
            it.source?.let { source -> put("source", source.toJson()) }
            put("target", it.target.toJson())
        }
    })
}
