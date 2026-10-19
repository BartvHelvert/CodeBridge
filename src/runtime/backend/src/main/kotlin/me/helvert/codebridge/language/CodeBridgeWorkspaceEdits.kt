package me.helvert.codebridge.language

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import me.helvert.codebridge.exthost.protocol.payload.FileOperations
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.WorkspaceEdit
import java.io.IOException

/**
 * Applies a `WorkspaceEdit` to the project: the one thing every quick fix, rename and refactoring an
 * extension offers ends in.
 *
 * All of it is one undoable command, so a fix that touched three files is taken back with one undo.
 * It is all or nothing only up to the point of starting: every text edit is checked against the
 * document it names before anything changes, but a file operation that fails partway leaves what
 * went before it in place -- for undo to take back, as VS Code does.
 *
 * Snippet edits to the file in the active editor have their stops walked there, as VS Code's bulk
 * edit service hands them to that editor's snippet controller; see [CodeBridgeSnippetTemplates]. In any
 * other file they are text.
 */
internal object CodeBridgeWorkspaceEdits {

    private val LOG = Logger.getInstance(CodeBridgeWorkspaceEdits::class.java)

    /** How many edits are being applied, nested; editor thread only. */
    private var applying = 0

    /** The editor snippet edits are walked in, and those placed in it so far. */
    private class SnippetEditor(val editor: Editor) {
        val placed = mutableListOf<CodeBridgeSnippetTemplates.Placed>()
    }

    /**
     * Whether the files created and deleted now are an extension's edit at work, which reports its
     * own operations -- [CodeBridgeFileCreatesAndDeletes] would otherwise take them for the user's.
     */
    val isApplying: Boolean get() = applying > 0

    /**
     * Applies [edit]; must run on the editor thread, outside a write action.
     *
     * [isCurrent] answers whether the host's copy of a file is still the editor's, given the version
     * the extension computed against. An edit made against text the user has since changed is
     * refused, since its offsets now point into different code. Returns whether it was applied.
     *
     * Shown for the user to choose from first when [showPreview], or when any change asks to be
     * confirmed, as VS Code's bulk edit service does; dismissing that, or unticking everything,
     * applies nothing and answers false.
     *
     * Files it creates, deletes or renames are offered to `onWillCreateFiles`, `onWillDeleteFiles`
     * or `onWillRenameFiles` first and reported to the matching did-event after, as every file
     * operation VS Code makes is, unless [participants] is false -- for the edits the participants
     * themselves answered with, which VS Code does not ask about again.
     */
    fun apply(
        project: Project,
        edit: WorkspaceEdit,
        title: String,
        isCurrent: (VirtualFile, Int?) -> Boolean,
        participants: Boolean = true,
        showPreview: Boolean = false,
    ): Boolean {
        if (edit.isEmpty) return true
        val chosen =
            if (showPreview || edit.needsConfirmation) CodeBridgeEditPreview.choose(project, edit, title) ?: return false
            else edit
        // VS Code's "Made no edits": what the user left ticked was nothing.
        if (chosen.isEmpty) return false
        // Refreshed here, outside the write action, so that inside it a plain lookup finds them.
        for (entry in chosen.entries) when (entry) {
            is WorkspaceEdit.ResourceEdit -> refresh(entry.resource)
            is WorkspaceEdit.FileEdit -> {
                entry.oldResource?.let(::refresh); entry.newResource?.let(::refresh)
            }
        }
        // Checked up front for every file that exists now. A file the same edit creates has nothing
        // to be stale against, and is resolved when its turn comes.
        for ((resource, _, versionId) in chosen.textEdits) {
            val file = textFile(project, resource) ?: continue
            if (!isCurrent(file, versionId)) {
                LOG.info("Refused an edit to ${file.name}: it was computed against text that has since changed")
                return false
            }
        }
        var applied = true
        val touched = linkedSetOf<Document>()
        // The active editor: the user's, as callers run this as the user's client.
        val snippets = if (chosen.textEdits.any { it.edit.snippet })
            FileEditorManager.getInstance(project).selectedTextEditor?.let(::SnippetEditor) else null
        applying++
        try {
            CommandProcessor.getInstance().executeCommand(project, {
                applied = applyInSteps(project, chosen.entries, participants, touched, snippets)
                if (snippets != null) WriteAction.run<RuntimeException> {
                    if (applied) CodeBridgeSnippetTemplates.start(project, snippets.editor, snippets.placed)
                    else snippets.placed.forEach { it.marker.dispose() }
                }
                CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
            }, title, null)
        } finally {
            applying--
        }
        save(touched)
        return applied
    }

