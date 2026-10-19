package me.helvert.codebridge.messages

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.project.projectId
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.ClientRequestDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

internal class CodeBridgeClientRequestsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeClientRequests>().start()
}

/**
 * What extensions do on the user's machine, done here: open a web or mail link in this client's
 * browser (`env.openExternal`), and read or write this client's clipboard (`env.clipboard`).
 *
 * A web page on a site the user has not trusted is asked about first, as VS Code asks "Do you want
 * Code to open the external website?": Open, Copy -- which the extension hears as not opened, and
 * GitLens then finds the link on the clipboard -- or Cancel, and "Do not ask" for the site. Mail
 * links and this machine's own addresses open without asking, as they do in VS Code.
 *
 * Each is answered once it is done, so what the extension does next -- a sign-in's box for pasting
 * the code by hand -- comes after the question, not on top of it.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeClientRequests(private val project: Project, private val scope: CoroutineScope) {

    private val started = AtomicBoolean()
    private val shown = ConcurrentHashMap.newKeySet<Long>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Reconnects like the message stream: a request still waiting must be done after all.
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().clientRequests(project.projectId()).collect { requests ->
                        requests.filter { shown.add(it.id) }.forEach { request ->
                            // Each on its own, so a question does not hold up the stream behind it. On the
                            // editor thread, which the clipboard expects, under any modality: a device sign-in
                            // copies its code while a modal question shows it.
                            scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                                answer(request.id, request)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension request stream; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private fun answer(id: Long, request: ClientRequestDto) {
        val (done, text) = try {
            when (request.kind) {
                ClientRequestDto.BROWSE -> browse(request.value.orEmpty()) to null
                ClientRequestDto.READ_CLIPBOARD -> true to CopyPasteManager.getInstance()
                    .getContents<String>(DataFlavor.stringFlavor)

                ClientRequestDto.WRITE_CLIPBOARD -> {
                    @Suppress("UsePropertyAccessSyntax") // The getter is nullable, so the property is a val.
                    CopyPasteManager.getInstance().setContents(StringSelection(request.value.orEmpty()))
                    true to null
                }

                else -> false to null
            }
        } catch (failure: Exception) {
            LOG.warn("Could not do what an extension asked (${request.kind})", failure)
            false to null
        }
        scope.launch {
            runCatching { CodeBridgeApi.getInstance().answerClientRequest(project.projectId(), id, done, text) }
                .onFailure {
                    if (it is CancellationException) throw it else LOG.warn(
                        "Could not answer request $id",
                        it
                    )
                }
        }
    }

    private fun browse(url: String): Boolean {
        if (project.isDisposed) return false
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (uri?.scheme.equals("mailto", ignoreCase = true) || host != null && trusted(host)) {
            BrowserUtil.browse(url)
            return true
        }
        // Over the project's window: given the project alone, the dialog can land at the screen's corner.
        val frame = WindowManager.getInstance().getFrame(project)?.rootPane
        val choice =
            MessageDialogBuilder.Message("Open External Website", "Do you want to open the external website?\n\n$url")
                .buttons(OPEN, COPY, CANCEL)
                .defaultButton(OPEN)
                .focusedButton(OPEN)
                .asWarning()
                .doNotAsk(host?.let { trustOption(it) })
                .show(if (frame == null) project else null, frame)
        return when (choice) {
            OPEN -> {
                BrowserUtil.browse(url)
                true
            }

            COPY -> {
                @Suppress("UsePropertyAccessSyntax") // The getter is nullable, so the property is a val.
                CopyPasteManager.getInstance().setContents(StringSelection(url))
                false
            }

            else -> false
        }
    }

    /** "Do not ask for '[host]'", remembered only when the page is opened. */
    private fun trustOption(host: String) = object : DoNotAskOption.Adapter() {
        override fun getDoNotShowMessage() = "Do not ask for '$host'"
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
            if (isSelected && exitCode == 0) trust(host)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeClientRequests::class.java)
        private const val RETRY_MILLIS = 2_000L
        private const val OPEN = "Open"
        private const val COPY = "Copy"
        private const val CANCEL = "Cancel"

        /** The sites the user said not to ask about again, for every project. */
        private const val TRUSTED_KEY = "codebridge.trustedLinkHosts"

        /** This machine: a sign-in's own loopback server, which VS Code trusts too. */
        private val LOCAL = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        fun trusted(host: String): Boolean =
            host in LOCAL || host in PropertiesComponent.getInstance().getList(TRUSTED_KEY).orEmpty()

        private fun trust(host: String) {
            val properties = PropertiesComponent.getInstance()
            properties.setList(TRUSTED_KEY, (properties.getList(TRUSTED_KEY).orEmpty() + host).distinct())
        }
    }
}
