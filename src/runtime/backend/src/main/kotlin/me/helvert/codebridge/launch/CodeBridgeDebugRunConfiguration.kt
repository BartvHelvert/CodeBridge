package me.helvert.codebridge.launch

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.FormBuilder
import kotlinx.serialization.json.*
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.exthost.protocol.payload.CodeActions
import org.jdom.Element
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/** Where a debug configuration comes from, which decides what it is each time it runs. */
sealed interface DebugSource {
    /** What the run is called. */
    val name: String

    /** One configuration, started as one debug session. */
    sealed interface Single : DebugSource {
        /** The configuration to start, as it stands now. */
        fun configuration(workspace: Path): JsonObject
    }

    /**
     * An entry of `launch.json`, by name: read again on every run. [folder] is the workspace folder
     * whose `launch.json` it is in a multi-root workspace; null is the workspace's own, see [DebugLaunch].
     */
    data class LaunchJson(override val name: String, val folder: String? = null) : Single {
        override fun configuration(workspace: Path): JsonObject = DebugLaunch.configuration(workspace, name, folder)
    }

    /** A configuration an extension wrote and started with `vscode.debug.startDebugging`, kept as it was. */
    data class Extension(val configuration: JsonObject) : Single {
        override val name: String get() = (configuration["name"] as? JsonPrimitive)?.contentOrNull ?: "VS Code launch"
        override fun configuration(workspace: Path): JsonObject = configuration
    }

    /** A `compounds` entry of `launch.json`, by name and [folder] as for [LaunchJson]: its configurations, started together. */
    data class Compound(override val name: String, val folder: String? = null) : DebugSource

    /**
     * An extension's command -- a code lens's Run -- that started a debug session: run again, so the
     * extension makes its configuration afresh. See [CodeBridgeDebugOrigins]. [name] is what its session was called.
     */
    data class Command(override val name: String, val command: CodeActions.Command) : DebugSource

    companion object {
        /** Whether [source] is a configuration whose `request` is `attach`; false when it cannot be read. */
        fun attaches(source: DebugSource?, workspace: Path?): Boolean {
            val single = source as? Single ?: return false
            val configuration = runCatching { single.configuration(workspace ?: return false) }.getOrNull() ?: return false
            return (configuration["request"] as? JsonPrimitive)?.contentOrNull == "attach"
        }
    }
}

class CodeBridgeDebugConfigurationType : ConfigurationTypeBase(
    ID, "VS Code launch configuration", "Debug configurations from .vscode/launch.json, and those VS Code extensions start",
    AllIcons.RunConfigurations.Application,
) {
    private val launch = object : ConfigurationFactory(this) {
        // Matches the name-based id the platform used before, so stored run configurations keep loading.
        override fun getId(): String = FACTORY_ID
        override fun createTemplateConfiguration(project: Project): RunConfiguration = CodeBridgeDebugRunConfiguration(project, this)
    }

    /**
     * Makes [CodeBridgeAttachRunConfiguration]s, kept by a factory of their own so that one stored loads
     * as one again. Not offered in the New menu: the launch factory's configuration names any entry.
     */
    private val attach = object : ConfigurationFactory(this) {
        override fun getId(): String = ATTACH_FACTORY_ID
        override fun getName(): String = "Attach"
        override fun isApplicable(project: Project): Boolean = false
        override fun createTemplateConfiguration(project: Project): RunConfiguration = CodeBridgeAttachRunConfiguration(project, this)
    }

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(launch, attach)

    /** The factory for a configuration that [attaches] to something already running, or not. */
    fun factory(attaches: Boolean): ConfigurationFactory = if (attaches) attach else launch

    companion object {
        const val ID = "CodeBridge.LaunchJson"
        const val FACTORY_ID = "VS Code launch.json"
        const val ATTACH_FACTORY_ID = "VS Code launch.json attach"
    }
}

/**
 * A VS Code debug configuration as a run configuration, by its [source]. Run and Debug both start
 * the extension's debug adapter; Run is VS Code's Run Without Debugging.
 *
 * It has no Before launch steps of IntelliJ's: what runs first is the entry's `preLaunchTask`, which
 * `launch.json` sets and the editor shows.
 */
