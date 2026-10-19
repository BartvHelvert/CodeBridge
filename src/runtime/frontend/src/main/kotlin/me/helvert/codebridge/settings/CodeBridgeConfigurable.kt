package me.helvert.codebridge.settings

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.rpc.BackendRequests
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Reports which extension host CodeBridge is running on.
 *
 * Nothing here is a preference. The runtime is a pinned VSCodium build that ships inside the
 * plugin, and letting someone substitute another one only ever produced the failure that is hardest
 * to diagnose -- extensions running against an API version nobody chose. So this page answers the
 * two questions worth asking about it: which one is running, and where it is.
 *
 * Below that, a way to the one thing here that is a preference: VS Code's user settings, which
 * are a file -- as in VS Code -- rather than options on this page.
 *
 * The extensions themselves live on a separate page nested under the IDE's Plugins settings, since
 * that is where people look for anything install-shaped.
 */
class CodeBridgeConfigurable : SearchableConfigurable {
    private var panel: RuntimePanel? = null

    override fun getId(): String = "me.helvert.codebridge"
    override fun getDisplayName(): String = "CodeBridge"
    override fun createComponent(): JComponent = panel ?: RuntimePanel().also {
        panel = it
        it.reset()
    }

    override fun isModified(): Boolean = false
    override fun apply() = Unit
    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel?.let(Disposer::dispose)
        panel = null
    }
}

private class RuntimePanel : JPanel(BorderLayout()), Disposable {

    /** Where the settings file opens: once the Settings dialog has closed, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }
    private val state = JBLabel()
    private val location = JBLabel()
    private var disposed = false
    private var statusVersion = 0
    private val requests = BackendRequests()

    init {
        border = JBUI.Borders.empty(8)
        add(content(), BorderLayout.NORTH)
    }

    private fun content(): JComponent {
        // VerticalLayout stretches each row and asks it for its own height. A BoxLayout needs a
        // maximumSize per row, and freezing that at construction -- while the status labels are
        // still empty, and so zero-height -- collapses them to nothing.
        val column = JPanel(VerticalLayout(JBUI.scale(2), VerticalLayout.FILL))
        column.add(TitledSeparator("Extension Host"))
        column.add(state)
        location.border = JBUI.Borders.empty(6, 0, 10, 0)
        location.setCopyable(true)
        column.add(location)
        column.add(TitledSeparator("Settings"))
        column.add(userSettingsLink())
        return column
    }

    /**
     * VS Code's user settings, which extensions in every project read; see [OpenUserSettingsAction].
     *
     * Closes this dialog first, with OK as a click there would: the editor opens behind a modal
     * dialog, where nobody would see it.
     */
    private fun userSettingsLink(): JComponent {
        val link = ActionLink("Open VS Code user settings (JSON)") { event ->
            val source = event.source as JComponent
            val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(source))
                ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return@ActionLink
            DialogWrapper.findInstance(source)?.close(DialogWrapper.OK_EXIT_CODE)
            Scope.getInstance(project).scope.launch(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                OpenUserSettingsAction.open(project)
            }
        }
        if (ProjectManager.getInstance().openProjects.isEmpty()) {
            link.isEnabled = false
            link.toolTipText = "Open a project first: the file opens in its editor"
        }
        return link
    }

    /**
     * Two lines: which runtime applies, and where it lives.
     *
     * A third line saying it is present added nothing -- the state line already names it and the
     * location line already shows it.
     */
    fun reset() {
        if (disposed) return
        val version = ++statusVersion
        state.icon = null
        state.text = "Checking the extension host…"
        location.text = ""
        requests.request({ runtimeStatus() }, { status ->
            if (!disposed && version == statusVersion) {
                state.icon = when {
                    !status.supported -> AllIcons.General.Error
                    status.source == "ENVIRONMENT" && !status.usable -> AllIcons.General.Error
                    status.installed || status.usable -> AllIcons.General.InspectionsOK
                    else -> AllIcons.General.Error
                }
                state.text = when {
                    !status.supported -> status.unsupportedMessage
                    // Developer override. Worth naming, or a runtime nobody remembers setting looks
                    // like the plugin ignoring its own runtime. Its own path is in the diagnosis.
                    status.source == "ENVIRONMENT" -> "Using the environment override: " + status.diagnosis
                    status.installed -> "${status.name} ${status.version}"
                    // A supported platform always gets a runtime in its plugin variant, so this is
                    // a damaged or hand-assembled installation.
                    else -> status.diagnosis
                }
                location.text = when {
                    !status.installed || status.source == "ENVIRONMENT" -> ""
                    else -> "Stored in: ${status.directory}"
                }
            }
        }, { error ->
            if (version == statusVersion) {
                state.icon = AllIcons.General.Error
                state.text = "Could not check the extension host: ${error.message}"
            }
        })
    }

    override fun dispose() {
        disposed = true; requests.dispose()
    }
}
