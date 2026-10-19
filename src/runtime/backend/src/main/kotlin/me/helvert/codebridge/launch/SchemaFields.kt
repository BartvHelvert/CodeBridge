package me.helvert.codebridge.launch

import com.intellij.execution.configuration.EnvironmentVariablesDialog
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStepEx
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.StatusText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import me.helvert.codebridge.workspace.Jsonc
import me.helvert.codebridge.workspace.JsoncEdit
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/** What a field does to its key. */
internal sealed interface FieldChange {
    /** The field was not touched: the key stays as the file has it. */
    data object Unchanged : FieldChange
    data class Set(val value: JsonElement) : FieldChange
    data object Remove : FieldChange
}

/**
 * One key of a `launch.json` entry as a field of a form, chosen by the shape the debugger's schema
 * gives its value -- nothing here knows any debugger. A value no field can show is edited as JSON.
 */
internal abstract class SchemaField(val key: String) {
    abstract val component: JComponent

    /** How the value is edited. */
    enum class Kind { CHECK, CHOICE, TEXT, NUMBER, TASK, LIST, MAP, JSON }
    abstract val kind: Kind

    /** Whether it carries its own label, as a checkbox does. */
    open val labelled: Boolean get() = false

    private var shownState: Any? = null
    private var shownValue: JsonElement? = null
    private var changed = false

    /**
     * Shows [value], or the schema's default for a key the entry does not have. [changed] is a value
     * other than the file's -- carried over from the field of another shape -- written even untouched.
     */
    fun show(value: JsonElement?, changed: Boolean = false) {
        display(value)
        shownState = state()
        shownValue = value
        this.changed = changed
    }

    /** What the field does to the key: nothing until someone changes it, so a value is never rewritten in another form. */
    fun change(): FieldChange {
        if (!changed && state() == shownState) return FieldChange.Unchanged
        return read()?.let(FieldChange::Set) ?: FieldChange.Remove
    }

    /** The value the field holds now, as it was shown until it is changed; null for none. */
    fun current(): JsonElement? = if (state() == shownState) shownValue else read()

    protected abstract fun display(value: JsonElement?)
    protected abstract fun state(): Any?

    /** The value the field holds now; null when it holds none and the key should go. */
    protected abstract fun read(): JsonElement?

