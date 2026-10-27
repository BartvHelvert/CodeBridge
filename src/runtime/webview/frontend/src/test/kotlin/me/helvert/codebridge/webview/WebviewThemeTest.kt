package me.helvert.codebridge.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WebviewThemeTest {
    private fun hex(text: String) = VsCodeColor.hex(text)!!

    private fun theme(dark: Boolean, sets: Map<String, String>, contributed: Map<String, Map<String, String>> = emptyMap()) =
        WebviewTheme.of(dark, sets.mapValues { hex(it.value) }, emptyMap(), contributed)

    @Test
    fun `extension colors follow the frontend theme and resolve aliases`() {
        val colors = mapOf(
            "gitlens.graphScrollMarkerHeadColor" to mapOf("light" to "#1e9f4d", "dark" to "#59cf84"),
            "example.alias" to mapOf("dark" to "gitlens.graphScrollMarkerHeadColor"),
            "example.link" to mapOf("dark" to "textLink.foreground"),
        )
        val dark = theme(true, mapOf("textLink.foreground" to "#abcdef"), colors)
        assertEquals("#59cf84", dark.vars["--vscode-gitlens-graphScrollMarkerHeadColor"])
        assertEquals("#59cf84", dark.vars["--vscode-example-alias"])
        assertEquals("#abcdef", dark.vars["--vscode-example-link"])
        val light = theme(false, emptyMap(), colors)
        assertEquals("#1e9f4d", light.vars["--vscode-gitlens-graphScrollMarkerHeadColor"])
    }

    @Test
    fun `invalid defaults and alias cycles cannot leak into styles`() {
        val colors = mapOf(
            "a" to mapOf("dark" to "b"),
            "b" to mapOf("dark" to "a"),
            "missing" to mapOf("dark" to "unknown"),
            "invalid" to mapOf("dark" to "#fff; background: red"),
            "bad;id" to mapOf("dark" to "#fff"),
            "foreground" to mapOf("dark" to "#000"),
        )
        val theme = theme(true, mapOf("foreground" to "#fff"), colors)
        assertEquals("#ffffff", theme.vars["--vscode-foreground"])
        for (name in listOf("a", "b", "missing", "invalid", "bad;id")) assertNull(theme.vars["--vscode-$name"])
        assertFalse(theme.css.contains("background: red"))
    }

    @Test
    fun `colors the IDE does not set follow VS Code's defaults`() {
        val sets = mapOf("list.activeSelectionBackground" to "#0060c0", "list.activeSelectionForeground" to "#ffffff")
        // `banner.background` is the selection in a dark theme, and the selection darkened by 30% in a light one.
        assertEquals("#0060c0", theme(true, sets).vars["--vscode-banner-background"])
        assertEquals("#004386", theme(false, sets).vars["--vscode-banner-background"])
        assertEquals("#ffffff", theme(false, sets).vars["--vscode-banner-foreground"])
        // Defaults of its own where nothing is set: `editorError.foreground`.
        assertEquals("#f14c4c", theme(true, emptyMap()).vars["--vscode-editorError-foreground"])
    }

    @Test
    fun `transformed defaults are computed as VS Code computes them`() {
        val theme = theme(true, mapOf("checkbox.background" to "#000000", "checkbox.foreground" to "#ffffff"))
        // A mix, named as VS Code names it: only the first dot becomes a dash.
        assertEquals("#545454", theme.vars["--vscode-checkbox-disabled.background"])
        // A transparent color.
        assertEquals("rgba(155, 185, 85, 0.2)", theme.vars["--vscode-diffEditor-insertedLineBackground"])
    }

    @Test
    fun `colors without a default for the theme's kind are left out`() {
        assertNull(theme(true, emptyMap()).vars["--vscode-contrastBorder"])
    }

    @Test
    fun `sizes are there too`() {
        assertEquals("6px", theme(true, emptyMap()).vars["--vscode-cornerRadius-medium"])
    }
}
