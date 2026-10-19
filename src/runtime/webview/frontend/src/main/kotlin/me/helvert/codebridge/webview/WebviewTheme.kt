package me.helvert.codebridge.webview

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.awt.Color

/**
 * The IDE's look, as the CSS variables VS Code gives every webview (`--vscode-editor-background`
 * and so on) and the class it puts on the page's body (`vscode-dark`). Pages are written against
 * these, so a page themed by them follows the IDE's theme.
 *
 * Only the colours pages commonly read; each from the IDE colour that plays the same part, since
 * there is no table between the two themes to read them from.
 */
internal data class WebviewTheme(val vars: Map<String, String>, val kind: String, val name: String) {

    fun withContributedColors(colors: Map<String, Map<String, String>>): WebviewTheme {
        val themeKind = if (kind == "vscode-light") "light" else "dark"
        fun variable(id: String) = "--vscode-${id.replace('.', '-')}"
        fun resolve(id: String, visited: Set<String>): String? {
            vars[variable(id)]?.let { return it }
            if (id in visited || visited.size >= 32) return null
            val value = colors[id]?.get(themeKind) ?: return null
            return if (value.startsWith('#')) {
                value.takeIf { it.matches(Regex("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}")) }
            } else resolve(value, visited + id)
        }

        val contributed = colors.keys.filter { it.matches(Regex("[a-zA-Z0-9_.-]+")) }.mapNotNull { id ->
            resolve(id, emptySet())?.let { variable(id) to it }
        }.toMap()
        return copy(vars = contributed + vars)
    }

    /** The variables as a `:root` rule, for the page's first paint. */
    val css: String get() = vars.entries.joinToString(" ", ":root { ", " }") { (name, value) -> "$name: $value;" }

    /** What the page's script reads: see `bootstrap.js`. */
    val json: String get() = buildJsonObject {
        putJsonObject("vars") { vars.forEach { (name, value) -> put(name, value) } }
        put("kind", kind)
        put("name", name)
    }.toString()

