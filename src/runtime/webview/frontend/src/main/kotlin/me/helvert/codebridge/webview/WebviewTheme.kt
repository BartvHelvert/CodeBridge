package me.helvert.codebridge.webview

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.awt.Color

/**
 * The IDE's look, as the CSS variables VS Code gives every webview (`--vscode-editor-background`
 * and so on) and the class it puts on the page's body (`vscode-dark`). Pages are written against
 * these, so a page themed by them follows the IDE's theme.
 *
 * The colors pages commonly read come from the IDE colors that play the same part, since there is
 * no table between the two themes to read them from. Every other color VS Code registers, or an
 * extension contributes, is worked out from those by its defaults, as VS Code does for a theme that
 * does not set it: see [VsCodeThemeDefaults].
 */
internal data class WebviewTheme(val vars: Map<String, String>, val kind: String, val name: String) {

    /** The variables as a `:root` rule, for the page's first paint. */
    val css: String get() = vars.entries.joinToString(" ", ":root { ", " }") { (name, value) -> "$name: $value;" }

    /** What the page's script reads: see `bootstrap.js`. */
    val json: String get() = buildJsonObject {
        putJsonObject("vars") { vars.forEach { (name, value) -> put(name, value) } }
        put("kind", kind)
        put("name", name)
    }.toString()

