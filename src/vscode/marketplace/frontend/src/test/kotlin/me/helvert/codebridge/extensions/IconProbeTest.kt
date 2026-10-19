package me.helvert.codebridge.extensions

import com.intellij.icons.AllIcons
import kotlin.test.Test

/**
 * Reports whether `AllIcons` resolve outside a running IDE.
 *
 * The rendered screenshots show no plugin logo and no download/rating glyphs. That is only
 * acceptable if it is a limitation of rendering headlessly; if the icons resolve here and still do
 * not paint, the row is wiring them wrongly and the screenshots are hiding a real defect.
 */
class IconProbeTest {

    @Test
    fun `report whether AllIcons resolve headlessly`() {
        runCatching {
            val laf = Class.forName("com.intellij.ide.ui.laf.IntelliJLaf")
                .getDeclaredConstructor().newInstance() as javax.swing.LookAndFeel
            javax.swing.UIManager.setLookAndFeel(laf)
        }
        for ((name, icon) in listOf(
            "Plugins.PluginLogo" to AllIcons.Plugins.PluginLogo,
            "Plugins.Downloads" to AllIcons.Plugins.Downloads,
            "Plugins.Rating" to AllIcons.Plugins.Rating,
        )) {
            println("  $name -> ${icon.iconWidth}x${icon.iconHeight} (${icon.javaClass.simpleName})")
        }
    }
}
