package me.helvert.codebridge.notebook

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Notebooks, kept on the backend and drawn by the frontend.
 *
 * A notebook is open for as long as some editor collects its [open] flow: the backend reads the file
 * through the extension's serializer, tells the extension host it is open, and streams what becomes
 * of it. Every other call names the editor by the id it opened with, so two editors of one file each
 * hear the other's edits.
 */
@Rpc
interface NotebookApi : RemoteApi<Unit> {
    /**
     * The notebook types enabled extensions contribute (`contributes.notebooks`), built-in ones too;
     * each emission replaces the last.
     */
    suspend fun notebookTypes(projectId: ProjectId): Flow<List<NotebookTypeDto>>

    /**
     * Opens [file] as a notebook of [viewType] in editor [editorId]. The first event is
     * [NotebookEventDto.Loaded], or [NotebookEventDto.Failed] if it cannot be read; the flow then
     * lasts until the editor stops collecting it, which closes the notebook.
     */
    suspend fun open(projectId: ProjectId, file: VirtualFileId, viewType: String, editorId: String): Flow<NotebookEventDto>

    /** Cell [handle]'s text is now [text], as typed in editor [editorId]. */
    suspend fun setText(projectId: ProjectId, editorId: String, handle: Int, text: String)

    /** Inserts a new empty cell of [kind] at [index]; [NotebookEventDto.Spliced] says what it is. */
    suspend fun insertCell(projectId: ProjectId, editorId: String, index: Int, kind: Int)

    /** Removes cell [handle]. */
    suspend fun deleteCell(projectId: ProjectId, editorId: String, handle: Int)

    /** Moves cell [handle] by [delta] places, up for a negative one. */
    suspend fun moveCell(projectId: ProjectId, editorId: String, handle: Int, delta: Int)

    /**
     * Moves cells [handles], next to each other, to the gap before the cell at [gap] -- after the
     * last for the number of cells -- as a dragged cell is dropped.
     */
    suspend fun moveCells(projectId: ProjectId, editorId: String, handles: List<Int>, gap: Int)

    /** Removes the outputs of cell [handle], or of every cell for null. */
    suspend fun clearOutputs(projectId: ProjectId, editorId: String, handle: Int?)

    /**
     * Puts copies of cells [handles] on the notebook clipboard, which every notebook of the project
     * pastes from; with [cut], removes them too, when they are next to each other.
     */
    suspend fun copyCells(projectId: ProjectId, editorId: String, handles: List<Int>, cut: Boolean)

    /** Inserts copies of the clipboard's cells at [index]: their handles, none when it is empty. */
    suspend fun pasteCells(projectId: ProjectId, editorId: String, index: Int): List<Int>

    /** Inserts copies of cells [handles], next to each other, after the last of them -- Copy Cell Down: their handles. */
    suspend fun duplicateCells(projectId: ProjectId, editorId: String, handles: List<Int>): List<Int>

    /** Inserts copies of cells [handles], next to each other, at [index], as cells dropped with Ctrl do: their handles. */
    suspend fun copyCellsTo(projectId: ProjectId, editorId: String, handles: List<Int>, index: Int): List<Int>

    /**
     * Joins cells [handles], next to each other and of one kind, into one with their texts a line
     * apart: its handle, or null when they cannot be joined.
     */
    suspend fun joinCells(projectId: ProjectId, editorId: String, handles: List<Int>): Int?

    /** Makes cell [handle] a cell of [kind], its text kept: the new cell's handle, null when it already was one. */
    suspend fun changeCellKind(projectId: ProjectId, editorId: String, handle: Int, kind: Int): Int?

    /**
     * Takes back the notebook's last insert, delete, move, paste, join or output clearing, made in any of its
     * editors; cell text is each cell editor's own to undo. The handles of the cells to select for
     * it, top to bottom: empty when there was nothing to take back or no cell is left.
     */
    suspend fun undo(projectId: ProjectId, editorId: String): List<Int>

    /** Makes again what [undo] last took back, with the handles of the cells to select for it. */
    suspend fun redo(projectId: ProjectId, editorId: String): List<Int>

    /**
     * Runs cells [handles] with the selected kernel, asking for one first when none is: false when
     * there is none to ask.
     */
    suspend fun run(projectId: ProjectId, editorId: String, handles: List<Int>): Boolean

    /** Stops cells [handles], or the kernel's whole run where it can interrupt. */
    suspend fun interrupt(projectId: ProjectId, editorId: String, handles: List<Int>)

    /** Selects kernel [kernelId] for the notebook; [NotebookEventDto.Kernels] says it took. */
    suspend fun selectKernel(projectId: ProjectId, editorId: String, kernelId: String)

    /** Writes the notebook to its file through the extension's serializer. False when that failed. */
    suspend fun save(projectId: ProjectId, editorId: String): Boolean

    /**
     * Reads the notebook again from its file, what it changed since it was saved let go: the answer
     * to [NotebookEventDto.ChangedOnDisk]. A notebook without such changes is read again by itself.
     */
    suspend fun reload(projectId: ProjectId, editorId: String)

    // A cell's language features, asked as VS Code asks them: of the cell's own text document, by the
    // extensions whose selectors claim it. [line] and [column] are 1-based, in the cell's text.

    /** What could be typed there, ranked as VS Code ranks it. */
    suspend fun completions(projectId: ProjectId, editorId: String, handle: Int, line: Int, column: Int): List<CellCompletionDto>

