package me.helvert.codebridge.language

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Position
import me.helvert.codebridge.exthost.protocol.payload.TextEditors
import me.helvert.codebridge.rpc.OpenFileDto
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `MainThreadTextEditors`: an extension acting on an editor rather than on a document.
 *
 * The host knows one editor per mirrored document, so an editor id here is a file. Showing a
 * document opens it in IntelliJ and answers once the host has been told about its editor -- the
 * extension looks the editor up by that id straight away, and `showTextDocument` fails if it is not
 * there. Edits go through the same offsets and staleness check as a workspace edit.
 *
 * A snippet's stops are walked in the editor it went into ([CodeBridgeSnippetTemplates]). Decorations are
 * [CodeBridgeEditorDecorations]'. Editor options are acknowledged and not applied.
 */
@Service(Service.Level.PROJECT)
class CodeBridgeTextEditors(private val project: Project, private val scope: CoroutineScope) {

    private val features get() = HostedLanguageFeatures.getInstance(project)

    /**
     * Files to open for the user, served to the frontend by `CodeBridgeApi.openRequests`.
     *
     * Not replayed: a request is for whoever is connected now. The small buffer only keeps an emit
     * from failing while a collector is between two items.
     */
    val openRequests = MutableSharedFlow<OpenFileDto>(extraBufferCapacity = 16)

    /**
     * The split-mode client collecting [openRequests], by the id its call came in under.
     *
     * [ClientId.ownerId] names the controller only once the platform has made it the owner, and a
     * split-mode backend has answered with its own local id while the client was connected and
     * showing the project. Every reveal then took the open-request path, where an untitled buffer
     * the client cannot look up never opened. Calls from the client run under its own id.
     */
    @Volatile
    private var subscribedClient: ClientId? = null

    /** [openRequests], for [client] -- the frontend calling -- to collect. */
    fun openRequestsFor(client: ClientId?): Flow<OpenFileDto> {
        val remote = client?.takeIf { it != ClientId.localId }
        LOG.debug("Open requests collected by ${remote ?: "the local frontend"}")
        return openRequests
            .onStart { if (remote != null) subscribedClient = remote }
            .onCompletion { if (remote != null && subscribedClient == remote) subscribedClient = null }
    }

    /** Every call, answered; the host awaits the ones that return something and ignores the rest. */
    suspend fun handle(method: String, argsJson: String): MainThreadResult =
        CodeBridgeEditorDecorations.getInstance(project).handle(method, argsJson) ?: when (method) {
            TextEditors.SHOW -> show(TextEditors.show(argsJson))
            TextEditors.APPLY_EDITS -> apply(TextEditors.edits(argsJson))
            TextEditors.INSERT_SNIPPET -> apply(TextEditors.snippet(argsJson))
            TextEditors.SET_SELECTIONS -> onEditor(method, argsJson) { editor, document ->
                val (anchor, caret) = TextEditors.selection(argsJson) ?: return@onEditor
                val from = offsetOf(document, anchor)
                val to = offsetOf(document, caret)
                editor.caretModel.moveToOffset(to)
                editor.selectionModel.setSelection(minOf(from, to), maxOf(from, to))
            }

            TextEditors.REVEAL_RANGE -> onEditor(method, argsJson) { editor, _ ->
                val range = TextEditors.revealed(argsJson) ?: return@onEditor
                editor.scrollingModel.scrollTo(
                    LogicalPosition(range.startLineNumber - 1, range.startColumn - 1),
                    ScrollType.MAKE_VISIBLE
                )
            }

            else -> {
                LOG.debug("Not drawn: MainThreadTextEditors.$method")
                MainThreadResult()
            }
        }

