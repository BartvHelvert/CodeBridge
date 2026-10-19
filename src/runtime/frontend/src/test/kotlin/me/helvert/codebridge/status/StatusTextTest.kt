package me.helvert.codebridge.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RowIcon
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.icons.ContributedIcons
import kotlin.test.*

/**
 * VS Code status bar text read into a label, an icon and a tooltip.
 *
 * The strings are the ones rust-analyzer actually writes, because it is the extension this exists
 * for: its item is the only sign a user gets that the server is still loading or has failed.
 */
class StatusTextTest {

    // Headless -- a Linux CI runner -- the platform draws every icon as one black pixel unless told
    // otherwise, and these tests are about what the icons draw.
    @BeforeTest
    fun activateIcons() = IconLoader.activate()

    @Test
    fun `a spinning codicon is lifted out of the text and becomes the spinner`() {
        val parsed = StatusText.parse("$(loading~spin) rust-analyzer")

        assertEquals("rust-analyzer", parsed.label)
        assertEquals(listOf(Codicons.Reference("loading", spin = true)), parsed.icons)
        assertSame(AnimatedIcon.Default.INSTANCE, CodeBridgeStatusBarWidget.iconFor(parsed.icons, null))
    }

    @Test
    fun `a ready server is plain text with no icon`() {
        val parsed = StatusText.parse("rust-analyzer")

        assertEquals("rust-analyzer", parsed.label)
        assertNull(CodeBridgeStatusBarWidget.iconFor(parsed.icons, null))
    }

    @Test
    fun `known codicons map to the IDE's own icons`() {
        assertSame(AllIcons.General.Warning, CodeBridgeStatusBarWidget.iconFor(StatusText.parse("$(warning) x").icons, null))
        assertSame(AllIcons.General.Error, CodeBridgeStatusBarWidget.iconFor(StatusText.parse("$(error) x").icons, null))
        assertSame(
            AllIcons.Actions.Suspend,
            CodeBridgeStatusBarWidget.iconFor(StatusText.parse("$(stop-circle) x").icons, null)
        )
    }

    @Test
    fun `a severity still says something is wrong`() {
        // IntelliJ's status bar has no coloured backgrounds, which is how VS Code says it.
        assertSame(
            AllIcons.General.Warning,
            CodeBridgeStatusBarWidget.iconFor(StatusText.parse("rust-analyzer").icons, "warning")
        )
        assertSame(
            AllIcons.General.Error,
            CodeBridgeStatusBarWidget.iconFor(StatusText.parse("$(error) x").icons, "error"),
            "Not twice"
        )

        val parsed = StatusText.parse("$(unheard-of) rust-analyzer")
        assertEquals("rust-analyzer", parsed.label, "An unknown codicon is dropped, not printed")
        val row = assertIs<RowIcon>(CodeBridgeStatusBarWidget.iconFor(parsed.icons, "warning"))
        assertSame(AllIcons.General.Warning, row.getIcon(0), "The severity leads")
        val icon = assertIs<ContributedIcons.Contributed>(CodeBridgeStatusBarWidget.iconFor(parsed.icons, null))
        assertSame(Codicons.DEFAULT, icon.current, "Without one, the default icon, unless an extension contributes it")
    }

    @Test
    fun `every icon of an item is drawn, in order`() {
        // GitLens's Launchpad while no integration is connected.
        val parsed = StatusText.parse("$(rocket)$(gitlens-unplug) Launchpad")

        assertEquals("Launchpad", parsed.label)
        val row = assertIs<RowIcon>(CodeBridgeStatusBarWidget.iconFor(parsed.icons, null))
        val icons = row.allIcons.filter { it.iconWidth > JBUI.scale(2) }
        assertEquals(Codicons.icon("rocket"), icons[0])
        assertEquals("gitlens-unplug", assertIs<ContributedIcons.Contributed>(icons[1]).id)
        assertEquals(2, icons.size)
        // The spinner too, beside the icon it is loading for.
        val loading =
            assertIs<RowIcon>(CodeBridgeStatusBarWidget.iconFor(StatusText.parse("$(rocket)$(loading~spin)").icons, null))
        assertSame(AnimatedIcon.Default.INSTANCE, loading.allIcons.last())
    }

    @Test
    fun `an icon in a tooltip is the icon its name is`() {
        // The backend writes `$(name)` as `<icon src="codicon:name">` where the extension asked for icons.
        assertSame(AllIcons.General.Settings, StatusHover.icon("codicon:gear"))
        assertSame(AnimatedIcon.Default.INSTANCE, StatusHover.icon("codicon:sync~spin"))
        assertEquals(
            "gitlens-unplug",
            assertIs<ContributedIcons.Contributed>(StatusHover.icon("codicon:gitlens-unplug")).id
        )
        assertNull(StatusHover.icon("AllIcons.General.Settings"), "Anything else is the pane's to resolve")
    }
}
