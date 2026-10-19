package me.helvert.codebridge.language

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.util.RefactoringUIUtil
import me.helvert.codebridge.exthost.protocol.payload.FileOperations
import me.helvert.codebridge.exthost.protocol.payload.FileOperations.Operation
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.nio.file.Path

/**
 * Files and folders the user creates, copies or deletes, told to the extensions as VS Code tells
 * them: `onWillCreateFiles` or `onWillDeleteFiles`, with the edits they answer applied, and
 * `onDidCreateFiles` or `onDidDeleteFiles`.
 *
 * What counts as the user's is what VS Code counts: an operation the IDE makes in a command -- an
 * action, a refactoring, a paste -- and not a file a build or git puts on disk, which the IDE only
 * notices on refresh. Those are collected from the file system as each command runs, and reported
 * when it finishes.
 *
 * The will-events differ in when they can be asked. A deletion is known before it happens: the
 * Delete and Safe Delete actions name what they delete, and confirm it, before the command that
 * deletes starts, so the extensions are asked as it starts, and their edits are in the same undo.
 * A creation is not: every way of making a file -- New File, a template, a paste -- learns the name
 * only inside the command that makes it. So the extensions are asked as that command finishes, with
 * the files already made. Their edits are about other files -- an index or a project file that
 * lists the new one -- so they land as they would have, and still in the same undo.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeFileCreatesAndDeletes(private val project: Project) {

    /** What a Delete action under way is to delete, and the name of the command that will; editor thread only. */
    private var deleting: Pair<List<VirtualFile>, String>? = null

    /** What the current command created, copied and deleted, each by its topmost file; editor thread only. */
    private val created = LinkedHashMap<String, FileOperations.Change>()
    private val copied = LinkedHashMap<String, FileOperations.Change>()
    private val deleted = LinkedHashMap<String, FileOperations.Change>()

    /** Whether the creations of the current command have been put to the participants. */
    private var asked = false

    fun deleteActionStarted(elements: Array<out PsiElement>) {
        val topmost = PsiTreeUtil.filterAncestors(elements)
        // An action deleting code as well as files is not one VS Code has; its files are reported after.
        if (topmost.isEmpty() || topmost.any { it !is PsiFileSystemItem }) return
        val files =
            topmost.mapNotNull { (it as PsiFileSystemItem).virtualFile?.takeIf(VirtualFile::isInLocalFileSystem) }
        if (files.isEmpty()) return
        // What DeleteHandler and SafeDeleteProcessor both name the command that deletes.
        deleting = files to RefactoringBundle.message(
            "safe.delete.command",
            RefactoringUIUtil.calculatePsiElementDescriptionList(topmost)
        )
    }

    fun deleteActionEnded() {
        deleting = null
    }

    fun commandStarted(name: String?) {
        clear()
        val (files, command) = deleting ?: return
        if (name != command || ApplicationManager.getApplication().isWriteAccessAllowed) return
        deleting = null
        CodeBridgeFileParticipants.getInstance(project).participate(
            Operation.DELETE,
            files.filter { it.isValid }.map { FileOperations.Change(null, uri(it.toNioPath())) })
    }

    /** One file system event of the current command. */
    fun changed(event: VFileEvent) {
        when (event) {
            // The event's path is the original's; the copy is where it was put.
            is VFileCopyEvent -> {
                val target = event.newParent.toNioPath().resolve(event.newChildName)
                if (inProject(target)) add(
                    copied,
                    target,
                    FileOperations.Change(uri(event.file.toNioPath()), uri(target))
                )
            }

            is VFileCreateEvent -> {
                val path = Path.of(event.path)
                if (inProject(path)) add(created, path, FileOperations.Change(null, uri(path)))
            }

            is VFileDeleteEvent -> {
                val path = Path.of(event.path)
                // Made and deleted in one go: it was never there to report.
                if (inProject(path) && created.remove(key(path)) == null) add(
                    deleted,
                    path,
                    FileOperations.Change(null, uri(path))
                )
            }
        }
    }

    /** The command is ending: the extensions are asked about what it created, inside it, so their edits share its undo. */
    fun beforeCommandFinished() {
        if (asked || (created.isEmpty() && copied.isEmpty())) return
        asked = true
        if (ApplicationManager.getApplication().isWriteAccessAllowed) return
        val participants = CodeBridgeFileParticipants.getInstance(project)
        participants.participate(Operation.CREATE, created.values.toList())
        participants.participate(Operation.COPY, copied.values.toList())
    }

    fun commandFinished() {
        val participants = CodeBridgeFileParticipants.getInstance(project)
        participants.ran(Operation.CREATE, created.values.toList())
        participants.ran(Operation.COPY, copied.values.toList())
        participants.ran(Operation.DELETE, deleted.values.toList())
        clear()
    }

    private fun clear() {
        created.clear()
        copied.clear()
        deleted.clear()
        asked = false
    }

    /** Adds [path] unless a folder it is in is already there, as VS Code reports a folder and not its contents. */
    private fun add(to: MutableMap<String, FileOperations.Change>, path: Path, change: FileOperations.Change) {
        var parent: Path? = path.parent
        while (parent != null) {
            if (key(parent) in to) return
            parent = parent.parent
        }
        to[key(path)] = change
    }

    /** Whether [path] is the project's: under its directory or one of its content roots. */
    private fun inProject(path: Path): Boolean {
        val roots = buildList {
            project.basePath?.let { add(Path.of(it)) }
            // Not every root is on disk: one in memory has no path to be under.
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                root.fileSystem.getNioPath(root)?.let(::add)
            }
            addAll(extraRoots)
        }
        return roots.any { path.startsWith(it) }
    }

    /** Folders that count as the project's besides its own; for tests, whose files are in a temporary one. */
    @Volatile
    internal var extraRoots: List<Path> = emptyList()

    private fun uri(path: Path) = UriComponents.of(path)

    private fun key(path: Path) = UriComponents.key(path)

    companion object {
        /** The actions that delete what is selected: the Delete key, and Safe Delete. */
        private val DELETE_ACTIONS = setOf($$"$Delete", "SafeDelete")

        fun getInstance(project: Project): CodeBridgeFileCreatesAndDeletes = project.service()

        /** Whether [event] is one of the user's operations, made by the IDE in a command for [project]. */
        fun isUsers(event: VFileEvent, project: Project): Boolean {
            if (event.isFromRefresh || event.fileSystem !is LocalFileSystem) return false
            if (event !is VFileCreateEvent && event !is VFileCopyEvent && event !is VFileDeleteEvent) return false
            val commands = CommandProcessor.getInstance()
            if (commands.currentCommand == null || commands.currentCommandProject.let { it != null && it != project }) return false
            // An extension's edit reports its own; an undo puts back what VS Code would not report either.
            if (CodeBridgeWorkspaceEdits.isApplying || UndoManager.getInstance(project).isUndoOrRedoInProgress) return false
            return true
        }

        internal fun isDeleteAction(action: AnAction): Boolean =
            ActionManager.getInstance().getId(action) in DELETE_ACTIONS
    }
}

