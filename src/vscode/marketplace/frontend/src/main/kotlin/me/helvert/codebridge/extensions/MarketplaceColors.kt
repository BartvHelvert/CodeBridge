package me.helvert.codebridge.extensions

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil

/**
 * The colours the IDE's plugin page uses, by the same theme keys.
 *
 * Shared between both tabs because the IDE shares them: `InstalledPluginsTab` and
 * `MarketplacePluginsTab` are both `PluginsTab`, painted from the same `PluginManagerConfigurable`
 * constants. Reading the keys rather than hardcoding values means a custom or accessibility theme
 * restyles these pages along with the IDE's own.
 *
 * Fallbacks are the platform's, taken from `PluginManagerConfigurable` and `ListPluginComponent`.
 */
object MarketplaceColors {
    val MAIN_BG: JBColor = JBColor.namedColor("Plugins.background", UIUtil.getListBackground())
    val SEARCH_BG: JBColor = JBColor.namedColor("Plugins.SearchField.background", MAIN_BG)
    val BORDER: JBColor = JBColor.namedColor("Plugins.borderColor", JBColor.border())
    val SELECTION_BG: JBColor = JBColor.namedColor("Plugins.lightSelectionBackground", 0xEDF6FE, 0x464A4D)
    val HOVER_BG: JBColor = JBColor.namedColor("Plugins.hoverBackground", 0xEDF6FE, 0x464A4D)
    val DISABLED_FG: JBColor = JBColor.namedColor("Plugins.disabledForeground", 0xB1B1B1, 0x696969)
    val GRAY: JBColor = JBColor.namedColor("Label.infoForeground", 0x787878, 0x878787)
}
