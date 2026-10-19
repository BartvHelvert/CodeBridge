package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * The workbench's half of document synchronization.
 *
 * The host is told what opened, what changed, what was saved and what closed; the extension's own
 * language client turns that into whatever its server speaks, so nothing on this protocol mentions
 * LSP. Getting a payload wrong here is invisible from this side -- a server answers from a stale
 * copy rather than failing -- which is why the shapes are built in one place and the fixtures
 * answer with the version and line they were actually sent.
 */
object Documents {

    const val DOCUMENTS_AND_EDITORS: String = "ExtHostDocumentsAndEditors"
    const val DOCUMENTS: String = "ExtHostDocuments"

    const val DELTA: String = $$"$acceptDocumentsAndEditorsDelta"
    const val MODEL_CHANGED: String = $$"$acceptModelChanged"
    const val MODEL_SAVED: String = $$"$acceptModelSaved"

    /** `$acceptEncodingChanged(uri, encoding)`: the document is now read in another encoding. */
    const val ENCODING_CHANGED: String = $$"$acceptEncodingChanged"

    /** The workbench side, which an extension reaches through `workspace.openTextDocument`. */
    const val MAIN_THREAD: String = "MainThreadDocuments"

    /**
     * `$tryOpenDocument(uri, options)`: put a document the editor has not opened into the host.
     *
     * Answered with the resource the document was added under, *after* the host has it: the host
     * looks the document up by the answer at once, and fails `openTextDocument` if it is not there.
     */
    const val TRY_OPEN: String = $$"$tryOpenDocument"

    /** `$tryCreateDocument(options)`: a new untitled buffer, answered with its resource. */
    const val TRY_CREATE: String = $$"$tryCreateDocument"

    /** `$trySaveDocument(uri)`: `TextDocument.save()`, answered with whether it was saved. */
    const val TRY_SAVE: String = $$"$trySaveDocument"

    /** What `openTextDocument(uri, { encoding })` asks for: a resource, and how to decode it if said. */
    data class Open(val resource: UriComponents, val encoding: String?)

    fun open(argsJson: String): Open {
        val args = Args.of(TRY_OPEN, argsJson)
        return Open(args.uri(0), args.objOrNull(1)?.get("encoding")?.jsonPrimitive?.contentOrNull)
    }

    /** What `openTextDocument({ language, content, encoding })` asks for. */
    data class Untitled(val languageId: String?, val content: String, val encoding: String?)

