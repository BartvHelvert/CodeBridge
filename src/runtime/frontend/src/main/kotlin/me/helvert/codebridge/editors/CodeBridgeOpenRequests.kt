package me.helvert.codebridge.editors

import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.rpc.OpenFileDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.util.concurrent.atomic.AtomicBoolean

/** Starts opening the files a project's extensions ask to show, as soon as it opens. */
internal class CodeBridgeOpenRequestsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeOpenRequests>().start()
}

/**
 * `window.showTextDocument` for a file that is not open yet, opened where the user is.
 *
 * In the frontend for the same reason messages are: the backend's own editors are not the ones on
 * screen. An editor the backend opened for itself was closed again at once, and that close withdrew
 * the document from the extension host mid-edit. The backend waits for the editor opened here to be
 * reported to the host before answering the extension.
 *
 * A split-mode backend no longer sends these: it navigates as the connected client instead, which
 * also reaches files this side has never seen. See `CodeBridgeTextEditors.reveal`.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeOpenRequests(private val project: Project, private val scope: CoroutineScope) {

    private val started = AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Reconnects like the message stream: a backend restart ends it.
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().openRequests(project.projectId()).collect { request ->
                        withContext(Dispatchers.EDT) { open(request) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension open-request stream; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private fun open(request: OpenFileDto) {
        if (project.isDisposed) return
        val file = request.file.virtualFile() ?: return LOG.info("An extension asked to show a file this client cannot find")
        OpenFileDescriptor(project, file, request.line, request.column).navigate(request.focus)
    }

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeOpenRequests::class.java)
        const val RETRY_MILLIS = 2_000L
    }
}