    companion object {
        /**
         * The field for [key] of shape [schema] -- null for a key the debugger does not declare --
         * holding [value]. A schema with alternatives takes the first that can hold the value.
         * Typed values offer to insert the `${...}` [variables]; environment fields belong to [project].
         */
        fun of(
            key: String, schema: JsonObject?, value: JsonElement?, variables: Variables, tasks: (() -> List<String>)?,
            project: Project? = null,
        ): SchemaField {
            if (tasks != null && (value == null || value.isString())) return TaskField(key, tasks)
            if (schema == null) return inferred(key, value, variables)
            val alternatives = ((schema["anyOf"] ?: schema["oneOf"]) as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.map { JsonObject(schema - setOf("anyOf", "oneOf") + it) }
                ?: listOf(schema)
            return alternatives.firstNotNullOfOrNull { simple(key, it, value, variables, project) } ?: inferred(key, value, variables)
        }

        private fun simple(key: String, schema: JsonObject, value: JsonElement?, variables: Variables, project: Project?): SchemaField? {
            val default = schema["default"]
            (schema["enum"] as? JsonArray)?.let { values ->
                val choices = values.filterIsInstance<JsonPrimitive>()
                if (choices.size != values.size || (value != null && value !is JsonPrimitive)) return null
                val descriptions = ((schema["markdownEnumDescriptions"] ?: schema["enumDescriptions"]) as? JsonArray).orEmpty()
                    .map { (it as? JsonPrimitive)?.contentOrNull?.let(::plain) }
                return ChoiceField(key, choices, choices.zip(descriptions).toMap(), default as? JsonPrimitive)
            }
            val types = when (val type = schema["type"]) {
                is JsonPrimitive -> listOfNotNull(type.contentOrNull)
                is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }.filter { it != "null" }.toSet()
            return when {
                types == setOf("boolean") -> if (value == null || value.isBoolean()) CheckField(key, (default as? JsonPrimitive)?.booleanOrNull ?: false) else null
                types == setOf("string") -> if (value == null || value.isString()) TextField(key, default, variables) else null
                types.isNotEmpty() && types.all { it == "number" || it == "integer" } -> if (value == null || value.isNumber()) NumberField(key, default, variables) else null
                types == setOf("array") && (schema["items"] as? JsonObject)?.let { it["type"] == JsonPrimitive("string") } == true ->
                    if (value == null || value.isStrings()) ListField(key, variables) else null
                types == setOf("object") && schema["properties"] == null &&
                    (schema["additionalProperties"] as? JsonObject)?.let { it["type"] == JsonPrimitive("string") } == true ->
                    if (value == null || value.isStringMap()) MapField(key, project) else null
                else -> null
            }
        }

        /** A key no schema describes, by the value it has. */
        private fun inferred(key: String, value: JsonElement?, variables: Variables): SchemaField = when {
            value == null -> JsonField(key, variables)
            value.isBoolean() -> CheckField(key, false)
            value.isString() -> TextField(key, null, variables)
            value.isNumber() -> NumberField(key, null, variables)
            value.isStrings() -> ListField(key, variables)
            else -> JsonField(key, variables)
        }

        /** [markdown] as plain text, for a tooltip. */
        fun plain(markdown: String): String = markdown
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[`*]"), "")

        private fun JsonElement.isString() = this is JsonPrimitive && isString
        private fun JsonElement.isBoolean() = this is JsonPrimitive && !isString && booleanOrNull != null
        private fun JsonElement.isNumber() = this is JsonPrimitive && !isString && doubleOrNull != null
        private fun JsonElement.isStrings() = this is JsonArray && all { it.isString() }
        private fun JsonElement.isStringMap() = this is JsonObject && values.all { it.isString() }
        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

private class CheckField(key: String, private val default: Boolean) : SchemaField(key) {
    override val component = JBCheckBox(key)
    override val kind get() = Kind.CHECK
    override val labelled get() = true
    override fun display(value: JsonElement?) { component.isSelected = (value as? JsonPrimitive)?.booleanOrNull ?: default }
    override fun state(): Any = component.isSelected
    override fun read(): JsonElement = JsonPrimitive(component.isSelected)
}

private class ChoiceField(
    key: String,
    private val choices: List<JsonPrimitive>,
    descriptions: Map<JsonPrimitive, String?>,
    private val default: JsonPrimitive?,
) : SchemaField(key) {
    override val component = ComboBox<JsonPrimitive>().apply {
        renderer = listCellRenderer<JsonPrimitive>("null") {
            text(value.contentOrNull ?: "null")
            toolTipText = descriptions[value]
        }
    }
    override val kind get() = Kind.CHOICE
    override fun display(value: JsonElement?) {
        // A value the schema does not list -- a `${input:...}`, say -- is offered as it is.
        val shown = value as? JsonPrimitive ?: default ?: choices.first()
        component.model = DefaultComboBoxModel((if (shown in choices) choices else choices + shown).toTypedArray())
        component.selectedItem = shown
    }
    override fun state(): Any? = component.selectedItem
    override fun read(): JsonElement? = component.selectedItem as? JsonPrimitive
}

/** The `${...}` variables a field offers to insert: those of `input:` from the entry's own file among them. */
internal class Variables(val inputs: () -> List<String>)

/**
 * A text field, of the platform's own kinds, with the button run configurations insert macros by:
 * here it inserts a `${...}` variable; see [LaunchVariables].
 */
internal abstract class TypedField(key: String, variables: Variables, val textField: ExtendableTextField) : SchemaField(key) {
    override val component: JComponent get() = textField

    init {
        textField.addExtension(ExtendableTextComponent.Extension.create(AllIcons.General.InlineVariables, AllIcons.General.InlineVariablesHover,
            "Insert variable", true) { pick(textField, variables) })
    }

    var text: String
        get() = textField.text
        set(value) { textField.text = value }

    override fun state(): Any = text

    companion object {
        /** Offers every variable there is, and inserts the one chosen where the caret is. */
        fun pick(field: JTextComponent, variables: Variables) {
            val step = object : BaseListPopupStep<LaunchVariables.Choice>("Insert Variable", LaunchVariables.choices(variables.inputs)),
                ListPopupStepEx<LaunchVariables.Choice> {
                override fun getTextFor(value: LaunchVariables.Choice) = value.text
                override fun getSecondaryTextFor(t: LaunchVariables.Choice) = t.description
                override fun getTooltipTextFor(value: LaunchVariables.Choice): String? = null
                override fun setEmptyText(emptyText: StatusText) {}
                override fun isSpeedSearchEnabled() = true
                override fun onChosen(selectedValue: LaunchVariables.Choice, finalChoice: Boolean) =
                    doFinalStep { insert(field, selectedValue.text) }
            }
            JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(field)
        }

        /** [field] with [text] in place of its selection, or at its caret, and the caret after it. */
        fun insert(field: JTextComponent, text: String) {
            val start = field.selectionStart
            field.document.remove(start, field.selectionEnd - start)
            field.document.insertString(start, text, null)
            field.caretPosition = start + text.length
            IdeFocusManager.findInstance().requestFocus(field, true)
        }
    }
}

private class TextField(key: String, default: JsonElement?, variables: Variables) :
    TypedField(key, variables, placeholder(default)) {
    override val kind get() = Kind.TEXT
    override fun display(value: JsonElement?) { text = (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
    override fun read(): JsonElement? = text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
}

/** A number, or -- as `"${input:port}"` -- a string where one is substituted in. */
private class NumberField(key: String, default: JsonElement?, variables: Variables) :
    TypedField(key, variables, placeholder(default)) {
    override val kind get() = Kind.NUMBER
    override fun display(value: JsonElement?) { text = (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
    override fun read(): JsonElement? {
        val text = text.trim().takeIf { it.isNotEmpty() } ?: return null
        return text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
    }
}

/** A task's label, from those `tasks.json` lists, or any other. */
private class TaskField(key: String, private val tasks: () -> List<String>) : SchemaField(key) {
    override val component = ComboBox<String>().apply { isEditable = true }
    override val kind get() = Kind.TASK
    override fun display(value: JsonElement?) {
        val shown = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        // No empty choice: the text is cleared, or the option removed, to name no task.
        component.model = DefaultComboBoxModel((tasks() + shown).filter { it.isNotEmpty() }.distinct().toTypedArray())
        component.selectedItem = shown
    }
    private fun text(): String = (component.editor?.item ?: component.selectedItem)?.toString().orEmpty()
    override fun state(): Any = text()
    override fun read(): JsonElement? = text().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
}

/** A plain field, its schema's default shown in it while it is empty. */
private fun placeholder(default: JsonElement?) = ExtendableTextField().apply {
    (default as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { emptyText.text = it }
}

/** A list of strings, as IntelliJ edits program arguments: quoted where they need it, one per line expanded. */
private class ListField(key: String, variables: Variables, private val editor: RawCommandLineEditor = RawCommandLineEditor()) :
    TypedField(key, variables, editor.editorField) {
    override val component: JComponent get() = editor
    override val kind get() = Kind.LIST
    override fun display(value: JsonElement?) {
        text = ParametersListUtil.join((value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
    }
    override fun read(): JsonElement? =
        ParametersListUtil.parse(text).takeIf { it.isNotEmpty() }?.let { items -> JsonArray(items.map(::JsonPrimitive)) }
}

/** Names to strings, as IntelliJ edits environment variables. */
private class MapField(key: String, project: Project?) : SchemaField(key) {
    override val component = object : EnvironmentVariablesTextFieldWithBrowseButton(project) {
        // The program's environment is the one the debugger gives it: there is no choice to offer here.
        override fun createDialog(): EnvironmentVariablesDialog = object : EnvironmentVariablesDialog(this, true) {}
    }
    override val kind get() = Kind.MAP
    override fun display(value: JsonElement?) {
        component.envs = (value as? JsonObject)?.mapValues { (_, item) -> (item as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
    }
    override fun state(): Any = component.envs
    override fun read(): JsonElement? = component.envs.takeIf { it.isNotEmpty() }?.let { envs -> JsonObject(envs.mapValues { JsonPrimitive(it.value) }) }
}

/** Any value, as the JSON it is; comments are allowed and dropped. */
private class JsonField(key: String, variables: Variables) : TypedField(key, variables, ExpandableTextField()) {
    override val kind get() = Kind.JSON
    override fun display(value: JsonElement?) { text = value?.let(JsoncEdit::render).orEmpty() }
    override fun read(): JsonElement? {
        val text = text.takeIf { it.isNotBlank() } ?: return null
        return try { Json.parseToJsonElement(Jsonc.stripToJson(text)) }
        catch (error: IllegalArgumentException) { throw ConfigurationException("'$key' is not valid JSON: ${error.message}") }
    }
}