    /**
     * Opens the document and answers with its editor id, once the host has one.
     *
     * The id arrives when the file is mirrored, which happens on the language worker after the
     * editor opens -- so for a file that was not open yet the answer waits, off the editor thread.
     */
    private suspend fun show(show: TextEditors.Show): MainThreadResult {
        // The host's own name for it first: an untitled buffer has no path to look up.
        val file = features.fileOf(show.resource.uri)
            ?: runCatching { show.resource.toPath() }.getOrNull()
                ?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
            ?: return MainThreadResult()
        // Already open: answered without touching the editors. Opening it again is not harmless in
        // split mode, where the backend's editor manager is not what the user sees -- the open there
        // created a transient editor whose close then read as the file being closed, withdrew the
        // document from the host, and left the extension failing every request for it.
        features.editorOf(file)?.let { return MainThreadResult("\"$it\"") }
        reveal(file, show.selection?.let { Position(it.startLineNumber, it.startColumn) }, !show.preserveFocus)
        val editor = withTimeoutOrNull(SHOW_TIMEOUT_SECONDS.seconds) {
            var id = features.editorOf(file)
            while (id == null) {
                delay(20.milliseconds)
                id = features.editorOf(file)
            }
            id
        }
        if (editor == null) LOG.info("Showed ${file.name}, but the extension host was not told about its editor in time")
        return MainThreadResult(editor?.let { "\"$it\"" })
    }

    /**
     * Opens [file] for the user at [position], completing once the open has been asked for -- not
     * once there is an editor, which is reported to the host separately.
     *
     * With a split-mode frontend connected, the backend navigates on that client's behalf, which
     * is how the platform's own Go to Declaration reaches it: the editors are per client, and only
     * an open made as the client lands in the ones on screen. Asking the frontend to open the file
     * itself only works for files it already knows, and toolchain sources outside the project --
     * where rust-analyzer's "Go to" links lead -- are not among them.
     *
     * Otherwise the frontend in the same process opens it, and with nothing listening at all -- a
     * fixture -- the backend opens it itself.
     */
    suspend fun reveal(file: VirtualFile, position: Position?, focus: Boolean) {
        val line = (position?.lineNumber ?: 1) - 1
        val column = (position?.column ?: 1) - 1
        val client = client()
        LOG.debug("Showing ${file.name} ${if (client != null) "as frontend client $client" else "without a frontend client"}")
        if (client != null) return asClient { descriptor(file, position).navigate(focus) }
        if (openRequests.subscriptionCount.value > 0 && openRequests.tryEmit(OpenFileDto(file.rpcId(), line, column, focus)))
            return
        val opening = withContext(Dispatchers.EDT) {
            val descriptor = descriptor(file, position)
            val request = FileEditorOpenRequest.fromDescriptor(descriptor).withRequestFocus(focus)
                .withOpenMode(FileEditorOpenMode.DEFAULT)
            FileEditorManager.getInstance(project).requestOpenTextEditor(descriptor, request)
        }
        // Asked for is all this promises: an editor that fails to open is reported by the platform.
        try {
            opening.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.debug("Could not open ${file.name}", failure)
        }
    }

    /**
     * Runs [action] on the editor thread as the connected split-mode frontend, or as this process
     * when there is none, completing once it has run.
     *
     * Editors, tool windows and focus are per client in split mode; only what is done as the client
     * lands on the screen the user is looking at.
     */
    suspend fun <T> asClient(action: () -> T): T {
        val client = client() ?: ClientId.localId
        return withContext(Dispatchers.EDT + client.asContextElement()) {
            check(!project.isDisposed) { "The project has been disposed" }
            action()
        }
    }

    /**
     * The connected split-mode client: on a remote-development host the owner is the controller, or,
     * while the owner is still this process, the client collecting [openRequests]. Null in a local IDE.
     */
    private fun client(): ClientId? = ClientId.ownerId.takeIf { it != ClientId.localId } ?: subscribedClient

    private fun descriptor(file: VirtualFile, position: Position?): OpenFileDescriptor {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val offset = if (document != null && position != null) offsetOf(document, position) else -1
        return if (offset >= 0) OpenFileDescriptor(project, file, offset) else OpenFileDescriptor(project, file)
    }