    /** The hover there, as HTML; null when no extension has one. */
    suspend fun hover(projectId: ProjectId, editorId: String, handle: Int, line: Int, column: Int): String?

    /**
     * Shows where the symbol there is defined: false when no extension knows. A definition in a file
     * opens it; one in a cell of the notebook is shown with [NotebookEventDto.Reveal].
     */
    suspend fun goToDefinition(projectId: ProjectId, editorId: String, handle: Int, line: Int, column: Int): Boolean

    companion object {
        suspend fun getInstance(): NotebookApi = RemoteApiProviderService.resolve(remoteApiDescriptor<NotebookApi>())
    }
}

/**
 * One `contributes.notebooks` entry: files whose name matches one of [patterns] open as [viewType] --
 * unless [opensByDefault] is false, its `priority` being `option`, when only asking for it does.
 */
@Serializable
data class NotebookTypeDto(val viewType: String, val displayName: String, val patterns: List<String>, val opensByDefault: Boolean)

/** What becomes of an open notebook, for its editors. */
@Serializable
sealed interface NotebookEventDto {
    /** The notebook as read from its file; [Kernels] follows. */
    @Serializable
    data class Loaded(val cells: List<CellDto>) : NotebookEventDto

    /** The notebook could not be opened, for [message]; nothing follows. */
    @Serializable
    data class Failed(val message: String) : NotebookEventDto

    /** [deleted] cells at [start] gave way to [cells]. */
    @Serializable
    data class Spliced(val start: Int, val deleted: Int, val cells: List<CellDto>) : NotebookEventDto

    /** Another editor of the notebook changed cell [handle]'s text. */
    @Serializable
    data class Text(val handle: Int, val text: String) : NotebookEventDto

    /** Cell [handle]'s outputs are now [outputs]. */
    @Serializable
    data class Outputs(val handle: Int, val outputs: List<OutputDto>) : NotebookEventDto

    /** Cell [handle] is now [run]. */
    @Serializable
    data class Run(val handle: Int, val run: RunDto) : NotebookEventDto

    /** The kernels that can run this notebook, and the one [selected] for it. */
    @Serializable
    data class Kernels(val kernels: List<KernelDto>, val selected: String?, val detecting: Boolean) : NotebookEventDto

    /** Whether the notebook has changes its file does not. */
    @Serializable
    data class Dirty(val dirty: Boolean) : NotebookEventDto

    /**
     * Whether the file was changed elsewhere while the notebook had changes of its own, so it was
     * not read again: [NotebookApi.reload] does, saving writes over the file's.
     */
    @Serializable
    data class ChangedOnDisk(val changed: Boolean) : NotebookEventDto

    /** Something the user should hear about, such as a kernel that will not run. */
    @Serializable
    data class Problem(val message: String) : NotebookEventDto

    /** What the extensions now report as wrong in cell [handle]'s text. */
    @Serializable
    data class Problems(val handle: Int, val problems: List<CellProblemDto>) : NotebookEventDto

    /** Shows cell [handle] with the caret at [line], [column], 1-based: where a definition followed in this editor is. */
    @Serializable
    data class Reveal(val handle: Int, val line: Int, val column: Int) : NotebookEventDto
}

/**
 * One completion, ready to insert: [text] replaces what was typed, with the caret [caret] characters
 * into it and another at each of [carets]. It replaces from [replaceLine], [replaceColumn] (1-based),
 * or from the start of the word before the caret when they are null. Listed by [label], matched on [filter].
 */
@Serializable
data class CellCompletionDto(
    val label: String,
    val filter: String,
    val detail: String?,
    val kind: Int?,
    val text: String,
    val caret: Int,
    val carets: List<Int> = emptyList(),
    val replaceLine: Int? = null,
    val replaceColumn: Int? = null,
)

/**
 * One problem in a cell: from [startLine], [startColumn] to [endLine], [endColumn], 1-based, as VS
 * Code's markers are. [severity] is VS Code's `MarkerSeverity`: see [CellProblemDto.ERROR] and the rest.
 */
@Serializable
data class CellProblemDto(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: Int,
    val message: String,
    val source: String? = null,
) {
    companion object {
        const val HINT: Int = 1
        const val INFO: Int = 2
        const val WARNING: Int = 4
        const val ERROR: Int = 8
    }
}

/**
 * One cell. [fileExtension] is one an IDE file type would know the cell's [language] by, `.py` for
 * `python`, or null when no extension or VS Code itself names one.
 */
@Serializable
data class CellDto(
    val handle: Int,
    val kind: Int,
    val language: String,
    val fileExtension: String?,
    val text: String,
    val outputs: List<OutputDto>,
    val run: RunDto,
)

/**
 * A cell's run: [state], and from its last run the [order] the kernel gave it and how it went. While
 * the cell runs, [millis] is how long it has run so far, when this was sent.
 */
@Serializable
data class RunDto(val state: RunState, val order: Int? = null, val success: Boolean? = null, val millis: Long? = null)

@Serializable
enum class RunState { IDLE, PENDING, RUNNING }

/** One output, in each of the formats its [items] offer. */
@Serializable
data class OutputDto(val id: String, val items: List<OutputItemDto>)

@Serializable
class OutputItemDto(val mime: String, val data: ByteArray)

/** A kernel: an extension's notebook controller. */
@Serializable
data class KernelDto(val id: String, val label: String, val description: String?, val detail: String?)

/** `CellKind`, which [CellDto.kind] and [NotebookApi.insertCell] use. */
object CellKinds {
    const val MARKUP: Int = 1
    const val CODE: Int = 2
}
