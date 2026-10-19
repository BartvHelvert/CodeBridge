package me.helvert.codebridge.launch

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import me.helvert.codebridge.language.CodeBridgeCompletionContributor

/**
 * The editor a breakpoint's condition and log message are typed in, as VS Code's breakpoint widget
 * has it: in the language of the file the breakpoint is in, so that its grammar colours the
 * expression, and completing what the extension completes at the start of the breakpoint's line.
 *
 * The expression is a scratch file of the source file's type, named for its extension -- which is
 * what TextMate picks a grammar by -- and it carries the breakpoint's position for
 * [CodeBridgeCompletionContributor], which asks a paused debugger first. Nothing is mirrored into the
 * extension host: the expression is the debugger's language, not the file's, and only the file's
 * own completions are borrowed.
 */
internal class CodeBridgeExpressionEditors : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode
    ): Document {
        val source = sourcePosition?.file
        val type = source?.fileType ?: PlainTextFileType.INSTANCE
        val name = "expression" + (source?.extension?.let { ".$it" } ?: ".txt")
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText(name, type, expression.expression, LocalTimeCounter.currentTime(), true)
        if (source != null && sourcePosition.line >= 0) {
            file.viewProvider.virtualFile.putUserData(
                CodeBridgeCompletionContributor.BORROWED,
                CodeBridgeCompletionContributor.Borrowed(source, sourcePosition.line, CodeBridgeDebugCompletions(project))
            )
        }
        return checkNotNull(
            PsiDocumentManager.getInstance(project).getDocument(file)
        ) { "No document for a debugger expression" }
    }
}
