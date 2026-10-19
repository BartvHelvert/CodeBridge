package me.helvert.codebridge.language

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import me.helvert.codebridge.exthost.protocol.payload.FileOperations
import me.helvert.codebridge.exthost.protocol.payload.FileOperations.Operation
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.nio.file.Path

/**
 * Files and folders the user renames or moves, told to the extensions as VS Code tells them:
 * `onWillRenameFiles` before, with the edits it answers applied, and `onDidRenameFiles` after.
 *
 * The will-event is the one that matters. It is a language client's `workspace/willRenameFiles`,
 * where a server such as the TypeScript one or rust-analyzer works out the imports a move breaks;
 * the did-event alone only lets it notice the file is somewhere else.
 *
 * These are the user's refactorings; an extension's own edit is [CodeBridgeWorkspaceEdits]' to report.
 * They are hooked at the refactoring, not the file system, since as in VS Code a file moved by a
 * build or by git is not a move to fix imports for. Where those go is
 * known at different points of the two refactorings, and neither says it when it starts, so it is
 * noted while each is prepared -- by [CodeBridgeFileRenameNotes] and [CodeBridgeFileMoveNotes], both offered every
 * element whatever plugin handles it -- and picked up by [CodeBridgeFileMoveListener] as the refactoring
 * enters its write phase. The edits are applied then, before the files move and in the
 * refactoring's own command, so one undo takes back both; that is VS Code's order too, and what the
 * edits' ranges were computed against.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeFileMoves(private val project: Project) {

    /**
     * One file or folder, and the path it is going to.
     *
     * Its path now is kept rather than read when needed: by the did-event, the file is already at [target].
     */
    private class Planned(val file: VirtualFile, val target: Path) {
        val change = FileOperations.Change(UriComponents.of(file.toNioPath()), UriComponents.of(target))
    }

    /** An element a rename was prepared for, and its new name, in the order the rename visited them. */
    private class Noted(val element: PsiElement, val newName: String)

    /**
     * What renames have been prepared for, most recent last.
     *
     * A rename may be prepared, given up in its preview and prepared again; only the pass that
     * starts is read, by finding where it began.
     */
    private val renames = ArrayList<Noted>()

    /** The latest move prepared: the files and folders moved, and the folder they go into. */
    @Volatile
    private var move: Pair<Set<VirtualFile>, VirtualFile>? = null

    /** What the refactoring under way moves, between its start and its end; editor thread only. */
    private var running: List<Planned>? = null

    fun renamePrepared(element: PsiElement, newName: String) = synchronized(renames) {
        renames += Noted(element, newName)
        if (renames.size > NOTES_KEPT) renames.subList(0, renames.size - NOTES_KEPT).clear()
    }

    fun movePrepared(elements: Array<out PsiElement>, target: PsiDirectory) {
        val files = elements.mapNotNull { (it as? PsiFileSystemItem)?.let(::local) }
        if (files.isEmpty()) return
        move = files.toSet() to (local(target) ?: return)
    }

    /** A refactoring is about to change files; on the editor thread, inside its command, outside a write action. */
    fun started(refactoringId: String, data: RefactoringEventData?) {
        running = null
        val planned = when (refactoringId) {
            RENAME -> planRename(data)
            MOVE -> planMove(data)
            else -> return
        }
        if (planned.isEmpty()) return
        running = planned
        // Told after either way: that is only what happened, not a change to the code.
        if (!searchesForReferences(refactoringId, data)) return
        CodeBridgeFileParticipants.getInstance(project).participate(Operation.MOVE, planned.map { it.change })
    }

    /**
     * Whether the user left "Search for references" ticked, which is whether references to what is
     * moved should be updated -- by the IDE, and so by the extensions too.
     *
     * Read from the settings the dialogs keep it in: the move dialog saves its checkbox before
     * moving, and a move by drag and drop goes by the same setting. A rename's depends on what is
     * renamed; one that started from a symbol, and renames a file with it, has no such checkbox.
     */
    private fun searchesForReferences(refactoringId: String, data: RefactoringEventData?): Boolean {
        val settings = RefactoringSettings.getInstance()
        if (refactoringId == MOVE) return settings.MOVE_SEARCH_FOR_REFERENCES_FOR_FILE
        return when (data?.getUserData(RefactoringEventData.PSI_ELEMENT_KEY)) {
            is PsiFile -> settings.RENAME_SEARCH_FOR_REFERENCES_FOR_FILE
            is PsiDirectory -> settings.RENAME_SEARCH_FOR_REFERENCES_FOR_DIRECTORY
            else -> true
        }
    }

    /** The refactoring [started] is over, whether or not it got as far as moving anything. */
    fun done() {
        val planned = running ?: return
        running = null
        // A refactoring that failed partway is ended the same way, so the files say what happened.
        val moved = planned.filter { it.file.isValid && it.file.toNioPath() == it.target }
        CodeBridgeFileParticipants.getInstance(project).ran(Operation.MOVE, moved.map { it.change })
    }

    /**
     * The files and folders the starting rename renames: the element the user picked, and any file
     * renamed along with it -- the file of a class the IDE renames with the class, say.
     */
    private fun planRename(data: RefactoringEventData?): List<Planned> {
        val primary = data?.getUserData(RefactoringEventData.PSI_ELEMENT_KEY) ?: return emptyList()
        val pass = synchronized(renames) {
            // The rename visits the element the user picked first, then the rest, so the pass that
            // is starting is everything from the last time it visited that element.
            val manager = PsiManager.getInstance(project)
            val start =
                renames.indexOfLast { it.element == primary || manager.areElementsEquivalent(it.element, primary) }
            val pass = if (start < 0) emptyList() else renames.subList(start, renames.size).toList()
            renames.clear()
            pass
        }
        return pass.distinctBy { it.element }.mapNotNull { noted ->
            val file =
                (noted.element as? PsiFileSystemItem)?.takeIf { it.isValid }?.let(::local) ?: return@mapNotNull null
            val parent = file.parent ?: return@mapNotNull null
            if (noted.newName == file.name) return@mapNotNull null
            Planned(file, parent.toNioPath().resolve(noted.newName))
        }
    }

    private fun planMove(data: RefactoringEventData?): List<Planned> {
        val (files, target) = move ?: return emptyList()
        move = null
        val elements = data?.getUserData(RefactoringEventData.PSI_ELEMENT_ARRAY_KEY) ?: return emptyList()
        // The move prepared last is the one starting only if it moves the same things.
        if (elements.mapNotNull { (it as? PsiFileSystemItem)?.let(::local) }.toSet() != files) return emptyList()
        if (!target.isValid) return emptyList()
        return files.filter { it.isValid && it.parent != target }
            .map { Planned(it, target.toNioPath().resolve(it.name)) }
    }

    /** The file an element is, when it is one on disk that the extensions could be told about. */
    private fun local(element: PsiFileSystemItem): VirtualFile? =
        element.virtualFile?.takeIf { it.isInLocalFileSystem }

    companion object {
        private const val RENAME = "refactoring.rename"
        private const val MOVE = "refactoring.move"

        /** More than one rename prepares this many elements only when renames are given up unstarted. */
        private const val NOTES_KEPT = 256

        fun getInstance(project: Project): CodeBridgeFileMoves = project.service()
    }
}

