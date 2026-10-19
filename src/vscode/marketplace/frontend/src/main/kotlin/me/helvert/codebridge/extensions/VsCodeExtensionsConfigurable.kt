package me.helvert.codebridge.extensions
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.BuildStamp
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * VS Code extensions, shown as a top-level page directly below the IDE's own Plugins page.
 *
 * A sibling page rather than a tab is a constraint, not a preference:
 * `PluginManagerConfigurable` is final and internal, and every customization extension point
 * around it (`pluginsViewCustomizer`, `pluginManagerCustomizer`, `pluginInstallationCustomization`)
 * is `@ApiStatus.Internal` and none of them adds a tab. A matching `groupWeight` is the supported way to
 * appear where users already look for this, and it keeps VS Code extensions clearly distinct from IDE
 * plugins -- which they are, since nothing here installs into the IDE.
 *
 * Installs take effect immediately, so there is nothing for Apply to do.
 *
 * [Configurable.NoScroll] and [Configurable.NoMargin] are the same two markers
 * `PluginManagerConfigurable` carries, and both are load-bearing. Without `NoScroll` the settings
 * dialog wraps the page in a scroll pane, and then any descendant that asks for more width than it
 * has -- a wrapping description in a tab that has never been laid out, say -- makes the whole page
 * scroll sideways instead of being squeezed to fit. Without `NoMargin` the dialog insets it by 16,
 * so the page does not sit flush the way the IDE's plugin page does.
 */
class VsCodeExtensionsConfigurable :
    SearchableConfigurable, Configurable.NoScroll, Configurable.NoMargin, Configurable.TopComponentProvider {

    private var panel: ExtensionsPage? = null

    override fun getId(): String = "me.helvert.codebridge.extensions"
    override fun getDisplayName(): String = "VS Code Extensions"
    override fun createComponent(): JComponent = page()

    /** The shared search field and gear live beside the settings page title. */
    override fun getCenterComponent(controller: Configurable.TopComponentController): Component =
        page().header

    private fun page(): ExtensionsPage = panel ?: ExtensionsPage().also {
        // Which build is actually running. Not on the page -- the IDE's plugin page has no footer
        // -- but a stale IDE still has to be tellable from a change that did not take.
        thisLogger().info(BuildStamp.describe())
        panel = it
        it.reset()
    }

    override fun isModified(): Boolean = false
    override fun apply() = Unit
    override fun reset() { panel?.reset() }
    override fun disposeUIResources() {
        panel?.let(Disposer::dispose)
        panel = null
    }
}

/** One searchable page, with installed extensions and registry suggestions in the same list. */
private class ExtensionsPage : JPanel(BorderLayout()), Disposable {
    private val marketplace = MarketplacePanel(onInstalled = { })
    val header = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }

    init {
        Disposer.register(this, marketplace)
        header.isOpaque = false
        // As `UnifiedPluginsPageView.searchComponent`: no wider than this, shrinking only when the
        // header runs out of room, with the gear right beside it rather than at the far end.
        val searchWidth = JBUI.scale(SEARCH_COMPONENT_WIDTH)
        marketplace.search.preferredSize = Dimension(searchWidth, marketplace.search.preferredSize.height)
        marketplace.search.maximumSize = Dimension(searchWidth, marketplace.search.preferredSize.height)
        marketplace.search.minimumSize = Dimension(0, marketplace.search.minimumSize.height)
        header.add(marketplace.search)
        header.add(Box.createHorizontalStrut(JBUI.scale(8)))
        val actions = DefaultActionGroup()
        marketplace.search.textEditor.putClientProperty(
            "search.extension",
            com.intellij.ui.components.fields.ExtendableTextComponent.Extension.create(
                com.intellij.icons.AllIcons.General.Filter, "Filter extensions"
            ) {
                val filters = DefaultActionGroup()
                filters.add(object : DumbAwareAction("All Extensions") {
                    override fun actionPerformed(e: AnActionEvent) {
                        marketplace.filterInstalledOnly(false)
                    }
                })
                filters.add(object : DumbAwareAction("Installed") {
                    override fun actionPerformed(e: AnActionEvent) {
                        marketplace.filterInstalledOnly(true)
                    }
                })
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createActionGroupPopup(
                        "Show", filters,
                        com.intellij.ide.DataManager.getInstance().getDataContext(marketplace.search),
                        true, null, Int.MAX_VALUE
                    )
                    .showUnderneathOf(marketplace.search)
            }
        )
        marketplace.search.textEditor.updateUI()
        actions.add(object :
            DumbAwareAction("Extension Settings", null, com.intellij.icons.AllIcons.General.GearPlain) {
            override fun actionPerformed(event: AnActionEvent) {
                val settings = DefaultActionGroup()
                settings.add(object : DumbAwareAction("Install Extension from Disk…") {
                    override fun actionPerformed(e: AnActionEvent) {
                        InstallVsixAction.chooseAndInstall(e.project, this@ExtensionsPage)
                    }
                })
                val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createActionGroupPopup(null, settings, event.dataContext, true, null, Int.MAX_VALUE)
                // Under the gear that opened it; a keyboard invocation has no button to anchor to.
                val gear = event.inputEvent?.component
                if (gear != null) popup.showUnderneathOf(gear) else popup.showInBestPositionFor(event.dataContext)
            }
        })
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("CodeBridgeMarketplaceHeader", actions, true)
        toolbar.targetComponent = this
        // As the IDE's own plugin-page gear: the auto layout, reserving room for an overflow arrow,
        // can decide a lone button does not fit and park it off-screen, where it is never drawn.
        toolbar.isReservePlaceAutoPopupIcon = false
        toolbar.layoutStrategy = com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy.NOWRAP_STRATEGY
        toolbar.component.isOpaque = false
        // Held at its own size, so the glue after it takes the rest of the row. Asked each layout,
        // since the toolbar fills in its actions after it is first shown.
        header.add(object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                add(toolbar.component, BorderLayout.CENTER)
            }

            override fun getMaximumSize(): Dimension = preferredSize
        })
        header.add(Box.createHorizontalGlue())
        add(marketplace, BorderLayout.CENTER)
    }

    fun reset() {
        marketplace.activate()
    }
    override fun dispose() = Unit

    private companion object {
        /** `UnifiedPluginsPageView.SEARCH_COMPONENT_WIDTH`. Unscaled. */
        const val SEARCH_COMPONENT_WIDTH = 340
    }
}