/** Notes what a Delete action is about to delete. */
internal class CodeBridgeDeleteActionListener : AnActionListener {
    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (!CodeBridgeFileCreatesAndDeletes.isDeleteAction(action)) return
        val project = event.project ?: return
        val elements = event.getData(LangDataKeys.PSI_ELEMENT_ARRAY) ?: event.getData(LangDataKeys.PSI_ELEMENT)
            ?.let { arrayOf(it) } ?: return
        CodeBridgeFileCreatesAndDeletes.getInstance(project).deleteActionStarted(elements)
    }

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        if (!CodeBridgeFileCreatesAndDeletes.isDeleteAction(action)) return
        event.project?.let { CodeBridgeFileCreatesAndDeletes.getInstance(it).deleteActionEnded() }
    }
}

/** Hands each command's start and end to [CodeBridgeFileCreatesAndDeletes]. */
internal class CodeBridgeFileCommandListener : CommandListener {
    override fun commandStarted(event: CommandEvent) {
        val project = event.project ?: return
        if (ApplicationManager.getApplication().isDispatchThread) CodeBridgeFileCreatesAndDeletes.getInstance(project)
            .commandStarted(event.commandName)
    }

    override fun beforeCommandFinished(event: CommandEvent) {
        val project = event.project ?: return
        if (ApplicationManager.getApplication().isDispatchThread) CodeBridgeFileCreatesAndDeletes.getInstance(project)
            .beforeCommandFinished()
    }

    override fun commandFinished(event: CommandEvent) {
        val project = event.project ?: return
        if (ApplicationManager.getApplication().isDispatchThread) CodeBridgeFileCreatesAndDeletes.getInstance(project)
            .commandFinished()
    }
}

/** Hands the file system events of the user's operations to [CodeBridgeFileCreatesAndDeletes]. */
internal class CodeBridgeFileOperationsListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (!ApplicationManager.getApplication().isDispatchThread) return
        val project = CommandProcessor.getInstance().currentCommandProject
        val projects = if (project != null) listOf(project) else ProjectManager.getInstance().openProjects.toList()
        for (candidate in projects) {
            if (candidate.isDisposed) continue
            val users = events.filter { CodeBridgeFileCreatesAndDeletes.isUsers(it, candidate) }
            if (users.isEmpty()) continue
            val service = CodeBridgeFileCreatesAndDeletes.getInstance(candidate)
            users.forEach(service::changed)
        }
    }
}