/** Hands each refactoring's start and end to [CodeBridgeFileMoves]. */
internal class CodeBridgeFileMoveListener(private val project: Project) : RefactoringEventListener {
    override fun refactoringStarted(refactoringId: String, beforeData: RefactoringEventData?) {
        // Published on the editor thread by every refactoring; checked, since the edits rely on it.
        if (!ApplicationManager.getApplication().isDispatchThread) return
        CodeBridgeFileMoves.getInstance(project).started(refactoringId, beforeData)
    }

    override fun refactoringDone(refactoringId: String, afterData: RefactoringEventData?) {
        if (!ApplicationManager.getApplication().isDispatchThread) return
        CodeBridgeFileMoves.getInstance(project).done()
    }
}

/**
 * Notes what a rename renames, and to what, for [CodeBridgeFileMoves].
 *
 * An automatic renamer that never renames anything: the one place a rename's new names are handed
 * to every plugin before the rename, whichever handles the element. Applicable to everything, since
 * the rename that renames a file may have started from a class; it asks for no checkbox, so it is
 * consulted on every rename and the dialog gains nothing.
 */
internal class CodeBridgeFileRenameNotes : AutomaticRenamerFactory {
    override fun isApplicable(element: PsiElement): Boolean = true

    override fun getOptionName(): String? = null

    override fun isEnabled(): Boolean = true

    override fun setEnabled(enabled: Boolean) {}

    override fun createRenamer(element: PsiElement, newName: String, usages: Collection<UsageInfo>): AutomaticRenamer {
        CodeBridgeFileMoves.getInstance(element.project).renamePrepared(element, newName)
        return NoRenames
    }

    private object NoRenames : AutomaticRenamer() {
        override fun getDialogTitle(): String = ""
        override fun getDialogDescription(): String = ""
        override fun entityName(): String = ""
    }
}

/**
 * Notes where files and folders are being moved to, for [CodeBridgeFileMoves].
 *
 * Handles no file itself. Conflict detection is the one step of a move every handler is asked
 * about, with everything moved -- folders as well as files -- and the folder it goes into.
 */
internal class CodeBridgeFileMoveNotes : MoveFileHandler() {
    override fun canProcessElement(element: PsiFile?): Boolean = false

    override fun detectConflicts(
        conflicts: MultiMap<PsiElement, String>,
        elementsToMove: Array<out PsiElement>,
        usages: Array<out UsageInfo>,
        targetDirectory: PsiDirectory,
    ) {
        CodeBridgeFileMoves.getInstance(targetDirectory.project).movePrepared(elementsToMove, targetDirectory)
    }

    override fun prepareMovedFile(
        file: PsiFile?,
        moveDestination: PsiDirectory?,
        oldToNewMap: MutableMap<PsiElement, PsiElement>?
    ) {
    }

    override fun findUsages(
        psiFile: PsiFile,
        newParent: PsiDirectory,
        searchInComments: Boolean,
        searchInNonJavaFiles: Boolean
    ): List<UsageInfo>? = null

    override fun retargetUsages(usageInfos: List<UsageInfo>, oldToNewMap: Map<PsiElement, PsiElement>) {}

    override fun updateMovedFile(file: PsiFile?) {}
}
