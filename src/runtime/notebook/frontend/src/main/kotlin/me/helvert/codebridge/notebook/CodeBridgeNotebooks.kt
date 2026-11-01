package me.helvert.codebridge.notebook

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * The project's notebook editors, and the notebook types that decide which files open in one.
 *
 * Where the file is opened decides how. In one process the IDE asks [NotebookEditorProvider] about
 * the file itself. A split-mode backend picks the editors of a project file on its own side, and
 * has the client show its text: the client then opens a [NotebookFile] standing for it in its place,
 * as it opens an extension's webview panel, and closes the text.
 *
 * The types come from the backend, which reads them off the extensions' manifests; until they have,
 * no file is a notebook, so a notebook open when the project is reopened shows as text for a moment.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeNotebooks(private val project: Project, val scope: CoroutineScope) {
    @Volatile var types: List<NotebookTypeDto> = emptyList()
        private set
    private val editors = CopyOnWriteArrayList<NotebookEditor>()
    /** The tab standing for each notebook file, so opening one again shows the tab it already has. */
    private val stand = HashMap<VirtualFile, NotebookFile>()

    fun typeOf(file: VirtualFile): NotebookTypeDto? =
        if (file is NotebookFile) file.type else NotebookTypes.forFile(types, file.name, file.path)

    /** Cells were copied or cut, so the backend's notebook clipboard has some to paste. */
    @Volatile var copiedCells = false

    /** Lays out every notebook's cells again: their toolbars moved to the other side. On the EDT. */
    fun toolbarMoved() = editors.forEach { it.relayout() }

    fun opened(editor: NotebookEditor) = editors.add(editor)
    fun closed(editor: NotebookEditor) = editors.remove(editor)

    /** Writes every notebook with changes its file lacks; on the EDT, before the IDE saves its documents. */
    fun saveAll() = editors.filter { it.isModified }.forEach { it.save() }

    /** On the EDT. */
    fun typesChanged(types: List<NotebookTypeDto>) {
        if (types != this.types) LOG.info("Notebook types: ${types.joinToString { it.viewType + " " + it.patterns }}")
        this.types = types
        FileEditorManager.getInstance(project).openFiles.forEach(::shown)
    }

    /**
     * [file] is open in an editor: a notebook shown as anything but one is shown as one instead.
     * On the EDT.
     */
    fun shown(file: VirtualFile) {
        if (file is NotebookFile || file.isDirectory) return
        val type = NotebookTypes.forFile(types, file.name, file.path) ?: return
        val manager = FileEditorManager.getInstance(project)
        if (manager.getEditors(file).any { it is NotebookEditor }) return
        val notebook = stand.getOrPut(file) { NotebookFile(file, type) }
        LOG.info("Showing ${file.name} as a ${type.viewType} notebook")
        // Later: closing a file from inside the listener that heard it open is not allowed. The text
        // goes once the notebook is open in its place, so the tab strip is never left empty.
        scope.launch(Dispatchers.EDT) {
            if (!manager.isFileOpen(file)) return@launch
            manager.openFile(notebook, FileEditorOpenRequest.defaults().withRequestFocus(true))
            manager.closeFile(file)
        }
    }

    fun forget(file: NotebookFile) {
        stand.remove(file.target, file)
    }

    companion object {
        fun getInstance(project: Project): CodeBridgeNotebooks = project.service()
        private val LOG = Logger.getInstance(CodeBridgeNotebooks::class.java)
    }
}

/** A notebook file's tab on a split-mode client, which the backend did not open: see [CodeBridgeNotebooks]. */
internal class NotebookFile(val target: VirtualFile, val type: NotebookTypeDto) :
    LightVirtualFile(target.name, target.fileType, "") {
    init {
        isWritable = false
    }

    override fun getPresentableName(): String = target.presentableName
    override fun isValid(): Boolean = target.isValid
}

/** Follows the notebook types from the backend, reconnecting as the tree views do. */
internal class CodeBridgeNotebooksActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val notebooks = CodeBridgeNotebooks.getInstance(project)
        project.messageBus.connect(notebooks.scope).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            // At once, in the event that opened the file: its text is not painted yet.
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = notebooks.shown(file)

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file is NotebookFile) notebooks.forget(file)
            }
        })
        while (currentCoroutineContext().isActive) {
            try {
                NotebookApi.getInstance().notebookTypes(project.projectId()).collect { types ->
                    withContext(Dispatchers.EDT) { notebooks.typesChanged(types) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.info("Lost the notebook types; retrying", error)
            }
            delay(RETRY.milliseconds)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeNotebooksActivity::class.java)
        private const val RETRY = 2_000L
    }
}

/** Opens a file an extension's notebook type claims as that notebook, instead of as text. */
internal class NotebookEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && CodeBridgeNotebooks.getInstance(project).typeOf(file) != null

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val type = checkNotNull(CodeBridgeNotebooks.getInstance(project).typeOf(file))
        return NotebookEditor(project, file, (file as? NotebookFile)?.target ?: file, type)
    }

    override fun getEditorTypeId(): String = "codebridge-notebook"

    /** The text is the serializer's, which the notebook rewrites on save: two editors of it would disagree. */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * Hides a notebook file's text editor as it is made, before it is first painted: a split-mode client
 * shows the notebook in its place a moment later (see [CodeBridgeNotebooks]), and its text, the
 * serializer's JSON, would show meanwhile.
 */
internal class NotebookTextHider : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file is NotebookFile) return
        if (project.serviceIfCreated<CodeBridgeNotebooks>()?.typeOf(file) != null) editor.component.isVisible = false
    }
}

/** Saving all, as Ctrl+S does, saves the notebooks too, which are no document of the IDE's. */
internal class NotebookSaveListener : FileDocumentManagerListener {
    override fun beforeAllDocumentsSaving() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) project.serviceIfCreated<CodeBridgeNotebooks>()?.saveAll()
        }
    }

    override fun beforeDocumentSaving(document: Document) = Unit
}
