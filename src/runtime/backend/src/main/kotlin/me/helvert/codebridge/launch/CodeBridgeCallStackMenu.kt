package me.helvert.codebridge.launch

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XStackFrame
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.rpc.TreeActionDto
import me.helvert.codebridge.workbench.*
import kotlin.time.Duration.Companion.seconds

/**
 * What extensions add to VS Code's Call Stack right-click menu (`debug/callstack/context`), in the
 * right-click menu of IntelliJ's frames list: CodeLLDB's Exclude Caller.
 *
 * Right-clicking a frame selects it, so the frame is the session's current one, and the DAP frame
 * behind it is the backend's own. (The platform's id for a client-side frame would name it exactly,
 * but the module it lives in is internal to the platform.) The command is given
 * what VS Code gives it: the frame's source, then `{sessionId, threadId, frameId, frameName}`, whose
 * `frameId` VS Code spells `stackframe:thread:<session>:<threadId>:<index>:<source>`.
 */
internal object CodeBridgeCallStackMenu {

    private val LOG = Logger.getInstance(CodeBridgeCallStackMenu::class.java)
    private const val MENU = "debug/callstack/context"

    /** The current debug session, if it is one of ours, and its current frame. */
    private class Target(val session: XDebugSession, val process: CodeBridgeDebugProcess, val frame: XStackFrame?)

    private fun target(project: Project): Target? {
        val session = XDebuggerManager.getInstance(project).currentSession ?: return null
        val process = session.debugProcess as? CodeBridgeDebugProcess ?: return null
        return Target(session, process, session.currentStackFrame)
    }

    /**
     * The keys VS Code sets while its Call Stack menu is open on a frame, then the extensions' own.
     * `focusedView` is the Call Stack's by definition here: CodeLLDB enables Exclude Caller only there.
     */
    private fun context(project: Project, target: Target): (String) -> JsonElement? =
        HostedContextKeys.getInstance(project).lookup { key ->
            when (key) {
                "debugType" -> target.process.debugger?.let(::JsonPrimitive)
                "inDebugMode" -> JsonPrimitive(true)
                "callStackItemType" -> if (target.frame != null) JsonPrimitive("stackFrame") else null
                "focusedView" -> JsonPrimitive("workbench.debug.callStackView")
                else -> null
            }
        }

    private fun entries(project: Project) =
        ExtensionMenus.entries(
            MENU,
            image = { extension, value -> HostedTreeViews.getInstance(project).image(extension, value) })

    /** The entries offered for the current frame, in menu order. */
    fun actions(project: Project): List<TreeActionDto> {
        val target = target(project) ?: return emptyList()
        val context = context(project, target)
        return ExtensionMenus.offered(entries(project), context).map { it.toDto(context) }
    }

    /** Runs entry [actionId] on the current frame, if it is offered there now. */
    suspend fun run(project: Project, actionId: Int) {
        val target = target(project) ?: return
        val context = context(project, target)
        val entry = ExtensionMenus.offered(entries(project), context).flatMap { it.flatten() }
            .firstOrNull { it.id == actionId && !it.submenu } ?: return
        if (!entry.enabled(context)) return
        val frame = target.frame as? DefaultDapXStackFrame
        val args = buildJsonArray {
            add(entry.command)
            if (frame != null) {
                add(frame.frame.source?.url.orEmpty())
                add(describe(target, frame))
            }
        }
        val failure = HostedTreeViews.getInstance(project).execute(args)
        if (failure != null) {
            LOG.warn("Call stack command ${entry.command} failed", failure)
            if (!project.isDisposed) HostedMessages.getInstance(project).notify(
                Messages.Severity.ERROR,
                "'${entry.title}' failed: ${HostedStatusBar.reason(failure)}",
                source = target.session.sessionName,
            )
        }
    }

    /** VS Code's description of a call stack frame, which is what `debug/callstack/context` commands read. */
    private suspend fun describe(target: Target, frame: DefaultDapXStackFrame) = buildJsonObject {
        val threadId = frame.thread.id.id
        // The frame's place in its thread's stack, which is how an adapter such as CodeLLDB names it.
        val index = withTimeout(5.seconds) {
            frame.executor.withSession { with(frame.thread) { stackFrames() } }
        }.indexOfFirst { it.id == frame.frame.id }
        // Colon-free: an extension takes the id apart at its colons.
        val session = Integer.toHexString(System.identityHashCode(target.session))
        val thread = "thread:$session:$threadId"
        put("sessionId", session)
        put("threadId", thread)
        put("frameId", "stackframe:$thread:$index:${frame.frame.source?.name.orEmpty()}")
        put("frameName", frame.frame.name)
    }
}