open class CodeBridgeDebugRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
) : RunConfigurationBase<Any>(project, factory, "Launch.json"), WithoutOwnBeforeRunSteps {
    var source: DebugSource? = null

    // Dropped, so that none stored before, or set on the template, runs unseen.
    override fun getBeforeRunTasks(): List<BeforeRunTask<*>> = emptyList()
    override fun setBeforeRunTasks(value: List<BeforeRunTask<*>>) = Unit

    /**
     * Made by [CodeBridgeLaunchConfigurations] for an entry of `launch.json`, and kept as that entry says:
     * edited as a form of the entry, which writes the file, and put back as the file has it if it is
     * changed or removed any other way.
     */
    var managed: Boolean = false

    /**
     * What the form of a [managed] one changed in its entry, for [CodeBridgeLaunchConfigurations] to write
     * to the file once the dialog applies it; see [LaunchEntryEditor]. Written out, so that the dialog
     * sees the form changed something, and never read back: once applied it is in the file.
     */
    var pending: EntryChanges? = null

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        if (managed && (source is DebugSource.LaunchJson || source is DebugSource.Compound)) LaunchEntryEditor(this) else Editor()

    /**
     * Starts nothing itself: the DAP runner starts the adapter, and with no execution result of
     * ours it makes the console and process handler that follow the adapter's session.
     */
    override fun getState(executor: com.intellij.execution.Executor, environment: com.intellij.execution.runners.ExecutionEnvironment): RunProfileState {
        // One made by hand for an attach entry still offers Run; it would attach and do nothing.
        if (executor.id == DefaultRunExecutor.EXECUTOR_ID && attaches()) {
            throw ExecutionException("'$name' attaches to a running program, which only makes sense with Debug")
        }
        return RunProfileState { _, _ -> null }
    }

    /**
     * Whether it attaches to something already running. Run is not offered for one: without
     * debugging, an adapter attaches and does nothing else -- CodeLLDB sets no breakpoint -- so
     * the run would only look as if it had worked.
     */
    fun attaches(): Boolean = DebugSource.attaches(source, workspace())

    override fun checkConfiguration() {
        val workspace = workspace() ?: throw RuntimeConfigurationException("The project has no workspace directory")
        val source = source ?: throw RuntimeConfigurationException("Choose a launch configuration")
        try {
            when (source) {
                is DebugSource.Single -> DebugLaunch.validate(source.configuration(workspace))
                is DebugSource.Compound -> DebugLaunch.compound(workspace, source.name, source.folder).members
                    .forEach { DebugLaunch.validate(DebugLaunch.configuration(workspace, it.name, it.folder ?: source.folder)) }
                // What it starts is the extension's to check, when it runs.
                is DebugSource.Command -> Unit
            }
        } catch (error: IllegalArgumentException) { throw RuntimeConfigurationException(error.message ?: "Invalid debug configuration", error) }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        // Written this way since before sources had a name, and still read back the same way.
        val inline = element.getChildText("configuration")?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val folder = element.getAttributeValue(FOLDER)?.takeIf { it.isNotEmpty() }
        source = inline?.let(DebugSource::Extension)
            ?: element.getAttributeValue("launchConfiguration")?.takeIf { it.isNotEmpty() }?.let { DebugSource.LaunchJson(it, folder) }
            ?: element.getAttributeValue("launchCompound")?.takeIf { it.isNotEmpty() }?.let { DebugSource.Compound(it, folder) }
                    ?: element.getChild(COMMAND)?.let(::readCommand)
        managed = element.getAttributeValue(MANAGED) == "true"
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        when (val source = source) {
            is DebugSource.LaunchJson -> {
                element.setAttribute("launchConfiguration", source.name)
                source.folder?.let { element.setAttribute(FOLDER, it) }
            }
            is DebugSource.Extension -> element.addContent(Element("configuration").setText(source.configuration.toString()))
            is DebugSource.Compound -> {
                element.setAttribute("launchCompound", source.name)
                source.folder?.let { element.setAttribute(FOLDER, it) }
            }
            is DebugSource.Command -> element.addContent(
                Element(COMMAND)
                    .setAttribute("session", source.name)
                    .setAttribute("id", source.command.id)
                    .setAttribute("title", source.command.title)
                    .setText(source.command.arguments.toString())
            )
            null -> Unit
        }
        if (managed) element.setAttribute(MANAGED, "true")
        pending?.let { changes ->
            element.addContent(Element(PENDING).setText(buildJsonObject {
                put("set", JsonObject(changes.set))
                put("removed", JsonArray(changes.removed.sorted().map(::JsonPrimitive)))
            }.toString()))
        }
    }

    fun workspace(): Path? = project.basePath?.let(Path::of)

    /**
     * The views the debugger's extension puts in VS Code's Run and Debug side bar, as tabs of the
     * session; see [CodeBridgeDebugViewTabs]. A compound has no debugger of its own, its members do.
     */
    override fun createAdditionalTabComponents(manager: AdditionalTabComponentManager, startedProcess: ProcessHandler?) {
        val type = runCatching { (rawConfiguration()["type"] as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return
        CodeBridgeDebugViewTabs.add(project, type, manager)
    }

    /** The label of the task run before [source] starts, its `preLaunchTask` as it stands now; null for none or when it cannot be read. */
    fun preLaunchTask(source: DebugSource? = this.source): String? {
        val workspace = workspace() ?: return null
        return runCatching {
            when (source) {
                is DebugSource.Single -> (source.configuration(workspace)["preLaunchTask"] as? JsonPrimitive)?.contentOrNull
                is DebugSource.Compound -> DebugLaunch.compound(workspace, source.name, source.folder).preLaunchTask
                is DebugSource.Command, null -> null
            }
        }.getOrNull()
    }

    private companion object {
        const val MANAGED = "fromLaunchJson"
        const val PENDING = "pendingLaunchJsonChanges"
        const val FOLDER = "launchFolder"
        const val COMMAND = "command"

        fun readCommand(element: Element): DebugSource.Command? {
            val id = element.getAttributeValue("id")?.takeIf { it.isNotEmpty() } ?: return null
            val arguments = runCatching { Json.parseToJsonElement(element.text) as? JsonArray }.getOrNull()
                ?: JsonArray(emptyList())
            val title = element.getAttributeValue("title").orEmpty()
            return DebugSource.Command(
                element.getAttributeValue("session") ?: title.ifEmpty { id },
                CodeActions.Command(id, title, arguments)
            )
        }
    }

    /** The configuration as it stands now; a compound has none of its own. */
    fun rawConfiguration(): JsonObject {
        val single = checkNotNull(source as? DebugSource.Single) { "No launch configuration chosen" }
        return single.configuration(workspace() ?: error("Project has no workspace"))
    }

    /**
     * Picks a `launch.json` entry or compound; `launch.json` stays the one place the entry is written. An
     * extension's configuration is shown, not edited: it is the extension's.
     */
    private class Editor : SettingsEditor<CodeBridgeDebugRunConfiguration>() {
        /** How each entry is shown, by source; see [LaunchEntries]. */
        private var shown: Map<DebugSource, String> = emptyMap()
        private val combo = JComboBox<DebugSource>().apply {
            renderer = textListCellRenderer("") { shown[it] ?: if (it is DebugSource.Compound) "${it.name} (compound)" else it.name }
        }
        private val extension = JBLabel()
        private val preLaunch = JBLabel()
        private var configuration: CodeBridgeDebugRunConfiguration? = null
        private val panel = JPanel(BorderLayout())
        init {
            panel.add(FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("Launch configuration:"), combo)
                .addLabeledComponent(JBLabel("Before launch:"), preLaunch)
                .addComponent(extension)
                .panel, BorderLayout.NORTH)
            combo.addActionListener { showPreLaunchTask(combo.selectedItem as? DebugSource) }
        }
        private fun showPreLaunchTask(source: DebugSource?) {
            preLaunch.text = configuration?.preLaunchTask(source)?.let { "Task '$it' (preLaunchTask)" } ?: "Nothing (no preLaunchTask)"
        }
        override fun resetEditorFrom(s: CodeBridgeDebugRunConfiguration) {
            configuration = s
            val source = s.source
            showPreLaunchTask(source)
            if (s.managed) {
                combo.model = DefaultComboBoxModel(listOfNotNull(source).toTypedArray())
                combo.selectedItem = source
                combo.isEnabled = false
                val folder = (source as? DebugSource.LaunchJson)?.folder ?: (source as? DebugSource.Compound)?.folder
                extension.text = "Defined in ${s.workspace()?.let { DebugLaunch.origin(it, folder) } ?: ".vscode/launch.json"}; edit that file to change it."
                return
            }
            val entries = s.workspace()?.let(LaunchEntries::of).orEmpty()
            shown = entries.toMap()
            val choices = entries.map { it.first }
            combo.model = DefaultComboBoxModel(choices.toTypedArray<DebugSource>())
            val extensions = source is DebugSource.Extension || source is DebugSource.Command
            combo.selectedItem = source?.takeUnless { extensions }
            combo.isEnabled = !extensions
            extension.text = when (source) {
                is DebugSource.Extension -> "Started by an extension: '${source.name}'"
                is DebugSource.Command -> "Runs the extension's command '${source.command.title.ifEmpty { source.command.id }}' again, which starts '${source.name}'"
                else -> ""
            }
        }
        override fun applyEditorTo(s: CodeBridgeDebugRunConfiguration) {
            if (s.managed || s.source is DebugSource.Extension || s.source is DebugSource.Command) return
            s.source = combo.selectedItem as? DebugSource
        }
        override fun createEditor(): JComponent = panel
    }
}

/**
 * A configuration that attaches, which the platform's default runner does not offer Run for: it
 * runs any configuration not marked like this one -- the per-class marker is why this is a class.
 * Debug is the DAP runner's, as for every configuration of this type.
 */
class CodeBridgeAttachRunConfiguration(project: Project, factory: ConfigurationFactory) :
    CodeBridgeDebugRunConfiguration(project, factory), RunConfigurationWithSuppressedDefaultRunAction
