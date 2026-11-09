package me.helvert.codebridge.webview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.platform.project.projectId
import com.intellij.ui.jcef.JBCefApp
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.CodeBridgeApi
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs the web worker extension hosts the backend wants, each in a browser page of its own (see
 * [WorkerHostBrowser]), and stops each when the backend no longer wants it. Reconnects, as the
 * webviews do: a backend that restarts ends the flow.
 */
internal class CodeBridgeWorkerHostsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Listened for even without the browser, which runs them: the backend times a host out from
        // the first client listening, and says why. Were this to return, it would wait for a client
        // that never comes.
        val browser = JBCefApp.isSupported()
        if (!browser) LOG.info("No browser (JCEF) to run the web worker extension hosts in")
        coroutineScope {
            val running = HashMap<String, Job>()
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().workerHosts(project.projectId()).collect { wanted ->
                        running.entries.removeIf { (id, job) -> (id !in wanted || !job.isActive).also { gone -> if (gone) job.cancel() } }
                        if (browser) for (id in wanted) if (id !in running) running[id] = launch { run(project, id) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the web worker extension hosts; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private suspend fun run(project: Project, id: String) {
        val browser = withContext(Dispatchers.EDT) { WorkerHostBrowser(project, id) }
        try {
            browser.run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LOG.warn("Could not run the web worker extension host $id", error)
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) { Disposer.dispose(browser) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeWorkerHostsActivity::class.java)
        private const val RETRY_MILLIS = 2_000L
    }
}
