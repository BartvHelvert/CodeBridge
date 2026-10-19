package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableSharedFlow
import me.helvert.codebridge.rpc.TerminalEventDto
import me.helvert.codebridge.rpc.TerminalRequestDto

/**
 * The way to the frontend's Terminal tool window, served by `CodeBridgeApi.terminalRequests`.
 *
 * A project service, not part of [HostedTerminals], so a frontend that subscribed once keeps
 * hearing requests after the extension host restarts. Whoever handles terminals now -- the current
 * host's [HostedTerminals] -- hears what the frontend reports through [events].
 */
@Service(Service.Level.PROJECT)
class CodeBridgeToolWindowTerminals {
    val requests = MutableSharedFlow<TerminalRequestDto>(extraBufferCapacity = 256)

    @Volatile
    var events: ((TerminalEventDto) -> Unit)? = null

    /** Whether a frontend with the Terminal plugin is listening, and can be asked for a tab. */
    val connected: Boolean get() = requests.subscriptionCount.value > 0

    fun send(request: TerminalRequestDto): Boolean = requests.tryEmit(request)

    fun reported(event: TerminalEventDto) = events?.invoke(event)

    companion object {
        fun getInstance(project: Project): CodeBridgeToolWindowTerminals = project.service()
    }
}
