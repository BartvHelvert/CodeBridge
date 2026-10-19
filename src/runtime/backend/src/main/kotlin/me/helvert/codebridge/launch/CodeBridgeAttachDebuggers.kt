package me.helvert.codebridge.launch

import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.util.ui.EmptyIcon
import com.intellij.xdebugger.attach.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import me.helvert.codebridge.debug.DebugAttach
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.workbench.HostedMessages
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/**
 * VS Code debuggers in IntelliJ's Run | Attach to Process: each installed one that declares how it
 * attaches to a process id ([DebugAttach]) is offered for every local process, beside IntelliJ's own.
 * Attaching starts that configuration with the chosen id, as `vscode.debug.startDebugging` would.
 */
internal class CodeBridgeAttachDebuggerProvider : XAttachDebuggerProvider {
    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

    /**
     * Named for the debuggers it offers. The dialog lists debuggers by group -- the Available
     * Debuggers column and its filter -- and the platform's default group has no name, which left
     * them blank there.
     */
    override fun getPresentationGroup(): XAttachProcessPresentationGroup = Group

    override fun getAvailableDebuggers(
        project: Project, attachHost: XAttachHost, processInfo: ProcessInfo,
        contextHolder: UserDataHolder
    ): List<XAttachDebugger> {
        if (project.basePath == null) return emptyList()
        // Asked once per process listed; the manifests are read once per listing.
        val templates = contextHolder.getUserData(TEMPLATES)
            ?: DebugAttach.templates(ProjectExtensions.getInstance(project).enabled())
                .also { contextHolder.putUserData(TEMPLATES, it) }
        return templates.map(::Debugger)
    }

    private class Debugger(private val template: DebugAttach.Template) : XAttachDebugger {
        override fun getDebuggerDisplayName(): String = template.label

        override fun attachDebugSession(project: Project, attachHost: XAttachHost, info: ProcessInfo) {
            val configuration = DebugAttach.configuration(template, info.pid.toLong(), info.executableDisplayName)
            val args = JsonArray(listOf(JsonNull, configuration, buildJsonObject { })).toString()
            Scope.getInstance(project).scope.launch {
                val reason = try {
                    "the run did not start".takeIf { CodeBridgeStartDebugging.start(project, args).json == "false" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error.message
                }
                if (reason != null) HostedMessages.getInstance(project).notify(
                    Messages.Severity.ERROR,
                    "Could not attach ${template.label} to ${info.executableDisplayName} (${info.pid}): $reason",
                    source = "VS Code debugger"
                )
            }
        }
    }

    /** Where an attach is started from: past the dialog that asked, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    private object Group : XAttachProcessPresentationGroup {
        override fun getOrder(): Int = 100

        override fun getGroupName(): String =
            DebugAttach.templates(ExtensionInstallationService.getInstance().list()).map { it.label }.distinct()
                .joinToString(", ").ifEmpty { "VS Code" }

        override fun getItemIcon(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): Icon =
            EmptyIcon.ICON_16

        override fun getItemDisplayText(project: Project, info: ProcessInfo, dataHolder: UserDataHolder): String =
            info.executableDisplayName
    }

    private companion object {
        val TEMPLATES: Key<List<DebugAttach.Template>> = Key.create("codebridge.attach.templates")
    }
}
