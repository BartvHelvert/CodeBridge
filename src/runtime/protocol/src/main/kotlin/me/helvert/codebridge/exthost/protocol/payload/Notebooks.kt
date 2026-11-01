package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import java.util.Base64

/**
 * The notebook services: `MainThreadNotebook`, `MainThreadNotebookDocumentsShape`,
 * `MainThreadNotebookEditorsShape` and `MainThreadNotebookKernels` on this side; `ExtHostNotebook`,
 * `ExtHostNotebookDocuments`, `ExtHostNotebookEditors` and `ExtHostNotebookKernels` on the host's.
 *
 * The workbench owns the notebook. An extension's serializer turns bytes into [Data] and back
 * ([DATA_TO_NOTEBOOK], [NOTEBOOK_TO_DATA]); the host only mirrors what the workbench tells it is
 * open ([documentsDelta]) and how it changed ([modelChanged]), and makes a text document of each
 * cell itself, under a [cellUri]. A cell's text then changes as any text document's does, through
 * `ExtHostDocuments.$acceptModelChanged`, with a [CONTENT] event here beside it.
 *
 * Running cells is the kernels' half. An extension's controller is a kernel ([ADD_KERNEL]); the
 * workbench picks one for a notebook ([ACCEPT_ASSOCIATION]) and asks it to run cells
 * ([EXECUTE_CELLS]), and the controller reports back through executions it makes, one per cell
 * ([CREATE_EXECUTION], [UPDATE_EXECUTION], [COMPLETE_EXECUTION]). Outputs are the workbench's to
 * apply, and then to tell the host about as [OUTPUT] events.
 *
 * Every output's bytes are a `VSBuffer`, so the values that carry outputs travel as a
 * `SerializableObjectWithBuffers`: the JSON names each buffer by a `{"$$ref$$": n}`, and the buffers
 * go beside it. [Buffers] reads and writes that.
 *
 * Read off the pinned runtime: `ExtHostNotebookController`, `ExtHostNotebookDocument`,
 * `ExtHostNotebookKernels`, `NotebookCellExecutionTask` and the `NotebookData`, `NotebookCellData`,
 * `NotebookCellOutput` and `NotebookCellOutputItem` converters in `extHostTypeConverters`.
 */
object Notebooks {

    const val SERVICE: String = "MainThreadNotebook"
    const val DOCUMENTS_SERVICE: String = "MainThreadNotebookDocumentsShape"
    const val EDITORS_SERVICE: String = "MainThreadNotebookEditorsShape"
    const val KERNELS_SERVICE: String = "MainThreadNotebookKernels"
    const val RENDERERS_SERVICE: String = "MainThreadNotebookRenderers"

    const val EXT_HOST: String = "ExtHostNotebook"
    const val EXT_HOST_DOCUMENTS: String = "ExtHostNotebookDocuments"
    const val EXT_HOST_KERNELS: String = "ExtHostNotebookKernels"

    /** Every main-thread service a notebook is answered through. */
    val SERVICES: Set<String> = setOf(SERVICE, DOCUMENTS_SERVICE, EDITORS_SERVICE, KERNELS_SERVICE, RENDERERS_SERVICE)

    // MainThreadNotebook
    const val REGISTER_SERIALIZER: String = $$"$registerNotebookSerializer"
    const val UNREGISTER_SERIALIZER: String = $$"$unregisterNotebookSerializer"

    // MainThreadNotebookDocumentsShape
    const val TRY_CREATE: String = $$"$tryCreateNotebook"
    const val TRY_OPEN: String = $$"$tryOpenNotebook"
    const val TRY_SAVE: String = $$"$trySaveNotebook"

    // MainThreadNotebookEditorsShape
    const val TRY_SHOW: String = $$"$tryShowNotebookDocument"

    // MainThreadNotebookKernels
    const val ADD_KERNEL: String = $$"$addKernel"
    const val UPDATE_KERNEL: String = $$"$updateKernel"
    const val REMOVE_KERNEL: String = $$"$removeKernel"
    const val UPDATE_PRIORITY: String = $$"$updateNotebookPriority"
    const val ADD_DETECTION_TASK: String = $$"$addKernelDetectionTask"
    const val REMOVE_DETECTION_TASK: String = $$"$removeKernelDetectionTask"
    const val CREATE_EXECUTION: String = $$"$createExecution"
    const val UPDATE_EXECUTION: String = $$"$updateExecution"
    const val COMPLETE_EXECUTION: String = $$"$completeExecution"

