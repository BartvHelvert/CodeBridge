package me.helvert.codebridge.language

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import kotlinx.coroutines.*
import me.helvert.codebridge.exthost.protocol.payload.Formatting
import me.helvert.codebridge.exthost.protocol.payload.Position
import kotlin.coroutines.cancellation.CancellationException

/**
 * An extension's on-type formatter, asked once one of the characters it names has been typed, as
 * VS Code asks it with `editor.formatOnType` on.
 *
 * Asked off the editor thread and applied afterwards as an edit of its own, which Undo takes back
 * before the typing. Dropped if the document changed meanwhile, as VS Code drops it: its edits are
 * about the text as it was when the character went in.
 */
internal object CodeBridgeOnTypeFormatting {

    /** Asks about [ch], just typed in [editor]; answers whether edits were applied. Typing never waits for it. */
    fun typed(project: Project, editor: Editor, file: PsiFile, ch: String): Deferred<Boolean> {
        val virtualFile = file.virtualFile ?: return DONE
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.isMirrored(virtualFile)) return DONE
        val handle = features.onTypeFormatter(virtualFile, ch) ?: return DONE
        val document = editor.document
        val text = document.text
        val stamp = document.modificationStamp
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val position = Position(line + 1, offset - document.getLineStartOffset(line) + 1)
        val indent = CodeStyle.getIndentOptions(file)
        val options = Formatting.Options(indent.TAB_SIZE, !indent.USE_TAB_CHARACTER)
        val modality = ModalityState.defaultModalityState()
        return features.scope.async {
            try {
                // The provider is waited on by a blocking call, which the IDE's own formatter makes too.
                val edits = withContext(Dispatchers.IO) { features.onTypeFormatting(virtualFile, handle, text, position, ch, options) }
                val located = edits?.let { CodeBridgeTextEdits.locate(text, it) } ?: return@async false
                withContext(Dispatchers.EDT + modality.asContextElement()) {
                    if (editor.isDisposed || document.modificationStamp != stamp) {
                        LOG.debug("Dropped on-type formatting of ${virtualFile.name}: the document changed meanwhile")
                        return@withContext false
                    }
                    WriteCommandAction.writeCommandAction(project, file).withName(COMMAND).run<RuntimeException> {
                        for ((range, replacement) in located) document.replaceString(
                            range.startOffset,
                            range.endOffset,
                            replacement
                        )
                    }
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("On-type formatting of ${virtualFile.name} failed", failure)
                false
            }
        }
    }

    private val DONE: Deferred<Boolean> = CompletableDeferred(false)

    private const val COMMAND = "Format on Type"

    private val LOG = Logger.getInstance(CodeBridgeOnTypeFormatting::class.java)
}

/** Every character but Enter, which reaches [CodeBridgeOnTypeFormattingEnterHandler] instead. */
internal class CodeBridgeOnTypeFormattingTypedHandler : TypedHandlerDelegate() {
    @Suppress("DeferredResultUnused") // Typing never waits for the formatter.
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        CodeBridgeOnTypeFormatting.typed(project, editor, file, c.toString())
        return Result.CONTINUE
    }
}

/** Enter, which an on-type formatter names as `"\n"`; asked once the new line is indented. */
internal class CodeBridgeOnTypeFormattingEnterHandler : EnterHandlerDelegate {
    @Suppress("DeferredResultUnused") // Typing never waits for the formatter.
    override fun postProcessEnter(
        file: PsiFile,
        editor: Editor,
        dataContext: DataContext
    ): EnterHandlerDelegate.Result {
        CodeBridgeOnTypeFormatting.typed(file.project, editor, file, "\n")
        return EnterHandlerDelegate.Result.Continue
    }
}
