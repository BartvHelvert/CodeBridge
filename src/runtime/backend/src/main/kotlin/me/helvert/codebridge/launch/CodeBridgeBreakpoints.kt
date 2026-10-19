package me.helvert.codebridge.launch

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.*
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.jetbrains.dap.protocol.ExceptionBreakpointsFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.language.matches
import me.helvert.codebridge.manifest.LanguageContribution
import java.util.concurrent.ConcurrentHashMap

/**
 * What a breakpoint carries of its own: only the id the extension host knows it by. The DAP client
 * sends its position and condition.
 *
 * Kept with the breakpoint, so the id outlives a host restart and the IDE's: extensions store
 * things by it -- CodeLLDB its caller exclusions -- as they do by VS Code's own breakpoint ids.
 */
class CodeBridgeBreakpointProperties : XBreakpointProperties<CodeBridgeBreakpointProperties>() {
    /** Given the first time the host is told of the breakpoint; empty until then. */
    @get:Attribute("hostId")
    var hostId: String = ""

    override fun getState() = this
    override fun loadState(state: CodeBridgeBreakpointProperties) {
        hostId = state.hostId
    }
}

/**
 * A line breakpoint for an extension's debugger, sent to its adapter as a DAP source breakpoint.
 *
 * It can be put where VS Code allows one: in a file of a language that an enabled extension lists
 * under `contributes.breakpoints` -- CodeLLDB does for Rust and C++, for example. The file's
 * language is the one an extension's `contributes.languages` gives its name.
 */
class CodeBridgeLineBreakpointType : XLineBreakpointType<CodeBridgeBreakpointProperties>("codebridge.line", "VS Code line") {
    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.isInLocalFileSystem && !file.isDirectory && Allowed.current().allows(file.name)

    override fun createBreakpointProperties(file: VirtualFile, line: Int) = CodeBridgeBreakpointProperties()

    /** What lets a breakpoint have a condition and a log message, both of which the adapter is sent. */
    override fun getEditorsProvider(
        breakpoint: XLineBreakpoint<CodeBridgeBreakpointProperties>,
        project: Project
    ): XDebuggerEditorsProvider = CodeBridgeExpressionEditors()

    override fun getDisplayText(breakpoint: XLineBreakpoint<CodeBridgeBreakpointProperties>): String {
        val position = breakpoint.sourcePosition
        return if (position == null) "VS Code breakpoint" else "${position.file.name}:${position.line + 1}"
    }

    /** What the enabled extensions declare, read again only when the installed set changes. */
    private class Allowed(val revision: Long, private val languages: List<LanguageContribution>) {
        fun allows(name: String): Boolean = languages.any { it.matches(name) }

        companion object {
            @Volatile
            private var cached: Allowed? = null

            fun current(): Allowed {
                val service = ExtensionInstallationService.getInstance()
                val revision = service.changes.value
                cached?.takeIf { it.revision == revision }?.let { return it }
                val enabled = service.list().filter { it.enabled }
                val breakpoints = enabled.flatMap { it.manifest.contributes.breakpointLanguages }.toSet()
                val languages = enabled.flatMap { it.manifest.contributes.languages }.filter { it.id in breakpoints }
                return Allowed(revision, languages).also { cached = it }
            }
        }
    }
}

/**
 * One exception filter of one debugger -- CodeLLDB's "Rust: on panic", say -- as VS Code's
 * Breakpoints view lists them: a breakpoint per filter, made when a session first reports it,
 * enabled when the adapter says the filter is on by default, and switched on or off from then on.
 */
class CodeBridgeExceptionProperties(
    @get:Attribute("debugger") var debugger: String = "",
    @get:Attribute("filter") var filter: String = "",
    @get:Attribute("label") var label: String = "",
) : XBreakpointProperties<CodeBridgeExceptionProperties>() {
    override fun getState() = this
    override fun loadState(state: CodeBridgeExceptionProperties) {
        debugger = state.debugger; filter = state.filter; label = state.label
    }
}

class CodeBridgeExceptionBreakpointType :
    XBreakpointType<XBreakpoint<CodeBridgeExceptionProperties>, CodeBridgeExceptionProperties>(
        "codebridge.exception",
        "VS Code exceptions"
    ) {
    override fun getDisplayText(breakpoint: XBreakpoint<CodeBridgeExceptionProperties>): String =
        breakpoint.properties.label.ifEmpty { breakpoint.properties.filter } + " (${breakpoint.properties.debugger})"

    override fun createProperties() = CodeBridgeExceptionProperties()

    /** Filters that support it take a condition, which the adapter evaluates. */
    override fun getEditorsProvider(
        breakpoint: XBreakpoint<CodeBridgeExceptionProperties>,
        project: Project
    ): XDebuggerEditorsProvider = CodeBridgeExpressionEditors()

    companion object {
        /**
         * Lists [filters] for [debugger], each once. Called with every set of capabilities a
         * session reports, so a debugger's filters appear the first time it runs.
         */
        fun learned(project: Project, debugger: String, filters: List<ExceptionBreakpointsFilter>) {
            if (filters.isEmpty()) return
            Scope.getInstance(project).scope.launch(Dispatchers.EDT) {
                val type = XDebuggerUtil.getInstance().findBreakpointType(CodeBridgeExceptionBreakpointType::class.java)
                val manager = XDebuggerManager.getInstance(project).breakpointManager
                val known = manager.getBreakpoints(type).map { it.properties.debugger to it.properties.filter }.toSet()
                WriteAction.run<RuntimeException> {
                    for ((id, label, _, default) in filters.filter { (debugger to it.filter) !in known }
                        .distinctBy { it.filter }) {
                        manager.addBreakpoint(
                            type,
                            CodeBridgeExceptionProperties(debugger, id, label)
                        )
                            .isEnabled = default == true
                    }
                }
            }
        }
    }

    /** Where learned filters are listed: on the editor thread, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }
}

/**
 * Sends a session's enabled exception breakpoints to its adapter, as `setExceptionBreakpoints`
 * filters. The platform's DAP process registers a handler for line breakpoints only.
 */
internal class CodeBridgeExceptionBreakpointHandler(private val session: DapDebugSession, private val debugger: String?) :
    XBreakpointHandler<XBreakpoint<CodeBridgeExceptionProperties>>(CodeBridgeExceptionBreakpointType::class.java) {
    private val sent = ConcurrentHashMap<XBreakpoint<CodeBridgeExceptionProperties>, DapExceptionBreakpoint>()

    override fun registerBreakpoint(breakpoint: XBreakpoint<CodeBridgeExceptionProperties>) {
        // A filter belongs to the debugger that reported it; another adapter does not know it.
        if (breakpoint.properties.debugger != debugger) return
        val exception = DapExceptionBreakpoint.create(
            breakpoint.properties.filter,
            breakpoint.conditionExpression?.expression,
            breakpoint
        )
        sent.put(breakpoint, exception)?.let(::remove)
        session.post { with(session.breakpointManager) { addExceptionBreakpoint(exception) } }
    }

    override fun unregisterBreakpoint(breakpoint: XBreakpoint<CodeBridgeExceptionProperties>, temporary: Boolean) {
        sent.remove(breakpoint)?.let(::remove)
    }

    private fun remove(exception: DapExceptionBreakpoint) =
        session.post { with(session.breakpointManager) { removeExceptionBreakpoint(exception) } }
}