    companion object {
        /**
         * The theme now. [panel] is for a page in a tool window, whose background is the panel's rather
         * than the editor's; [contributed] are the colors extensions contribute, by id, with their
         * `light` and `dark` defaults.
         */
        fun current(panel: Boolean, contributed: Map<String, Map<String, String>> = emptyMap()): WebviewTheme {
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
            val fonts = linkedMapOf(
                "--vscode-font-family" to "'${font.family}', system-ui, sans-serif",
                "--vscode-font-size" to "${font.size}px",
                "--vscode-font-weight" to "normal",
                "--vscode-editor-font-family" to "'${scheme.editorFontName}', monospace",
                "--vscode-editor-font-size" to "${scheme.editorFontSize}px",
                "--vscode-editor-font-weight" to "normal",
                "--monaco-monospace-font" to "'${scheme.editorFontName}', monospace",
            )
            val colors = linkedMapOf(
                "foreground" to foreground,
                "descriptionForeground" to muted,
                "disabledForeground" to muted,
                "errorForeground" to named("Label.errorForeground", JBColor.RED),
                "icon.foreground" to foreground,
                "focusBorder" to JBUI.CurrentTheme.Focus.focusColor(),
                "widget.border" to border,
                "widget.shadow" to alpha(Color.BLACK, if (dark) 0.36 else 0.16),
                "selection.background" to selection,
                "editor.background" to editorBackground,
                "editor.foreground" to editorForeground,
                "editor.selectionBackground" to selection,
                "editorCursor.foreground" to (scheme.getColor(EditorColors.CARET_COLOR) ?: editorForeground),
                "editorLineNumber.foreground" to (scheme.getColor(EditorColors.LINE_NUMBERS_COLOR) ?: muted),
                "editorWidget.background" to popup,
                "editorWidget.foreground" to foreground,
                "editorWidget.border" to border,
                "editorHoverWidget.background" to popup,
                "editorHoverWidget.border" to border,
                "sideBar.background" to background,
                "sideBar.foreground" to foreground,
                "sideBarTitle.foreground" to foreground,
                "sideBarSectionHeader.background" to background,
                "sideBarSectionHeader.foreground" to foreground,
                "sideBarSectionHeader.border" to border,
                "panel.background" to background,
                "panel.border" to border,
                "panelTitle.activeForeground" to foreground,
                "button.background" to buttonBackground,
                "button.foreground" to named("Button.default.foreground", Color.WHITE),
                "button.hoverBackground" to lighter(buttonBackground, dark),
                "button.secondaryBackground" to named("Button.startBackground", background),
                "button.secondaryForeground" to named("Button.foreground", foreground),
                "button.secondaryHoverBackground" to lighter(named("Button.startBackground", background), dark),
                "input.background" to inputBackground,
                "input.foreground" to named("TextField.foreground", foreground),
                "input.border" to named("Component.borderColor", border),
                "input.placeholderForeground" to muted,
                "inputOption.hoverBackground" to JBUI.CurrentTheme.ActionButton.hoverBackground(),
                "inputOption.activeBorder" to JBUI.CurrentTheme.Focus.focusColor(),
                "inputValidation.errorBorder" to named("Component.errorFocusColor", JBColor.RED),
                "dropdown.background" to named("ComboBox.background", inputBackground),
                "dropdown.foreground" to named("ComboBox.foreground", foreground),
                "dropdown.border" to named("Component.borderColor", border),
                "dropdown.listBackground" to popup,
                "checkbox.background" to inputBackground,
                "checkbox.foreground" to foreground,
                "checkbox.border" to named("Component.borderColor", border),
                "list.hoverBackground" to JBUI.CurrentTheme.List.Hover.background(true),
                "list.activeSelectionBackground" to listSelection,
                "list.activeSelectionForeground" to listForeground,
                "list.inactiveSelectionBackground" to JBUI.CurrentTheme.List.Selection.background(false),
                "list.focusOutline" to JBUI.CurrentTheme.Focus.focusColor(),
                "list.highlightForeground" to link,
                "badge.background" to named("Counter.background", buttonBackground),
                "badge.foreground" to named("Counter.foreground", Color.WHITE),
                "progressBar.background" to named("ProgressBar.progressColor", buttonBackground),
                "textLink.foreground" to link,
                "textLink.activeForeground" to JBUI.CurrentTheme.Link.Foreground.HOVERED,
                "textPreformat.foreground" to editorForeground,
                "textPreformat.background" to alpha(editorForeground, 0.1),
                "textCodeBlock.background" to alpha(editorForeground, 0.08),
                "textBlockQuote.background" to alpha(editorForeground, 0.06),
                "textBlockQuote.border" to alpha(link, 0.5),
                "textSeparator.foreground" to border,
                "toolbar.hoverBackground" to JBUI.CurrentTheme.ActionButton.hoverBackground(),
                "keybindingLabel.background" to alpha(editorForeground, 0.1),
                "keybindingLabel.foreground" to foreground,
                "keybindingLabel.border" to border,
                "keybindingLabel.bottomBorder" to border,
                "scrollbarSlider.background" to alpha(foreground, 0.2),
                "scrollbarSlider.hoverBackground" to alpha(foreground, 0.35),
                "scrollbarSlider.activeBackground" to alpha(foreground, 0.5),
                "tab.activeBackground" to editorBackground,
                "tab.activeForeground" to foreground,
                "tab.inactiveForeground" to muted,
                "menu.background" to popup,
                "menu.foreground" to foreground,
                "menu.selectionBackground" to listSelection,
                "menu.selectionForeground" to listForeground,
                "notifications.background" to popup,
                "notifications.foreground" to foreground,
            )
            return of(dark, colors.mapValues { VsCodeColor.of(it.value) }, fonts, contributed)
        }

        /** The theme with the colors [sets], [fonts], and everything else from the defaults. */
        internal fun of(
            dark: Boolean,
            sets: Map<String, VsCodeColor>,
            fonts: Map<String, String>,
            contributed: Map<String, Map<String, String>>,
        ): WebviewTheme {
            val kind = if (dark) "dark" else "light"
            // Contributed ids become CSS names, so only the characters VS Code allows in them.
            val contributedDefaults = contributed.filterKeys { it.matches(Regex("[a-zA-Z0-9_.-]+")) }
                .mapValues { (_, defaults) ->
                    fun value(kind: String) = defaults[kind]?.let(::JsonPrimitive) ?: JsonNull
                    JsonObject(mapOf("light" to value("light"), "dark" to value("dark")))
                }
            val colors = VsCodeThemeDefaults.resolve(kind, sets, VsCodeThemeDefaults.colors + contributedDefaults)
            // As VS Code names them: a color's first dot becomes a dash, and a size's every dot.
            val vars = fonts +
                colors.map { (id, color) -> "--vscode-${id.replaceFirst('.', '-')}" to color.css } +
                VsCodeThemeDefaults.sizes(kind).map { (id, size) -> "--vscode-${id.replace('.', '-')}" to size }
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
