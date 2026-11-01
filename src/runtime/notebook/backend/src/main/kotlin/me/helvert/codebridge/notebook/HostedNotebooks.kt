package me.helvert.codebridge.notebook

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.Notebooks
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.language.CodeBridgeHostService
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HoverMarkdown
import me.helvert.codebridge.workbench.HostedContentProviders
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The project's open notebooks, the serializers and kernels extensions provide for them, and the
 * runs those kernels report: the workbench half of VS Code's notebooks.
 *
 * Everything that reads or changes that state runs on [thread], one task at a time, so the host
 * hears of each change in the order it was made: a call to it is sent before its caller first
 * suspends, and [send] launches each undispatched for that reason.
 *
 * A notebook is open while an editor shows it ([open]) or an extension asked for it
 * (`openNotebookDocument`); closing its last editor saves what it changed first, as the IDE keeps
 * an edited file's text. Its file changed elsewhere is read again, unless the notebook has changes
 * of its own: then its editors ask first ([NotebookEventDto.ChangedOnDisk]).
 */
@Service(Service.Level.PROJECT)
internal class HostedNotebooks(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val thread = Dispatchers.Default.limitedParallelism(1)

    @Volatile private var host: CodeBridgeHostService.Host? = null
    private val serializers = MutableStateFlow<Map<Int, Notebooks.Serializer>>(emptyMap())
    private val kernels = linkedMapOf<Int, Notebooks.Kernel>()
    /** What each kernel said of each notebook, by [Open.key]: `NotebookControllerAffinity`. */
    private val affinities = HashMap<Int, MutableMap<String, Int>>()
    /** Notebook types an extension is still looking for kernels for, by its task's handle. */
    private val detecting = HashMap<Int, String>()
    private val notebooks = HashMap<String, Open>()
    /** In the order they opened: the last is the one the host is told is active. */
    private val editors = linkedMapOf<String, Open>()
    private val executions = HashMap<Int, Execution>()
    /** The cells last copied or cut, in any notebook, as the workbench's notebook clipboard keeps them. */
    private var clipboard = emptyList<Notebooks.Cell>()

    private class Editor(val id: String, val events: Channel<NotebookEventDto>)

    private class Open(val file: VirtualFile, val model: NotebookModel) {
        val key: String = UriComponents.key(model.uri.uri)
        val editors = linkedMapOf<String, Editor>()
        /** The handle of the kernel selected for the notebook. */
        var kernel: Int? = null
        /** An extension opened it with `openNotebookDocument`, so it stays open without an editor. */
        var keptByExtension = false
        /** Its last editor closed, but what it changed could not be written: kept so that is not lost. */
        var keptUnsaved = false
        /** The host told of it: one that has not heard of it is told nothing of its editors. */
        var announcedTo: CodeBridgeHostService.Host? = null
        /** Its file changed elsewhere while it had changes of its own, so it was not read again. */
        var changedOnDisk = false
        val kept: Boolean get() = keptByExtension || keptUnsaved
    }

    /** A cell's run, from `$createExecution` until `$completeExecution`. */
    private class Execution(val notebook: String, val cell: Int, var start: Long? = null, var order: Int? = null)

    private class NotebookException(message: String) : Exception(message)

    init {
        project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // Not what [save] wrote, which names this as the requestor.
                val changed = events.filter { it is VFileContentChangeEvent && it.requestor !== this@HostedNotebooks }.mapNotNull { it.file }
                if (changed.isEmpty()) return
                scope.launch(thread) {
                    for (open in notebooks.values) if (open.file in changed) scope.launch { changedOnDisk(open) }
                }
            }
        })
    }

    // ------------------------------------------------------------------------------------- the host

    fun hostStarted(calls: CodeBridgeHostService.Host) {
        host = calls
        // A notebook that stayed open across a restart is told to the new host once its extensions
        // run: not one opened since, which [announce] told already. No host is started for it, so
        // one that failed to start, or was stopped, stays stopped.
        scope.launch(thread) {
            val types = notebooks.values.filter { it.announcedTo !== calls }.map { it.model.viewType }.distinct()
            if (types.isEmpty()) return@launch
            scope.launch {
                try {
                    types.forEach { activate(it, startHost = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.info("Could not reopen notebooks in the new extension host", failure)
                }
                withContext(thread) {
                    if (host === calls) notebooks.values.filter { it.announcedTo !== calls }.forEach(::reannounce)
                }
            }
        }
    }

    fun hostStopped() {
        host = null
        scope.launch(thread) {
            serializers.value = emptyMap()
            kernels.clear(); affinities.clear(); detecting.clear(); executions.clear()
            for (open in notebooks.values) {
                open.kernel = null
                for (cell in open.model.cells) if (cell.run.state != RunState.IDLE) {
                    cell.runStart = null
                    cell.run = cell.run.copy(state = RunState.IDLE)
                    broadcast(open, NotebookEventDto.Run(cell.handle, cell.run))
                }
                broadcast(open, kernelsEvent(open))
            }
        }
    }

    suspend fun answer(call: MainThreadCall): MainThreadResult = when (call.service) {
        Notebooks.SERVICE -> withContext(thread) { notebookCall(call) }
        Notebooks.DOCUMENTS_SERVICE -> documentsCall(call)
        Notebooks.EDITORS_SERVICE -> editorsCall(call)
        Notebooks.KERNELS_SERVICE -> withContext(thread) { kernelsCall(call) }
        // Renderers draw in a webview this editor does not have: a message to one goes nowhere.
        else -> if (call.method == POST_MESSAGE) MainThreadResult("false") else MainThreadResult()
    }

    private fun notebookCall(call: MainThreadCall): MainThreadResult {
        when (call.method) {
            Notebooks.REGISTER_SERIALIZER -> {
                val serializer = Notebooks.serializer(call.argsJson)
                serializers.update { it + (serializer.handle to serializer) }
            }
            Notebooks.UNREGISTER_SERIALIZER -> {
                val handle = Args.of(call.method, call.argsJson).int(0)
                serializers.update { it - handle }
            }
        }
        return MainThreadResult()
    }

    private suspend fun documentsCall(call: MainThreadCall): MainThreadResult = when (call.method) {
        Notebooks.TRY_OPEN -> {
            val uri = Args.of(call.method, call.argsJson).uri(0)
            keep(uri)
            MainThreadResult(uri.toJson().toString())
        }
        Notebooks.TRY_SAVE -> {
            val key = UriComponents.key(Args.of(call.method, call.argsJson).uri(0).uri)
            val open = withContext(thread) { notebooks[key] }
            MainThreadResult((open != null && save(open)).toString())
        }
        Notebooks.TRY_CREATE -> throw UnsupportedOperationException("Untitled notebooks are not supported in this editor yet")
        else -> MainThreadResult()
    }

    private suspend fun editorsCall(call: MainThreadCall): MainThreadResult {
        if (call.method != Notebooks.TRY_SHOW) return MainThreadResult()
        val uri = Args.of(call.method, call.argsJson).uri(0)
        val file = fileOf(uri) ?: throw NotebookException("No file at ${uri.uri}")
        CodeBridgeTextEditors.getInstance(project).reveal(file, null, focus = true)
        val key = UriComponents.key(uri.uri)
        // The editor opens on the frontend, and announces itself with [open].
        val editor = withTimeoutOrNull(SHOW_WAIT) {
            var id = withContext(thread) { notebooks[key]?.editors?.keys?.lastOrNull() }
            while (id == null) {
                delay(50.milliseconds)
                id = withContext(thread) { notebooks[key]?.editors?.keys?.lastOrNull() }
            }
            id
        } ?: throw NotebookException("No notebook editor opened for ${file.name}")
        return MainThreadResult(JsonPrimitive(editor).toString())
    }

    private fun kernelsCall(call: MainThreadCall): MainThreadResult {
        when (call.method) {
            Notebooks.ADD_KERNEL, Notebooks.UPDATE_KERNEL -> {
                val kernel = Notebooks.kernel(call.method, call.argsJson)
                kernels[kernel.handle] = kernel
                kernelsChanged(kernel.notebookType)
            }
            Notebooks.REMOVE_KERNEL -> {
                val handle = Args.of(call.method, call.argsJson).int(0)
                val kernel = kernels.remove(handle) ?: return MainThreadResult()
                affinities.remove(handle)
                notebooks.values.filter { it.kernel == handle }.forEach { it.kernel = null }
                kernelsChanged(kernel.notebookType)
            }
            Notebooks.UPDATE_PRIORITY -> {
                val priority = Notebooks.priority(call.argsJson)
                val key = UriComponents.key(priority.notebook.uri)
                val map = affinities.getOrPut(priority.handle) { HashMap() }
                when (val value = priority.priority) {
                    null -> map.remove(key)
                    else -> map[key] = value
                }
                notebooks[key]?.let(::selectAutomatically)
            }
            Notebooks.ADD_DETECTION_TASK -> {
                val args = Args.of(call.method, call.argsJson)
                detecting[args.int(0)] = args.str(1)
                kernelsChanged(args.str(1))
            }
            Notebooks.REMOVE_DETECTION_TASK -> {
                val type = detecting.remove(Args.of(call.method, call.argsJson).int(0))
                if (type != null) kernelsChanged(type)
            }
            Notebooks.CREATE_EXECUTION -> {
                val execution = Notebooks.execution(call.argsJson)
                executions[execution.handle] = Execution(UriComponents.key(execution.notebook.uri), execution.cell)
            }
            Notebooks.UPDATE_EXECUTION -> executionUpdated(call)
            Notebooks.COMPLETE_EXECUTION -> executionCompleted(call)
            POST_MESSAGE -> return MainThreadResult("false")
        }
        return MainThreadResult()
    }

    private fun kernelsChanged(notebookType: String) {
        for (open in notebooks.values) if (open.model.viewType == notebookType) {
            selectAutomatically(open)
            broadcast(open, kernelsEvent(open))
        }
    }

    private fun executionUpdated(call: MainThreadCall) {
        val (handle, edits) = Notebooks.executionUpdate(call.argsJson, call.objectBuffers)
        val execution = executions[handle] ?: return
        val open = notebooks[execution.notebook] ?: return
        val model = open.model
        for (edit in edits) when (edit) {
            is Notebooks.ExecutionEdit.Outputs -> {
                val cell = model.cell(edit.cell) ?: continue
                val outputs = if (edit.append) cell.outputs + edit.outputs else edit.outputs
                send(model.setOutputs(cell.handle, outputs).orEmpty())
                broadcast(open, NotebookEventDto.Outputs(cell.handle, cell.outputs.map(NotebookModel::outputDto)))
            }
            is Notebooks.ExecutionEdit.Items -> {
                val (cell, messages) = model.setOutputItems(edit.outputId, edit.items, edit.append) ?: continue
                send(messages)
                broadcast(open, NotebookEventDto.Outputs(cell.handle, cell.outputs.map(NotebookModel::outputDto)))
            }
            is Notebooks.ExecutionEdit.State -> {
                val cell = model.cell(execution.cell) ?: continue
                edit.runStartTime?.let { execution.start = it; cell.runStart = it; cell.run = cell.run.copy(state = RunState.RUNNING) }
                edit.executionOrder?.let { execution.order = it; cell.run = cell.run.copy(order = it) }
                broadcast(open, NotebookEventDto.Run(cell.handle, model.run(cell)))
            }
        }
        dirtied(open)
    }

    private fun executionCompleted(call: MainThreadCall) {
        val completion = Notebooks.completion(call.argsJson, call.objectBuffers)
        val execution = executions.remove(completion.handle) ?: return
        val open = notebooks[execution.notebook] ?: return
        val cell = open.model.cell(execution.cell) ?: return
        val start = execution.start
        val end = completion.runEndTime
        val order = execution.order ?: cell.run.order
        cell.runStart = null
        cell.run = RunDto(RunState.IDLE, order, completion.success, if (start != null && end != null) end - start else null)
        send(open.model.setExecutionSummary(cell.handle, Notebooks.executionSummary(order, completion.success, start, end)).orEmpty())
        broadcast(open, NotebookEventDto.Run(cell.handle, cell.run))
        completion.error?.takeIf { it.isNotBlank() }?.let { broadcast(open, NotebookEventDto.Problem(it)) }
        dirtied(open)
    }

    // ------------------------------------------------------------------------------------- opening

    /** [file] as a notebook of [viewType] for editor [editorId], for as long as it collects this. */
    fun open(file: VirtualFile, viewType: String, editorId: String): Flow<NotebookEventDto> = flow {
        val editor = Editor(editorId, Channel(Channel.UNLIMITED))
        try {
            try {
                attach(file, viewType, editor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("Could not open ${file.name} as a notebook", failure)
                emit(NotebookEventDto.Failed(failure.message ?: failure.javaClass.simpleName))
                return@flow
            }
            for (event in editor.events) emit(event)
        } finally {
            withContext(NonCancellable) { detach(editorId) }
        }
    }

    private suspend fun attach(file: VirtualFile, viewType: String, editor: Editor) {
        val uri = UriComponents.of(file.toNioPath())
        val key = UriComponents.key(uri.uri)
        while (true) {
            if (withContext(thread) { notebooks[key]?.also { addEditor(it, editor) } } != null) return
            val model = load(file, uri, viewType)
            // Another editor may have read it meanwhile; the first copy is the one kept.
            val added = withContext(thread) {
                if (key in notebooks) return@withContext false
                val open = Open(file, model)
                notebooks[key] = open
                LOG.info("Opened ${file.name} as a $viewType notebook of ${model.cells.size} cells")
                announce(open)
                addEditor(open, editor)
                true
            }
            if (added) return
        }
    }

    /** Keeps [uri] open for an extension, without an editor: `workspace.openNotebookDocument`. */
    private suspend fun keep(uri: UriComponents) {
        val key = UriComponents.key(uri.uri)
        if (withContext(thread) { notebooks[key]?.also { it.keptByExtension = true } } != null) return
        val file = fileOf(uri) ?: throw NotebookException("No file at ${uri.uri}")
        val type = NotebookTypes.forFile(NotebookContributions.types(project), file.name, file.path)
            ?: throw NotebookException("No extension opens ${file.name} as a notebook")
        val model = load(file, uri, type.viewType)
        withContext(thread) {
            val open = notebooks[key] ?: Open(file, model).also { notebooks[key] = it; announce(it) }
            open.keptByExtension = true
        }
    }

    /** Reads [file] with the serializer of [viewType], activating the extensions it wakes first. */
    private suspend fun load(file: VirtualFile, uri: UriComponents, viewType: String): NotebookModel {
        val data = read(file, viewType)
        val extensions = NotebookContributions.fileExtensions(project)
        return NotebookModel(uri, viewType, data) { extensions[it] }
    }

    /** [file]'s notebook as the serializer of [viewType] reads it. */
    private suspend fun read(file: VirtualFile, viewType: String): Notebooks.Data {
        activate(viewType)
        val serializer = withTimeoutOrNull(SERIALIZER_WAIT) {
            serializers.mapNotNull { all -> all.values.firstOrNull { it.viewType == viewType } }.first()
        } ?: throw NotebookException("No extension reads notebooks of type '$viewType'")
        val bytes = withContext(Dispatchers.IO) { file.contentsToByteArray() }
        val host = host ?: throw NotebookException("The extension host is not running")
        val reply = host.call(
            Notebooks.EXT_HOST, Notebooks.DATA_TO_NOTEBOOK, Notebooks.dataToNotebook(serializer.handle),
            cancellable = true, buffers = mapOf(Notebooks.DATA_BYTES to bytes),
        )
        return Notebooks.data(Json.parseToJsonElement(reply.json), Notebooks.Buffers(reply.buffers))
    }

    /** Fires what VS Code fires before it opens a notebook of [viewType]; see [HostedLanguageServers.activateByEvent]. */
    private suspend fun activate(viewType: String, startHost: Boolean = true) {
        val servers = project.service<HostedLanguageServers>()
        servers.activateByEvent("onNotebook:$viewType", startHost)
        servers.activateByEvent("onNotebook:*", startHost)
    }

    private fun announce(open: Open) {
        open.announcedTo = host
        val buffers = Notebooks.Buffers()
        val delta = Notebooks.documentsDelta(added = listOf(open.model.hostDocument(buffers)))
        send(listOf(delta(delta, buffers)))
    }

    /** Tells a new host of [open] and its editors, as they were. */
    private fun reannounce(open: Open) {
        open.announcedTo = host
        val buffers = Notebooks.Buffers()
        val delta = Notebooks.documentsDelta(
            added = listOf(open.model.hostDocument(buffers)),
            addedEditors = open.editors.keys.map { Notebooks.addedEditor(it, open.model.uri, open.model.viewType, open.model.cells.size) },
            visible = editors.keys.toList(),
            active = open.editors.keys.lastOrNull(),
        )
        send(listOf(delta(delta, buffers)))
        selectAutomatically(open)
        broadcast(open, kernelsEvent(open))
    }

    private fun addEditor(open: Open, editor: Editor) {
        open.editors[editor.id] = editor
        editors[editor.id] = open
        open.keptUnsaved = false
        // A host that has not heard of the notebook yet hears of the editor with it, in [reannounce].
        if (open.announcedTo != null && open.announcedTo === host) {
            val added = Notebooks.addedEditor(editor.id, open.model.uri, open.model.viewType, open.model.cells.size)
            send(listOf(delta(Notebooks.documentsDelta(addedEditors = listOf(added), visible = editors.keys.toList(), active = editor.id))))
        }
        editor.events.trySend(NotebookEventDto.Loaded(open.model.cells.map(open.model::dto)))
        if (open.model.dirty) editor.events.trySend(NotebookEventDto.Dirty(true))
        if (open.changedOnDisk) editor.events.trySend(NotebookEventDto.ChangedOnDisk(true))
        // What is already known to be wrong in its cells: another editor of it may have been told.
        hearProblems()
        for (cell in open.model.cells) {
            val problems = features().problemsFor(cellUri(open, cell.handle))
            if (problems.isNotEmpty()) editor.events.trySend(NotebookEventDto.Problems(cell.handle, CellFeatures.problems(problems)))
        }
        selectAutomatically(open)
        editor.events.trySend(kernelsEvent(open))
    }

    private suspend fun detach(editorId: String) {
        val open = withContext(thread) { editors[editorId] } ?: return
        // What its last editor changed is kept, as the IDE keeps an edited file's text. When it cannot
        // be written, the notebook stays open without an editor, for the next one to show and save.
        val unsaved = withContext(thread) { open.editors.keys == setOf(editorId) && open.model.dirty && !open.kept } &&
            !save(open, quietly = true)
        withContext(thread) {
            editors.remove(editorId)
            open.editors.remove(editorId)?.events?.close()
            if (unsaved && open.model.dirty && open.editors.isEmpty()) {
                open.keptUnsaved = true
                Notification(
                    NOTIFICATION_GROUP, "Could not save ${open.file.name}",
                    "Its changes are kept until the project closes: reopen it to save them.", NotificationType.WARNING,
                ).notify(project)
            }
            val closing = open.editors.isEmpty() && !open.kept
            if (closing) {
                notebooks.remove(open.key)
                dirtyShown.remove(open.key)
                executions.values.removeIf { it.notebook == open.key }
            }
            send(listOf(delta(Notebooks.documentsDelta(
                removed = if (closing) listOf(open.model.uri) else emptyList(),
                removedEditors = listOf(editorId),
                visible = editors.keys.toList(),
                active = editors.keys.lastOrNull(),
                deactivated = editors.isEmpty(),
            ))))
        }
    }

    // --------------------------------------------------------------------------------------- edits

    suspend fun setText(editorId: String, handle: Int, text: String) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val messages = open.model.setText(handle, text) ?: return@withContext
        send(messages)
        broadcast(open, NotebookEventDto.Text(handle, open.model.cell(handle)!!.text), except = editorId)
        dirtied(open)
    }

    suspend fun insertCell(editorId: String, index: Int, kind: Int) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val (cell, messages) = open.model.insert(index, kind, languageFor(open, index, kind))
        send(messages)
        broadcast(open, NotebookEventDto.Spliced(open.model.index(cell.handle), 0, listOf(open.model.dto(cell))))
        dirtied(open)
    }

    /** A new cell's language: markdown for markup; for code, that of the code cell before it, else the kernel's. */
    private fun languageFor(open: Open, index: Int, kind: Int): String {
        if (kind == CellKinds.MARKUP) return "markdown"
        val code = open.model.cells.filter { it.kind == CellKinds.CODE }
        return open.model.cells.take(index).lastOrNull { it.kind == CellKinds.CODE }?.language
            ?: code.firstOrNull()?.language
            ?: open.kernel?.let { kernels[it] }?.supportedLanguages?.firstOrNull()
            ?: languageOfMetadata(open)
            ?: "plaintext"
    }

    /** The language a Jupyter notebook's own metadata names, as `metadata.language_info.name`. */
    private fun languageOfMetadata(open: Open): String? =
        ((open.model.metadata["metadata"] as? JsonObject)?.get("language_info")
            as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }

    suspend fun deleteCell(editorId: String, handle: Int) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val (index, messages) = open.model.delete(handle) ?: return@withContext
        send(messages)
        broadcast(open, NotebookEventDto.Spliced(index, 1, emptyList()))
        dirtied(open)
    }

    suspend fun moveCell(editorId: String, handle: Int, delta: Int) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val (moved, messages) = open.model.move(handle, delta) ?: return@withContext
        send(messages)
        broadcastMove(open, moved.first, moved.second, 1)
        dirtied(open)
    }

    /** Moves cells [handles], next to each other, to the gap before cell [gap]. */
    suspend fun moveCells(editorId: String, handles: List<Int>, gap: Int) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val (move, messages) = open.model.move(handles, gap) ?: return@withContext
        send(messages)
        broadcastMove(open, move.from, move.to, move.length)
        dirtied(open)
    }

    /** Tells the editors [length] cells went from [from] to [to], once the model has moved them: they are the cells at [to] now. */
    private fun broadcastMove(open: Open, from: Int, to: Int, length: Int) {
        broadcast(open, NotebookEventDto.Spliced(from, length, emptyList()))
        broadcast(open, NotebookEventDto.Spliced(to, 0, open.model.cells.subList(to, to + length).map(open.model::dto)))
    }

    suspend fun clearOutputs(editorId: String, handle: Int?) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val (cleared, messages) = open.model.clearOutputs(handle)
        send(messages)
        for (cell in cleared) broadcast(open, NotebookEventDto.Outputs(cell.handle, emptyList()))
        dirtied(open)
    }

    /** Puts copies of cells [handles] on the clipboard; with [cut], takes them out as well, as one step. */
    suspend fun copyCells(editorId: String, handles: List<Int>, cut: Boolean) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val copied = open.model.cells.filter { it.handle in handles }
        if (copied.isEmpty()) return@withContext
        clipboard = copied.map(open.model::snapshot)
        if (!cut) return@withContext
        val range = open.model.range(handles) ?: return@withContext
        val (_, messages) = open.model.replace(range.first, range.count(), emptyList())
        spliced(open, range.first, range.count(), emptyList(), messages)
    }

    /** Inserts copies of the clipboard's cells at [index]: their handles, none when it is empty. */
    suspend fun pasteCells(editorId: String, index: Int): List<Int> = withContext(thread) {
        val open = editors[editorId] ?: return@withContext emptyList()
        if (clipboard.isEmpty()) return@withContext emptyList()
        insertCopies(open, index, clipboard)
    }

    /** Inserts copies of cells [handles], next to each other, after the last of them: their handles. */
    suspend fun duplicateCells(editorId: String, handles: List<Int>): List<Int> = withContext(thread) {
        val open = editors[editorId] ?: return@withContext emptyList()
        val range = open.model.range(handles) ?: return@withContext emptyList()
        insertCopies(open, range.last + 1, open.model.cells.slice(range).map(open.model::snapshot))
    }

    /** Inserts copies of cells [handles], next to each other, at [index]: their handles. */
    suspend fun copyCellsTo(editorId: String, handles: List<Int>, index: Int): List<Int> = withContext(thread) {
        val open = editors[editorId] ?: return@withContext emptyList()
        val range = open.model.range(handles) ?: return@withContext emptyList()
        insertCopies(open, index, open.model.cells.slice(range).map(open.model::snapshot))
    }

    private fun insertCopies(open: Open, index: Int, cells: List<Notebooks.Cell>): List<Int> {
        val (made, messages) = open.model.insertCopies(index, cells)
        spliced(open, open.model.index(made.first().handle), 0, made, messages)
        return made.map { it.handle }
    }

    /** Joins cells [handles] into one: its handle, or null when they cannot be joined. */
    suspend fun joinCells(editorId: String, handles: List<Int>): Int? = withContext(thread) {
        val open = editors[editorId] ?: return@withContext null
        val count = open.model.range(handles)?.count() ?: return@withContext null
        val (cell, messages) = open.model.join(handles) ?: return@withContext null
        spliced(open, open.model.index(cell.handle), count, listOf(cell), messages)
        cell.handle
    }

    /** Makes cell [handle] a cell of [kind]: the new cell's handle, or null when it already was one. */
    suspend fun changeCellKind(editorId: String, handle: Int, kind: Int): Int? = withContext(thread) {
        val open = editors[editorId] ?: return@withContext null
        val index = open.model.index(handle).takeIf { it >= 0 } ?: return@withContext null
        val (cell, messages) = open.model.changeKind(handle, kind, languageFor(open, index, kind)) ?: return@withContext null
        spliced(open, index, 1, listOf(cell), messages)
        cell.handle
    }

    /** Tells the host and the editors that [deleted] cells at [start] gave way to [cells]. */
    private fun spliced(open: Open, start: Int, deleted: Int, cells: List<NotebookModel.Cell>, messages: List<NotebookModel.HostMessage>) {
        send(messages)
        broadcast(open, NotebookEventDto.Spliced(start, deleted, cells.map(open.model::dto)))
        dirtied(open)
    }

    /** Takes back the notebook's last cell edit, whichever editor made it: the cells to select for it. */
    suspend fun undo(editorId: String): List<Int> = replay(editorId) { it.undo() }

    /** Makes again the notebook's last edit [undo] took back: the cells to select for it. */
    suspend fun redo(editorId: String): List<Int> = replay(editorId) { it.redo() }

    private suspend fun replay(editorId: String, step: (NotebookModel) -> NotebookModel.Replayed?): List<Int> = withContext(thread) {
        val open = editors[editorId] ?: return@withContext emptyList()
        val replayed = step(open.model) ?: return@withContext emptyList()
        send(replayed.messages)
        for (edit in replayed.edits) when (edit) {
            is NotebookModel.Edit.Splice ->
                broadcast(open, NotebookEventDto.Spliced(edit.index, edit.removed.size, edit.added.map(open.model::dto)))
            is NotebookModel.Edit.Move -> broadcastMove(open, edit.from, edit.to, edit.length)
            is NotebookModel.Edit.Outputs -> broadcast(open, NotebookEventDto.Outputs(edit.handle, edit.after.map(NotebookModel::outputDto)))
        }
        dirtied(open)
        replayed.selection
    }

    // ------------------------------------------------------------------------------------- kernels

    private fun kernelsFor(open: Open) = kernels.values.filter { it.notebookType == open.model.viewType }

    private fun kernelsEvent(open: Open) = NotebookEventDto.Kernels(
        kernelsFor(open).map { KernelDto(it.id, it.label, it.description, it.detail) },
        open.kernel?.let { kernels[it] }?.id,
        detecting = open.model.viewType in detecting.values,
    )

    /**
     * Selects a kernel for a notebook that has none, where there is no choice to make: the one its
     * extension prefers for it, or the only one once nothing is looking for more.
     */
    private fun selectAutomatically(open: Open) {
        if (open.kernel != null) return
        val candidates = kernelsFor(open)
        val choice = candidates.firstOrNull { affinities[it.handle]?.get(open.key) == Notebooks.PREFERRED }
            ?: candidates.singleOrNull()?.takeIf { open.model.viewType !in detecting.values }
            ?: return
        select(open, choice.handle)
    }

    private fun select(open: Open, handle: Int) {
        if (open.kernel == handle) return
        open.kernel?.let { send(listOf(kernelMessage(Notebooks.ACCEPT_ASSOCIATION, Notebooks.association(it, open.model.uri, false)))) }
        open.kernel = handle
        send(listOf(kernelMessage(Notebooks.ACCEPT_ASSOCIATION, Notebooks.association(handle, open.model.uri, true))))
        broadcast(open, kernelsEvent(open))
    }

    suspend fun selectKernel(editorId: String, kernelId: String) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val kernel = kernelsFor(open).firstOrNull { it.id == kernelId } ?: return@withContext
        select(open, kernel.handle)
    }

    /**
     * Asks the selected kernel to run [handles]. The cells wait until the kernel starts each one's
     * execution; one it never starts goes back to idle once its run request returns, as in VS Code.
     */
    suspend fun run(editorId: String, handles: List<Int>): Boolean = withContext(thread) {
        val open = editors[editorId] ?: return@withContext false
        selectAutomatically(open)
        val kernel = open.kernel ?: return@withContext false
        val host = host ?: return@withContext false
        val cells = handles.filter { open.model.cell(it)?.kind == CellKinds.CODE }
        if (cells.isEmpty()) return@withContext true
        for (handle in cells) {
            val cell = open.model.cell(handle)!!
            cell.run = cell.run.copy(state = RunState.PENDING)
            broadcast(open, NotebookEventDto.Run(handle, cell.run))
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Answered once the kernel has run them all, however long that takes.
                host.call(Notebooks.EXT_HOST_KERNELS, Notebooks.EXECUTE_CELLS, Notebooks.cells(kernel, open.model.uri, cells), timesOut = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("A kernel failed to run cells of ${open.file.name}", failure)
                withContext(thread) { broadcast(open, NotebookEventDto.Problem("The kernel could not run the cells: ${failure.message}")) }
            } finally {
                withContext(NonCancellable + thread) {
                    for (handle in cells) {
                        val cell = open.model.cell(handle) ?: continue
                        val started = executions.values.any { it.notebook == open.key && it.cell == handle }
                        if (cell.run.state == RunState.PENDING && !started) {
                            cell.run = cell.run.copy(state = RunState.IDLE)
                            broadcast(open, NotebookEventDto.Run(handle, cell.run))
                        }
                    }
                }
            }
        }
        true
    }

    suspend fun interrupt(editorId: String, handles: List<Int>) = withContext(thread) {
        val open = editors[editorId] ?: return@withContext
        val kernel = open.kernel ?: return@withContext
        send(listOf(kernelMessage(Notebooks.CANCEL_CELLS, Notebooks.cells(kernel, open.model.uri, handles))))
    }

    // --------------------------------------------------------------------------------------- saving

    suspend fun save(editorId: String): Boolean {
        val open = withContext(thread) { editors[editorId] } ?: return false
        return save(open)
    }

    /** Writes [open] through its serializer; false when it could not be, logged, and told its editors unless [quietly]. */
    private suspend fun save(open: Open, quietly: Boolean = false): Boolean {
        try {
            val host = host ?: throw NotebookException("The extension host is not running")
            val (handle, version, data, buffers) = withContext(thread) {
                val serializer = serializers.value.values.firstOrNull { it.viewType == open.model.viewType }
                    ?: throw NotebookException("No extension writes notebooks of type '${open.model.viewType}'")
                val buffers = Notebooks.Buffers()
                val data = Notebooks.dataJson(open.model.data(serializer), buffers)
                Saving(serializer.handle, open.model.version, data, buffers.all)
            }
            val reply = host.call(
                Notebooks.EXT_HOST, Notebooks.NOTEBOOK_TO_DATA, Notebooks.notebookToData(handle, data),
                cancellable = true, objectBuffers = mapOf(Notebooks.NOTEBOOK_DATA to buffers),
            )
            val bytes = reply.bytes ?: throw NotebookException("The serializer wrote nothing")
            withContext(Dispatchers.EDT) { WriteAction.run<Exception> { open.file.setBinaryContent(bytes, -1, -1, this) } }
            LOG.info("Saved the notebook ${open.file.name}, ${bytes.size} bytes")
            withContext(thread) {
                setChangedOnDisk(open, false)
                // An edit made while it was being written leaves it dirty: that edit is not in the file.
                if (open.model.version == version) {
                    send(open.model.saved())
                    broadcast(open, NotebookEventDto.Dirty(false))
                }
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.warn("Could not save the notebook ${open.file.name}", failure)
            if (!quietly) withContext(thread) { broadcast(open, NotebookEventDto.Problem("Could not save ${open.file.name}: ${failure.message}")) }
            return false
        }
    }

    // ------------------------------------------------------------------------------ changed on disk

    /** [open]'s file changed elsewhere: read again, unless the notebook has changes of its own. */
    private suspend fun changedOnDisk(open: Open) {
        if (withContext(thread) { open.model.dirty }) withContext(thread) { setChangedOnDisk(open, true) }
        else reload(open, discard = false)
    }

    /** Reads editor [editorId]'s notebook again from its file, letting go of what it changed. */
    suspend fun reload(editorId: String) {
        val open = withContext(thread) { editors[editorId] } ?: return
        reload(open, discard = true)
    }

    /**
     * Reads [open] again from its file. Changes made while it was being read are let go only with
     * [discard]; otherwise its editors are asked about them.
     */
    private suspend fun reload(open: Open, discard: Boolean) {
        val data = try {
            read(open.file, open.model.viewType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("Could not read ${open.file.name} again", failure)
            withContext(thread) { broadcast(open, NotebookEventDto.Problem("Could not read ${open.file.name} again: ${failure.message}")) }
            return
        }
        withContext(thread) {
            if (notebooks[open.key] !== open) return@withContext
            if (open.model.dirty && !discard) {
                setChangedOnDisk(open, true)
                return@withContext
            }
            val reloaded = open.model.reload(data)
            LOG.info("Read ${open.file.name} again: ${reloaded.removed} cells gave way to ${reloaded.added.size}")
            send(reloaded.messages)
            if (reloaded.removed > 0 || reloaded.added.isNotEmpty())
                broadcast(open, NotebookEventDto.Spliced(reloaded.index, reloaded.removed, reloaded.added.map(open.model::dto)))
            setChangedOnDisk(open, false)
            dirtied(open)
        }
    }

    private fun setChangedOnDisk(open: Open, changed: Boolean) {
        if (open.changedOnDisk == changed) return
        open.changedOnDisk = changed
        broadcast(open, NotebookEventDto.ChangedOnDisk(changed))
    }

    private data class Saving(val handle: Int, val version: Int, val data: JsonObject, val buffers: List<ByteArray>)

    // ------------------------------------------------------------------------ the cells' languages

    /**
     * Cell [handle] of editor [editorId]'s notebook, as the host holds its text document: under its
     * cell URI, in its language, in a notebook of its type -- what a provider's selector claims.
     */
    private suspend fun cellDocument(editorId: String, handle: Int): HostedLanguageFeatures.HostDocument? = withContext(thread) {
        val open = editors[editorId] ?: return@withContext null
        val cell = open.model.cell(handle) ?: return@withContext null
        HostedLanguageFeatures.HostDocument(cellUri(open, handle), cell.language, open.model.viewType)
    }

    private fun cellUri(open: Open, handle: Int): String = Notebooks.cellUri(open.model.uri, handle).uri

    private fun features() = HostedLanguageFeatures.getInstance(project)

    suspend fun completions(editorId: String, handle: Int, line: Int, column: Int): List<CellCompletionDto> {
        val document = cellDocument(editorId, handle) ?: return emptyList()
        return CellFeatures.completions(features().completions(document, line, column))
    }

    /** The hover as HTML, drawn as a file's hover is: its code highlighted as the IDE highlights code. */
    suspend fun hover(editorId: String, handle: Int, line: Int, column: Int): String? {
        val document = cellDocument(editorId, handle) ?: return null
        val markdown = features().hover(document, line, column) ?: return null
        return readAction { HoverMarkdown.toHtml(project, markdown) }
    }

    /**
     * Follows the first definition there, as Go to Declaration follows a single one: into another
     * cell of the notebook, which editor [editorId] then shows, or into a file -- a library class
     * an extension provides the text of included.
     */
    suspend fun goToDefinition(editorId: String, handle: Int, line: Int, column: Int): Boolean {
        val document = cellDocument(editorId, handle) ?: return false
        val link = features().definitions(document, line, column).firstOrNull() ?: return false
        val target = UriComponents.key(link.resource.uri)
        val shown = withContext(thread) {
            val open = editors[editorId] ?: return@withContext false
            val cell = open.model.cells.firstOrNull { UriComponents.key(cellUri(open, it.handle)) == target } ?: return@withContext null
            open.editors[editorId]?.events?.trySend(NotebookEventDto.Reveal(cell.handle, link.caret.lineNumber, link.caret.column))
            true
        }
        if (shown != null) return shown
        val file = withContext(Dispatchers.IO) {
            fileOf(link.resource) ?: HostedContentProviders.getInstance(project).document(link.resource)
        } ?: return false
        CodeBridgeTextEditors.getInstance(project).reveal(file, link.caret, focus = true)
        return true
    }

    /** Whether the cells' problems are listened for yet: once, when the first notebook opens. */
    private var hearingProblems = false

    private fun hearProblems() {
        if (hearingProblems) return
        hearingProblems = true
        features().onProblemsChanged(this) { key -> scope.launch(thread) { problemsChanged(key) } }
    }

    /** Tells the editors of the cell whose document [key] names what its problems are now. */
    private fun problemsChanged(key: String) {
        for (open in notebooks.values) {
            val cell = open.model.cells.firstOrNull { UriComponents.key(cellUri(open, it.handle)) == key } ?: continue
            broadcast(open, NotebookEventDto.Problems(cell.handle, CellFeatures.problems(features().problemsFor(cellUri(open, cell.handle)))))
        }
    }

    override fun dispose() = Unit

    // ------------------------------------------------------------------------------------- helpers

    private val dirtyShown = HashMap<String, Boolean>()

    /** Tells the editors when the notebook first differs from its file. */
    private fun dirtied(open: Open) {
        if (dirtyShown[open.key] == open.model.dirty) return
        dirtyShown[open.key] = open.model.dirty
        broadcast(open, NotebookEventDto.Dirty(open.model.dirty))
    }

    private fun broadcast(open: Open, event: NotebookEventDto, except: String? = null) {
        if (event is NotebookEventDto.Dirty) dirtyShown[open.key] = event.dirty
        for (editor in open.editors.values) if (editor.id != except) editor.events.trySend(event)
    }

    private fun delta(delta: JsonObject, buffers: Notebooks.Buffers = Notebooks.Buffers()) =
        NotebookModel.HostMessage(Notebooks.EXT_HOST, Notebooks.DOCUMENTS_DELTA, "[$delta]", mapOf(0 to buffers.all))

    private fun kernelMessage(method: String, argsJson: String) = NotebookModel.HostMessage(Notebooks.EXT_HOST_KERNELS, method, argsJson)

    /**
     * Sends [messages] in order. Each call goes out before its coroutine first suspends, so
     * launching them undispatched from [thread] keeps the order they were made in.
     */
    private fun send(messages: List<NotebookModel.HostMessage>) {
        val host = host ?: return
        for (message in messages) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                host.call(message.service, message.method, message.argsJson, objectBuffers = message.objectBuffers)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("The extension host refused ${message.service}.${message.method}", failure)
            }
        }
    }

    private fun fileOf(uri: UriComponents): VirtualFile? =
        if (uri.scheme != UriComponents.FILE) null else LocalFileSystem.getInstance().refreshAndFindFileByNioFile(uri.toPath())

    companion object {
        fun getInstance(project: Project): HostedNotebooks = project.service()

        private val LOG = Logger.getInstance(HostedNotebooks::class.java)
        private val SERIALIZER_WAIT = 30.seconds
        private val SHOW_WAIT = 10.seconds
        private const val POST_MESSAGE: String = $$"$postMessage"
        /** Registered in the notebook backend's descriptor. */
        private const val NOTIFICATION_GROUP = "CodeBridge notebooks"
    }
}

/** Answers the notebook services for the project whose host calls. */
internal class NotebookHostService : CodeBridgeHostService {
    override val services: Set<String> = Notebooks.SERVICES
    /** The built-in extensions that read notebooks: `ipynb`, which serializes `.ipynb` files. */
    override fun builtinExtensions(directory: Path): List<Path> = NotebookContributions.builtinSerializers(directory)
    override fun hostStarted(project: Project, host: CodeBridgeHostService.Host) = HostedNotebooks.getInstance(project).hostStarted(host)
    override fun hostStopped(project: Project) = HostedNotebooks.getInstance(project).hostStopped()
    override suspend fun answer(project: Project, call: MainThreadCall): MainThreadResult =
        HostedNotebooks.getInstance(project).answer(call)
}
