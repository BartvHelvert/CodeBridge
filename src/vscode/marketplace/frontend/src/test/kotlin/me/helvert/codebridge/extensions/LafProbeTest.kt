package me.helvert.codebridge.extensions

import javax.swing.UIManager
import kotlin.test.Test

/**
 * Finds out whether the IDE's look and feel can be installed outside a running IDE.
 *
 * It matters because the renders are only worth comparing if they use the real one: under the
 * default Metal LaF the accent button ignores the `JButton.*Color` client properties, `AllIcons`
 * resolve to nothing, and every `namedColor` falls back. A screenshot taken that way cannot answer
 * "does this match".
 */
class LafProbeTest {

    @Test
    fun `report whether an intellij look and feel installs headlessly`() {
        for (name in listOf(
            "com.intellij.ide.ui.laf.IntelliJLaf",
            "com.intellij.ide.ui.laf.darcula.DarculaLaf",
        )) {
            val outcome = runCatching {
                val laf = Class.forName(name).getDeclaredConstructor().newInstance() as javax.swing.LookAndFeel
                UIManager.setLookAndFeel(laf)
                val probe = UIManager.get("Plugins.background")
                "installed; Plugins.background=$probe"
            }.getOrElse { "${it::class.simpleName}: ${it.message?.take(160)}" }
            println("  $name -> $outcome")
        }
    }
}