    companion object {
        /** The theme now. [panel] is for a page in a tool window, whose background is the panel's rather than the editor's. */
        fun current(panel: Boolean): WebviewTheme {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val dark = !JBColor.isBright()
            val editorBackground = scheme.defaultBackground
            val editorForeground = scheme.defaultForeground
            val background = if (panel) UIUtil.getPanelBackground() else editorBackground
            val foreground = UIUtil.getLabelForeground()
            val font = JBUI.Fonts.label()
            val selection = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR) ?: JBUI.CurrentTheme.List.Selection.background(true)
            val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
            val border = JBColor.border()
            val muted = UIUtil.getContextHelpForeground()
            val buttonBackground = named("Button.default.startBackground", JBUI.CurrentTheme.Focus.focusColor())
            val inputBackground = named("TextField.background", background)
            val listSelection = JBUI.CurrentTheme.List.Selection.background(true)
            val listForeground = JBUI.CurrentTheme.List.Selection.foreground(true)
            val popup = JBUI.CurrentTheme.Popup.BACKGROUND
            val vars = linkedMapOf(
                "--vscode-font-family" to "'${font.family}', system-ui, sans-serif",
                "--vscode-font-size" to "${font.size}px",
                "--vscode-font-weight" to "normal",
                "--vscode-editor-font-family" to "'${scheme.editorFontName}', monospace",
                "--vscode-editor-font-size" to "${scheme.editorFontSize}px",
                "--vscode-editor-font-weight" to "normal",
                "--monaco-monospace-font" to "'${scheme.editorFontName}', monospace",
                "--vscode-foreground" to css(foreground),
                "--vscode-descriptionForeground" to css(muted),
                "--vscode-disabledForeground" to css(muted),
                "--vscode-errorForeground" to css(named("Label.errorForeground", JBColor.RED)),
                "--vscode-icon-foreground" to css(foreground),
                "--vscode-focusBorder" to css(JBUI.CurrentTheme.Focus.focusColor()),
                "--vscode-contrastBorder" to "transparent",
                "--vscode-widget-border" to css(border),
                "--vscode-widget-shadow" to css(alpha(Color.BLACK, if (dark) 0.36 else 0.16)),
                "--vscode-selection-background" to css(selection),
                "--vscode-editor-background" to css(editorBackground),
                "--vscode-editor-foreground" to css(editorForeground),
                "--vscode-editor-selectionBackground" to css(selection),
                "--vscode-editorCursor-foreground" to css(scheme.getColor(EditorColors.CARET_COLOR) ?: editorForeground),
                "--vscode-editorLineNumber-foreground" to css(scheme.getColor(EditorColors.LINE_NUMBERS_COLOR) ?: muted),
                "--vscode-editorWidget-background" to css(popup),
                "--vscode-editorWidget-foreground" to css(foreground),
                "--vscode-editorWidget-border" to css(border),
                "--vscode-editorHoverWidget-background" to css(popup),
                "--vscode-editorHoverWidget-border" to css(border),
                "--vscode-sideBar-background" to css(background),
                "--vscode-sideBar-foreground" to css(foreground),
                "--vscode-sideBarTitle-foreground" to css(foreground),
                "--vscode-sideBarSectionHeader-background" to css(background),
                "--vscode-sideBarSectionHeader-foreground" to css(foreground),
                "--vscode-sideBarSectionHeader-border" to css(border),
                "--vscode-panel-background" to css(background),
                "--vscode-panel-border" to css(border),
                "--vscode-panelTitle-activeForeground" to css(foreground),
                "--vscode-button-background" to css(buttonBackground),
                "--vscode-button-foreground" to css(named("Button.default.foreground", Color.WHITE)),
                "--vscode-button-hoverBackground" to css(lighter(buttonBackground, dark)),
                "--vscode-button-border" to "transparent",
                "--vscode-button-secondaryBackground" to css(named("Button.startBackground", background)),
                "--vscode-button-secondaryForeground" to css(named("Button.foreground", foreground)),
                "--vscode-button-secondaryHoverBackground" to css(lighter(named("Button.startBackground", background), dark)),
                "--vscode-input-background" to css(inputBackground),
                "--vscode-input-foreground" to css(named("TextField.foreground", foreground)),
                "--vscode-input-border" to css(named("Component.borderColor", border)),
                "--vscode-input-placeholderForeground" to css(muted),
                "--vscode-inputOption-hoverBackground" to css(JBUI.CurrentTheme.ActionButton.hoverBackground()),
                "--vscode-inputOption-activeBorder" to css(JBUI.CurrentTheme.Focus.focusColor()),
                "--vscode-inputValidation-errorBorder" to css(named("Component.errorFocusColor", JBColor.RED)),
                "--vscode-dropdown-background" to css(named("ComboBox.background", inputBackground)),
                "--vscode-dropdown-foreground" to css(named("ComboBox.foreground", foreground)),
                "--vscode-dropdown-border" to css(named("Component.borderColor", border)),
                "--vscode-dropdown-listBackground" to css(popup),
                "--vscode-checkbox-background" to css(inputBackground),
                "--vscode-checkbox-foreground" to css(foreground),
                "--vscode-checkbox-border" to css(named("Component.borderColor", border)),
                "--vscode-list-hoverBackground" to css(JBUI.CurrentTheme.List.Hover.background(true)),
                "--vscode-list-activeSelectionBackground" to css(listSelection),
                "--vscode-list-activeSelectionForeground" to css(listForeground),
                "--vscode-list-inactiveSelectionBackground" to css(JBUI.CurrentTheme.List.Selection.background(false)),
                "--vscode-list-focusOutline" to css(JBUI.CurrentTheme.Focus.focusColor()),
                "--vscode-list-highlightForeground" to css(link),
                "--vscode-badge-background" to css(named("Counter.background", buttonBackground)),
                "--vscode-badge-foreground" to css(named("Counter.foreground", Color.WHITE)),
                "--vscode-progressBar-background" to css(named("ProgressBar.progressColor", buttonBackground)),
                "--vscode-textLink-foreground" to css(link),
                "--vscode-textLink-activeForeground" to css(JBUI.CurrentTheme.Link.Foreground.HOVERED),
                "--vscode-textPreformat-foreground" to css(editorForeground),
                "--vscode-textPreformat-background" to css(alpha(editorForeground, 0.1)),
                "--vscode-textCodeBlock-background" to css(alpha(editorForeground, 0.08)),
                "--vscode-textBlockQuote-background" to css(alpha(editorForeground, 0.06)),
                "--vscode-textBlockQuote-border" to css(alpha(link, 0.5)),
                "--vscode-textSeparator-foreground" to css(border),
                "--vscode-toolbar-hoverBackground" to css(JBUI.CurrentTheme.ActionButton.hoverBackground()),
                "--vscode-keybindingLabel-background" to css(alpha(editorForeground, 0.1)),
                "--vscode-keybindingLabel-foreground" to css(foreground),
                "--vscode-keybindingLabel-border" to css(border),
                "--vscode-keybindingLabel-bottomBorder" to css(border),
                "--vscode-scrollbarSlider-background" to css(alpha(foreground, 0.2)),
                "--vscode-scrollbarSlider-hoverBackground" to css(alpha(foreground, 0.35)),
                "--vscode-scrollbarSlider-activeBackground" to css(alpha(foreground, 0.5)),
                "--vscode-tab-activeBackground" to css(editorBackground),
                "--vscode-tab-activeForeground" to css(foreground),
                "--vscode-tab-inactiveForeground" to css(muted),
                "--vscode-menu-background" to css(popup),
                "--vscode-menu-foreground" to css(foreground),
                "--vscode-menu-selectionBackground" to css(listSelection),
                "--vscode-menu-selectionForeground" to css(listForeground),
                "--vscode-notifications-background" to css(popup),
                "--vscode-notifications-foreground" to css(foreground),
            )
            return WebviewTheme(vars, if (dark) "vscode-dark" else "vscode-light", if (dark) "Dark" else "Light")
        }

        private fun named(key: String, fallback: Color): Color = JBColor.namedColor(key, fallback)

        private fun alpha(color: Color, alpha: Double) = Color(color.red, color.green, color.blue, (alpha * 255).toInt())

        private fun lighter(color: Color, dark: Boolean): Color = if (dark) color.brighter() else color.darker()

        internal fun css(color: Color): String =
            if (color.alpha == 255) "#%02x%02x%02x".format(color.red, color.green, color.blue)
            else "rgba(%d, %d, %d, %.3f)".format(java.util.Locale.ROOT, color.red, color.green, color.blue, color.alpha / 255.0)
    }
}
