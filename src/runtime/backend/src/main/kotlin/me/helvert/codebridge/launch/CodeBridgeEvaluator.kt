package me.helvert.codebridge.launch

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.dap.DapInlineValueLocator
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapThread
import com.intellij.platform.dap.xdebugger.DefaultDapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.ExpressionInfo
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.language.HostedLanguageFeatures
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/** The platform's frames, each with a [CodeBridgeEvaluator] in front of its own. */
internal class CodeBridgePresentationFactory(locator: DapInlineValueLocator?) :
    DefaultDapXDebuggerPresentationFactory(locator) {
    override fun createStackFrame(
        executor: DapSessionExecutor,
        thread: DapThread,
        frame: DapStackFrame
    ): XStackFrame =
        object : DefaultDapXStackFrame(this, executor, thread, frame) {
            private val evaluator by lazy { CodeBridgeEvaluator(super.getEvaluator()) }
            override fun getEvaluator(): XDebuggerEvaluator = evaluator
        }
}

/**
 * A frame's evaluator, with what a hover evaluates decided as VS Code decides it: by the extension's
 * `EvaluatableExpressionProvider` where one claims the file -- and then by it alone, so a hover the
 * extension declines shows nothing -- and by the platform's guess at the expression otherwise.
 * Evaluating is the platform's.
 */
internal class CodeBridgeEvaluator(private val delegate: XDebuggerEvaluator) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        delegate.evaluate(expression, callback, expressionPosition)

    override fun evaluate(
        expression: XExpression,
        callback: XEvaluationCallback,
        expressionPosition: XSourcePosition?
    ) =
        delegate.evaluate(expression, callback, expressionPosition)

    override fun isCodeFragmentEvaluationSupported(): Boolean = delegate.isCodeFragmentEvaluationSupported

    override fun getExpressionRangeAtOffset(
        project: Project,
        document: Document,
        offset: Int,
        sideEffectsAllowed: Boolean
    ): TextRange? =
        delegate.getExpressionRangeAtOffset(project, document, offset, sideEffectsAllowed)

    override fun getExpressionInfoAtOffsetAsync(
        project: Project,
        document: Document,
        offset: Int,
        sideEffectsAllowed: Boolean
    ): Promise<ExpressionInfo> {
        val features = HostedLanguageFeatures.getInstance(project)
        val file = FileDocumentManager.getInstance().getFile(document)
        if (file == null || !features.hasEvaluatableProviders(file)) {
            return delegate.getExpressionInfoAtOffsetAsync(project, document, offset, sideEffectsAllowed)
        }
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line) + 1
        val result = AsyncPromise<ExpressionInfo>()
        // Off the editor thread, which the answer is waited for from the extension on.
        Scope.getInstance(project).scope.launch(Dispatchers.IO) {
            try {
                val found = features.evaluatableExpression(file, line + 1, column)
                result.setResult(found?.let { expression ->
                    ReadAction.computeBlocking<ExpressionInfo?, Throwable> {
                        val range = textRange(document, expression.range) ?: return@computeBlocking null
                        ExpressionInfo(range, expression.expression ?: document.getText(range), null)
                    }
                })
            } catch (error: Throwable) {
                result.setError(error)
            }
        }
        return result
    }

    /** Where an expression is looked for: past the hover that asked, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    /** VS Code's 1-based range, inclusive start and exclusive end column, as offsets; null when stale. */
    private fun textRange(document: Document, range: me.helvert.codebridge.exthost.protocol.payload.Range): TextRange? {
        fun offset(line: Int, column: Int): Int? {
            if (line < 1 || line > document.lineCount) return null
            val start = document.getLineStartOffset(line - 1)
            return (start + column - 1).takeIf { it in start..document.getLineEndOffset(line - 1) }
        }

        val start = offset(range.startLineNumber, range.startColumn) ?: return null
        val end = offset(range.endLineNumber, range.endColumn) ?: return null
        return if (end > start) TextRange(start, end) else null
    }
}
