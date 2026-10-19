package me.helvert.codebridge.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.DebugService
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.launch.CodeBridgeBreakpointProperties
import me.helvert.codebridge.launch.CodeBridgeLineBreakpointType
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The IDE's VS Code line breakpoints as the extension host's `vscode.debug.breakpoints`.
 *
 * VS Code keeps its breakpoints in the workbench and tells the host about every change; extensions
 * read them, and ask through `getDebugProtocolBreakpoint` what an adapter made of one. Here the
 * breakpoints are IntelliJ's, set on the adapter by IntelliJ's DAP client, so the host is told of
 * them from the breakpoint manager, and what the adapter made of each is read from the DAP messages
 * as they pass; see [DapBreakpoints].
 */
@Service(Service.Level.PROJECT)
class HostedBreakpoints(private val project: Project, private val scope: CoroutineScope) : Disposable {

    @Volatile private var callHost: (suspend (String, String) -> String)? = null

    /** What each session's adapter reported; fed by the debug adapters' messages. */
    val adapters = DapBreakpoints()

    init {
        project.messageBus.connect(this).subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
                ours(breakpoint)?.let { delta(added = listOf(it)) }
            }

            override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
                val id = ours(breakpoint)?.let(::properties)?.hostId?.takeIf { it.isNotEmpty() } ?: return
                delta(removed = listOf(id))
            }

            override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
                ours(breakpoint)?.let { delta(changed = listOf(it)) }
            }
        })
    }

    /** Tells a host that has just started about every breakpoint there is. */
    fun hostStarted(callHost: suspend (String, String) -> String) {
        this.callHost = callHost
        val all = all()
        // Two breakpoints with one id -- one copied from another -- would be one breakpoint to the host.
        val seen = mutableSetOf<String>()
        all.mapNotNull(::properties).forEach { if (it.hostId.isNotEmpty() && !seen.add(it.hostId)) it.hostId = "" }
        if (all.isNotEmpty()) delta(added = all)
    }

    fun hostStopped() {
        callHost = null
        adapters.clear()
    }

    /**
     * `$getDebugProtocolBreakpoint(sessionId, breakpointId)`: the DAP breakpoint the session's adapter
     * reported for that breakpoint, as JSON; null when there is none -- the host reads that as undefined.
     */
    fun protocolBreakpoint(argsJson: String): String? {
        val args = Args.of(DebugService.GET_PROTOCOL_BREAKPOINT, argsJson)
        val sessionId = args.str(0)
        val id = args.str(1)
        val breakpoint = all().firstOrNull { properties(it)?.hostId == id } ?: return null
        val file = path(breakpoint) ?: return null
        return adapters.find(sessionId, file, breakpoint.line)?.toString()
    }

    private fun all(): List<XLineBreakpoint<*>> = ReadAction.computeBlocking<List<XLineBreakpoint<*>>, RuntimeException> {
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.mapNotNull(::ours)
    }

    private fun properties(breakpoint: XLineBreakpoint<*>) = breakpoint.properties as? CodeBridgeBreakpointProperties

    /** [breakpoint] if it is a VS Code line breakpoint, the only kind the host is told of. */
    private fun ours(breakpoint: XBreakpoint<*>): XLineBreakpoint<*>? =
        (breakpoint as? XLineBreakpoint<*>)?.takeIf { it.type is CodeBridgeLineBreakpointType }

    private fun path(breakpoint: XLineBreakpoint<*>): Path? =
        runCatching { Path.of(VfsUtilCore.urlToPath(breakpoint.fileUrl)) }.getOrNull()

    private fun delta(
        added: List<XLineBreakpoint<*>> = emptyList(),
        removed: List<String> = emptyList(),
        changed: List<XLineBreakpoint<*>> = emptyList(),
    ) {
        val host = callHost ?: return
        val payload = buildJsonObject {
            if (added.isNotEmpty()) put("added", JsonArray(added.mapNotNull(::dto)))
            if (removed.isNotEmpty()) put("removed", JsonArray(removed.map(::JsonPrimitive)))
            if (changed.isNotEmpty()) put("changed", JsonArray(changed.mapNotNull(::dto)))
        }
        val args = buildJsonArray { add(payload) }.toString()
        // Sent now, in order with the next change; the answer is only looked at for a failure.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                host(DebugService.ACCEPT_BREAKPOINTS_DELTA, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Breakpoint delta failed", failure)
            }
        }
    }

    /** VS Code's `ISourceBreakpointDto`: where it is, 0-based, and what the user set on it. */
    private fun dto(breakpoint: XLineBreakpoint<*>): JsonObject? {
        val file = path(breakpoint) ?: return null
        val properties = properties(breakpoint) ?: return null
        if (properties.hostId.isEmpty()) properties.hostId = UUID.randomUUID().toString()
        return buildJsonObject {
            put("type", "source")
            put("id", properties.hostId)
            put("enabled", breakpoint.isEnabled)
            breakpoint.conditionExpression?.expression?.takeIf { it.isNotBlank() }?.let { put("condition", it) }
            breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }?.let { put("logMessage", it) }
            put("uri", UriComponents.of(file).toJson())
            put("line", breakpoint.line)
            put("character", 0)
        }
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = Logger.getInstance(HostedBreakpoints::class.java)

        fun getInstance(project: Project): HostedBreakpoints = project.service()
    }
}
