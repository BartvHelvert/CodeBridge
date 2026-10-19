package me.helvert.codebridge.language

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import me.helvert.codebridge.exthost.protocol.payload.FileOperations
import me.helvert.codebridge.exthost.protocol.payload.FileOperations.Operation
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.WorkspaceEdit
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CancellationException

/**
 * VS Code's file operation participants: what every file created, deleted, renamed, moved or copied
 * is offered to before it happens, and reported to after.
 *
 * The callers are whatever knows an operation is about to happen: [CodeBridgeWorkspaceEdits] for an
 * extension's edit, [CodeBridgeFileMoves] for the user's rename and move refactorings, and
 * [CodeBridgeFileCreatesAndDeletes] for the user's creating and deleting.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeFileParticipants(private val project: Project) {

    /**
     * Asks the extensions about an [operation] on [changes] about to happen, and applies what they
     * answer.
     *
     * On the editor thread, outside a write action. [settled] is text just written that the host
     * should hold before it is asked, so its answer is about that text. Edits to files in [reserved]
     * are left out: the caller is about to change those itself, and its offsets would no longer fit.
     *
     * The user is asked how the edits are applied as VS Code asks -- applied, previewed or skipped --
     * until they tick "Do not ask me again"; a preview is shown regardless when an edit asks for one.
     * Cancelling or skipping leaves the operation itself to go ahead: the user asked for that.
     */
    fun participate(
        operation: Operation,
        changes: List<FileOperations.Change>,
        settled: Map<Document, String> = emptyMap(),
        reserved: Set<String> = emptySet(),
    ) {
        val features = HostedLanguageFeatures.getInstance(project)
        // Not even a progress dialog for a project with no extensions to ask.
        if (changes.isEmpty() || !features.hostRunning) return
        val written = settled.mapNotNull { (document, text) ->
            FileDocumentManager.getInstance().getFile(document)?.let { it to text }
        }.toMap()
        val title = "Running '${operation.participants}' participants..."
        val participation = try {
            runWithModalProgressBlocking(project, title) { features.willRunFileOperation(operation, changes, written) }
        } catch (_: ProcessCanceledException) {
            return
        } catch (_: CancellationException) {
            return
        } ?: return
        val edit = WorkspaceEdit(participation.edit.entries.filter {
            val resource = (it as? WorkspaceEdit.ResourceEdit)?.resource ?: return@filter true
            val kept = UriComponents.key(resource.uri) !in reserved
            if (!kept) LOG.info("Left out an edit to ${resource.path} from ${participation.extensionNames}: the same edit changes it after the ${operation.noun}")
            kept
        })
        if (edit.isEmpty) return
        val preview = choice.preview(project, operation, edit, participation.extensionNames) ?: return
        LOG.info("Applying edits from ${participation.extensionNames} for the ${operation.noun} of ${changes.size} file(s)")
        val applied = runCatching {
            CodeBridgeWorkspaceEdits.apply(
                project,
                edit,
                title,
                features::isCurrent,
                participants = false,
                showPreview = preview
            )
        }.onFailure { LOG.warn("Could not apply the edits extensions made for a file ${operation.noun}", it) }
            .getOrDefault(false)
        if (!applied) LOG.info("Edits for a file ${operation.noun} from ${participation.extensionNames} were not applied")
    }

    /** Tells the extensions an [operation] on [changes] happened. */
    fun ran(operation: Operation, changes: List<FileOperations.Change>) {
        if (changes.isNotEmpty()) HostedLanguageFeatures.getInstance(project).didRunFileOperation(operation, changes)
    }

    /**
     * How the participants' edits are applied: VS Code's `file.particpants.additionalEdits` choice.
     *
     * Asked the first time, and every time until the user ticks "Do not ask me again"; the answer
     * kept is whether to preview. Asked with no option to apply directly when an edit itself asks
     * for confirmation, and that answer is not kept.
     */
    internal interface Choice {
        /** Whether to preview the edits, or null to skip them. */
        fun preview(project: Project, operation: Operation, edit: WorkspaceEdit, extensionNames: List<String>): Boolean?
    }

    private object Asked : Choice {
        override fun preview(
            project: Project,
            operation: Operation,
            edit: WorkspaceEdit,
            extensionNames: List<String>
        ): Boolean? {
            remembered()?.let { return it }
            val message =
                if (extensionNames.size == 1) "Extension '${extensionNames.single()}' wants to make refactoring changes with this file ${operation.noun}"
                else "${extensionNames.size} extensions want to make refactoring changes with this file ${operation.noun}"
            if (edit.needsConfirmation) {
                val shown = Messages.showDialog(
                    project,
                    message,
                    TITLE,
                    arrayOf(SHOW_PREVIEW, SKIP),
                    0,
                    Messages.getInformationIcon()
                )
                return if (shown == 0) true else null
            }
            val doNotAsk = object : DoNotAskOption.Adapter() {
                override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                    if (isSelected && exitCode in 0..1) remember(exitCode == 1)
                }

                override fun getDoNotShowMessage(): String = "Do not ask me again"
            }
            return when (Messages.showDialog(
                project,
                message,
                TITLE,
                arrayOf(OK, SHOW_PREVIEW, SKIP),
                0,
                Messages.getInformationIcon(),
                doNotAsk
            )) {
                0 -> false
                1 -> true
                else -> null
            }
        }
    }

    @Volatile
    private var choice: Choice = Asked

    @TestOnly
    fun choiceForTest(choice: Choice?) {
        this.choice = choice ?: Asked
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeFileParticipants::class.java)

        private const val TITLE = "Refactoring Changes"
        private const val OK = "OK"
        private const val SHOW_PREVIEW = "Show Preview"
        private const val SKIP = "Skip Changes"

        /** Where the user's answer is kept, for every project; VS Code's key is spelt the same way. */
        private const val CHOICE_KEY = "codebridge.file.particpants.additionalEdits"

        /** Whether to preview, as the user chose to be remembered, or null if they never did. */
        fun remembered(): Boolean? = PropertiesComponent.getInstance().getValue(CHOICE_KEY)?.toBooleanStrictOrNull()

        fun remember(preview: Boolean?) {
            if (preview == null) PropertiesComponent.getInstance().unsetValue(CHOICE_KEY)
            else PropertiesComponent.getInstance().setValue(CHOICE_KEY, preview.toString())
        }

        fun getInstance(project: Project): CodeBridgeFileParticipants = project.service()
    }
}

/** VS Code's "Reset choice for 'File operation needs preview'": asks again on the next operation with edits. */
internal class CodeBridgeResetFileOperationChoiceAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = CodeBridgeFileParticipants.remembered() != null
    }

    override fun actionPerformed(event: AnActionEvent) = CodeBridgeFileParticipants.remember(null)
}
