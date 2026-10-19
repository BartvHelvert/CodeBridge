package me.helvert.codebridge.language

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.impl.FinishMarkAction
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.refactoring.rename.Renamer
import com.intellij.refactoring.rename.RenamerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rename, in a file an extension offers to rename in.
 *
 * A renamer rather than a rename target: the platform's own rename replaces the text of each usage
 * it found, where an extension answers with a `WorkspaceEdit` of its own making -- one that may
 * expand a shorthand field, fix up an import or rename the file a module lives in. That edit is
 * what VS Code applies, so it is what is applied here.
 *
 * Only offered from an editor. Renaming a file from the project view is the IDE's to do; an
 * extension that wants to follow it does so through file operation events, not through rename.
 */
internal class CodeBridgeRenamerFactory : RenamerFactory {
    override fun createRenamers(dataContext: DataContext): Collection<Renamer> {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return emptyList()
        val project = editor.project ?: return emptyList()
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return emptyList()
        if (!HostedLanguageFeatures.getInstance(project).canRename(file)) return emptyList()
        return listOf(CodeBridgeRenamer(project, editor, file))
    }
}

/**
 * Renames the symbol at [editor]'s caret, as VS Code's F2 does, but typed in place as IntelliJ's
 * own rename is.
 *
 * The extension is asked what is to be renamed, and the name becomes a template the user types
 * over -- with the other occurrences in the file the extension highlights following along. Once
 * the template is finished the typing is put back, the extension is asked for the edit, and that
 * is applied. A refusal at either step is shown where the caret is, in the extension's own words.
 *
 * Where the name cannot be edited where it stands -- the extension named text that is not what
 * the document holds there, or the document is read-only -- the new name is asked for in a dialog.
 *
 * Dumb aware: nothing here reads an index, and an extension's language server is no less able to
 * rename while the IDE indexes.
 */
