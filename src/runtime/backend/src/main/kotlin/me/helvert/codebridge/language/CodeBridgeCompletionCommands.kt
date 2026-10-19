package me.helvert.codebridge.language

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.helvert.codebridge.completion.CodeBridgeCompletionCommandRunner
import me.helvert.codebridge.exthost.protocol.payload.CodeActions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The commands of completion items on offer, kept here while the client holds only their ids.
 *
 * An item with a command is inserted by the client, as any other item whose text it can place:
 * a backend insert handler runs a round trip later, and the filter text is drawn first and then
 * replaced. Nothing on the backend hears that the item was chosen -- the platform skips the insert
 * there -- so the client's handler asks for the command by id, through
 * [me.helvert.codebridge.rpc.CodeBridgeApi.runCompletionCommand].
 */
@Service(Service.Level.PROJECT)
class CodeBridgeCompletionCommands(private val project: Project, private val scope: CoroutineScope) {

    private class Pending(
        val command: CodeActions.Command, val document: Document, val features: HostedLanguageFeatures, val at: Long,
    )

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val next = AtomicLong()

    /**
     * Keeps [command], to be run in [document] if its item is chosen, and answers its id.
     *
     * Those of lists shown longer ago than the host keeps a list's commands are let go: the
     * extension has released what they refer to by then.
     */
    fun register(command: CodeActions.Command, document: Document, features: HostedLanguageFeatures): Long {
        val now = System.nanoTime()
        pending.values.removeIf { now - it.at > KEEP_NANOS }
        return next.incrementAndGet().also { pending[it] = Pending(command, document, features, now) }
    }

    /**
     * Runs command [id] once the document reads as the client left it, which the extension then
     * sees too. Waits a little for the client's edit to arrive, since it travels apart from this
     * call, and runs the command anyway after that: a document typed into meanwhile never matches.
     */
    suspend fun run(id: Long, length: Int, hash: Int) {
        val chosen = pending.remove(id) ?: return
        val arrived = withTimeoutOrNull(SYNC_MILLIS.milliseconds) {
            while (!readAction { chosen.document.textLength == length && chosen.document.text.hashCode() == hash }) {
                delay(POLL_MILLIS.milliseconds)
            }
        }
        if (arrived == null) LOG.info("The client's insertion of a completion had not arrived after ${SYNC_MILLIS}ms; running its command anyway")
        project.service<HostedLanguageServers>().afterSync(chosen.document) {
            chosen.features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    chosen.features.executeCommand(chosen.command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.warn("Could not run the command of a completion", failure)
                }
            }
        }
    }

    /** [run], from a caller that cannot wait for it. */
    fun request(id: Long, length: Int, hash: Int) {
        scope.launch { run(id, length, hash) }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeCompletionCommands::class.java)
        private val KEEP_NANOS = TimeUnit.SECONDS.toNanos(60)
        private const val SYNC_MILLIS = 2_000L
        private const val POLL_MILLIS = 20L

        fun getInstance(project: Project): CodeBridgeCompletionCommands = project.service()
    }
}

/** The completion commands of the backend's own process; see [CodeBridgeCompletionCommandRunner]. */
internal class LocalCompletionCommandRunner : CodeBridgeCompletionCommandRunner {
    override fun run(project: Project, id: Long, length: Int, hash: Int) {
        if (!project.isDisposed) CodeBridgeCompletionCommands.getInstance(project).request(id, length, hash)
    }
}
