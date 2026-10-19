package me.helvert.codebridge.icons

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** Starts fetching the icons extensions contribute as soon as a project opens. */
internal class CodeBridgeContributedIconsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = service<CodeBridgeContributedIcons>().start()
}

/**
 * Keeps this side's [ContributedIcons] to what the backend's enabled extensions contribute, fetched
 * again whenever extensions are installed, removed, enabled or disabled.
 *
 * Icons named before they are here are drawn as the default icon and look themselves up when
 * painted, so once they arrive every window is repainted to show them.
 */
@Service(Service.Level.APP)
internal class CodeBridgeContributedIcons(private val scope: CoroutineScope) {

    private val started = AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Reconnects like the status bar: a backend restart ends the stream.
            while (isActive) {
                try {
                    val api = CodeBridgeApi.getInstance()
                    api.extensionChanges().collect {
                        if (ContributedIcons.replace(api.contributedIcons())) {
                            withContext(Dispatchers.EDT) { Window.getWindows().forEach(Window::repaint) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension change stream; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeContributedIcons::class.java)
        private const val RETRY_MILLIS = 2_000L
    }
}
