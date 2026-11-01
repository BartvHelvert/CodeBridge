package me.helvert.codebridge.notebook

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.helvert.codebridge.exthost.protocol.payload.Documents
import me.helvert.codebridge.exthost.protocol.payload.Notebooks
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.util.UUID

/**
 * One open notebook as the workbench keeps it, and what to tell the extension host as it changes.
 *
 * Every change returns the [HostMessage]s that keep the host's copy the same -- the notebook's own
 * events and, for typing, the cell document's -- in the order they are to be sent. Not thread-safe:
 * [HostedNotebooks] only touches a model on its own single thread.
 */
internal class NotebookModel(
    val uri: UriComponents,
    val viewType: String,
    data: Notebooks.Data,
    /** A file extension for a language id, for [CellDto.fileExtension]. */
    private val fileExtensionOf: (String) -> String?,
) {
    class Cell(
        val handle: Int,
        val kind: Int,
        var language: String,
        val mime: String?,
        var text: String,
        var outputs: List<Notebooks.Output>,
        val metadata: JsonObject?,
        var executionSummary: JsonObject?,
        var run: RunDto,
        /** The version of the cell's own text document in the host, which starts at 1. */
        var textVersion: Int = Documents.FIRST_VERSION,
    ) {
        /** While the cell runs, when it started, in ms since the epoch: the host's clock, on this machine. */
        var runStart: Long? = null
    }

    /** A call to make into the host: its JSON, and the buffers of its `SerializableObjectWithBuffers`. */
    data class HostMessage(val service: String, val method: String, val argsJson: String, val objectBuffers: Map<Int, List<ByteArray>> = emptyMap())

    /**
     * An edit [undo] can take back, as VS Code's `SpliceCellsEdit` and `MoveCellEdit` do. A cell's
     * typing is not one: as in VS Code, where `notebook.undoRedoPerCell` is on by default, that is
     * the cell editor's own to undo.
     */
    sealed interface Edit {
        /** The edit that takes this one back. */
        val inverse: Edit

        /** [removed] cells at [index] gave way to [added]; the cells themselves, so their handles and outputs come back. */
        class Splice(val index: Int, val removed: List<Cell>, val added: List<Cell>) : Edit {
            override val inverse: Edit get() = Splice(index, added, removed)
        }

        /** The [length] cells at [from] went to [to], where the first of them is once they are put back in. */
        class Move(val from: Int, val to: Int, val length: Int = 1) : Edit {
            override val inverse: Edit get() = Move(to, from, length)
        }

        /** Cell [handle]'s outputs went from [before] to [after]. */
        class Outputs(val handle: Int, val before: List<Notebooks.Output>, val after: List<Notebooks.Output>) : Edit {
            override val inverse: Edit get() = Outputs(handle, after, before)
        }
    }

    /** What [undo] or [redo] did: [edits], in the order made, and the cells to select for it, top to bottom. */
    class Replayed(val edits: List<Edit>, val messages: List<HostMessage>, val selection: List<Int>)

    var metadata: JsonObject = data.metadata
        private set
    private var nextHandle = 0
    val cells: MutableList<Cell> = data.cells.mapTo(mutableListOf(), ::newCell)
    var version: Int = 0
        private set
    var dirty: Boolean = false
        private set

    /** Each a step of edits the user made together, the last made last. */
    private val undoStack = ArrayDeque<List<Edit>>()
    private val redoStack = ArrayDeque<List<Edit>>()

    fun cell(handle: Int): Cell? = cells.firstOrNull { it.handle == handle }
    fun index(handle: Int): Int = cells.indexOfFirst { it.handle == handle }

    fun dto(cell: Cell): CellDto = CellDto(
        cell.handle, cell.kind, cell.language, fileExtensionOf(cell.language), cell.text, cell.outputs.map(::outputDto), run(cell),
    )

    /** The cell's run as the editors are told it: while it runs, with how long it has run so far. */
    fun run(cell: Cell): RunDto {
        val start = cell.runStart
        if (cell.run.state != RunState.RUNNING || start == null) return cell.run
        return cell.run.copy(millis = (System.currentTimeMillis() - start).coerceAtLeast(0))
    }

    /** The notebook for [Notebooks.documentsDelta]'s `addedDocuments`, its outputs' bytes in [buffers]. */
    fun hostDocument(buffers: Notebooks.Buffers): JsonObject =
        Notebooks.addedDocument(uri, viewType, version, metadata, cells.map { cellDto(it, buffers) })

    // --------------------------------------------------------------------------------------- edits

    /** Cell [handle]'s text is now [text]; null when it already was, or there is no such cell. */
    fun setText(handle: Int, text: String): List<HostMessage>? {
        val cell = cell(handle) ?: return null
        val normalized = Documents.normalize(text)
        if (cell.text == normalized) return null
        val event = Documents.changeEvent(cell.text, normalized, cell.textVersion + 1)
        cell.text = normalized
        cell.textVersion++
        val document = HostMessage(
            Documents.DOCUMENTS, Documents.MODEL_CHANGED,
            "[${Notebooks.cellUri(uri, handle).toJson()},$event,true]",
        )
        return listOf(document) + changed(Notebooks.contentEvent(index(handle)))
    }

    /** A new empty cell of [kind] in [language] at [index], clamped to the cells there are. */
    fun insert(index: Int, kind: Int, language: String): Pair<Cell, List<HostMessage>> {
        val cell = newCell(Notebooks.Cell(kind, "", language))
        return cell to edit(listOf(Edit.Splice(index.coerceIn(0, cells.size), emptyList(), listOf(cell))))
    }

    fun delete(handle: Int): Pair<Int, List<HostMessage>>? {
        val at = index(handle).takeIf { it >= 0 } ?: return null
        return at to edit(listOf(Edit.Splice(at, listOf(cells[at]), emptyList())))
    }

    /**
     * Moves cell [handle] by [delta] places, keeping its handle -- so its outputs and its document's
     * URI -- as VS Code's own move does: where it was and where it went, or null when it stays put.
     */
    fun move(handle: Int, delta: Int): Pair<Pair<Int, Int>, List<HostMessage>>? {
        val from = index(handle).takeIf { it >= 0 } ?: return null
        val to = (from + delta).coerceIn(0, cells.size - 1)
        if (to == from) return null
        return (from to to) to edit(listOf(Edit.Move(from, to)))
    }

    /**
     * Moves cells [handles], next to each other, to the gap before cell [gap] as the cells are now --
     * after the last for the number of cells -- keeping their handles, as VS Code's drag and drop
     * does: the move, or null when they stay put or are not next to each other.
     */
    fun move(handles: List<Int>, gap: Int): Pair<Edit.Move, List<HostMessage>>? {
        val range = range(handles) ?: return null
        val at = gap.coerceIn(0, cells.size)
        if (at in range.first..range.last + 1) return null
        val move = Edit.Move(range.first, if (at > range.last) at - range.count() else at, range.count())
        return move to edit(listOf(move))
    }

    /** Removes the outputs of cell [handle], or of every cell for null, as one step: the cells that had any. */
    fun clearOutputs(handle: Int?): Pair<List<Cell>, List<HostMessage>> {
        val cleared = cells.filter { (handle == null || it.handle == handle) && it.outputs.isNotEmpty() }
        return cleared to edit(cleared.map { Edit.Outputs(it.handle, it.outputs, emptyList()) })
    }

    /** [cell] as a file would keep it: what the clipboard holds of a copied cell. */
    fun snapshot(cell: Cell): Notebooks.Cell =
        Notebooks.Cell(cell.kind, cell.text, cell.language, cell.mime, cell.outputs, cell.metadata, cell.executionSummary)

    /** Where cells [handles] are, when they are next to each other: null when any is missing or they are apart. */
    fun range(handles: List<Int>): IntRange? {
        val indices = handles.distinct().map(::index).sorted()
        if (indices.isEmpty() || indices.first() < 0 || indices.zipWithNext().any { (a, b) -> b != a + 1 }) return null
        return indices.first()..indices.last()
    }

    /**
     * New cells made of [added] in place of the [count] cells at [index], as one step: the new
     * cells. Pasting, joining and changing a cell's kind are each such a step, as in VS Code.
     */
    fun replace(index: Int, count: Int, added: List<Notebooks.Cell>): Pair<List<Cell>, List<HostMessage>> {
        val at = index.coerceIn(0, cells.size)
        val removed = cells.subList(at, (at + count).coerceIn(at, cells.size)).toList()
        val made = added.map(::newCell)
        return made to edit(listOf(Edit.Splice(at, removed, made)))
    }

    /** Copies of [cells] inserted at [index], their outputs given ids of their own: pasted, or Copy Cell Down. */
    fun insertCopies(index: Int, cells: List<Notebooks.Cell>): Pair<List<Cell>, List<HostMessage>> =
        replace(index, 0, cells.map { cell -> cell.copy(outputs = cell.outputs.map { it.copy(id = UUID.randomUUID().toString()) }) })

    /**
     * Cells [handles] joined into one, as VS Code's Join Cells does: the first one's kind, language
     * and outputs, their texts a line apart. Null unless they are more than one, next to each other
     * and of one kind.
     */
    fun join(handles: List<Int>): Pair<Cell, List<HostMessage>>? {
        val range = range(handles)?.takeIf { it.first < it.last } ?: return null
        val joined = cells.slice(range)
        if (joined.any { it.kind != joined[0].kind }) return null
        val (made, messages) = replace(range.first, joined.size, listOf(snapshot(joined[0]).copy(source = joined.joinToString("\n") { it.text })))
        return made.single() to messages
    }

    /**
     * Cell [handle] made a cell of [kind] in [language], its text and metadata kept and its outputs
     * not, as VS Code's Change Cell to Markdown and to Code do. Null when it already is one.
     */
    fun changeKind(handle: Int, kind: Int, language: String): Pair<Cell, List<HostMessage>>? {
        val at = index(handle).takeIf { it >= 0 } ?: return null
        val cell = cells[at]
        if (cell.kind == kind) return null
        val (made, messages) = replace(at, 1, listOf(Notebooks.Cell(kind, cell.text, language, metadata = cell.metadata)))
        return made.single() to messages
    }

    /** Takes back the last step [redo] has not made again; null when there is none. */
    fun undo(): Replayed? {
        val step = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(step)
        return replay(step.reversed().map { it.inverse })
    }

    /** Makes again the last step [undo] took back; null when there is none. */
    fun redo(): Replayed? {
        val step = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(step)
        return replay(step)
    }

    /** Makes [step], a new one the user made, which [undo] can take back. */
    private fun edit(step: List<Edit>): List<HostMessage> {
        if (step.isEmpty()) return emptyList()
        undoStack.addLast(step)
        redoStack.clear()
        return step.flatMap(::apply)
    }

    private fun replay(edits: List<Edit>): Replayed {
        val messages = edits.flatMap(::apply)
        // The cells the last edit left changed, as VS Code selects the cells an undo touched, so cells
        // moved together are selected together again; for cells taken out, the one now where they were.
        val selection = when (val last = edits.last()) {
            is Edit.Splice -> last.added.map { it.handle }.ifEmpty { listOfNotNull(cells.getOrNull(last.index.coerceAtMost(cells.size - 1))?.handle) }
            is Edit.Move -> cells.subList(last.to, last.to + last.length).map { it.handle }
            is Edit.Outputs -> listOf(last.handle)
        }
        return Replayed(edits, messages, selection)
    }

    private fun apply(edit: Edit): List<HostMessage> = when (edit) {
        is Edit.Splice -> {
            repeat(edit.removed.size) { cells.removeAt(edit.index) }
            cells.addAll(edit.index, edit.added)
            // The host makes each added cell's document anew, at its first version.
            edit.added.forEach { it.textVersion = Documents.FIRST_VERSION }
            splice(edit.index, edit.removed.size, edit.added)
        }
        is Edit.Move -> {
            val moved = cells.subList(edit.from, edit.from + edit.length).toList()
            repeat(edit.length) { cells.removeAt(edit.from) }
            cells.addAll(edit.to, moved)
            changed(Notebooks.moveEvent(edit.from, edit.to, edit.length))
        }
        is Edit.Outputs -> setOutputs(edit.handle, edit.after).orEmpty()
    }

    /** Cell [handle]'s outputs become [outputs]. */
    fun setOutputs(handle: Int, outputs: List<Notebooks.Output>): List<HostMessage>? {
        val cell = cell(handle) ?: return null
        if (cell.outputs.isEmpty() && outputs.isEmpty()) return null
        cell.outputs = outputs
        val buffers = Notebooks.Buffers()
        return changed(Notebooks.outputEvent(index(handle), Notebooks.outputsJson(outputs, buffers)), buffers)
    }

    /** Output [outputId]'s items become [items], or have them added with [append]; the cell it is in, or null. */
    fun setOutputItems(outputId: String, items: List<Notebooks.Item>, append: Boolean): Pair<Cell, List<HostMessage>>? {
        val cell = cells.firstOrNull { cell -> cell.outputs.any { it.id == outputId } } ?: return null
        val outputs = cell.outputs.map { output ->
            if (output.id != outputId) output else output.copy(items = if (append) appended(output.items, items) else items)
        }
        return cell to setOutputs(cell.handle, outputs).orEmpty()
    }

    /**
     * [added] after [items], streams joined: an item of a mime the output already has is written
     * onto it, as VS Code's `NotebookCellOutputItem` joins stdout and stderr chunks.
     */
    private fun appended(items: List<Notebooks.Item>, added: List<Notebooks.Item>): List<Notebooks.Item> {
        val result = items.toMutableList()
        for (item in added) {
            val existing = result.indexOfFirst { it.mime == item.mime }
            if (existing >= 0 && item.mime in STREAMS) result[existing] = Notebooks.Item(item.mime, result[existing].data + item.data)
            else result.add(item)
        }
        return result
    }

    /** Cell [handle]'s last run, as the kernel reported it. */
    fun setExecutionSummary(handle: Int, summary: JsonObject): List<HostMessage>? {
        val cell = cell(handle) ?: return null
        cell.executionSummary = summary
        return changed(Notebooks.executionSummaryEvent(index(handle), summary))
    }

    /** What [reload] changed: [removed] cells at [index] gave way to [added]. */
    class Reloaded(val index: Int, val removed: Int, val added: List<Cell>, val messages: List<HostMessage>)

    /**
     * The notebook becomes [data], read again from its file, as VS Code reverts a notebook: no longer
     * dirty, and nothing left to undo. The cells the file still has at its start and end are kept, with
     * their handles, so their editors stay as they are; the ones between are made anew.
     */
    fun reload(data: Notebooks.Data): Reloaded {
        val old = cells.map(::snapshot)
        val new = data.cells.map { it.copy(source = Documents.normalize(it.source)) }
        var prefix = 0
        while (prefix < old.size && prefix < new.size && same(old[prefix], new[prefix])) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && same(old[old.size - 1 - suffix], new[new.size - 1 - suffix])) suffix++
        val removed = old.size - prefix - suffix
        val added = new.subList(prefix, new.size - suffix).map(::newCell)
        repeat(removed) { cells.removeAt(prefix) }
        cells.addAll(prefix, added)
        undoStack.clear()
        redoStack.clear()
        val metadataChanged = data.metadata != metadata
        metadata = data.metadata
        val wasDirty = dirty
        dirty = false
        val messages = mutableListOf<HostMessage>()
        if (removed > 0 || added.isNotEmpty() || metadataChanged) {
            version++
            val buffers = Notebooks.Buffers()
            val events = if (removed > 0 || added.isNotEmpty()) listOf(Notebooks.spliceEvent(prefix, removed, added.map { cellDto(it, buffers) })) else emptyList()
            messages += HostMessage(
                Notebooks.EXT_HOST_DOCUMENTS, Notebooks.MODEL_CHANGED,
                Notebooks.modelChanged(uri, version, events, dirty = false, metadata = metadata.takeIf { metadataChanged }),
                mapOf(Notebooks.MODEL_EVENT to buffers.all),
            )
        }
        if (wasDirty) messages += HostMessage(Notebooks.EXT_HOST_DOCUMENTS, Notebooks.DIRTY_CHANGED, Notebooks.dirtyChanged(uri, false))
        return Reloaded(prefix, removed, added, messages)
    }

    /** Whether two cells are the same but for their outputs' ids, which a serializer makes anew on each read. */
    private fun same(a: Notebooks.Cell, b: Notebooks.Cell): Boolean =
        a.copy(outputs = emptyList()) == b.copy(outputs = emptyList()) && a.outputs.size == b.outputs.size &&
            a.outputs.zip(b.outputs).all { (x, y) -> x.items == y.items && x.metadata == y.metadata }

    /** The notebook was written to its file. */
    fun saved(): List<HostMessage> {
        dirty = false
        return listOf(
            HostMessage(Notebooks.EXT_HOST_DOCUMENTS, Notebooks.MODEL_SAVED, Notebooks.modelSaved(uri)),
            HostMessage(Notebooks.EXT_HOST_DOCUMENTS, Notebooks.DIRTY_CHANGED, Notebooks.dirtyChanged(uri, false)),
        )
    }

    /**
     * The notebook as its serializer is to write it: what the serializer says it does not keep --
     * outputs, metadata keys -- left out, as VS Code's own save does.
     */
    fun data(serializer: Notebooks.Serializer): Notebooks.Data {
        val transientCell = keys(serializer.options["transientCellMetadata"])
        val transientDocument = keys(serializer.options["transientDocumentMetadata"])
        return Notebooks.Data(
            JsonObject(metadata - transientDocument),
            cells.map { cell ->
                Notebooks.Cell(
                    cell.kind, cell.text, cell.language, cell.mime,
                    if (serializer.transientOutputs) emptyList() else cell.outputs,
                    cell.metadata?.let { JsonObject(it - transientCell) },
                    cell.executionSummary,
                )
            },
        )
    }

    /** The keys a `TransientCellMetadata` / `TransientDocumentMetadata` map marks as not kept. */
    private fun keys(element: JsonElement?): Set<String> =
        (element as? JsonObject)?.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }?.keys.orEmpty()

    // ------------------------------------------------------------------------------------- helpers

    private fun newCell(cell: Notebooks.Cell): Cell {
        val summary = cell.executionSummary
        fun long(key: String) = (summary?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
        val start = long("runStartTime")
        val end = long("runEndTime")
        val run = RunDto(
            RunState.IDLE,
            long("executionOrder")?.toInt(),
            (summary?.get("lastRunSuccess") as? JsonPrimitive)?.booleanOrNull,
            if (start != null && end != null) end - start else null,
        )
        return Cell(nextHandle++, cell.kind, cell.language, cell.mime, Documents.normalize(cell.source), cell.outputs, cell.metadata, summary, run)
    }

    private fun cellDto(cell: Cell, buffers: Notebooks.Buffers): JsonObject =
        Notebooks.cellDto(uri, cell.handle, Notebooks.Cell(cell.kind, cell.text, cell.language, cell.mime, cell.outputs, cell.metadata, cell.executionSummary), buffers)

    private fun splice(start: Int, deleted: Int, added: List<Cell>): List<HostMessage> {
        val buffers = Notebooks.Buffers()
        return changed(Notebooks.spliceEvent(start, deleted, added.map { cellDto(it, buffers) }), buffers)
    }

    /** The notebook changed by [event]: a new version, dirty, and the host told so. */
    private fun changed(event: JsonObject, buffers: Notebooks.Buffers = Notebooks.Buffers()): List<HostMessage> {
        version++
        dirty = true
        return listOf(
            HostMessage(
                Notebooks.EXT_HOST_DOCUMENTS, Notebooks.MODEL_CHANGED,
                Notebooks.modelChanged(uri, version, listOf(event), dirty = true),
                mapOf(Notebooks.MODEL_EVENT to buffers.all),
            )
        )
    }

    companion object {
        /** The mimes VS Code joins as one stream of text. */
        val STREAMS: Set<String> = setOf("application/vnd.code.notebook.stdout", "application/vnd.code.notebook.stderr")

        fun outputDto(output: Notebooks.Output): OutputDto = OutputDto(output.id, output.items.map { OutputItemDto(it.mime, it.data) })
    }
}
