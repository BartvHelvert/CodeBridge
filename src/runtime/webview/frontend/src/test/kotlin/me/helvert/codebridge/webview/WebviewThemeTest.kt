package me.helvert.codebridge.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebviewThemeTest {
    @Test
    fun `extension colors follow the frontend theme and resolve aliases`() {
        val colors = mapOf(
            "gitlens.graphScrollMarkerHeadColor" to mapOf("light" to "#1e9f4d", "dark" to "#59cf84"),
            "example.alias" to mapOf("dark" to "gitlens.graphScrollMarkerHeadColor"),
            "example.link" to mapOf("dark" to "textLink.foreground"),
        )
        val dark = WebviewTheme(mapOf("--vscode-textLink-foreground" to "#abcdef"), "vscode-dark", "Dark")
            .withContributedColors(colors)
        assertEquals("#59cf84", dark.vars["--vscode-gitlens-graphScrollMarkerHeadColor"])
        assertEquals("#59cf84", dark.vars["--vscode-example-alias"])
        assertEquals("#abcdef", dark.vars["--vscode-example-link"])
        val light = WebviewTheme(emptyMap(), "vscode-light", "Light").withContributedColors(colors)
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
        val theme = WebviewTheme(mapOf("--vscode-foreground" to "#fff"), "vscode-dark", "Dark")
            .withContributedColors(colors)
        assertEquals(mapOf("--vscode-foreground" to "#fff"), theme.vars)
        assertFalse(theme.css.contains("background"))
    }
}
