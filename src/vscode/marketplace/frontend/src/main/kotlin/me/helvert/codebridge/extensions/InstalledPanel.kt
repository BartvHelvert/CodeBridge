package me.helvert.codebridge.extensions
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Divider
import com.intellij.openapi.ui.Messages
import com.intellij.platform.project.projectId
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.border.CustomLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import me.helvert.codebridge.extensions.ui.BaselinePanel
import me.helvert.codebridge.extensions.ui.OptionButton
import me.helvert.codebridge.extensions.ui.PluginSearchTextField
import me.helvert.codebridge.extensions.ui.RegistryIcon
import me.helvert.codebridge.registry.registryPageUrl
import me.helvert.codebridge.rpc.*
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Installed extension rows and details, embedded in the unified marketplace. */
class InstalledPanel(
    private val embedded: Boolean = false, private val onChosen: (Int) -> Unit = {},
    private val onCount: (Int) -> Unit = {}
) : JPanel(BorderLayout()), Disposable {

    private val search = PluginSearchTextField()
    private val rows = ExtensionList<InstalledExtensionDto>({ it.id }) { choose(it) }
    /**
     * Shown in the list, and only when there is something to say.
     *
     * The IDE's plugin page has no status line, so this one appears where the rows would be and
     * only for a state the list cannot show by itself: loading, or a failure to read the store.
     */
    private val message = JBLabel()

    /**
     * Decoded package icons, keyed by extension identity.
     *
     * Read on the same background pass that lists the extensions, so a row never decodes a PNG on
     * the EDT and never has to wait for one either: by the time rows are built these are here.
     */
    private val icons = HashMap<String, java.awt.Image>()
    private val requests = BackendRequests()

    /**
     * The project the settings were opened from, for what is disabled in its workspace alone; none from the
     * welcome screen. The page is the IDE's, not a project's, as the IDE's own plugin page is.
     */
    private val project: Project? = ProjectUtil.getActiveProject()?.takeUnless { it.isDefault }

    /** Ids of the extensions disabled in [project]'s workspace alone, lower-case. */
    private var disabledHere: Set<String> = emptySet()

    private var model: List<InstalledExtensionDto> = emptyList()
    private var selected: InstalledExtensionDto? = null
    private var disposed = false
    private var refreshVersion = 0

    internal val listContent = JPanel(BorderLayout())
    internal val detailsComponent: JComponent field = DetailsPane()
    internal val installedIds: Set<String> get() = model.map { it.id.lowercase() }.toSet()

    internal fun filter(text: String) {
        search.text = text
    }

    internal fun clearSelection() {
        selected = null; rows.selected = null
    }

    internal fun selectFirst() {
        visible().firstOrNull()?.let(::choose) ?: detailsComponent.clear()
    }

    init {
        search.textEditor.emptyText.text = "Search installed extensions"
        search.textEditor.isOpaque = true
        search.textEditor.background = MarketplaceColors.SEARCH_BG
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildRows()
            override fun removeUpdate(e: DocumentEvent) = rebuildRows()
            override fun changedUpdate(e: DocumentEvent) = rebuildRows()
        })

        val holder = JPanel(BorderLayout())
        holder.isOpaque = true
        holder.background = MarketplaceColors.MAIN_BG
        message.border = JBUI.Borders.empty(16, 20)
        message.foreground = MarketplaceColors.GRAY
        message.isVisible = false

        val stack = listContent
        stack.isOpaque = false
        stack.add(message, BorderLayout.NORTH)
        stack.add(rows, BorderLayout.CENTER)
        holder.add(stack, BorderLayout.NORTH)

        val scroller = JBScrollPane(holder)
        scroller.border = JBUI.Borders.empty()
        scroller.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        val listPanel = JPanel(BorderLayout())
        listPanel.border = CustomLineBorder(MarketplaceColors.BORDER, JBUI.insetsTop(1))
        listPanel.add(search, BorderLayout.NORTH)
        listPanel.add(scroller, BorderLayout.CENTER)

        if (!embedded) {
            val splitter = object : OnePixelSplitter(false, 0.5f) {
                override fun createDivider(): Divider = super.createDivider().also {
                    (it as? JComponent)?.background = MarketplaceColors.BORDER
                }
            }
            splitter.firstComponent = listPanel
            splitter.secondComponent = detailsComponent
            add(splitter, BorderLayout.CENTER)
        }

        if (ApplicationManager.getApplication() != null) {
            requests.watchExtensions(::refresh) { say("Could not load installed extensions: ${it.message}") }
        }
    }

    fun refresh() {
        if (disposed) return
        val version = ++refreshVersion
        say("Loading?")
        requests.request({ marketplace {
            val installed = extensions()
            val loaded = installed.mapNotNull { extension ->
                if (!extension.hasIcon) return@mapNotNull null
                // Decodes bytes already in memory.
                @Suppress("BlockingMethodInNonBlockingContext")
                val image = try {
                    extensionIcon(extension.id)?.let { ImageIO.read(it.inputStream()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                image?.let { extension.id to it }
            }
            Triple(installed, loaded, project?.let { disabledInProject(it.projectId()).toSet() }.orEmpty())
        } }, { (installed, loaded, here) ->
            if (!disposed && version == refreshVersion) {
                icons.clear()
                icons.putAll(loaded)
                disabledHere = here
                model = installed
                selected =
                    installed.firstOrNull { it.id == selected?.id } ?: if (embedded) null else installed.firstOrNull()
                rebuildRows()
                selected?.let { detailsComponent.show(it) } ?: detailsComponent.clear()
                say(null)
            }
        }, { error ->
            if (version == refreshVersion) say("Could not load installed extensions: ${error.message ?: error.javaClass.simpleName}")
        })
    }

    private fun say(text: String?) {
        message.text = text.orEmpty()
        message.isVisible = text != null
    }

    private fun visible(): List<InstalledExtensionDto> {
        val query = search.text.trim().lowercase()
        if (query.isEmpty()) return model
        return model.filter {
            it.id.contains(query) || (it.displayName ?: it.name).lowercase().contains(query)
        }
    }

    private fun rebuildRows() {
        val empty = if (model.isEmpty()) "No extensions installed yet." else "No installed extension matches this search."
        onCount(visible().size)
        rows.show(visible(), empty) { ExtensionRow(it) }
        rows.selected = selected
    }

    private fun choose(extension: InstalledExtensionDto) {
        selected = extension
        rows.selected = extension
        detailsComponent.show(extension)
        onChosen(visible().indexOfFirst { it.id == extension.id })
    }

    private fun uninstall(extension: InstalledExtensionDto) {
        val confirmed = Messages.showYesNoDialog(
            this,
            "Uninstall ${extension.displayName ?: extension.name}?",
            "Uninstall Extension",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return
        try {
            backendModal(this, "Uninstalling ${extension.label}") { marketplace { uninstall(extension.id) }.awaitOperation() }
        } catch (_: ProcessCanceledException) {
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            Messages.showErrorDialog(this, error.message ?: error.javaClass.simpleName, "Could Not Uninstall")
        } finally { refresh() }
    }

    /** Enables or disables [extension] everywhere. */
    private fun setEnabled(extension: InstalledExtensionDto, enabled: Boolean) {
        requests.request({ marketplace { setEnabled(extension.id, enabled) } }, { refresh() }, {
            say("Could not change ${extension.label}: ${it.message}")
            refresh()
        })
    }

    /** Disables [extension] in [project]'s workspace alone, or lifts that; its extension host restarts without it. */
    private fun setDisabledHere(extension: InstalledExtensionDto, disabled: Boolean) {
        val project = project ?: return
        requests.request({ marketplace { setDisabledInProject(project.projectId(), extension.id, disabled) } }, { refresh() }, {
            say("Could not change ${extension.label} in this workspace: ${it.message}")
            refresh()
        })
    }

    override fun dispose() { disposed = true; requests.dispose() }

    /** Installed rows use enable switches; Uninstall is in the detail header's option button. */
    private inner class ExtensionRow(private val extension: InstalledExtensionDto) : ListedRow() {

        override val dimmed: Boolean get() = !extension.enabled || extension.id.lowercase() in disabledHere

        init {
            // The package ships its own icon; `RegistryIcon` falls back to the IDE's plugin logo
            // when it has none, and rasterises at whatever scale the row ends up being painted at.
            icon(RegistryIcon(ICON_SIZE, source = { icons[extension.id] }))
            name(extension.displayName ?: extension.name)

            // One another plugin bundles is switched with that plugin, in Settings | Plugins.
            if (extension.providedBy == null) {
                addButtonComponent(me.helvert.codebridge.extensions.ui.EnableCheckBox(extension.enabled) { enabled ->
                    setEnabled(extension, enabled)
                })
            }
            metrics(
                Metric(extension.version),
            )
        }
    }

    /** The same header and tabs as the marketplace details page. */
    private inner class DetailsPane : JPanel(BorderLayout()) {
        private val name = JBLabel()
        private val links = JPanel(ListLayout.horizontal(12))
        private val version = JBLabel()

        /**
         * Enable or Disable everywhere, as the IDE's plugin page; disabling in this workspace alone
         * and uninstalling are in its arrow, as in VS Code.
         */
        private val action = OptionButton()
        private val nameAndButtons = BaselinePanel(12, false)
        private val top = JPanel(ListLayout.vertical(8, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        private val textRows =
            JPanel(ListLayout.vertical(8, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        private val description = JBTextArea()
        private val facts = JPanel(ListLayout.vertical(4, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        /** Hidden while nothing is selected: an empty pane should be empty. */
        private var tabs: MarketplaceDetailsTabs
        private var current: InstalledExtensionDto? = null

        init {
            isOpaque = true
            background = MarketplaceColors.MAIN_BG
            name.font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD, 18f)
            links.isOpaque = false
            action.background = MarketplaceColors.MAIN_BG

            nameAndButtons.add(version)
            nameAndButtons.addButtonComponent(action)

            top.isOpaque = true
            top.background = MarketplaceColors.MAIN_BG
            top.border = DetailsLayout.headerBorder()
            textRows.isOpaque = false
            textRows.border = DetailsLayout.textRowsBorder()
            textRows.add(name, ListLayout.GrowPolicy.GROW)
            textRows.add(links, ListLayout.GrowPolicy.GROW)
            top.add(textRows, ListLayout.GrowPolicy.GROW)
            top.add(nameAndButtons, ListLayout.GrowPolicy.GROW)
            add(top, BorderLayout.NORTH)

            description.isEditable = false
            description.lineWrap = true
            description.wrapStyleWord = true
            description.font = UIUtil.getLabelFont()
            description.isOpaque = false
            description.border = JBUI.Borders.empty()

            val overview = JPanel(BorderLayout())
            overview.isOpaque = true
            overview.background = MarketplaceColors.MAIN_BG
            overview.border = DetailsLayout.tabContentBorder()
            overview.add(description, BorderLayout.CENTER)

            facts.isOpaque = true
            facts.background = MarketplaceColors.MAIN_BG
            facts.border = DetailsLayout.tabContentBorder()

            tabs = MarketplaceDetailsTabs()
            tabs.addTab("Overview", scrolled(overview))
            tabs.addTab("Additional Info", scrolled(facts))
            add(tabs, BorderLayout.CENTER)

            clear()
        }

        private fun scrolled(component: JComponent): JComponent {
            val scroller = JBScrollPane(component)
            scroller.border = JBUI.Borders.empty()
            scroller.isOpaque = false
            scroller.viewport.isOpaque = false
            scroller.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            return scroller
        }

        fun clear() {
            current = null
            tabs.isVisible = false
            name.text = ""
            version.text = ""
            description.text = ""
            links.removeAll(); facts.removeAll()
            links.isVisible = false
            action.isVisible = false
            revalidate(); repaint()
        }

        fun show(extension: InstalledExtensionDto) {
            tabs.isVisible = true
            current = extension
            name.text = extension.displayName ?: extension.name
            version.text = extension.version
            links.removeAll(); facts.removeAll()
            links.add(JBLabel(extension.publisher).apply { foreground = MarketplaceColors.GRAY })
            // The same registry page the Marketplace pane links to, so installing one does not take it away.
            links.add(HyperlinkLabel("Extension homepage").apply {
                setHyperlinkTarget(registryPageUrl(extension.publisher, extension.name))
            })
            links.isVisible = true

            description.text = extension.description.orEmpty()
            description.caretPosition = 0

            fun fact(text: String) = facts.add(JBLabel(text).apply { foreground = MarketplaceColors.GRAY })
            fact("Identifier: ${extension.id}")
            fact("Version: ${extension.version}")
            fact("Platform: ${extension.targetPlatform ?: "universal"}")
            if (extension.categories.isNotEmpty()) fact("Categories: " + extension.categories.joinToString())
            extension.engineRange?.let { fact("Requires VS Code: $it") }
            extension.providedBy?.let { fact("Bundled with the plugin $it") }
                ?: fact("Installed: ${extension.installedAt.substringBefore('T')}")
            fact("Location: ${extension.directory}")
            val missing = extension.missingDependencies(model.map { it.id }.toSet())
            if (missing.isNotEmpty()) fact("Missing dependencies: ${missing.joinToString()}")

            val bundled = extension.providedBy != null
            val options = mutableListOf<Action>()
            // Only for an extension not off everywhere already: there is nothing to disable here then.
            if (project != null && extension.enabled) {
                val here = extension.id.lowercase() in disabledHere
                options += object : AbstractAction(if (here) "Enable (Workspace)" else "Disable (Workspace)") {
                    override fun actionPerformed(e: ActionEvent) = setDisabledHere(extension, !here)
                }
            }
            if (!bundled) {
                options += object : AbstractAction("Uninstall") {
                    override fun actionPerformed(e: ActionEvent) = uninstall(extension)
                }
            }
            // A bundled extension is on or off with its plugin; what is left here is the workspace.
            val main = if (!bundled) object : AbstractAction(if (extension.enabled) "Disable" else "Enable") {
                override fun actionPerformed(e: ActionEvent) = setEnabled(extension, !extension.enabled)
            } else options.removeFirstOrNull()
            action.action = main
            action.options = options.toTypedArray()
            action.isVisible = main != null
            revalidate(); repaint()
        }
    }
}
