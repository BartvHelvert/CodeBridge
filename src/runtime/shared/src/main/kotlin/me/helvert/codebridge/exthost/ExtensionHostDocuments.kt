package me.helvert.codebridge.exthost

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.exthost.protocol.payload.*
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * The host's copy of the documents the editor has open, and of those extensions opened themselves,
 * kept in step with it.
 *
 * This is the workbench's half of document synchronization. The host is told what opened, what
 * changed, what was saved and what closed; the extension's own language client turns that into
 * whatever its server speaks, so nothing here knows about LSP. Getting it wrong is invisible from
 * this side -- a server answers from a stale copy rather than failing -- which is why the fixtures
 * answer with the version and line they were actually sent.
 *
 * One model per project host, shared by its language clients. Call off the RPC reader.
 */
class ExtensionHostDocuments(
    /**
     * Where a failure to publish the tab model goes; see [publishTabs] for why it is not thrown.
     *
     * Declared before [callHost] so that the trailing-lambda form still means "the call host",
     * which is what every caller writes and what a reader expects it to mean.
     */
    private val reportTabFailure: (Throwable) -> Unit = {},
    private val callHost: suspend (String, String, String) -> String,
) {
    constructor(session: ExtensionHostSession) : this(callHost = { service, method, args -> session.call(service, method, args) })

    /**
     * Where calls are started, so that each is written as it is made; [sending] waits for every one
     * of them, so nothing is left running here for long.
     */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The exact URI spelling a document was opened with, for the tab that shows it. */
    private val spellings = mutableMapOf<String, String>()

    private data class Document(
        var text: String,
        var version: Int,
        val language: String,
        val owners: MutableSet<String>,
        /** The owners with an editor open on it, rather than a reference an extension asked for. */
        val viewers: MutableSet<String>,
        /**
         * The editor the host believes is showing this document; see [Documents.addedEditor].
         *
         * Null while only extensions hold it: `openTextDocument` puts a document into the host
         * without showing it, and it gets an editor if the user opens it later.
         */
        var editorId: String?,
        /** VS Code's id for how the text was decoded; see [Encodings]. */
        var encoding: String,
        /** What an editor on it indents by where its text gives no hint; see [Indentation.guess]. */
        val indentation: Indentation,
    )

    private val documents = mutableMapOf<String, Document>()

    /**
     * Editor ids, which must be unique for the life of the host.
     *
     * Reusing one is not a wrong value but a hard failure: the runtime asserts `editor '<id>' already
     * exists` when a delta adds an id it still holds, so a file closed and reopened has to be a new
     * editor rather than the same one again.
     */
    private var nextEditorId = 0

    /** The editor the host was last told is active, so a close knows whether to clear it. */
    private var activeEditorId: String? = null

    /**
     * Mirrors [uri] into the host, or adds [owner] to a document that is already mirrored.
     *
     * [shown] when [owner] has the document open in an editor, which the host is told about too.
     * Otherwise the document is only held -- what `workspace.openTextDocument` does -- and stays out
     * of the visible editors and the tabs until an owner shows it.
     *
     * [active] when the editor showing it is the one in front of the user. A file restored with the
     * project, opened behind the one selected, must not take that place from it: extensions read
     * `window.activeTextEditor` as the file the user is in.
     *
     * Versions are counted here rather than taken from the caller. IntelliJ's modification stamps
     * are not the small ascending integers VS Code's editor model expects, and nothing rejects a
     * bad one: a version that went backwards would simply stop the extension's client from sending
     * the edit upstream, and the server would keep answering from the text before it.
     *
     * [indentation] is the editor's when its text does not say; otherwise the editor indents as the
     * text does, which is what VS Code tells an extension too.
     */
    fun open(
        owner: String, uri: String, language: String, text: String, dirty: Boolean, shown: Boolean = true,
        encoding: String = Encodings.UTF8, active: Boolean = true, indentation: Indentation = Indentation.DEFAULT,
    ): Unit = sending {
        val body = normalize(text)
        documents[key(uri)]?.let {
            require(it.language == language) { "$uri is already open as '${it.language}', not '$language'" }
            it.owners.add(owner)
            // The newcomer may hold a fresher snapshot than the host was last told about.
            emit(uri, it, body, dirty)
            encode(uri, it, encoding)
            if (shown) {
                it.viewers.add(owner)
                if (it.editorId == null) show(uri, it, dirty, active)
            }
            return@sending
        }
        if (!shown) {
            invoke(
                Documents.DOCUMENTS_AND_EDITORS, Documents.DELTA,
                Documents.loadDelta(Documents.added(components(uri), language, body, dirty, encoding))
            )
            documents[key(uri)] = Document(
                body,
                Documents.FIRST_VERSION,
                language,
                mutableSetOf(owner),
                mutableSetOf(),
                null,
                encoding,
                indentation
            )
            spellings[key(uri)] = uri
            return@sending
        }
        val editorId = "codebridge-editor-${nextEditorId++}"
        val data = Documents.added(components(uri), language, body, dirty, encoding)
        val editor = Documents.addedEditor(editorId, components(uri), body, Indentation.guess(body, indentation))
        // The document and an editor showing it, together. Without the editor the host has a file
        // nobody can see, and every language feature that pulls rather than waits to be pushed --
        // live diagnostics above all -- has nothing to pull for.
        invoke(
            Documents.DOCUMENTS_AND_EDITORS,
            Documents.DELTA,
            Documents.openDelta(data, editor, editorId.takeIf { active })
        )
        if (active) activeEditorId = editorId
        documents[key(uri)] = Document(
            body,
            Documents.FIRST_VERSION,
            language,
            mutableSetOf(owner),
            mutableSetOf(owner),
            editorId,
            encoding,
            indentation
        )
        spellings[key(uri)] = uri
        // Tabs, not just editors. The language client's pull-diagnostics feature builds its set of
        // open resources from `window.tabGroups`, not from the visible editors, and asks whether a
        // document is in it before requesting `textDocument/diagnostic`. Without this it answers
        // every refresh the server sends and never pulls, so a server whose live analysis is
        // pull-based reports nothing at all until it pushes something of its own accord.
        publishTabs(dirty)
    }

    /**
     * Replaces the host's copy of [uri] with the text the editor holds now.
     *
     * Whole-document changes, deliberately. Several IntelliJ endpoints report the same edit, edits
     * are coalesced before they reach here, and a file re-read from disk arrives as a replacement
     * anyway; sending the text we have makes all three the same operation. Returns whether the host
     * was told anything, which is false whenever the text it already has is the text we were given.
     */
    fun change(owner: String, uri: String, text: String, dirty: Boolean): Boolean =
        sending { emit(uri, owned(owner, uri), normalize(text), dirty) }

    /**
     * Tells the host that [uri] is now decoded as [encoding], if that is news to it. Returns whether
     * it was. The text a new encoding produces arrives as an ordinary change, through [change].
     */
    fun encodingChanged(owner: String, uri: String, encoding: String): Boolean =
        sending { encode(uri, owned(owner, uri), encoding) }

    /** The encoding the host was last told [uri] is decoded with, for tests and diagnosis. */
    @Synchronized
    fun encodingOf(uri: String): String? = documents[key(uri)]?.encoding

    fun save(owner: String, uri: String): Unit = sending {
        owned(owner, uri)
        // The host clears the document's dirty flag itself on the way to `onDidSaveTextDocument`.
        invoke(Documents.DOCUMENTS, Documents.MODEL_SAVED, components(uri).toJson())
    }

    /**
     * Withdraws [owner]'s claim on [uri]: the document once nobody holds it, and its editor once
     * nobody shows it.
     */
    fun close(owner: String, uri: String): Unit = sending {
        val key = key(uri)
        val document = documents[key] ?: return@sending
        if (!document.owners.remove(owner)) return@sending
        document.viewers.remove(owner)
        val editorId = document.editorId
        val wasActive = editorId != null && activeEditorId == editorId
        if (document.owners.isNotEmpty()) {
            if (editorId == null || document.viewers.isNotEmpty()) return@sending
            // Still held by an extension, but no longer on screen.
            if (wasActive) activeEditorId = null
            document.editorId = null
            invoke(Documents.DOCUMENTS_AND_EDITORS, Documents.DELTA, Documents.hideDelta(editorId, wasActive))
            publishTabs(dirty = false)
            return@sending
        }
        documents.remove(key)
        if (wasActive) activeEditorId = null
        spellings.remove(key)
        if (editorId == null) {
            invoke(Documents.DOCUMENTS_AND_EDITORS, Documents.DELTA, Documents.unloadDelta(components(uri)))
            return@sending
        }
        invoke(
            Documents.DOCUMENTS_AND_EDITORS,
            Documents.DELTA,
            Documents.closeDelta(components(uri), editorId, wasActive),
        )
        publishTabs(dirty = false)
    }

    /**
     * Makes [uri]'s editor the host's `window.activeTextEditor`, as selecting its tab does in VS Code.
     * Returns whether that was news to the host; a document no editor shows has nothing to activate.
     *
     * Opening and showing a document activate it too, but switching between files already open does
     * neither, and the host would go on believing the last file opened is the one in front of the
     * user. Extensions check: CodeLLDB's "Cargo ..." completion in `launch.json` does nothing unless
     * the active editor is that file.
     */
    fun activate(owner: String, uri: String): Boolean = sending {
        val document = documents[key(uri)]?.takeIf { owner in it.viewers } ?: return@sending false
        val editorId = document.editorId ?: return@sending false
        if (editorId == activeEditorId) return@sending false
        invoke(Documents.DOCUMENTS_AND_EDITORS, Documents.DELTA, Documents.activateDelta(editorId))
        activeEditorId = editorId
        publishTabs(dirty = false)
        true
    }

    fun release(owner: String): Unit = sending {
        val uris = documents.filterValues { owner in it.owners }.keys.toList()
        for (uri in uris) runCatching { close(owner, uri) }
    }

    /** Whether [owner] currently mirrors [uri]. */
    @Synchronized
    fun isOpen(owner: String, uri: String): Boolean =
        documents[key(uri)]?.owners?.contains(owner) == true

    /** Whether [owner] has [uri] open in an editor, rather than only holding it. */
    @Synchronized
    fun isShown(owner: String, uri: String): Boolean =
        documents[key(uri)]?.viewers?.contains(owner) == true

    /** The resource the host holds [uri] under, as it was first spelled, or null if it holds none. */
    @Synchronized
    fun spellingOf(uri: String): String? = spellings[key(uri)]

    /** The text the host was last sent for [uri], for tests and diagnosis. */
    @Synchronized
    fun textOf(uri: String): String? = documents[key(uri)]?.text

    /** The version the host last accepted for [uri], for tests and diagnosis. */
    @Synchronized
    fun versionOf(uri: String): Int? = documents[key(uri)]?.version

    /** The editor the host was told is showing [uri], for tests and diagnosis. */
    @Synchronized
    fun editorOf(uri: String): String? = documents[key(uri)]?.editorId

    /** The editor the host was last told is active, for tests and diagnosis. */
    @Synchronized
    fun activeEditor(): String? = activeEditorId

    /**
     * Gives the host its tab model before any extension runs, empty when no editor is open yet.
     *
     * VS Code's workbench sends one as the host connects, and `window.tabGroups.activeTabGroup`
     * asserts there is an active group. Without it, an extension that looks at the tabs before the
     * first document is mirrored -- GitLens's timeline does as its view resolves -- fails outright.
     */
    fun hostInitialized(): Unit = sending { publishTabs(dirty = false) }

    /**
     * Sends the whole tab model, one tab per mirrored document.
     *
     * Whole rather than incremental, as VS Code also sends it: the runtime reconciles tabs by id,
     * and a set this small is not worth diffing. [dirty] applies to the active document only, which
     * is the one an edit or a save was about.
     */
    private fun publishTabs(dirty: Boolean) {
        val active = activeEditorId?.let { id -> documents.entries.firstOrNull { it.value.editorId == id }?.key }
        // Only what an editor shows. A document an extension merely holds is in no tab in VS Code.
        val tabs = documents.mapNotNull { (key, document) ->
            val editorId = document.editorId ?: return@mapNotNull null
            val uri = spellings[key] ?: key
            EditorTabs.Tab(
                id = EditorTabs.idOf(editorId),
                uri = uri,
                label = EditorTabs.labelOf(uri),
                dirty = dirty && key == active,
            )
        }
        // Never allowed to take document synchronization down with it. Tabs are what make pull-based
        // language features work; a document that is not mirrored at all is a strictly worse
        // failure, and this call sits at the end of opening one. Sending a malformed model once
        // aborted `open` partway through and left the editor's files unmirrored -- everything
        // downstream then looked like an extension that had gone quiet.
        runCatching {
            invokeRaw(
                EditorTabs.TABS,
                EditorTabs.ACCEPT_MODEL,
                EditorTabs.model(tabs, spellings[active]),
                reportTabFailure
            )
        }.onFailure(reportTabFailure)
    }

    private fun encode(uri: String, document: Document, encoding: String): Boolean {
        if (encoding == document.encoding) return false
        invoke(Documents.DOCUMENTS, Documents.ENCODING_CHANGED, components(uri).toJson(), JsonPrimitive(encoding))
        document.encoding = encoding
        return true
    }

    /** Gives a held document an editor, now that an owner has it on screen. */
    private fun show(uri: String, document: Document, dirty: Boolean, active: Boolean) {
        val editorId = "codebridge-editor-${nextEditorId++}"
        val editor = Documents.addedEditor(
            editorId, components(spellings[key(uri)] ?: uri), document.text,
            Indentation.guess(document.text, document.indentation)
        )
        invoke(
            Documents.DOCUMENTS_AND_EDITORS,
            Documents.DELTA,
            Documents.showDelta(editor, editorId.takeIf { active })
        )
        document.editorId = editorId
        if (active) activeEditorId = editorId
        publishTabs(dirty)
    }

    /**
     * Sends [text] as one change covering the whole of the document's previous content.
     *
     * The range is in the *old* document's coordinates, which is what `$acceptModelChanged` applies
     * it against, so it has to be built before the stored text is replaced.
     */
    private fun emit(uri: String, document: Document, text: String, dirty: Boolean): Boolean {
        if (text == document.text) return false
        val version = document.version + 1
        val event = Documents.changeEvent(document.text, text, version)
        invoke(Documents.DOCUMENTS, Documents.MODEL_CHANGED, components(uri).toJson(), event, JsonPrimitive(dirty))
        document.text = text; document.version = version
        return true
    }

    private fun owned(owner: String, uri: String): Document =
        documents[key(uri)]?.takeIf { owner in it.owners } ?: error("Document is not open: $uri")

    private fun invoke(service: String, method: String, vararg args: JsonElement) {
        invokeRaw(service, method, JsonArray(args.toList()).toString())
    }

    /**
     * Sends a call, to be waited for once [sending] lets go of this model. [failed], when given,
     * takes the call's failure instead of the caller.
     */
    private fun invokeRaw(service: String, method: String, argsJson: String, failed: ((Throwable) -> Unit)? = null) {
        val replies = checkNotNull(outgoing) { "$service.$method sent outside sending {}" }
        // Undispatched: written to the host now, under the lock; only the answer is waited for later.
        replies += Outgoing(calls.async(start = CoroutineStart.UNDISPATCHED) { callHost(service, method, argsJson) }, failed)
    }

    private class Outgoing(val reply: Deferred<String>, val failed: ((Throwable) -> Unit)?)

    /** The calls [sending] has sent and not yet waited for; only touched while holding this model. */
    private var outgoing: MutableList<Outgoing>? = null

    /**
     * Runs [block] holding this model, then waits for the host to answer what it sent, without it.
     *
     * The sends stay under the lock, which is what keeps them in order: each is written as it is
     * made, so the host gets them as the versions here were counted. The wait does not. Replies are
     * delivered on the workbench thread, behind every call the host made before them, and a call
     * that asks this model anything -- a code lens provider registering, wanting to know which
     * documents are shown -- would otherwise wait for the lock while the reply it holds up waits
     * for it, until the reply timed out. Opening one file once cost rust-analyzer a minute that way.
     */
    private fun <T> sending(block: () -> T): T {
        val replies = mutableListOf<Outgoing>()
        val result = synchronized(this) {
            // Re-entered, as [release] does through [close]: the outer call waits for all of it.
            if (outgoing != null) return block()
            outgoing = replies
            try {
                block()
            } finally {
                outgoing = null
            }
        }
        if (replies.isEmpty()) return result
        // Bounded by the reply timeout rather than tied to a caller's progress: the model is also driven
        // where no IDE runs -- the protocol's own tests -- and the platform's blocking wait needs one.
        @Suppress("RAW_RUN_BLOCKING")
        val errors = runBlocking { replies.map { sent -> sent to failureOf(sent.reply) } }
        var failure: Throwable? = null
        for ((sent, error) in errors) {
            if (error == null) continue
            sent.failed?.invoke(error) ?: run { if (failure == null) failure = error }
        }
        failure?.let { throw it }
        return result
    }

    /** What [reply] failed with, or null for an answer; a timeout is a failure of the reply, not of the wait. */
    private suspend fun failureOf(reply: Deferred<String>): Throwable? = try {
        withTimeout(REPLY_SECONDS.seconds) { reply.await() }
        null
    } catch (_: TimeoutCancellationException) {
        TimeoutException("No answer from the extension host within ${REPLY_SECONDS}s")
    } catch (cancelled: CancellationException) {
        // The reply's own, not the waiter's, which ends the wait instead.
        currentCoroutineContext().ensureActive()
        cancelled
    } catch (error: Exception) {
        error
    }

    companion object {

        /** How long a document call may take before it counts as failed. */
        private const val REPLY_SECONDS = 30L

        private fun normalize(text: String) = Documents.normalize(text)

        private fun components(value: String): UriComponents = UriComponents.of(value)

        /** Compare equivalent URI spellings, including VS Code's encoded/lowercase Windows drive. */
        fun key(value: String): String = UriComponents.key(value)

    }
}