    // ExtHostNotebook
    const val DATA_TO_NOTEBOOK: String = $$"$dataToNotebook"
    const val NOTEBOOK_TO_DATA: String = $$"$notebookToData"
    const val DOCUMENTS_DELTA: String = $$"$acceptDocumentAndEditorsDelta"

    // ExtHostNotebookDocuments
    const val MODEL_CHANGED: String = $$"$acceptModelChanged"
    const val DIRTY_CHANGED: String = $$"$acceptDirtyStateChanged"
    const val MODEL_SAVED: String = $$"$acceptModelSaved"

    // ExtHostNotebookKernels
    const val ACCEPT_ASSOCIATION: String = $$"$acceptNotebookAssociation"
    const val EXECUTE_CELLS: String = $$"$executeCells"
    const val CANCEL_CELLS: String = $$"$cancelCells"

    /** `CellKind`. */
    const val MARKUP: Int = 1
    const val CODE: Int = 2

    /** `NotebookCellsChangeType`: the kinds of [modelChanged]'s raw events this side sends. */
    const val MODEL_CHANGE: Int = 1
    const val MOVE: Int = 2
    const val OUTPUT: Int = 8
    const val CHANGE_INTERNAL_METADATA: Int = 12
    const val CONTENT: Int = 10

    /** `CellExecutionUpdateType`: what one of [UPDATE_EXECUTION]'s edits does. */
    const val EXECUTION_OUTPUT: Int = 1
    const val EXECUTION_OUTPUT_ITEMS: Int = 2
    const val EXECUTION_STATE: Int = 3

    /** The scheme of a cell's text document. */
    const val CELL_SCHEME: String = "vscode-notebook-cell"

    /** What the host calls an output's bytes inside a `SerializableObjectWithBuffers`. */
    private const val BUFFER_REF: String = $$$"$$ref$$"

    /** One `NotebookCellOutputItem`: [data] in the format [mime] names. */
    class Item(val mime: String, val data: ByteArray) {
        override fun equals(other: Any?) = other is Item && mime == other.mime && data.contentEquals(other.data)
        override fun hashCode() = 31 * mime.hashCode() + data.contentHashCode()
        override fun toString() = "Item($mime, ${data.size} bytes)"
    }

    /** One `NotebookCellOutput`. */
    data class Output(val id: String, val items: List<Item>, val metadata: JsonObject? = null)

    /**
     * One cell: `NotebookCellDataDto`. [metadata] and [executionSummary] -- the host's
     * `internalMetadata`: `executionOrder`, `lastRunSuccess`, `runStartTime`, `runEndTime` -- are
     * kept as JSON, since only the serializer that wrote them reads them back.
     */
    data class Cell(
        val kind: Int,
        val source: String,
        val language: String,
        val mime: String? = null,
        val outputs: List<Output> = emptyList(),
        val metadata: JsonObject? = null,
        val executionSummary: JsonObject? = null,
    )

    /** `NotebookDataDto`: what a serializer reads a file into, and writes back from. */
    data class Data(val metadata: JsonObject, val cells: List<Cell>)

    /**
     * The buffers of one `SerializableObjectWithBuffers`: [ref] puts one in and names it, [read]
     * takes one back out of a reference.
     */
    class Buffers(initial: List<ByteArray> = emptyList()) {
        private val list = initial.toMutableList()
        val all: List<ByteArray> get() = list

        fun ref(bytes: ByteArray): JsonObject {
            list.add(bytes)
            return buildJsonObject { put(BUFFER_REF, list.size - 1) }
        }

        /** The bytes [reference] names; none for a reference to nothing, as an empty output item is. */
        fun read(reference: JsonElement?): ByteArray {
            val index = ((reference as? JsonObject)?.get(BUFFER_REF) as? JsonPrimitive)?.intOrNull
            return index?.let(list::getOrNull) ?: ByteArray(0)
        }
    }

    /**
     * The value of a `SerializableObjectWithBuffers` argument at [index]: bare when the call carried
     * its buffers beside it, as `{"value": ...}` when it had none and went as plain JSON.
     */
    fun objectArgument(args: Args, index: Int, objectBuffers: Map<Int, List<ByteArray>>): Pair<JsonElement?, Buffers> {
        val buffers = objectBuffers[index]
        val element = args.element(index)
        return if (buffers != null) element to Buffers(buffers)
        else ((element as? JsonObject)?.get("value") ?: element) to Buffers()
    }