    fun untitled(argsJson: String): Untitled {
        val options = Args.of(TRY_CREATE, argsJson).objOrNull(0)
        return Untitled(
            options?.get("language")?.jsonPrimitive?.contentOrNull,
            options?.get("content")?.jsonPrimitive?.contentOrNull ?: "",
            options?.get("encoding")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** VS Code's editor models start here, and its clients send it upstream as the LSP version. */
    const val FIRST_VERSION: Int = 1

    /** Line endings are normalized before a document is sent, so the host is always told `\n`. */
    const val EOL: String = "\n"

    /** The document itself, as `addedDocuments` carries it. */
    fun added(
        uri: UriComponents,
        languageId: String,
        text: String,
        dirty: Boolean,
        encoding: String = Encodings.UTF8
    ): JsonObject =
        buildJsonObject {
            put("uri", uri.toJson())
            put("versionId", FIRST_VERSION)
            put("lines", JsonArray(text.split('\n').map(::JsonPrimitive)))
            put("EOL", EOL)
            put("languageId", languageId)
            put("isDirty", dirty)
            put("encoding", encoding)
        }

    /**
     * One editor showing a document, as `addedEditors` carries it.
     *
     * Extensions mostly do not ask which documents are open -- they ask which are *visible*, and a
     * document with no editor is visible to nobody. That is not a cosmetic gap. The language
     * features that pull rather than wait to be pushed -- `textDocument/diagnostic`, inlay hints,
     * code lens -- are driven by `window.visibleTextEditors`, so with no editors a client answers
     * every `workspace/diagnostic/refresh` and then pulls nothing, and the only diagnostics that
     * ever arrive are the ones the server volunteers. For rust-analyzer that is `cargo check` on
     * save, and nothing at all while typing.
     *
     * Every field here is read eagerly when the runtime builds its `ExtHostTextEditor`, so none of
     * them can be left out and guessed at later.
     */
    fun addedEditor(id: String, uri: UriComponents, text: String, indentation: Indentation): JsonObject =
        buildJsonObject {
            put("id", id)
            put("documentUri", uri.toJson())
            // The caret, which nothing here tracks yet: an editor has to have one, and the start of the
            // document is the honest answer until the selection is mirrored too.
            put("selections", buildJsonArray {
                add(buildJsonObject {
                    put("selectionStartLineNumber", 1)
                    put("selectionStartColumn", 1)
                    put("positionLineNumber", 1)
                    put("positionColumn", 1)
                })
            })
            put("options", buildJsonObject {
                // `indentSize` follows `tabSize`, as VS Code's `editor.indentSize` does by default.
                put("tabSize", indentation.tabSize)
                put("indentSize", indentation.tabSize)
                put("originalIndentSize", indentation.tabSize)
                put("insertSpaces", indentation.insertSpaces)
                // TextEditorCursorStyle.Line and RenderLineNumbersType.On, the runtime's own defaults.
                put("cursorStyle", 1)
                put("lineNumbers", 1)
            })
            // The whole document. IntelliJ's scroll position is not mirrored, and claiming less would
            // make a puller skip the part of the file it could not see.
            put("visibleRanges", buildJsonArray { add(extentOf(text).toJson()) })
            // EditorGroupColumn 0, which the runtime revives as ViewColumn.One.
            put("editorPosition", 0)
        }

    /**
     * `$acceptDocumentsAndEditorsDelta` announcing one opened document and the editor showing it.
     *
     * Both in one delta: the runtime applies `addedDocuments` before `addedEditors` and asserts that
     * an editor's document already exists, so the order within the message is what makes this safe.
     * [activeEditorId], when given, makes it the active editor; otherwise the active one stays.
     */
    fun openDelta(document: JsonObject, editor: JsonObject, activeEditorId: String?): JsonObject =
        buildJsonObject {
            put("addedDocuments", buildJsonArray { add(document) })
            put("addedEditors", buildJsonArray { add(editor) })
            if (activeEditorId != null) put("newActiveEditor", activeEditorId)
        }

    /**
     * `$acceptDocumentsAndEditorsDelta` announcing a document no editor shows.
     *
     * What `openTextDocument` produces: the extension can read it, and `onDidOpenTextDocument` fires
     * for it, but it is in no visible editor and no tab.
     */
    fun loadDelta(document: JsonObject): JsonObject = buildJsonObject {
        put("addedDocuments", buildJsonArray { add(document) })
    }

    /** `$acceptDocumentsAndEditorsDelta` showing a document the host already holds; [activeEditorId] as for [openDelta]. */
    fun showDelta(editor: JsonObject, activeEditorId: String?): JsonObject = buildJsonObject {
        put("addedEditors", buildJsonArray { add(editor) })
        if (activeEditorId != null) put("newActiveEditor", activeEditorId)
    }

    /** `$acceptDocumentsAndEditorsDelta` making an editor the host already has the active one. */
    fun activateDelta(activeEditorId: String): JsonObject = buildJsonObject {
        put("newActiveEditor", activeEditorId)
    }

    /**
     * `$acceptDocumentsAndEditorsDelta` withdrawing an editor whose document stays open, because an
     * extension still holds it. [deactivate] as for [closeDelta].
     */
    fun hideDelta(editorId: String, deactivate: Boolean): JsonObject = buildJsonObject {
        put("removedEditors", buildJsonArray { add(JsonPrimitive(editorId)) })
        if (deactivate) put("newActiveEditor", JsonNull)
    }

    /** `$acceptDocumentsAndEditorsDelta` withdrawing a document no editor was showing. */
    fun unloadDelta(uri: UriComponents): JsonObject = buildJsonObject {
        put("removedDocuments", buildJsonArray { add(uri.toJson()) })
    }

    /**
     * `$acceptDocumentsAndEditorsDelta` withdrawing one closed document and its editor.
     *
     * [deactivate] when the editor being removed is the one the host believes is active: the runtime
     * asserts that `newActiveEditor` names an editor it still has, so a stale id is a hard failure
     * rather than a wrong highlight.
     */
    fun closeDelta(uri: UriComponents, editorId: String, deactivate: Boolean): JsonObject =
        buildJsonObject {
            put("removedDocuments", buildJsonArray { add(uri.toJson()) })
            put("removedEditors", buildJsonArray { add(JsonPrimitive(editorId)) })
            if (deactivate) put("newActiveEditor", JsonNull)
        }

    /** The whole of [text] as a 1-based editor range, end column exclusive. */
    fun extentOf(text: String): Range {
        val lines = text.split('\n')
        return Range(1, 1, lines.size, lines.last().length + 1)
    }

    /**
     * The one span that differs between two texts: `[start, oldEnd)` of the old text became
     * `[start, newEnd)` of the new. Offsets are in UTF-16 code units, the unit both editors count in.
     */
    data class Change(val start: Int, val oldEnd: Int, val newEnd: Int)

    /**
     * The smallest single span that turns [previousText] into [text]: what they share at both ends
     * is left out.
     *
     * Worked out from the two texts rather than from the editor's events, so it holds whatever
     * happened in between -- several IntelliJ endpoints reporting one keystroke, a burst coalesced
     * into one sync, a file re-read from disk. Two edits far apart become one span covering both,
     * which is still correct, merely larger.
     */
    fun changeBetween(previousText: CharSequence, text: CharSequence): Change {
        val shorter = minOf(previousText.length, text.length)
        var start = 0
        while (start < shorter && previousText[start] == text[start]) start++
        var oldEnd = previousText.length
        var newEnd = text.length
        while (oldEnd > start && newEnd > start && previousText[oldEnd - 1] == text[newEnd - 1]) {
            oldEnd--
            newEnd--
        }
        // Never between the halves of a surrogate pair: the host would be told to replace half a
        // character, and a server that counts in code points could not say where that is.
        if (start > 0 && Character.isHighSurrogate(previousText[start - 1])) start--
        if (oldEnd < previousText.length && Character.isLowSurrogate(previousText[oldEnd])) {
            oldEnd++
            newEnd++
        }
        return Change(start, oldEnd, newEnd)
    }

    /**
     * `$acceptModelChanged`'s event: only the span that changed, in [previousText]'s coordinates.
     *
     * Deliberately not a whole-document replacement, which is what this used to send. The host
     * passes the range on as it is, so every keystroke reached the language server as "the whole
     * file was replaced" -- and a server told that discards what it knew about the file and reads
     * it again from scratch, which every completion, colour and diagnostic then waited behind.
     */
    fun changeEvent(previousText: String, text: String, version: Int): JsonObject {
        val change = changeBetween(previousText, text)
        val start = positionOf(previousText, change.start)
        val end = positionOf(previousText, change.oldEnd)
        return buildJsonObject {
            put("versionId", version)
            put("eol", EOL)
            put("isUndoing", false)
            put("isRedoing", false)
            put("isFlush", false)
            put("isEolChange", false)
            put("changes", buildJsonArray {
                add(buildJsonObject {
                    put("range", Range(start.lineNumber, start.column, end.lineNumber, end.column).toJson())
                    put("rangeOffset", change.start)
                    put("rangeLength", change.oldEnd - change.start)
                    put("text", text.substring(change.start, change.newEnd))
                })
            })
        }
    }

    /** The 1-based position of [offset] in [text]. */
    fun positionOf(text: CharSequence, offset: Int): Position {
        var line = 1
        var lineStart = 0
        for (index in 0 until offset) {
            if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        return Position(line, offset - lineStart + 1)
    }

    /** `\r\n` and bare `\r` are folded to `\n`, because that is the EOL the host is told about. */
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