    /**
     * Applies [entries] in order, stopping at each run of file operations to ask the participants.
     *
     * The asking cannot happen in a write action -- a language server may take seconds -- so each
     * stretch between file operations is a write action of its own, all of them in the caller's
     * command. A run is consecutive operations of one kind, as VS Code groups them: the participants
     * are asked once for it, after what comes before is applied and before the run itself.
     */
    private fun applyInSteps(
        project: Project,
        entries: List<WorkspaceEdit.Entry>,
        participants: Boolean,
        touched: MutableSet<Document>,
        snippets: SnippetEditor?,
    ): Boolean {
        fun write(part: List<WorkspaceEdit.Entry>): Boolean =
            part.isEmpty() || WriteAction.compute<Boolean, RuntimeException> {
                applyAll(
                    project,
                    part,
                    touched,
                    snippets
                )
            }

        var start = 0
        var index = 0
        while (index < entries.size) {
            val operation = if (participants) operationOf(entries[index]) else null
            if (operation == null) {
                index++
                continue
            }
            var end = index + 1
            while (end < entries.size && operationOf(entries[end]) == operation) end++
            if (!write(entries.subList(start, index))) return false
            val run = entries.subList(index, end).map { it as WorkspaceEdit.FileEdit }
            val changes = run.map {
                FileOperations.Change(
                    it.oldResource?.takeIf { _ -> it.newResource != null },
                    (it.newResource ?: it.oldResource)!!
                )
            }
            // Files the rest of this edit still changes: its own edits to them win over the participants'.
            val reserved = entries.subList(end, entries.size).filterIsInstance<WorkspaceEdit.ResourceEdit>()
                .mapTo(mutableSetOf()) { UriComponents.key(it.resource.uri) }
            val participantsService = CodeBridgeFileParticipants.getInstance(project)
            participantsService.participate(operation, changes, touched.associateWith { it.text }, reserved)
            val before = changes.map { find(it.source ?: it.target) }
            if (!write(run)) return false
            participantsService.ran(
                operation,
                changes.filterIndexed { i, change -> happened(operation, change, before[i]) })
            index = end
            start = end
        }
        return write(entries.subList(start, entries.size))
    }

    /**
     * The operation [entry] is, as the participants are told of it, or null when it is a text edit or
     * an operation that would do nothing: a rename to itself, or of a file that is not there.
     */
    private fun operationOf(entry: WorkspaceEdit.Entry): FileOperations.Operation? {
        if (entry !is WorkspaceEdit.FileEdit) return null
        val old = entry.oldResource
        val new = entry.newResource
        return when {
            old != null && new != null -> FileOperations.Operation.MOVE.takeIf { old != new && find(old) != null }
            old != null -> FileOperations.Operation.DELETE.takeIf { find(old) != null }
            new != null -> FileOperations.Operation.CREATE
            else -> null
        }
    }

    /**
     * Whether [change] took place, given the file it was about beforehand.
     *
     * A move is checked by identity rather than by path: a rename that changes only case still finds
     * the old path on Windows.
     */
    private fun happened(
        operation: FileOperations.Operation,
        change: FileOperations.Change,
        before: VirtualFile?
    ): Boolean =
        when (operation) {
            FileOperations.Operation.MOVE -> before != null && find(change.target) == before
            FileOperations.Operation.DELETE -> before != null && !before.isValid
            else -> before == null && find(change.target) != null
        }

    /**
     * Saves what an extension's edit changed, as VS Code does after applying a fix
     * (`files.refactoring.autoSave`, on by default).
     *
     * Not a nicety. Language servers analyse the file on disk for part of what they report --
     * rust-analyzer runs `cargo check` on save and on nothing else -- so until the save, the
     * problem the fix just removed stays underlined, for however long IntelliJ takes to autosave.
     * Also applied to what went before a failure: those changes are in the editor either way.
     *
     * Saved without stripping trailing spaces. The stripper puts every caret back where the
     * backend's editor last had it, and in split mode that editor lags the one the user types in:
     * a save shortly after a keystroke moved the caret back to before it -- Tab, then the caret
     * returning to where it was. The user's own saves still strip.
     */
    fun save(documents: Collection<Document>) {
        val manager = FileDocumentManager.getInstance()
        for (document in documents) {
            if (manager.isDocumentUnsaved(document)) manager.saveDocumentAsIs(document)
        }
    }