internal class CodeBridgeRenamer(
    private val project: Project,
    private val editor: Editor,
    private val file: VirtualFile,
) : Renamer, DumbAware {

    private val features get() = HostedLanguageFeatures.getInstance(project)

    /** Only seen when the IDE has a rename of its own here and the user is offered the choice. */
    override fun getPresentableText(): String =
        identifierRange(editor.document.charsSequence, editor.caretModel.offset)
            ?.let { "'${it.subSequence(editor.document.charsSequence)}' (VS Code extension)" }
            ?: "Symbol (VS Code extension)"

    override fun performRename() {
        val document = editor.document
        val offset = editor.caretModel.offset
        val lineIndex = document.getLineNumber(offset)
        val line = lineIndex + 1
        val column = offset - document.getLineStartOffset(lineIndex) + 1
        val (plan, highlights) = runWithModalProgressBlocking(project, TITLE) {
            val plan = features.prepareRename(file, line, column)
            val renames = plan.location != null || plan.rejections.isEmpty()
            plan to if (renames) occurrences(line, column) else emptyList()
        }
        // VS Code refuses outright when an extension refused and none named a location; the word at
        // the caret stands in only for extensions that cannot say.
        if (plan.location == null && plan.rejections.isNotEmpty()) return refuse(plan.rejections)
        if (plan.providers.isEmpty()) return
        val text = document.charsSequence
        val range = plan.location?.range?.let { symbolRange(document, it) } ?: identifierRange(text, offset)
        val current = plan.location?.text ?: range?.subSequence(text)?.toString() ?: ""
        val request = Request(plan, line, column, current)
        if (range != null && range.subSequence(text).toString() == current && document.isWritable &&
            TemplateManagerImpl.getTemplateState(editor) == null && startTemplate(request, range, highlights)) return
        askForName(request)
    }

    /** What is being renamed, and where it was asked from: the edit is asked for at the same place. */
    private class Request(val plan: HostedLanguageFeatures.RenamePlan, val line: Int, val column: Int, val current: String)

    /**
     * The other places in the file the extension highlights for the symbol, to edit along with it.
     *
     * Only for the look of it: they show what the rename will touch while the name is typed, and are
     * put back with the rest before the extension makes the real edit.
     */
    private fun occurrences(line: Int, column: Int): List<TextRange> {
        if (!features.hasHighlightProviders()) return emptyList()
        val highlights = features.documentHighlights(file, line, column)
        return runReadActionBlocking {
            highlights.mapNotNull { symbolRange(editor.document, it.range) }
        }
    }

    /**
     * Starts the template over [range], and over those of [highlights] that hold the same name.
     *
     * Returns false where no template could be started, for the dialog to ask instead.
     */
    private fun startTemplate(request: Request, range: TextRange, highlights: List<TextRange>): Boolean {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return false
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val text = document.charsSequence
        val others = mutableListOf<TextRange>()
        for (candidate in highlights.sortedBy { it.startOffset }) {
            if (candidate.subSequence(text).toString() != request.current) continue
            if (candidate.intersects(range) || others.any { it.intersects(candidate) }) continue
            others += candidate
        }
        val builder = TemplateBuilderImpl(psiFile)
        builder.replaceElement(psiFile, range, PRIMARY, ConstantNode(request.current), true)
        others.forEachIndexed { index, other -> builder.replaceElement(psiFile, other, "$OTHER$index", PRIMARY, false) }
        // Greedy, so that each still covers its occurrence once the name in it has been typed over.
        val markers = (listOf(range) + others).map {
            document.createRangeMarker(it).apply {
                isGreedyToLeft = true
                isGreedyToRight = true
            }
        }
        var mark: StartMarkAction? = null
        var refusal: String? = null
        CommandProcessor.getInstance().executeCommand(project, {
            try {
                mark = StartMarkAction.start(editor, project, TITLE)
            } catch (started: StartMarkAction.AlreadyStartedException) {
                refusal = started.message
                return@executeCommand
            }
            // Built and started in a write action, as the platform's own in-place rename does: building
            // an inline template marks up the document it is drawn over.
            ApplicationManager.getApplication().runWriteAction {
                val template = builder.buildInlineTemplate().apply {
                    isToShortenLongNames = false
                    isToReformat = false
                }
                // An inline template starts where the caret is, and spans its container: the whole
                // file. Starting it puts the caret back on the name, selected, for typing over.
                editor.caretModel.moveToOffset(0)
                TemplateManager.getInstance(project).startTemplate(editor, template, Listener(request, markers) { mark })
            }
        }, TITLE, null)
        refusal?.let {
            markers.forEach(RangeMarker::dispose)
            HintManager.getInstance().showErrorHint(editor, it)
        }
        return refusal != null || mark != null
    }

    /**
     * Follows the template to its end.
     *
     * The new name is read just before the template lets go of it. Finished, the typing is put back
     * and the rename carried out; abandoned -- Escape, or the caret leaving it -- the typing is put
     * back and that is all, as IntelliJ's own rename does.
     */
    private inner class Listener(
        private val request: Request,
        private val markers: List<RangeMarker>,
        private val mark: () -> StartMarkAction?,
    ) : TemplateEditingAdapter() {

        private var newName: String? = null

        override fun beforeTemplateFinished(state: TemplateState, template: Template?) {
            newName = state.getVariableValue(PRIMARY)?.text
        }

        override fun templateFinished(template: Template, brokenOff: Boolean) {
            revert(request.current, markers)
            val name = newName?.trim()
            if (brokenOff || name.isNullOrEmpty() || name == request.current) return finishMark(mark())
            Scope.getInstance(project).scope.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
                try {
                    rename(request, name)
                } finally {
                    finishMark(mark())
                }
            }
        }

        override fun templateCancelled(template: Template?) {
            revert(request.current, markers)
            finishMark(mark())
        }
    }

    /** Puts [name] back everywhere the template had it, so the extension renames the text it knows. */
    private fun revert(name: String, markers: List<RangeMarker>) {
        val document = editor.document
        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction {
                for (marker in markers.filter { it.isValid }.sortedByDescending { it.startOffset }) {
                    if (marker.textRange.subSequence(document.charsSequence) != name) {
                        document.replaceString(marker.startOffset, marker.endOffset, name)
                    }
                }
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }, TITLE, null)
        markers.forEach(RangeMarker::dispose)
    }

    private fun finishMark(mark: StartMarkAction?) {
        if (mark != null && !project.isDisposed) FinishMarkAction.finish(project, editor, mark)
    }

    private fun askForName(request: Request) {
        val current = request.current
        val newName = Messages.showInputDialog(
            project, "Rename '$current' to:", TITLE, null, current, NewName(current), TextRange(0, current.length),
        ) ?: return
        val title = titleOf(request, newName)
        val text = editor.document.text
        apply(runWithModalProgressBlocking(project, title) { edits(request, newName, text) }, title)
    }

    /** Asks for the edit that renames to [newName], and applies it; once the template is done. */
    private suspend fun rename(request: Request, newName: String) {
        val title = titleOf(request, newName)
        val text = editor.document.text
        // Off the editor thread, which the modal progress would otherwise run this on.
        apply(withModalProgress(project, title) { withContext(Dispatchers.IO) { edits(request, newName, text) } }, title)
    }

    private fun titleOf(request: Request, newName: String) = "Rename '${request.current}' to '$newName'"

    private fun edits(request: Request, newName: String, text: String): HostedLanguageFeatures.RenameResult {
        // After a template, the typing and its reverting are still on their way to the host;
        // an extension asked before they land answers about the name half typed.
        features.awaitMirrored(file, text)
        return features.renameEdits(file, request.line, request.column, newName, request.plan)
    }

    private fun apply(result: HostedLanguageFeatures.RenameResult, title: String) {
        val edit = result.edit ?: return refuse(result.rejections)
        if (!CodeBridgeWorkspaceEdits.apply(project, edit, title, features::isCurrent)) {
            HintManager.getInstance().showErrorHint(editor, "The rename no longer applies: the code changed while it was worked out")
        }
    }

    private fun refuse(reasons: List<String>) {
        val message = reasons.distinct().joinToString("\n").ifEmpty { HostedLanguageFeatures.NO_RENAME_RESULT }
        HintManager.getInstance().showErrorHint(editor, message)
    }

    /** A name to rename to: anything but nothing, or the name it already has. */
    private class NewName(private val current: String) : InputValidator {
        override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank() && inputString != current

        override fun canClose(inputString: String?): Boolean = checkInput(inputString)
    }

    /** Where the rename runs once the template is done: after it, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    private companion object {
        const val TITLE = "Rename"
        const val PRIMARY = "NAME"
        const val OTHER = "OCCURRENCE"
    }
}
