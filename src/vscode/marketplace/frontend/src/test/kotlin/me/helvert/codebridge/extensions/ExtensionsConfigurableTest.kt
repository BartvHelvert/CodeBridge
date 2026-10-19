package me.helvert.codebridge.extensions

import com.intellij.openapi.options.Configurable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two markers the settings dialog reads before it ever sees our component.
 *
 * Both were missing, and the symptom looked nothing like the cause: without `NoScroll` the dialog
 * wraps the page in a scroll pane, so a descendant asking for more width than it has -- a wrapping
 * description in a tab that had never been laid out -- made the whole page scroll sideways instead
 * of being squeezed to fit. `PluginManagerConfigurable` carries both; a page that means to look
 * like it has to carry them too.
 *
 * Cheap to check and impossible to notice missing, which is exactly why it is worth a test.
 */
class ExtensionsConfigurableTest {

    @Test
    fun `declares the markers the IDE's own plugin page declares`() {
        val page = VsCodeExtensionsConfigurable::class.java
        assertTrue(
            Configurable.NoScroll::class.java.isAssignableFrom(page),
            "the settings dialog must not wrap the page in a scroll pane"
        )
        assertTrue(
            Configurable.NoMargin::class.java.isAssignableFrom(page),
            "the page sits flush, like the IDE's plugin page"
        )
    }
}
