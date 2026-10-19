package me.helvert.codebridge.completion

import com.intellij.codeInsight.completion.FrontendFriendlyInsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import me.helvert.codebridge.rpc.CodeBridgeApi

/**
 * Inserts an item as [insert] does -- or leaves the filter text, when that is all it inserts --
 * and then has the backend run the item's command, which it keeps as [command].
 *
 * Frontend-friendly for the reason [CodeBridgeInsertHandler] is. The command, which reaches into the
 * extension host, is asked for once the insertion is over, along with what the document then
 * holds, so the backend can wait for that edit to reach it before the extension is told.
 */
@Serializable
class CodeBridgeCommandInsertHandler(private val insert: CodeBridgeInsertHandler?, private val command: Long) : FrontendFriendlyInsertHandler {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        insert?.handleInsert(context, item)
        service<CodeBridgeCompletionCommandRequests>().send(context.project, context.document, command)
    }
}

/**
 * Runs completion commands where the backend is: the backend registers this as an application
 * service, so it exists in the IDE's own process and not in a split-mode client.
 */
interface CodeBridgeCompletionCommandRunner {
    /** As [CodeBridgeApi.runCompletionCommand]. */
    fun run(project: Project, id: Long, length: Int, hash: Int)
}

/** Asks the backend for completion commands, from outside the insertion that chose them. */
@Service
internal class CodeBridgeCompletionCommandRequests(private val scope: CoroutineScope) {

    fun send(project: Project, document: Document, id: Long) {
        // After the lookup finishes, which may still type the character the item was chosen with.
        scope.launch(Dispatchers.EDT) {
            val text = document.immutableCharSequence.toString()
            val local = ApplicationManager.getApplication().getService(CodeBridgeCompletionCommandRunner::class.java)
            if (local != null) return@launch local.run(project, id, text.length, text.hashCode())
            val projectId = project.projectId()
            withContext(Dispatchers.Default) {
                try {
                    CodeBridgeApi.getInstance().runCompletionCommand(projectId, id, text.length, text.hashCode())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.warn("Could not ask for the command of completion $id", failure)
                }
            }
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeCompletionCommandRequests::class.java)
    }
}