    // ---------------------------------------------------------------------------------------- data

    fun data(json: JsonElement?, buffers: Buffers): Data {
        // A reply without buffers may come as the object itself, wrapped the way a plain argument is.
        val obj = (json as? JsonObject)?.let { if ("cells" !in it && it["value"] is JsonObject) it["value"] as JsonObject else it }
            ?: throw PayloadException("A notebook's data is not an object: $json")
        return Data(
            obj["metadata"] as? JsonObject ?: JsonObject(emptyMap()),
            (obj["cells"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { cell(it, buffers) },
        )
    }

    private fun cell(json: JsonObject, buffers: Buffers) = Cell(
        kind = (json["cellKind"] as? JsonPrimitive)?.intOrNull ?: CODE,
        // A dto's source is one string; a cell already open is sent as its lines instead.
        source = when (val source = json["source"]) {
            is JsonArray -> source.joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            else -> (source as? JsonPrimitive)?.contentOrNull.orEmpty()
        },
        language = (json["language"] as? JsonPrimitive)?.contentOrNull ?: "plaintext",
        mime = (json["mime"] as? JsonPrimitive)?.contentOrNull,
        outputs = outputs(json["outputs"], buffers),
        metadata = json["metadata"] as? JsonObject,
        executionSummary = json["internalMetadata"] as? JsonObject,
    )

    fun outputs(json: JsonElement?, buffers: Buffers): List<Output> =
        (json as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { output ->
            Output(
                (output["outputId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                items(output["items"], buffers),
                output["metadata"] as? JsonObject,
            )
        }

    fun items(json: JsonElement?, buffers: Buffers): List<Item> =
        (json as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { item ->
            Item((item["mime"] as? JsonPrimitive)?.contentOrNull.orEmpty(), buffers.read(item["valueBytes"]))
        }

    /** [data] as `NotebookDataDto`, for [NOTEBOOK_TO_DATA]; its outputs' bytes go into [buffers]. */
    fun dataJson(data: Data, buffers: Buffers): JsonObject = buildJsonObject {
        put("metadata", data.metadata)
        putJsonArray("cells") {
            data.cells.forEach { cell ->
                add(buildJsonObject {
                    put("cellKind", cell.kind)
                    put("language", cell.language)
                    cell.mime?.let { put("mime", it) }
                    put("source", cell.source)
                    put("metadata", cell.metadata ?: JsonObject(emptyMap()))
                    put("internalMetadata", cell.executionSummary ?: JsonObject(emptyMap()))
                    put("outputs", outputsJson(cell.outputs, buffers))
                })
            }
        }
    }

    fun outputsJson(outputs: List<Output>, buffers: Buffers): JsonArray = buildJsonArray {
        outputs.forEach { output ->
            add(buildJsonObject {
                put("outputId", output.id)
                put("items", itemsJson(output.items, buffers))
                output.metadata?.let { put("metadata", it) }
            })
        }
    }

    private fun itemsJson(items: List<Item>, buffers: Buffers): JsonArray = buildJsonArray {
        items.forEach { item -> add(buildJsonObject { put("mime", item.mime); put("valueBytes", buffers.ref(item.data)) }) }
    }

    // ---------------------------------------------------------------------------------- serializers

    /**
     * `$registerNotebookSerializer(handle, {id, location}, viewType, options, registration?)`.
     * [options] is `TransientOptions`: which outputs and metadata the serializer does not keep.
     */
    data class Serializer(val handle: Int, val extensionId: String, val viewType: String, val options: JsonObject) {
        /** `transientOutputs`: whether saving leaves the outputs out. */
        val transientOutputs: Boolean get() = (options["transientOutputs"] as? JsonPrimitive)?.booleanOrNull ?: false
    }

    fun serializer(argsJson: String): Serializer {
        val args = Args.of(REGISTER_SERIALIZER, argsJson)
        val extension = args.objOrNull(1)?.get("id")
        // An `ExtensionIdentifier`, which goes as `{value, _lower}`.
        val id = (extension as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
            ?: (extension as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Serializer(args.int(0), id, args.str(2), args.objOrNull(3) ?: JsonObject(emptyMap()))
    }

    /** `$dataToNotebook(handle, bytes, token)`'s JSON; the bytes go as the buffer at [DATA_BYTES]. */
    fun dataToNotebook(handle: Int): String = buildJsonArray { add(handle); add(JsonNull) }.toString()

    /** The position of [dataToNotebook]'s bytes among its arguments. */
    const val DATA_BYTES: Int = 1

    /**
     * `$notebookToData(handle, data, token)`'s JSON, its data's buffers at [NOTEBOOK_DATA]; the host
     * answers with the file's bytes.
     */
    fun notebookToData(handle: Int, data: JsonObject): String = buildJsonArray { add(handle); add(data) }.toString()

    /** The position of [notebookToData]'s `SerializableObjectWithBuffers`. */
    const val NOTEBOOK_DATA: Int = 1

    // ------------------------------------------------------------------------------ documents, editors

    /**
     * The URI of cell [handle] of [notebook]: VS Code's `CellUri.generate`, which extensions parse
     * back to find a cell's notebook -- the handle in base 7 behind a letter giving its length, then
     * the notebook's scheme in base64.
     */
    fun cellUri(notebook: UriComponents, handle: Int): UriComponents {
        val digits = handle.toString(7)
        val prefix = if (digits.length < LENGTHS.length) LENGTHS[digits.length - 1] else 'z'
        val scheme = Base64.getUrlEncoder().encodeToString(notebook.scheme.toByteArray(Charsets.UTF_8))
        return notebook.copy(scheme = CELL_SCHEME, fragment = "$prefix${digits}s$scheme")
    }

    private const val LENGTHS = "WXYZabcdef"

    /** A cell as the host is first told of it: `NotebookCellDto`, its text as lines. */
    fun cellDto(notebook: UriComponents, handle: Int, cell: Cell, buffers: Buffers): JsonObject = buildJsonObject {
        put("handle", handle)
        put("uri", cellUri(notebook, handle).toJson())
        putJsonArray("source") { cell.source.split('\n').forEach { add(it) } }
        put("eol", Documents.EOL)
        put("language", cell.language)
        cell.mime?.let { put("mime", it) }
        put("cellKind", cell.kind)
        put("outputs", outputsJson(cell.outputs, buffers))
        put("metadata", cell.metadata ?: JsonObject(emptyMap()))
        put("internalMetadata", cell.executionSummary ?: JsonObject(emptyMap()))
    }

    /** An open notebook in a [documentsDelta]: `INotebookModelAddedData`. */
    fun addedDocument(uri: UriComponents, viewType: String, version: Int, metadata: JsonObject, cells: List<JsonObject>): JsonObject =
        buildJsonObject {
            put("uri", uri.toJson())
            put("viewType", viewType)
            put("versionId", version)
            put("metadata", metadata)
            put("cells", JsonArray(cells))
        }

    /** An editor showing [uri] in a [documentsDelta]: `INotebookEditorAddData`, the first cell selected. */
    fun addedEditor(id: String, uri: UriComponents, viewType: String, cellCount: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("documentUri", uri.toJson())
        putJsonArray("selections") { add(firstCells(minOf(1, cellCount))) }
        putJsonArray("visibleRanges") { add(firstCells(cellCount)) }
        put("viewType", viewType)
    }

    /** The `ICellRange` of the first [count] cells. */
    private fun firstCells(count: Int) = buildJsonObject { put("start", 0); put("end", count) }

    /**
     * `$acceptDocumentAndEditorsDelta(delta)`'s value, a `SerializableObjectWithBuffers`:
     * notebooks opened and closed, editors shown and hidden, which are visible and which is active.
     * [active] is the editor that now has focus; [deactivated] says none does any more.
     */
    fun documentsDelta(
        added: List<JsonObject> = emptyList(),
        removed: List<UriComponents> = emptyList(),
        addedEditors: List<JsonObject> = emptyList(),
        removedEditors: List<String> = emptyList(),
        visible: List<String>? = null,
        active: String? = null,
        deactivated: Boolean = false,
    ): JsonObject = buildJsonObject {
        if (added.isNotEmpty()) put("addedDocuments", JsonArray(added))
        if (removed.isNotEmpty()) put("removedDocuments", JsonArray(removed.map { it.toJson() }))
        if (addedEditors.isNotEmpty()) put("addedEditors", JsonArray(addedEditors))
        if (removedEditors.isNotEmpty()) putJsonArray("removedEditors") { removedEditors.forEach { add(it) } }
        visible?.let { ids -> putJsonArray("visibleEditors") { ids.forEach { add(it) } } }
        if (active != null) put("newActiveEditor", active) else if (deactivated) put("newActiveEditor", JsonNull)
    }

    /**
     * `$acceptModelChanged(uri, event, isDirty, metadata?)`'s JSON, with the notebook's new [metadata]
     * when that changed too; the event's buffers at [MODEL_EVENT].
     */
    fun modelChanged(uri: UriComponents, version: Int, events: List<JsonObject>, dirty: Boolean, metadata: JsonObject? = null): String = buildJsonArray {
        add(uri.toJson())
        add(buildJsonObject { put("versionId", version); put("rawEvents", JsonArray(events)) })
        add(dirty)
        metadata?.let { add(it) }
    }.toString()

    /** The position of [modelChanged]'s event. */
    const val MODEL_EVENT: Int = 1

    /** Cells [deleted] at [start] gave way to [cells], each a [cellDto]. */
    fun spliceEvent(start: Int, deleted: Int, cells: List<JsonObject>): JsonObject = buildJsonObject {
        put("kind", MODEL_CHANGE)
        putJsonArray("changes") { add(buildJsonArray { add(start); add(deleted); add(JsonArray(cells)) }) }
    }

    /**
     * The [length] cells at [index] moved to [newIndex], keeping their handles: taken out, then put
     * back in at [newIndex] of the cells left, as the host's `_moveCells` does.
     */
    fun moveEvent(index: Int, newIndex: Int, length: Int = 1): JsonObject = buildJsonObject {
        put("kind", MOVE); put("index", index); put("length", length); put("newIdx", newIndex)
    }

    /** Cell [index]'s outputs are now [outputs], each from [outputsJson]. */
    fun outputEvent(index: Int, outputs: JsonArray): JsonObject = buildJsonObject {
        put("kind", OUTPUT); put("index", index); put("outputs", outputs); put("append", false)
    }

    /** Cell [index]'s text changed; the text itself went to its document. */
    fun contentEvent(index: Int): JsonObject = buildJsonObject { put("kind", CONTENT); put("index", index) }

    fun executionSummaryEvent(index: Int, summary: JsonObject): JsonObject =
        buildJsonObject { put("kind", CHANGE_INTERNAL_METADATA); put("index", index); put("internalMetadata", summary) }

    fun dirtyChanged(uri: UriComponents, dirty: Boolean): String = buildJsonArray { add(uri.toJson()); add(dirty) }.toString()

    fun modelSaved(uri: UriComponents): String = buildJsonArray { add(uri.toJson()) }.toString()

    // ---------------------------------------------------------------------------------------- kernels

    /**
     * `INotebookKernelDto2`: a controller an extension made with `createNotebookController`. [id]
     * is the extension's id and the controller's, joined by `/`.
     */
    data class Kernel(
        val handle: Int,
        val id: String,
        val notebookType: String,
        val extensionId: String,
        val label: String,
        val description: String?,
        val detail: String?,
        val supportedLanguages: List<String>,
        val supportsExecutionOrder: Boolean,
        val supportsInterrupt: Boolean,
    )

    /** `$addKernel(handle, data)` and `$updateKernel(handle, data)`, which repeats the whole of it. */
    fun kernel(method: String, argsJson: String): Kernel {
        val args = Args.of(method, argsJson)
        val data = args.obj(1)
        fun str(key: String) = (data[key] as? JsonPrimitive)?.contentOrNull
        val extension = data["extensionId"]
        return Kernel(
            handle = args.int(0),
            id = str("id").orEmpty(),
            notebookType = str("notebookType").orEmpty(),
            extensionId = (extension as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
                ?: (extension as? JsonPrimitive)?.contentOrNull.orEmpty(),
            label = str("label").orEmpty(),
            description = str("description")?.ifEmpty { null },
            detail = str("detail")?.ifEmpty { null },
            supportedLanguages = (data["supportedLanguages"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            supportsExecutionOrder = (data["supportsExecutionOrder"] as? JsonPrimitive)?.booleanOrNull ?: false,
            supportsInterrupt = (data["supportsInterrupt"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /** `$updateNotebookPriority(handle, uri, priority)`: `NotebookControllerAffinity`, 2 for preferred. */
    data class Priority(val handle: Int, val notebook: UriComponents, val priority: Int?)

    fun priority(argsJson: String): Priority {
        val args = Args.of(UPDATE_PRIORITY, argsJson)
        return Priority(args.int(0), args.uri(1), args.intOrNull(2))
    }

    /** `NotebookControllerAffinity.Preferred`. */
    const val PREFERRED: Int = 2

    fun association(kernel: Int, notebook: UriComponents, selected: Boolean): String =
        buildJsonArray { add(kernel); add(notebook.toJson()); add(selected) }.toString()

    /** `$executeCells(handle, uri, cellHandles)`, and `$cancelCells` with the same arguments. */
    fun cells(kernel: Int, notebook: UriComponents, cells: List<Int>): String =
        buildJsonArray { add(kernel); add(notebook.toJson()); add(JsonArray(cells.map(::JsonPrimitive))) }.toString()

    /** `$createExecution(handle, controllerId, uri, cellHandle)`: a cell's run begins. */
    data class Execution(val handle: Int, val controllerId: String, val notebook: UriComponents, val cell: Int)

    fun execution(argsJson: String): Execution {
        val args = Args.of(CREATE_EXECUTION, argsJson)
        return Execution(args.int(0), args.str(1), args.uri(2), args.int(3))
    }

    /** One edit of [UPDATE_EXECUTION]. */
    sealed interface ExecutionEdit {
        /** The outputs of cell [cell] become [outputs], or are added to with [append]. */
        data class Outputs(val cell: Int, val outputs: List<Output>, val append: Boolean) : ExecutionEdit

        /** The items of output [outputId] become [items], or are added to with [append]. */
        data class Items(val outputId: String, val items: List<Item>, val append: Boolean) : ExecutionEdit

        /** The run started at [runStartTime], or the cell is the [executionOrder]th run. */
        data class State(val runStartTime: Long?, val executionOrder: Int?) : ExecutionEdit
    }

    /** `$updateExecution(handle, edits)`: [ExecutionEdit]s, whose outputs' buffers came with the call. */
    fun executionUpdate(argsJson: String, objectBuffers: Map<Int, List<ByteArray>>): Pair<Int, List<ExecutionEdit>> {
        val args = Args.of(UPDATE_EXECUTION, argsJson)
        val (value, buffers) = objectArgument(args, 1, objectBuffers)
        val edits = (value as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { edit ->
            fun bool(key: String) = (edit[key] as? JsonPrimitive)?.booleanOrNull ?: false
            when ((edit["editType"] as? JsonPrimitive)?.intOrNull) {
                EXECUTION_OUTPUT -> ExecutionEdit.Outputs(
                    (edit["cellHandle"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                    outputs(edit["outputs"], buffers), bool("append"),
                )
                EXECUTION_OUTPUT_ITEMS -> ExecutionEdit.Items(
                    (edit["outputId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    items(edit["items"], buffers), bool("append"),
                )
                EXECUTION_STATE -> ExecutionEdit.State(
                    (edit["runStartTime"] as? JsonPrimitive)?.longOrNull,
                    (edit["executionOrder"] as? JsonPrimitive)?.intOrNull,
                )
                else -> null
            }
        }
        return args.int(0) to edits
    }

    /** `$completeExecution(handle, {runEndTime, lastRunSuccess, error})`. */
    data class Completion(val handle: Int, val runEndTime: Long?, val success: Boolean?, val error: String?)

    fun completion(argsJson: String, objectBuffers: Map<Int, List<ByteArray>>): Completion {
        val args = Args.of(COMPLETE_EXECUTION, argsJson)
        val value = objectArgument(args, 1, objectBuffers).first as? JsonObject ?: JsonObject(emptyMap())
        val error = value["error"] as? JsonObject
        return Completion(
            args.int(0),
            (value["runEndTime"] as? JsonPrimitive)?.longOrNull,
            (value["lastRunSuccess"] as? JsonPrimitive)?.booleanOrNull,
            error?.let { listOfNotNull(it["name"], it["message"]).mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull }.joinToString(": ") },
        )
    }

    /** A cell's execution summary, as `internalMetadata` spells it. */
    fun executionSummary(executionOrder: Int?, success: Boolean?, start: Long?, end: Long?): JsonObject = buildJsonObject {
        executionOrder?.let { put("executionOrder", it) }
        success?.let { put("lastRunSuccess", it) }
        start?.let { put("runStartTime", it) }
        end?.let { put("runEndTime", it) }
    }
}
