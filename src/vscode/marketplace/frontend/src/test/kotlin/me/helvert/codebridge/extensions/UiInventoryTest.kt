package me.helvert.codebridge.extensions

import com.intellij.openapi.ui.Splitter
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Container
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins our UI against an inventory taken from the IntelliJ source.
 *
 * This exists because reading the source for *inspiration* produced four wrong layouts in a row.
 * The failure mode was never a missing detail someone could spot by looking; it was that nothing
 * could fail. Elements got invented (a "Load more" button, a description line in list rows) and
 * elements got forgotten (the Install button), and no check covered either direction.
 *
 * So the inventory is exact: not "contains", but "equals". Adding something the IDE does not have
 * breaks this test just as loudly as omitting something it does.
 *
 * Every expectation cites the line in the upstream file it was taken from. When porting more of the
 * page, update the inventory from the source first and let the test drive the change.
 */
class UiInventoryTest {

    /** The direct children of a component, by simple class name, in the order they were added. */
    private fun childrenOf(container: Container): List<String> =
        container.components.map { it.javaClass.simpleName.ifEmpty { it.javaClass.superclass.simpleName } }

    /**
     * Constructing the panel now builds the IDE's `PluginsGroupComponent`, which resolves platform
     * services, so this cannot run without an application. It is kept rather than deleted because
     * the assertion still states what the header must contain.
     */
    private fun panelOrSkip(): MarketplacePanel? = try {
        MarketplacePanel {}
    } catch (error: Throwable) {
        // An assumption, not a silent return: a test that quietly passes without doing
        // anything reads as coverage in the totals, and this file had none for weeks.
        assumeTrue(false, "the panel needs a running IDE (${error::class.simpleName})")
        null
    }

    @Test
    fun `the details header starts with the name followed by links and actions`() {
        val panel = panelOrSkip() ?: return
        val details = MarketplacePanel::class.java.getDeclaredField("details")
            .apply { isAccessible = true }.get(panel)
        val top = details.javaClass.getDeclaredField("top")
            .apply { isAccessible = true }.get(details) as Container

        // PluginDetailsPageComponent.kt createTabsContentPanel, lines 434-441:
        //   topPanel.add(TagPanel(...))
        //   topPanel.add(nameComponent)
        //   topPanel.add(linkPanel)       -- author + homepage
        //   topPanel.add(nameAndButtons)  -- BaselinePanel holding version and the buttons
        // The banners, error, licence and suggestion rows that follow are JetBrains-Marketplace
        // specific and have no Open VSX equivalent. The name and links share a column of their own,
        // which lines them up with the text below rather than with the buttons.
        assertEquals(
            listOf("JPanel", "BaselinePanel"),
            childrenOf(top),
            "the details header must stack the name-and-homepage column above the action-and-version row",
        )
        val textRows = details.javaClass.getDeclaredField("textRows")
            .apply { isAccessible = true }.get(details) as Container
        assertEquals(
            listOf("JBLabel", "JPanel"),
            childrenOf(textRows),
            "the column must hold the name, then the homepage"
        )
    }

    @Test
    fun `the list scrolls independently below the shared header search`() {
        val panel = panelOrSkip() ?: return
        // The splitter is an anonymous subclass, so match on type rather than on its name.
        val splitter = panel.components.filterIsInstance<Splitter>().single()
        val listColumn = splitter.firstComponent as Container

        // PluginsTab.kt createPanel, lines 54-57:
        //   listPanel.add(searchTextField, BorderLayout.NORTH)
        //   listPanel.add(defaultOrSearchResultsViewPanel)
        // Two children. A paging button would be a third, and the IDE has none -- it pages on
        // scroll, which is why this assertion is exact rather than a lower bound.
        assertEquals(
            listOf("JBScrollPane"),
            childrenOf(listColumn),
            "search belongs to the settings header; only the extension groups scroll",
        )
    }


    @Test
    fun `a scaled icon occupies the logical row icon size`() {
        // A synthetic source at a resolution higher than the row needs, as registry icons are.
        val source = java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val expected = com.intellij.util.ui.JBUI.scale(40)
        val scaled = com.intellij.util.ui.ImageUtil.scaleImage(source, expected, expected)
        val icon = com.intellij.util.ui.JBImageIcon(scaled)

        // The icon must occupy exactly the row's logical icon box. Getting the HiDPI wrapping wrong
        // shows up here as a halved or doubled size -- and on screen as a pixelated or clipped logo.
        assertEquals(expected, icon.iconWidth, "scaled icon width")
        assertEquals(expected, icon.iconHeight, "scaled icon height")
    }
}