    private fun applyAll(
        project: Project,
        entries: List<WorkspaceEdit.Entry>,
        touched: MutableSet<Document>,
        snippets: SnippetEditor?,
    ): Boolean {
        var index = 0
        while (index < entries.size) {
            when (val entry = entries[index]) {
                is WorkspaceEdit.ResourceEdit -> {
                    // Consecutive edits to one file are one set: their ranges all refer to the text
                    // before any of them, which is how VS Code computes them.
                    val batch = mutableListOf(entry)
                    while (index + 1 < entries.size) {
                        val next = entries[index + 1] as? WorkspaceEdit.ResourceEdit ?: break
                        if (UriComponents.key(next.resource.uri) != UriComponents.key(entry.resource.uri)) break
                        batch += next
                        index++
                    }
                    if (!applyText(project, entry.resource, batch, touched, snippets)) return false
                }

                is WorkspaceEdit.FileEdit -> if (!applyFile(entry)) return false
            }
            index++
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return true
    }

    /** How the host's copy of [file] compares with [text], for a refusal to say. */
    private fun describeMirror(project: Project, file: VirtualFile, text: CharSequence): String {
        val held = HostedLanguageFeatures.getInstance(project).hostTextOf(file) ?: return "does not exist"
        if (held.contentEquals(text)) return "is the same"
        val common = held.commonPrefixWith(text).length
        return "differs from char $common: ${held.length} chars, " +
                "${StringUtil.escapeStringCharacters(held.substring(common).take(80))} where the document has " +
                StringUtil.escapeStringCharacters(text.substring(common).take(80))
    }

    private fun applyText(
        project: Project,
        resource: UriComponents,
        batch: List<WorkspaceEdit.ResourceEdit>,
        touched: MutableSet<Document>,
        snippets: SnippetEditor?,
    ): Boolean {
        val file = textFile(project, resource)
            ?: return false.also { LOG.info("Refused an edit to ${resource.uri}: no such file") }
        val document: Document = FileDocumentManager.getInstance().getDocument(file)
            ?: return false.also { LOG.info("Refused an edit to ${file.name}: it has no text document") }
        if (!document.isWritable) return false.also { LOG.info("Refused an edit to ${file.name}: it is read-only") }
        val live = snippets?.takeIf { it.editor.document == document }
        val indentation = live?.let { CodeBridgeSnippetTemplates.indentation(project, document) }
        val located = CodeBridgeTextEdits.locate(document.immutableCharSequence, batch.map { it.edit }, indentation)
            ?: return false.also {
                // What did not fit, and what it was measured against, since nothing else records either.
                val text = document.immutableCharSequence
                val lines = text.split('\n')
                LOG.info(
                    "Refused an edit to ${file.name}: its ranges do not fit the document " +
                            "(${lines.size} line(s), the last ${lines.last().length} long, ${text.length} chars; " +
                            "ranges ${batch.joinToString { it.edit.range.let { r -> "${r.startLineNumber}:${r.startColumn}-${r.endLineNumber}:${r.endColumn}" } }}; " +
                            "the host's copy ${describeMirror(project, file, text)})"
                )
            }
        for ((range, replacement) in located) document.replaceString(range.startOffset, range.endOffset, replacement)
        live?.placed?.addAll(CodeBridgeSnippetTemplates.place(document, located))
        touched += document
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return true
    }

    private fun applyFile(edit: WorkspaceEdit.FileEdit): Boolean = try {
        val old = edit.oldResource
        val new = edit.newResource
        when {
            old == null && new != null -> create(new, edit)
            old != null && new == null -> find(old)?.delete(this).let { true }
            old != null && new != null -> rename(old, new, edit)
            else -> true
        }
    } catch (failure: IOException) {
        LOG.warn("Could not apply a file operation from an extension", failure)
        false
    }

    private fun create(resource: UriComponents, edit: WorkspaceEdit.FileEdit): Boolean {
        val path = local(resource) ?: return false
        val existing = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (existing != null) {
            if (edit.ignoreIfExists) return true
            if (!edit.overwrite) return false
            existing.setBinaryContent(ByteArray(0))
            return true
        }
        val parent = VfsUtil.createDirectoryIfMissing(path.parent.toString()) ?: return false
        parent.createChildData(this, path.fileName.toString())
        return true
    }

    private fun rename(from: UriComponents, to: UriComponents, edit: WorkspaceEdit.FileEdit): Boolean {
        val file = find(from) ?: return edit.ignoreIfExists
        val target = local(to) ?: return false
        val existing = LocalFileSystem.getInstance().findFileByNioFile(target)
        if (existing != null && existing != file) {
            if (edit.ignoreIfExists) return true
            if (!edit.overwrite) return false
            existing.delete(this)
        }
        val parent = VfsUtil.createDirectoryIfMissing(target.parent.toString()) ?: return false
        if (file.parent != parent) file.move(this, parent)
        if (file.name != target.fileName.toString()) file.rename(this, target.fileName.toString())
        return true
    }

    private fun local(resource: UriComponents) =
        if (resource.scheme == UriComponents.FILE) runCatching { resource.toPath() }.getOrNull() else null

    /** The file a text edit is for: an untitled buffer the host holds, as well as a file on disk. */
    private fun textFile(project: Project, resource: UriComponents): VirtualFile? =
        HostedLanguageFeatures.getInstance(project).fileOf(resource.uri) ?: find(resource)

    private fun find(resource: UriComponents): VirtualFile? =
        local(resource)?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    private fun refresh(resource: UriComponents) {
        local(resource)?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
    }
}
