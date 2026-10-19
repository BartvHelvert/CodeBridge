package me.helvert.codebridge.terminal

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.project.projectId
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.TerminalEventDto
import me.helvert.codebridge.rpc.TerminalRequestDto
import me.helvert.codebridge.rpc.CodeBridgeApi
import kotlin.time.Duration.Companion.milliseconds

/** Starts carrying out a project's extension terminals as soon as it opens. */
internal class CodeBridgeTerminalRequestsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeTerminalRequests>().start()
}

/**
 * An extension's shell terminals, as tabs of the Terminal tool window.
 *
 * The backend asks; this makes the tab, types into it, shows it and closes it, and says when it has
 * ended. A new tab does not take focus or bring the tool window forward: `window.createTerminal`
 * does not reveal a terminal until the extension calls `show()`.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeTerminalRequests(private val project: Project, private val scope: CoroutineScope) {

    /** By the backend's id. Touched only on the editor thread. */
    private val tabs = HashMap<Int, TerminalToolWindowTab>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Reconnects like the open-request stream: a backend restart ends it.
            while (isActive) {
                try {
                    CodeBridgeApi.getInstance().terminalRequests(project.projectId()).collect { request ->
                        withContext(Dispatchers.EDT) { if (!project.isDisposed) handle(request) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    LOG.debug("Lost the extension terminal stream; retrying", error)
                }
                delay(RETRY_MILLIS.milliseconds)
            }
        }
    }

    private fun handle(request: TerminalRequestDto) {
        when (request.kind) {
            TerminalRequestDto.OPEN -> open(request)
            TerminalRequestDto.TYPE -> tabs[request.id]?.view?.sendText(request.text)
            TerminalRequestDto.SHOW -> tabs[request.id]?.let { show(it, request.preserveFocus) }
            TerminalRequestDto.DISPOSE -> tabs.remove(request.id)?.let { TerminalToolWindowTabsManager.getInstance(project).closeTab(it) }
        }
    }

    private fun open(request: TerminalRequestDto) {
        val builder = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .tabName(request.name)
            .envVariables(request.env)
            .requestFocus(false)
            // As VS Code closes a terminal whose shell has exited.
            .closeOnProcessTermination(true)
            .restoreOnProjectReopen(false)
        request.cwd?.let(builder::workingDirectory)
        if (request.command.isNotEmpty()) builder.shellCommand(request.command)
        val tab = builder.createTab()
        tabs[request.id] = tab
        // Its shell ending closes the tab as well, so a closed tab is the user's doing only while
        // the shell still runs.
        Disposer.register(tab.content) {
            if (tabs.remove(request.id) != null) ended(request.id, byUser = tab.view.sessionState.value !is TerminalViewSessionState.Terminated)
        }
    }

    private fun show(tab: TerminalToolWindowTab, preserveFocus: Boolean) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        tab.content.manager?.setSelectedContent(tab.content, !preserveFocus)
        if (preserveFocus) toolWindow.show()
        else toolWindow.activate {
            IdeFocusManager.getInstance(project).requestFocus(tab.view.preferredFocusableComponent, true)
        }
    }

    private fun ended(id: Int, byUser: Boolean) {
        scope.launch {
            try {
                CodeBridgeApi.getInstance().terminalEvent(project.projectId(), TerminalEventDto(id, byUser))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not report that extension terminal $id ended", failure)
            }
        }
    }

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeTerminalRequests::class.java)
        const val RETRY_MILLIS = 2_000L
        /** The Terminal plugin's tool window. */
        const val TOOL_WINDOW_ID = "Terminal"
    }
}
