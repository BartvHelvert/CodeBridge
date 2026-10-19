package me.helvert.codebridge.launch

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.dap.protocol.CompletionsArguments
import com.jetbrains.dap.protocol.DapColumn
import kotlinx.coroutines.withTimeoutOrNull
import me.helvert.codebridge.language.CodeBridgeCompletionContributor
import kotlin.time.Duration.Companion.seconds

/**
 * A debugger expression completed by the paused session's adapter, with DAP's `completions` in the
 * current frame: the adapter evaluates the expression, so it knows what `total.` can be followed by
 * in the language it evaluates, which the file's language server -- asked about a position, not an
 * expression -- cannot.
 *
 * No answer while nothing of ours is paused, or when the adapter cannot complete; the expression is
 * then completed from the file instead.
 */
internal class CodeBridgeDebugCompletions(private val project: Project) : CodeBridgeCompletionContributor.DebuggerCompletions {
    override fun complete(text: String, offset: Int): CodeBridgeCompletionContributor.Answer? {
        val session = XDebuggerManager.getInstance(project).currentSession?.takeIf { it.isSuspended } ?: return null
        val process = session.debugProcess as? CodeBridgeDebugProcess ?: return null
        val arguments = CompletionsArguments(
            frameId = (session.currentStackFrame as? DefaultDapXStackFrame)?.frame?.id,
            text = text,
            // IntelliJ's client initializes with `columnsStartAt1`.
            column = DapColumn(offset + 1),
        )
        val reply = process.dapDebugSession.postAsync { server.completions(arguments) }
        val targets = try {
            runBlockingMaybeCancellable { withTimeoutOrNull(TIMEOUT_SECONDS.seconds) { reply.await() } }
        } catch (cancelled: ProcessCanceledException) {
            reply.cancel()
            throw cancelled
        } catch (_: Exception) {
            null
        }?.targets.orEmpty()
        if (targets.isEmpty()) return null
        // `start` is where the text an item replaces begins, 1-based as the columns are.
        val start = targets.firstNotNullOfOrNull { it.start }?.let { it - 1 } ?: CodeBridgeCompletionContributor.wordStart(text, offset)
        return CodeBridgeCompletionContributor.Answer(start, targets.sortedBy { it.sortText ?: it.label }.map { target ->
            LookupElementBuilder.create(target.text ?: target.label)
                .withPresentableText(target.label)
                .withTypeText(target.detail ?: target.type?.toString()?.lowercase(), true)
        })
    }

    private companion object {
        /** A completion the user is typing through is not worth waiting long for. */
        const val TIMEOUT_SECONDS = 2L
    }
}
