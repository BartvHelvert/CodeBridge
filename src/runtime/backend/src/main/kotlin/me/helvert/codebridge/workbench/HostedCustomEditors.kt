package me.helvert.codebridge.workbench

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import me.helvert.codebridge.exthost.ExtensionHost
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.CustomEditors
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.rpc.CustomEditorDto
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadCustomEditors`: the editors extensions draw in a webview for the files their
 * `contributes.customEditors` claim, the workbench half of VS Code's custom editors.
 *
 * An editor is open while the frontend collects its [open] flow. Opening one wakes its extension
 * (`onCustomEditor:`), waits for it to register its provider, makes the document -- the file's text,
 * held in the host, for a text editor provider; one of the extension's own (`$createCustomDocument`)
 * otherwise -- and has the extension fill a webview for it (`$resolveCustomEditor`), which
 * [HostedWebviews] then keeps as it keeps a panel's. Editors of one file share its document, which
 * goes with the last of them; one with changes is saved first, as a closed notebook is.
 *
 * An editable custom document's history is kept here as VS Code's workbench keeps it: the ids of
 * the edits the extension reported, how far along them the document is, and where it was saved.
 * Undo and redo walk that list and ask the extension to take back or make again the edit under it.
 * A text document's history is [CustomTextUndo]'s.
 *
 * Everything that reads or changes that state runs on [thread], one task at a time.
 */
@Service(Service.Level.PROJECT)
class HostedCustomEditors(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val thread = Dispatchers.Default.limitedParallelism(1)

    private val providers = MutableStateFlow<Map<String, CustomEditors.Provider>>(emptyMap())
    @Volatile private var session: ExtensionHost? = null
    private val editors = HashMap<String, Editor>()
    /** Documents by [key], made once for every editor of the file that asks while it is being made. */
    private val documents = HashMap<String, Deferred<Doc?>>()

    private val webviews get() = HostedWebviews.getInstance(project)
    private val servers get() = project.service<HostedLanguageServers>()

    private class Editor(val id: String, val file: VirtualFile, val viewType: String, val position: Int) {
        val state = MutableStateFlow(CustomEditorDto())
        var handle: String? = null
        var document: Doc? = null
        var resolving: Job? = null
        var closed = false
    }

    /** One document, shown by [editors]; [text] is the IDE's document for a text editor provider. */
    private inner class Doc(
        val key: String,
        val file: VirtualFile,
        val resource: UriComponents,
        val viewType: String,
        val host: ExtensionHost,
        val editable: Boolean,
        val text: Document?,
    ) {
        val editors = linkedSetOf<Editor>()
        /** The edits the extension reported, oldest first, and the index of the last one the document has. */
        val edits = mutableListOf<Int>()
        var current = -1
        /** [current] when last saved. */
        var savePoint = -1
        /** Changed in a way that cannot be undone (`$onContentChange`) since it was saved. */
        var contentChanged = false
        var saving: Job? = null
        val disposable: Disposable = Disposer.newDisposable(this@HostedCustomEditors, "custom editor document")
        val undo: CustomTextUndo? = text?.let { CustomTextUndo(it, disposable) { scope.launch(thread) { publish(this@Doc) } } }

        val dirty: Boolean
            get() = if (text != null) FileDocumentManager.getInstance().isDocumentUnsaved(text)
                else contentChanged || (edits.isNotEmpty() && savePoint != current)
        val canUndo: Boolean get() = undo?.canUndo ?: (current >= 0)
        val canRedo: Boolean get() = undo?.canRedo ?: (current < edits.size - 1)
    }

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            // Saving all saves the extensions' own documents too, which are no document of the IDE's.
            override fun beforeAllDocumentsSaving() {
                scope.launch(thread) { openDocuments().filter { it.text == null && it.editable && it.dirty }.forEach { save(it) } }
            }

            override fun afterDocumentSaved(document: Document) {
                scope.launch(thread) { openDocuments().filter { it.text === document }.forEach(::publish) }
            }
        })
    }

    // ------------------------------------------------------------------------------------- the host

    fun hostStarted(session: ExtensionHost) {
        this.session = session
        // Editors left open when the last host went are filled again by this one; none is started for them.
        scope.launch(thread) { editors.values.filter { it.document == null && it.resolving?.isActive != true }.forEach(::startResolving) }
    }

    /**
     * The host went, and with it every document and page it made: their editors wait for the next
     * one. Unsaved changes to an extension's own documents went with it, as VS Code loses them
     * without a backup.
     */
    fun hostStopped() {
        session = null
        providers.value = emptyMap()
        scope.launch(thread) {
            val open = openDocuments()
            documents.clear()
            for (doc in open) {
                if (doc.text == null && doc.dirty) LOG.warn("Unsaved changes to ${doc.file.name} were lost with the extension host")
                Disposer.dispose(doc.disposable)
            }
            for (editor in editors.values) {
                editor.resolving?.cancel()
                editor.handle = null
                editor.document = null
                editor.state.value = CustomEditorDto(message = "Waiting for the extension host to start again...")
            }
        }
    }

    /** Handles one call of `MainThreadCustomEditors`, none of which answers with a value. Off the RPC reader. */
    fun handle(call: MainThreadCall) {
        try {
            when (call.method) {
                CustomEditors.REGISTER_TEXT_PROVIDER, CustomEditors.REGISTER_CUSTOM_PROVIDER -> {
                    val provider = CustomEditors.provider(call.method, call.argsJson)
                    providers.update { it + (provider.viewType to provider) }
                }
                CustomEditors.UNREGISTER_PROVIDER -> {
                    val viewType = CustomEditors.viewType(call.argsJson)
                    providers.update { it - viewType }
                }
                CustomEditors.ON_DID_EDIT, CustomEditors.ON_CONTENT_CHANGE -> {
                    val edit = CustomEditors.edit(call.method, call.argsJson)
                    scope.launch(thread) { edited(edit) }
                }
            }
        } catch (failure: Exception) {
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
    }

    // ------------------------------------------------------------------------------------- opening

    /**
     * Custom editor [editorId] of [viewType] on [file], in editor group [position], for as long as
     * the flow is collected; see [me.helvert.codebridge.rpc.CodeBridgeApi.openCustomEditor].
     */
    fun open(file: VirtualFile, viewType: String, editorId: String, position: Int): Flow<CustomEditorDto> = flow {
        val editor = Editor(editorId, file, viewType, position)
        withContext(thread) {
            editors[editorId]?.let { close(it) }
            editors[editorId] = editor
            startResolving(editor)
        }
        try {
            emitAll(editor.state)
        } finally {
            withContext(NonCancellable + thread) { close(editor) }
        }
    }

    private fun startResolving(editor: Editor) {
        editor.resolving = scope.launch(thread) {
            try {
                resolve(editor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("Could not open ${editor.file.name} in ${editor.viewType}", failure)
                if (!editor.closed) editor.state.value = CustomEditorDto(message = HostedStatusBar.reason(failure))
            }
        }
    }

    /** Fills [editor]: its provider registered, its document made, and its page resolved. On [thread]. */
    private suspend fun resolve(editor: Editor) {
        val type = CustomEditorContributions.types(project).firstOrNull { it.viewType == editor.viewType }
            ?: return fail(editor, "No enabled extension provides the '${editor.viewType}' editor.")
        val provider = providers.value[editor.viewType] ?: run {
            editor.state.value = CustomEditorDto(message = "Starting ${type.displayName}...")
            try {
                servers.activate(type.extensionId, CustomEditors.activationEvent(editor.viewType))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return fail(editor, "Could not start ${type.extensionId}: ${HostedStatusBar.reason(failure)}")
            }
            withTimeoutOrNull(REGISTRATION_WAIT_MILLIS.milliseconds) { providers.first { editor.viewType in it } }?.get(editor.viewType)
                ?: return fail(editor, "${type.extensionId} did not register the '${type.displayName}' editor.")
        }
        val host = session ?: return fail(editor, "The extension host is not running.")
        val doc = document(editor, provider, host) ?: return
        if (editor.closed) return
        if (doc.editors.isNotEmpty() && !provider.text && !provider.multipleEditors) {
            return fail(editor, "${type.displayName} shows ${editor.file.name} in one editor at a time.")
        }
        doc.editors += editor
        editor.document = doc
        val handle = UUID.randomUUID().toString()
        webviews.customEditorCreated(handle, editor.viewType, provider.extension, editor.file.name, provider.panelOptions, provider.binaryMessages)
        editor.handle = handle
        // The page the extension sets while resolving is shown as it arrives.
        publish(doc)
        try {
            host.call(
                CustomEditors.HOST, CustomEditors.RESOLVE_EDITOR,
                CustomEditors.resolveEditor(doc.resource, handle, editor.viewType, editor.file.name, provider.panelOptions, true, editor.position),
                cancellable = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("${type.extensionId} could not open ${editor.file.name}", failure)
            webviews.customEditorClosed(handle)
            editor.handle = null
            editor.state.value = CustomEditorDto(message = "${type.displayName} could not open ${editor.file.name}: ${HostedStatusBar.reason(failure)}")
        }
    }

    /**
     * The document [editor] shows, made if no editor of its file has one: the file's text held in the
     * host for a text editor provider, or the extension's own. Null once [editor] says why not.
     */
    private suspend fun document(editor: Editor, provider: CustomEditors.Provider, host: ExtensionHost): Doc? {
        val resource = runCatching { UriComponents.of(editor.file.toNioPath()) }.getOrNull()
            ?: return null.also { fail(editor, "${editor.file.name} is not a file on disk.") }
        val key = editor.viewType + "@" + UriComponents.key(resource.uri)
        val existing = documents[key]
        val made = if (existing != null && (existing.isActive || existing.await()?.host === host)) existing else {
            // Made whatever becomes of the editor that asked: the host may have made it by then, and
            // would refuse to make it again for the next editor.
            scope.async(thread + NonCancellable) { make(key, editor.file, resource, editor.viewType, provider, host) }.also { documents[key] = it }
        }
        return made.await() ?: null.also {
            if (documents[key] === made) documents.remove(key)
            fail(editor, "${editor.file.name} could not be opened in this editor.")
        }
    }

    private suspend fun make(
        key: String, file: VirtualFile, resource: UriComponents, viewType: String, provider: CustomEditors.Provider, host: ExtensionHost,
    ): Doc? = try {
        if (provider.text) {
            val held = servers.documents.holdFor(HOLDER, file) ?: return null
            val text = readActionDocument(file) ?: return null.also { servers.documents.releaseFor(HOLDER, file) }
            Doc(key, file, held, viewType, host, file.isWritable, text)
        } else {
            val reply = host.call(CustomEditors.HOST, CustomEditors.CREATE_DOCUMENT, CustomEditors.createDocument(resource, viewType), cancellable = true)
            Doc(key, file, resource, viewType, host, CustomEditors.editable(reply), null)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LOG.info("Could not make the ${viewType} document of ${file.name}", failure)
        null
    }

    private fun readActionDocument(file: VirtualFile): Document? =
        ReadAction.computeBlocking<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file) }

    private fun fail(editor: Editor, problem: String) {
        LOG.info("Custom editor ${editor.viewType} on ${editor.file.name}: $problem")
        if (!editor.closed) editor.state.value = CustomEditorDto(message = problem)
    }

    // ------------------------------------------------------------------------------------- closing

    /**
     * [editor] closed: its page goes, and its document with the last editor of it -- saved first if
     * it has changes, which would otherwise be lost. On [thread].
     */
    private suspend fun close(editor: Editor) {
        if (editor.closed) return
        editor.closed = true
        editor.resolving?.cancel()
        editors.remove(editor.id, editor)
        editor.handle?.let(webviews::customEditorClosed)
        editor.handle = null
        val doc = editor.document ?: return
        editor.document = null
        doc.editors.remove(editor)
        if (doc.editors.isNotEmpty()) return publish(doc)
        documents.remove(doc.key)
        Disposer.dispose(doc.disposable)
        if (doc.text != null) return servers.documents.releaseFor(HOLDER, doc.file)
        if (doc.host !== session) return
        doc.saving?.join()
        if (doc.editable && doc.dirty) save(doc)?.join()
        try {
            doc.host.call(CustomEditors.HOST, CustomEditors.DISPOSE_DOCUMENT, CustomEditors.document(doc.resource, doc.viewType))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("Could not let go of the ${doc.viewType} document of ${doc.file.name}", failure)
        }
    }

    override fun dispose() = Unit

    // ------------------------------------------------------------------------------------- editing

    /** The extension reported an edit of one of its documents: one to undo by its id, or a change that cannot be. On [thread]. */
    private fun edited(edit: CustomEditors.Edit) {
        val doc = openDocuments().firstOrNull { it.viewType == edit.viewType && it.text == null && UriComponents.key(it.resource.uri) == UriComponents.key(edit.resource.uri) }
            ?: return
        val editId = edit.editId
        if (editId == null) {
            doc.contentChanged = true
        } else {
            doc.contentChanged = false
            discardRedo(doc)
            doc.edits.add(editId)
            doc.current = doc.edits.size - 1
        }
        publish(doc)
    }

    /** Forgets the edits after the document's current one, which a new edit makes impossible to redo, and tells the extension. */
    private fun discardRedo(doc: Doc) {
        if (doc.current >= doc.edits.size - 1) return
        val discarded = doc.edits.subList(doc.current + 1, doc.edits.size)
        val ids = discarded.toList()
        discarded.clear()
        if (doc.savePoint > doc.current) doc.savePoint = -2
        call(doc, CustomEditors.DISPOSE_EDITS, CustomEditors.disposeEdits(doc.resource, doc.viewType, ids))
    }

    suspend fun save(editorId: String) {
        withContext(thread) { editors[editorId]?.document }?.let { doc -> withContext(thread) { save(doc) }?.join() }
    }

    /** Saves [doc], once at a time; its job, or null when there is nothing to save. On [thread]. */
    private fun save(doc: Doc): Job? {
        doc.saving?.takeIf { it.isActive }?.let { return it }
        if (!doc.editable || !doc.dirty) return null
        val text = doc.text
        val job = scope.launch(thread) {
            if (text != null) {
                withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveDocument(text) }
                return@launch publish(doc)
            }
            val saved = doc.current
            try {
                doc.host.call(CustomEditors.HOST, CustomEditors.SAVE, CustomEditors.document(doc.resource, doc.viewType), cancellable = true)
                doc.savePoint = saved
                doc.contentChanged = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not save ${doc.file.name} in ${doc.viewType}", failure)
                HostedMessages.getInstance(project).notify(Messages.Severity.ERROR, "Could not save ${doc.file.name}: ${HostedStatusBar.reason(failure)}")
            }
            publish(doc)
        }
        doc.saving = job
        return job
    }

    suspend fun undo(editorId: String) = step(editorId, back = true)
    suspend fun redo(editorId: String) = step(editorId, back = false)

    private suspend fun step(editorId: String, back: Boolean) {
        val doc = withContext(thread) { editors[editorId]?.document } ?: return
        val undo = doc.undo
        if (undo != null) return replay(doc, undo, back)
        withContext(thread) {
            val editId = if (back) {
                if (doc.current < 0) return@withContext
                doc.edits[doc.current--]
            } else {
                if (doc.current >= doc.edits.size - 1) return@withContext
                doc.edits[++doc.current]
            }
            publish(doc)
            call(doc, if (back) CustomEditors.UNDO else CustomEditors.REDO, CustomEditors.step(doc.resource, doc.viewType, editId, doc.dirty))
        }
    }

    /** Takes back, or makes again, a step of a text document's history, as an undoable command of its own. */
    private suspend fun replay(doc: Doc, undo: CustomTextUndo, back: Boolean) {
        val text = doc.text ?: return
        withContext(Dispatchers.EDT) {
            val changes = (if (back) undo.undo() else undo.redo()) ?: return@withContext
            undo.replaying = true
            try {
                WriteCommandAction.writeCommandAction(project).withName(if (back) "Undo" else "Redo").run<RuntimeException> {
                    for (change in changes) text.replaceString(change.offset, change.offset + change.old.length, change.new)
                }
            } finally {
                undo.replaying = false
            }
        }
        withContext(thread) { publish(doc) }
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun openDocuments(): List<Doc> =
        documents.values.mapNotNull { deferred -> if (deferred.isCompleted && !deferred.isCancelled) deferred.getCompleted() else null }

    /** Tells every editor of [doc] what it is now. On [thread]. */
    private fun publish(doc: Doc) {
        val dirty = doc.dirty
        for (editor in doc.editors) {
            editor.state.value = CustomEditorDto(editor.handle, null, doc.editable, dirty, doc.editable && doc.canUndo, doc.editable && doc.canRedo)
        }
    }

    /** Calls the host about [doc], in order with the calls before it; the answer is only looked at for a failure. */
    private fun call(doc: Doc, method: String, args: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                doc.host.call(CustomEditors.HOST, method, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("$method of ${doc.file.name} failed", failure)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(HostedCustomEditors::class.java)
        private const val REGISTRATION_WAIT_MILLIS = 10_000L

        /** Who holds the text documents custom editors show, in the host's copy of them. */
        private const val HOLDER = "customEditors"

        fun getInstance(project: Project): HostedCustomEditors = project.service()
    }
}