    /**
     * Applies edits an extension computed for one editor, as one undoable command.
     *
     * As the user's client, whose editor a snippet's stops are walked in; see [CodeBridgeSnippetTemplates].
     */
    private suspend fun apply(edits: TextEditors.Edits): MainThreadResult {
        val file = features.fileOfEditor(edits.editorId) ?: return MainThreadResult("false")
        val applied = asClient {
            runCatching { applyNow(file, edits) }
                .onFailure { LOG.warn("Could not apply an extension's edit to ${file.name}", it) }
                .getOrDefault(false)
        }
        return MainThreadResult(applied.toString())
    }

    private fun applyNow(file: VirtualFile, edits: TextEditors.Edits): Boolean {
        if (!features.isCurrent(file, edits.versionId)) {
            LOG.info("Refused an edit to ${file.name}: it was computed against text that has since changed")
            return false
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        if (!document.isWritable) return false
        // Snippets with no editor showing the file go in as text, as VS Code's do.
        val editor = if (edits.edits.any { it.snippet }) shownEditor(file) else null
        val indentation = editor?.let { CodeBridgeSnippetTemplates.indentation(project, document) }
        val located = CodeBridgeTextEdits.locate(document.immutableCharSequence, edits.edits, indentation) ?: return false
        WriteCommandAction.writeCommandAction(project).withName(EDIT_COMMAND_NAME).run<RuntimeException> {
            for ((range, replacement) in located) document.replaceString(
                range.startOffset,
                range.endOffset,
                replacement
            )
            if (editor != null) CodeBridgeSnippetTemplates.start(project, editor, CodeBridgeSnippetTemplates.place(document, located))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        // rust-analyzer applies most assists this way rather than as a workspace edit, and they are
        // saved for the same reason; see [CodeBridgeWorkspaceEdits.save].
        CodeBridgeWorkspaceEdits.save(listOf(document))
        return true
    }

    /** Runs [action] on the editor thread against the editor an id names, if it is showing. */
    private fun onEditor(
        method: String,
        argsJson: String,
        action: (Editor, Document) -> Unit
    ): MainThreadResult {
        val file = features.fileOfEditor(TextEditors.editorId(method, argsJson)) ?: return MainThreadResult()
        scope.launch(Dispatchers.EDT) {
            val editor = shownEditor(file) ?: return@launch
            action(editor, editor.document)
        }
        return MainThreadResult()
    }

    /** An editor showing [file], the selected one if it does; the client's when called as it. */
    private fun shownEditor(file: VirtualFile): Editor? {
        val documents = FileDocumentManager.getInstance()
        val editors = FileEditorManager.getInstance(project)
        return editors.selectedTextEditor?.takeIf { documents.getFile(it.document) == file }
            ?: editors.allEditors.asSequence()
                .mapNotNull { (it as? TextEditor)?.editor }
                .firstOrNull { documents.getFile(it.document) == file }
    }

    /** VS Code's 1-based position as an offset, clamped to the document. */
    private fun offsetOf(document: Document, position: Position): Int {
        if (document.lineCount == 0) return 0
        val line = (position.lineNumber - 1).coerceIn(0, document.lineCount - 1)
        val start = document.getLineStartOffset(line)
        return (start + (position.column - 1).coerceAtLeast(0)).coerceAtMost(document.getLineEndOffset(line))
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeTextEditors::class.java)

        /**
         * How long `showTextDocument` waits for a newly opened file to reach the host. Answered as soon
         * as it does; the wait is only this long when something has gone wrong. Five seconds was not
         * enough for a split-mode client still starting up, which opened the editor a second later
         * and left the extension with none.
         */
        private const val SHOW_TIMEOUT_SECONDS = 30L

        private const val EDIT_COMMAND_NAME = "Extension Edit"

        fun getInstance(project: Project): CodeBridgeTextEditors = project.service()
    }
}
