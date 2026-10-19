package me.helvert.codebridge.launch

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionHolder
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.launch.LaunchJsonEdit.Section
import me.helvert.codebridge.task.TaskSources
import me.helvert.codebridge.workbench.MirroredRunConfigurations
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*

/**
 * A `launch.json` entry -- a configuration or a compound -- as a form, its fields made from the
 * schema the debugger's extension declares for the entry's `type` and `request`; see [SchemaField].
 * Another `type` or `request` chosen here makes the form that debugger's.
 *
 * The file stays the one place the entry is written. Applied, the form leaves the keys it changed on
 * the run configuration as [CodeBridgeDebugRunConfiguration.pending], and [CodeBridgeLaunchConfigurations] writes
 * them to the file; the snapshots the dialog makes on every change are never written anywhere.
 *
 * The entry's `name` is the run configuration's own name.
 */
internal class LaunchEntryEditor(private val configuration: CodeBridgeDebugRunConfiguration) :
    SettingsEditor<CodeBridgeDebugRunConfiguration>() {

    private val project = configuration.project
    private val workspace = configuration.workspace()
    private val section =
        if (configuration.source is DebugSource.Compound) Section.COMPOUNDS else Section.CONFIGURATIONS
    private val entryName = configuration.source?.name.orEmpty()
    private val folder = (configuration.source as? DebugSource.LaunchJson)?.folder
        ?: (configuration.source as? DebugSource.Compound)?.folder
    private val manifests by lazy {
        ProjectExtensions.getInstance(configuration.project).enabled().map { it.manifest }
    }
    private val variables = Variables { workspace?.let { LaunchJsonFiles.inputs(it, folder) }.orEmpty() }

    /** The entry as the form last showed it; null when it cannot be read. */
    private var shown: JsonObject? = null

    /** The run configuration's name as the form last showed it, which a rename changes. */
    private var shownName: String? = null
    private var type: String? = null
    private var request: String? = null

    /** Every key that has had a field, by key. */
    private val fields = linkedMapOf<String, SchemaField>()

    /** The keys shown, in the order they are. */
    private val visible = linkedSetOf<String>()

    /** The keys taken out from Modify options: the only ones the form removes from the entry. */
    private val removed = mutableSetOf<String>()

    private val root = JPanel(BorderLayout())

    override fun createEditor(): JComponent = root

    override fun resetEditorFrom(settings: CodeBridgeDebugRunConfiguration) {
        val entry = read()
        shown = entry
        shownName = settings.name
        type = entry?.string("type")
        request = entry?.string("request")
        fields.clear()
        visible.clear()
        removed.clear()
        entry?.keys?.filterTo(visible) { it !in FIXED }
        rebuild()
    }

    override fun applyEditorTo(settings: CodeBridgeDebugRunConfiguration) {
        // Against the entry as it is now, so a change written already is not written again.
        val current = read() ?: run { settings.pending = null; return }
        val edited = current.toMutableMap()
        for ((key, field) in fields) {
            // A key not shown is left as the file has it, unless it was taken out.
            val change = when (key) {
                in removed -> FieldChange.Remove
                in visible -> field.change()
                else -> FieldChange.Unchanged
            }
            when (change) {
                FieldChange.Unchanged -> Unit
                FieldChange.Remove -> edited.remove(key)
                is FieldChange.Set -> edited[key] = change.value
            }
        }
        if (section == Section.CONFIGURATIONS) {
            if (type != shown?.string("type")) type?.let { edited["type"] = JsonPrimitive(it) }
            if (request != shown?.string("request")) request?.let { edited["request"] = JsonPrimitive(it) }
        }
        if (settings.name != shownName) edited["name"] = JsonPrimitive(entryNameOf(settings.name))
        settings.pending = EntryChanges.between(current, JsonObject(edited)).takeUnless { it.isEmpty }
    }

    private fun read(): JsonObject? = workspace?.let { LaunchJsonFiles.entry(it, folder, section, entryName) }

    private fun shape(): LaunchSchema.Shape? = when {
        section == Section.COMPOUNDS -> LaunchSchema.compound
        else -> type?.let { type -> request?.let { LaunchSchema.configuration(manifests, type, it) } }
    }

    private fun debuggers() = LaunchSchema.debuggers(manifests)

    /**
     * Whether the debugger uses [key] for its request: whether [shape] declares it, or any key when
     * no installed extension describes the debugger. A key it does not use -- `args` left in an
     * entry turned from launch to attach -- is shown as incompatible, for the user to take out.
     */
    private fun used(key: String, shape: LaunchSchema.Shape?): Boolean = shape == null || key in shape.properties

    private fun incompatibility(): String =
        "Not an option of ${debuggerName()} $request configurations, which ignore it"

    private fun debuggerName(): String = type?.let { debuggers()[it]?.label ?: it }.orEmpty()

    /** What the form shows now: built again whenever the keys shown, or the shape that has them, change. */
    private fun rebuild() {
        val shape = shape()
        val required = shape?.required.orEmpty() - FIXED
        visible += required
        root.removeAll()
        root.add(panel {
            header(shape)
            if (shown == null) return@panel
            if (section == Section.CONFIGURATIONS) {
                row("type:") { cell(typeChoice()).align(AlignX.FILL) }
                row("request:") { cell(requestChoice()).align(AlignX.FILL) }
            }
            for (key in visible) {
                val field = field(key, shape)
                val description = shape?.properties?.get(key)?.description()?.let(StringUtil::escapeXmlEntities)
                // As the UI guidelines have it: help text under the field, no wider than 70 characters
                // and no longer than five lines; a description longer than that is a help tooltip.
                val inline = description?.takeIf { it.length <= INLINE_HELP_LENGTH }
                val row = if (field.labelled) row {
                    cell(field.component).resizableColumn().also { cell -> inline?.let(cell::comment) }
                }
                else row("$key:") {
                    cell(field.component).align(AlignX.FILL).resizableColumn()
                        .also { cell -> inline?.let(cell::comment) }
                }
                row.apply {
                    if (inline == null) description?.let { contextHelp(it) }
                    if (!used(key, shape)) icon(AllIcons.General.Warning).applyToComponent {
                        toolTipText = incompatibility()
                    }
                    // Removed, as a run configuration's options are, from Modify options: there is no
                    // button of its own beside a field.
                }
            }
        }, BorderLayout.NORTH)
        root.revalidate()
        root.repaint()
        fireEditorStateChanged()
    }

    /** What the entry is and where it is written, and the options it can have. */
    private fun com.intellij.ui.dsl.builder.Panel.header(shape: LaunchSchema.Shape?) {
        val where = workspace?.let { DebugLaunch.origin(it, folder) } ?: ".vscode/launch.json"
        val text = when {
            shown == null -> "'$entryName' cannot be read from $where now."
            section == Section.COMPOUNDS -> "Compound in $where"
            shape == null -> "In $where. No installed extension describes this debugger; its keys are edited as they are."
            else -> "In $where"
        }
        row {
            label(text)
            link("Open in ${where.substringAfterLast('/').substringAfterLast(' ')}") { event ->
                openFile(event.source as? Component)
            }.resizableColumn()
            if (shown != null) cell(modifyOptions(shape)).align(AlignX.RIGHT)
        }
    }

    /**
     * Opens the entry in its file. The editor would only come up once the run configuration dialog
     * [source] is in closes, so the dialog is closed first -- with OK, so that what the form changed
     * is in the file -- and the file opened after [CodeBridgeLaunchConfigurations] has written it; by the
     * entry's name then, which the form may have changed. A dialog that does not close, its form
     * refused, stays as it is, and nothing opens.
     */
    private fun openFile(source: Component?) {
        val workspace = workspace ?: return
        val dialog = source?.let(DialogWrapper::findInstance)
        if (dialog != null) {
            dialog.clickDefaultButton()
            if (!dialog.isDisposed) return
        }
        Scope.getInstance(project).scope.launch(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            LaunchJsonFiles.open(project, workspace, folder, section, configuration.source?.name ?: entryName)
        }
    }

    private fun field(key: String, shape: LaunchSchema.Shape?): SchemaField = fields.getOrPut(key) {
        val value = shown?.get(key)
        SchemaField.of(key, shape?.properties?.get(key), value, variables, tasksFor(key), project)
            .also { it.show(value) }
    }

    /**
     * The form for another `type` or `request`: each field made again for its new shape, holding what
     * it holds now; the options only added here that the new debugger does not have gone; and those
     * the debugger's own snippet for the request starts a configuration with shown.
     */
    private fun reshape() {
        val shape = shape()
        val declared = shape?.properties.orEmpty()
        for ((key, old) in fields.toMap()) {
            // A field holding what does not parse keeps it, to be refused on apply as before.
            val value = try {
                old.current()
            } catch (_: ConfigurationException) {
                continue
            }
            fields[key] = SchemaField.of(key, declared[key], value, variables, tasksFor(key), project)
                .also { it.show(value, changed = value != shown?.get(key)) }
        }
        // Added here and not the new debugger's: gone. The entry's own stay, incompatible or not.
        visible.retainAll { key -> used(key, shape) || shown?.containsKey(key) == true }
        visible += snippetKeys() - FIXED
        rebuild()
    }

    /** The keys the chosen debugger's first configuration snippet for the chosen request has. */
    private fun snippetKeys(): Set<String> = manifests.asSequence()
        .flatMap { it.contributes.debuggers }.filter { it.type == type }
        .flatMap { it.configurationSnippets.orEmpty() }
        .mapNotNull { (it as? JsonObject)?.get("body") as? JsonObject }
        .firstOrNull { it.string("request") == request }?.keys.orEmpty()

    private fun tasksFor(key: String): (() -> List<String>)? = if (key in TASK_KEYS) {
        { tasks() }
    } else null

    private fun show(key: String, show: Boolean) {
        if (show) {
            visible += key
            removed -= key
        } else {
            visible -= key
            removed += key
        }
        rebuild()
    }

    /**
     * The link a run configuration's options are added and taken out by, as the platform's own editors
     * have it: a drop-down link, with the shortcut its mnemonic gives beside it.
     */
    private fun modifyOptions(shape: LaunchSchema.Shape?): JComponent {
        val text = TextWithMnemonic.parse("&Modify options")
        val link = DropDownLink(text.text) { link: DropDownLink<String> -> options(shape, link) }
        val panel = JPanel(BorderLayout())
        panel.add(link, BorderLayout.CENTER)
        KeymapUtil.getShortcutsForMnemonicCode(text.mnemonicCode)?.shortcuts
            ?.map(KeymapUtil::getShortcutText)?.minByOrNull { it.length }
            ?.let { shortcut ->
                panel.add(JLabel(shortcut).apply {
                    isEnabled = false; border = JBUI.Borders.empty(0, 5)
                }, BorderLayout.EAST)
            }
        return panel
    }

    /** Every option the entry can have, grouped as the debugger's and VS Code's, each shown or not. */
    private fun options(shape: LaunchSchema.Shape?, anchor: JComponent): JBPopup {
        val required = shape?.required.orEmpty()
        val properties = shape?.properties.orEmpty()
        val keys = (properties.keys + visible).distinct()
            .filter { it !in FIXED && it !in required && (it in visible || properties[it]?.deprecated() != true) }
        val group = DefaultActionGroup()
        // VS Code's own first, then the debugger's, each by where it comes from; one the debugger does
        // not use for this request is among them, marked.
        keys.groupBy { key -> groupOf(key, shape) }.toSortedMap(compareBy<OptionGroup> { it.rank }.thenBy { it.title })
            .forEach { (kind, members) ->
                group.addSeparator(kind.title)
                for (key in members) group.add(object : DumbAwareToggleAction(
                    key,
                    if (!used(key, shape)) incompatibility() else properties[key]?.description(), null
                ) {
                    private val warning = !used(key, shape)
                    override fun isSelected(e: AnActionEvent): Boolean = key in visible
                    override fun setSelected(e: AnActionEvent, state: Boolean) = show(key, state)
                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        // After the name: the check mark of a shown option takes the place before it.
                        if (warning) e.presentation.putClientProperty(
                            ActionUtil.SECONDARY_ICON,
                            AllIcons.General.Warning
                        )
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                })
            }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Add Run Options",
            group,
            DataManager.getInstance().getDataContext(anchor),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
            null,
            -1
        )
        popup.setHandleAutoSelectionBeforeShow(true)
        // The option under the cursor described at the bottom, as the platform's popup does.
        popup.addListSelectionListener { event ->
            val action = ((event.source as? JList<*>)?.selectedValue as? AnActionHolder)?.action
                ?: return@addListSelectionListener
            PopupUtil.getPopupContainerFor(event.source as JComponent)
                ?.setAdText(action.templatePresentation.description.orEmpty(), SwingConstants.LEFT)
        }
        return popup
    }

    /** A group of Modify options, shown in [rank] order. */
    private data class OptionGroup(val rank: Int, val title: String)

    /**
     * Where [key] comes from: VS Code's own options, then the chosen debugger's -- those it declares
     * for another request among them, `args` in an attach -- then another debugger's, left from a
     * `type` changed here, then anything else.
     */
    private fun groupOf(key: String, shape: LaunchSchema.Shape?): OptionGroup {
        if (section == Section.COMPOUNDS) return if (shape != null && key in shape.properties) OptionGroup(
            0,
            "Compound"
        ) else OptionGroup(4, "Other")
        if (shape == null && type !in debuggers()) return OptionGroup(4, "Entry")
        if (key in LaunchSchema.COMMON) return OptionGroup(1, "VS Code")
        val debuggers = debuggers()
        fun declares(debugger: LaunchSchema.Debugger) =
            debugger.attributes.values.any { request ->
                ((request as? JsonObject)?.get("properties") as? JsonObject)?.containsKey(
                    key
                ) == true
            }
        if (shape?.properties?.containsKey(key) == true || debuggers[type]?.let(::declares) == true) {
            return OptionGroup(2, debuggerName().ifEmpty { "Debugger" })
        }
        val other = debuggers.entries.firstOrNull { (_, debugger) -> declares(debugger) }
        return if (other != null) OptionGroup(3, other.value.label ?: other.key) else OptionGroup(4, "Other")
    }

    /** The debugger types installed extensions declare, and the entry's own if none does. */
    private fun typeChoice(): ComboBox<String> {
        val debuggers = debuggers()
        val types = (debuggers.keys + listOfNotNull(type)).distinct()
        return ComboBox(DefaultComboBoxModel(types.toTypedArray())).apply {
            renderer = textListCellRenderer("") { type -> debuggers[type]?.label?.let { "$it ($type)" } ?: type }
            selectedItem = type
            addActionListener {
                val chosen = selectedItem as? String ?: return@addActionListener
                if (chosen == type) return@addActionListener
                type = chosen
                // A request the new debugger does not have becomes the first it has.
                requests().takeIf { request !in it }?.firstOrNull()?.let { request = it }
                reshape()
            }
        }
    }

    private fun requestChoice(): ComboBox<String> =
        ComboBox(DefaultComboBoxModel((requests() + listOfNotNull(request)).distinct().toTypedArray())).apply {
            selectedItem = request
            addActionListener {
                val chosen = selectedItem as? String ?: return@addActionListener
                if (chosen == request) return@addActionListener
                request = chosen
                reshape()
            }
        }

    /** The requests the chosen debugger declares attributes for. */
    private fun requests(): List<String> =
        type?.let { debuggers()[it]?.attributes?.keys?.toList() }.orEmpty().ifEmpty { listOf("launch", "attach") }

    /** The entry name a run configuration named [name] stands for: without what [LaunchEntries] adds to tell entries apart. */
    private fun entryNameOf(name: String): String {
        val layout = workspace?.let(DebugLaunch::layout) ?: return name
        var entry = name
        if (layout.isMultiRoot) entry = entry.removeSuffix(" (${folder ?: MirroredRunConfigurations.WORKSPACE})")
        if (section == Section.COMPOUNDS) entry = entry.removeSuffix(" (compound)")
        return entry.trim()
    }

    /** The tasks a `preLaunchTask` of this entry can name: its folder's and the workspace's own. */
    private fun tasks(): List<String> = workspace?.let { TaskSources.listed(it) }.orEmpty()
        .filter { it.folder == null || it.folder == folder }.map { it.label }.distinct()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.description(): String? =
        (string("markdownDescription") ?: string("description"))?.let(SchemaField::plain)

    private fun JsonObject.deprecated(): Boolean = "deprecationMessage" in this || "markdownDeprecationMessage" in this

    /** Where the file opens: once the dialog has closed, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    private companion object {
        val TASK_KEYS = setOf("preLaunchTask", "postDebugTask")

        /** Five lines of help text 70 characters wide, the most the UI guidelines show under a field. */
        const val INLINE_HELP_LENGTH = 5 * 70

        /** Keys the form has its own controls for: the run configuration's name, and the choice of debugger. */
        val FIXED = setOf("name", "type", "request")
    }
}
