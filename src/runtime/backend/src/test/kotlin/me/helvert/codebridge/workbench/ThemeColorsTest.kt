package me.helvert.codebridge.workbench

import com.intellij.ui.JBColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Theme colour ids an extension contributes, and VS Code's own, as the colours they stand for. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class ThemeColorsTest {

    private val colors = ThemeColors(
        listOf(
            Json.parseToJsonElement(
                """{"contributes":{"colors":[
        {"id":"ext.stale","defaults":{"light":"#112233","dark":"#445566"}},
        {"id":"ext.faded","defaults":{"light":"#11223380","dark":"#abc"}},
        {"id":"ext.alias","defaults":{"light":"ext.stale","dark":"ext.stale"}},
        {"id":"ext.loop","defaults":{"light":"ext.loop"}}
    ]}}"""
            ) as JsonObject
        )
    )

    /** What the colour is under the theme the test runs with. */
    private fun shown(light: Int, dark: Int, alpha: Boolean = false) =
        Color(if (JBColor.isBright()) light else dark, alpha).rgb

    @Test
    fun `contributed ids have their declared defaults, following other ids`() {
        assertEquals(shown(0x112233, 0x445566), colors.resolve("ext.stale")?.rgb)
        assertEquals(shown(0x80112233.toInt(), 0xFFAABBCC.toInt(), alpha = true), colors.resolve("ext.faded")?.rgb)
        assertEquals(shown(0x112233, 0x445566), colors.resolve("ext.alias")?.rgb)
    }

    @Test
    fun `VS Code's own ids have its defaults, and unknown or circular ones none`() {
        assertEquals(shown(0xB01011, 0xF88070), colors.resolve("list.errorForeground")?.rgb)
        assertNull(colors.resolve("nobody.defines.this"))
        assertNull(colors.resolve("ext.loop"))
    }

    @Test
    fun `colour customizations apply everywhere, and a theme's block over them where its name matches`() {
        val settings = """{
            // JSONC, as settings.json is.
            "workbench.colorCustomizations": {
                "errorLens.errorForeground": "#ff0000",
                "editorError.foreground": "#00ff00",
                "[*Dark*]": { "errorLens.errorForeground": "#0000ff" },
                "[Light]": { "editorError.foreground": "#123456" },
            },
        }"""
        assertEquals(
            mapOf("errorLens.errorForeground" to "#0000ff", "editorError.foreground" to "#00ff00"),
            ThemeColors.Customizations.of(settings, "Darcula Dark"),
        )
        assertEquals(
            mapOf("errorLens.errorForeground" to "#ff0000", "editorError.foreground" to "#123456"),
            ThemeColors.Customizations.of(settings, "Light"),
        )
        assertEquals(emptyMap(), ThemeColors.Customizations.of("{}", "Light"))
        assertEquals(emptyMap(), ThemeColors.Customizations.of("not json", "Light"))
        assertEquals(emptyMap(), ThemeColors.Customizations.of(null, "Light"))
    }
}
