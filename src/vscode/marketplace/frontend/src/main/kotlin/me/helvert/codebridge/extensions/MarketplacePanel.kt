package me.helvert.codebridge.extensions
import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Divider
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
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
import kotlinx.coroutines.*
import me.helvert.codebridge.extensions.ui.*
import me.helvert.codebridge.registry.RegistryDetail
import me.helvert.codebridge.registry.RegistryExtension
import me.helvert.codebridge.rpc.BackendRequests
import me.helvert.codebridge.rpc.awaitOperation
import me.helvert.codebridge.rpc.backendModal
import me.helvert.codebridge.rpc.marketplace
import java.awt.BorderLayout
import java.awt.CardLayout
import java.text.DecimalFormat
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.time.Duration.Companion.milliseconds

/** Installed extensions and Open VSX suggestions in one searchable, split-pane page. */
class MarketplacePanel(private val onInstalled: () -> Unit) : JPanel(BorderLayout()), Disposable {

    internal val search = PluginSearchTextField()
    private val model = ArrayList<RegistryExtension>()

    /** The results, one [MarketplaceRow] each, keyed as the backend keys installed extensions. */
    private val rows = ExtensionList<RegistryExtension>({ it.id.lowercase() }) { choose(it) }

    /**
     * Shown in the list, and only when there is something to say.
     *
     * The IDE's plugin page has no status line, so neither does this: for the ordinary case --
     * results on screen -- nothing is added to the page at all. What it cannot do is disappear
     * entirely, because a search that failed has to say so somewhere.
     */
    private val message = JBLabel()
    private val details = DetailsPane()
    private val detailCards = JPanel(CardLayout())
    private val installedSection = MarketplaceSection("Installed", initiallyExpanded = false)

    // The registry pages in on scroll, so clipping it to a few rows would only stop the paging.
    private val suggestedSection = MarketplaceSection("Open VSX Registry", collapsible = false)
    private val installed = InstalledPanel(embedded = true, onChosen = { index ->
        installedSection.reveal(index)
        selected = null
        ++detailVersion
        showingInstalled = true
        rows.selected = null
        (detailCards.layout as CardLayout).show(detailCards, "installed")
    }, onCount = { installedSection.count = it; rebuildRows() })

    internal fun filterInstalledOnly(value: Boolean) {
        installedOnly = value
        ++queryVersion
        loading = false
        suggestedSection.isVisible = !value
        search.textEditor.emptyText.text = if (value) "Search installed extensions" else "Search all extensions"
        if (value) {
            installedSection.reveal(Int.MAX_VALUE)
            showingInstalled = true
            ++detailVersion
            rows.selected = null
            (detailCards.layout as CardLayout).show(detailCards, "installed")
            installed.selectFirst()
        } else {
            showingInstalled = false
            query(reset = true)
        }
        revalidate()
        repaint()
    }

    /** The search waiting for typing to pause, replaced by each keystroke. */
    private var debounce: Job? = null

    private var disposed = false
    private val requests = BackendRequests()
    private var changes: Job? = null
    private var backendPlatform: String? = null
    private var installedRefreshVersion = 0
    private var detailVersion = 0
    private var queryVersion = 0
    private var loaded = 0
    private var total = 0
    private var installedVersions: Map<String, String> = emptyMap()
    /** Extensions another plugin bundles, by lowercase id: installed and switched only with that plugin. */
    private var bundled: Set<String> = emptySet()
    private var selected: RegistryExtension? = null
    private var loading = false
    private var installedOnly = false
    private var showingInstalled = false

    /** Extensions the user has switched off, by lowercase id, so a row's checkbox reads back what it was set to. */
    private val disabled = HashSet<String>()

    /**
     * Extensions disabled in the project the settings were opened from alone, by lowercase id: their
     * rows are dimmed, while the switch, which is the IDE's, says whether they are on elsewhere.
     */
    private val disabledHere = HashSet<String>()
    private val project: Project? = ProjectUtil.getActiveProject()?.takeUnless { it.isDefault }

    /** One icon per URL, so a row rebuilt by paging keeps the image it had already rasterised. */
    private val icons = HashMap<String, RegistryIcon>()
    private val requestedIcons = HashSet<String>()

    init {
        search.textEditor.emptyText.text = "Search all extensions"
        search.textEditor.isOpaque = true
        search.textEditor.background = SEARCH_BG
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = schedule()
            override fun removeUpdate(e: DocumentEvent) = schedule()
            override fun changedUpdate(e: DocumentEvent) = schedule()
        })
        search.textEditor.addActionListener { query(reset = true) }

        message.border = JBUI.Borders.empty(16, 20)
        message.foreground = GRAY
        message.isVisible = false

        // In the list, where the rows would be: directly above them, since the list lays out only rows.
        val stack = JPanel(BorderLayout())
        stack.isOpaque = false
        val sections = JPanel(RowStackLayout())
        sections.isOpaque = false
        installedSection.content = installed.listContent
        suggestedSection.content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(message, BorderLayout.NORTH)
            add(rows, BorderLayout.CENTER)
        }
        sections.add(installedSection)
        sections.add(suggestedSection)
        stack.add(sections, BorderLayout.CENTER)

        // Pinned to the top: the rows ask for exactly the height they need, and the space below
        // them is the page background rather than stretched rows.
        val holder = JPanel(BorderLayout())
        holder.isOpaque = true
        holder.background = MAIN_BG
        holder.add(stack, BorderLayout.NORTH)

        val scroller = JBScrollPane(holder)
        // Page in on scroll rather than with a button; the IDE has no "load more" control.
        scroller.verticalScrollBar.addAdjustmentListener {
            val bar = scroller.verticalScrollBar
            if (!installedOnly && !loading && loaded < total && bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(
                    80
                )
            ) {
                query(reset = false)
            }
        }
        scroller.border = JBUI.Borders.empty()
        scroller.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        // The shared search is supplied to the settings header by the configurable.
        val listPanel = JPanel(BorderLayout())
        listPanel.border = CustomLineBorder(BORDER, JBUI.insetsTop(1))
        listPanel.add(scroller, BorderLayout.CENTER)

        listPanel.minimumSize = JBUI.size(LIST_MIN_WIDTH, 0)
        detailCards.minimumSize = JBUI.size(DETAILS_MIN_WIDTH, 0)

        val splitter = object : OnePixelSplitter(false, 0.5f) {
            override fun createDivider(): Divider = super.createDivider().also {
                (it as? JComponent)?.background = BORDER
            }

            // Sized as the IDE's plugin page sizes its list: split evenly up to a width, after which
            // the details take most of the extra room rather than half of it.
            override fun doLayout() {
                val available = width - insets.left - insets.right - dividerWidth
                if (available > 0) proportion = defaultListProportion(available)
                super.doLayout()
            }
        }
        splitter.lackOfSpaceStrategy = Splitter.LackOfSpaceStrategy.HONOR_THE_FIRST_MIN_SIZE
        splitter.firstComponent = listPanel
        detailCards.add(details, "marketplace")
        detailCards.add(installed.detailsComponent, "installed")
        splitter.secondComponent = detailCards
        add(splitter, BorderLayout.CENTER)

    }

    /** Loads the initial page and refreshes what counts as installed. */
    fun activate() {
        installed.refresh()
        if (changes == null && ApplicationManager.getApplication() != null) {
            changes =
                requests.watchExtensions(::refreshInstalled) { say("Could not load installed extensions: ${it.message}") }
        }
        refreshInstalled()
        requests.request({ marketplace { targetPlatform() } }, {
            backendPlatform = it
            details.refreshAction()
        }, { say("Could not determine the extension host platform: ${it.message}") })
        if (model.isEmpty()) query(reset = true)
    }

    fun refreshInstalled() {
        val version = ++installedRefreshVersion
        requests.request(
            { marketplace { extensions() to project?.let { disabledInProject(it.projectId()) }.orEmpty() } },
            { (extensions, here) ->
            if (!disposed && version == installedRefreshVersion) {
                installedVersions = extensions.associate { it.id to it.version }
                bundled = extensions.filter { it.providedBy != null }.mapTo(HashSet()) { it.id.lowercase() }
                disabled.clear()
                disabled.addAll(extensions.filterNot { it.enabled }.map { it.id.lowercase() })
                disabledHere.clear()
                disabledHere.addAll(here)
                details.refreshAction()
                rebuildRows()
            }
        }, { say("Could not load installed extensions: ${it.message}") })
    }

    private fun schedule() {
        installed.filter(search.text)
        suggestedSection.title = if (search.text.isBlank()) "Open VSX Registry" else "Search Results"
        debounce?.cancel()
        debounce = Scope.getInstance().scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            delay(SEARCH_DELAY_MS.milliseconds)
            if (!disposed) query(reset = true)
        }
    }

    private fun query(reset: Boolean) {
        if (disposed || installedOnly) return
        val version = ++queryVersion
        val text = search.text.trim()
        val offset = if (reset) 0 else loaded
        if (reset) {
            ++detailVersion
            model.clear()
            selected = null
            rebuildRows()
            loaded = 0
            total = 0
            details.clear()
            say("Searching?")
        }
        loading = true
        requests.request({ marketplace { search(text, offset) } }, { page ->
            if (!disposed && version == queryVersion) {
                loading = false
                model.addAll(page.extensions)
                loaded = offset + page.extensions.size
                total = page.total
                rebuildRows()
                say(if (total == 0) "No extensions matched" else null)
                if (selected == null && !showingInstalled) model.firstOrNull { search.text.isNotBlank() || it.id.lowercase() !in installed.installedIds }
                    ?.let { choose(it) }
            }
        }, { error ->
            if (version == queryVersion) {
                loading = false
                say("Could not reach Open VSX: ${error.message ?: error.javaClass.simpleName}")
            }
        })
    }

    private fun select(extension: RegistryExtension?) {
        val version = ++detailVersion
        if (extension == null) {
            details.clear(); return
        }
        details.showLoading(extension)
        requests.request({ marketplace { detail(extension.namespace, extension.name) } }, {
            if (!disposed && version == detailVersion) details.show(it)
        }, {
            if (!disposed && version == detailVersion) details.showError(extension, it)
        })
    }

    private fun install(detail: RegistryDetail) {
        try {
            val result = backendModal(this, "Installing ${detail.extension.label}") {
                marketplace { installRegistry(detail.extension.namespace, detail.extension.name) }.awaitOperation()
            }
            if (disposed) return
            result?.let {
                if (it.problems.isNotEmpty()) say(it.problems.first())
                installedVersions = installedVersions + (it.extension.id to it.extension.version)
                rebuildRows()
            }
        } catch (_: ProcessCanceledException) {
        } catch (_: CancellationException) {
        } catch (error: Exception) {
            if (!disposed) Messages.showErrorDialog(
                this,
                error.message ?: error.javaClass.simpleName,
                "Could Not Install Extension"
            )
        } finally {
            if (!disposed) {
                refreshInstalled()
                onInstalled()
            }
        }
    }

    override fun dispose() {
        disposed = true; debounce?.cancel(); requests.dispose(); installed.dispose()
    }

    private companion object {
        /** How long typing must pause before the registry is searched. */
        const val SEARCH_DELAY_MS = 350L

        /** Shared compact logo size for installed and suggested extension rows. */
        const val ICON_SIZE = ListedRow.ICON_SIZE

        /** `PluginNode.rating` as the IDE shows it: one decimal, none when it is whole. */
        val RATING = DecimalFormat("#.#")

        /** `presentableDownloads`: the count as is up to a thousand, then in K and M. */
        fun downloads(count: Int): String? = when {
            count <= 0 -> null
            count <= 1000 -> count.toString()
            count < 1_000_000 -> DecimalFormat("###.#K").format(count / 1000.0)
            else -> DecimalFormat("###.#M").format(count / 1_000_000.0)
        }

        // `UnifiedPluginsPageView`'s split. Unscaled.
        const val LIST_MIN_WIDTH = 280
        const val DETAILS_MIN_WIDTH = 220
        const val SPLIT_EQUAL_WIDTH = 700
        const val SPLIT_EXTRA_WIDTH_PARTS = 11

        /** Half of the first [SPLIT_EQUAL_WIDTH], then one part in [SPLIT_EXTRA_WIDTH_PARTS] of the rest. */
        fun defaultListProportion(available: Int): Float {
            val equal = JBUI.scale(SPLIT_EQUAL_WIDTH)
            val list =
                minOf(available, equal) / 2f + (available - equal).coerceAtLeast(0) / SPLIT_EXTRA_WIDTH_PARTS.toFloat()
            return list / available
        }

        // Shared with the Installed tab, exactly as the IDE shares them between its two tabs.
        val MAIN_BG get() = MarketplaceColors.MAIN_BG
        val SEARCH_BG get() = MarketplaceColors.SEARCH_BG
        val BORDER get() = MarketplaceColors.BORDER
        val GRAY get() = MarketplaceColors.GRAY
    }

    /** Name, homepage and actions above the overview and additional information. */
    private inner class DetailsPane : JPanel(BorderLayout()) {
        private val name = JBLabel()
        private val links = JPanel(ListLayout.horizontal(12))
        private val version = JBLabel()
        private val action = AccentButton("Install", filled = false)
        private val nameAndButtons = BaselinePanel(12, false)
        private val top = JPanel(ListLayout.vertical(8, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        private val textRows =
            JPanel(ListLayout.vertical(8, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))

        private val description = JBTextArea()
        private val info = JPanel(ListLayout.vertical(16, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        private val infoLinks =
            JPanel(ListLayout.vertical(6, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))
        private val facts = JPanel(ListLayout.vertical(4, ListLayout.Alignment.START, ListLayout.GrowPolicy.NO_GROW))

        /** Hidden while nothing is selected: an empty pane should be empty. */
        private var tabs: MarketplaceDetailsTabs
        private var current: RegistryDetail? = null

        init {
            isOpaque = true
            background = MAIN_BG

            name.font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD, 18f)
            links.isOpaque = false
            // `InstallButton` falls back to the page background, so the outlined button reads as an outline.
            action.setSurface(MAIN_BG)

            nameAndButtons.add(version)
            nameAndButtons.addButtonComponent(action)

            top.isOpaque = true
            top.background = MAIN_BG
            top.border = DetailsLayout.headerBorder()
            textRows.isOpaque = false
            textRows.border = DetailsLayout.textRowsBorder()
            textRows.add(name, ListLayout.GrowPolicy.GROW)
            textRows.add(links, ListLayout.GrowPolicy.GROW)
            top.add(textRows, ListLayout.GrowPolicy.GROW)
            top.add(nameAndButtons, ListLayout.GrowPolicy.GROW)
            add(top, BorderLayout.NORTH)

            tabs = MarketplaceDetailsTabs()
            tabs.addTab("Overview", scrolled(overview()))
            tabs.addTab("Additional Info", scrolled(additionalInfo()))
            add(tabs, BorderLayout.CENTER)

            action.addActionListener { current?.let { install(it) } }
            clear()
        }

        /** The overview uses the same horizontal inset as the header. */
        private fun overview(): JComponent {
            description.isEditable = false
            description.lineWrap = true
            description.wrapStyleWord = true
            // JBTextArea defaults to a monospaced font, which makes prose read like a code listing.
            description.font = UIUtil.getLabelFont()
            description.isOpaque = false
            description.border = JBUI.Borders.empty()
            val parent = JPanel(BorderLayout())
            parent.isOpaque = true
            parent.background = MAIN_BG
            parent.border = DetailsLayout.tabContentBorder()
            parent.add(description, BorderLayout.CENTER)
            return parent
        }

        /** `createAdditionalInfoTab`: links, then grey fact lines. */
        private fun additionalInfo(): JComponent {
            info.isOpaque = true
            info.background = MAIN_BG
            info.border = DetailsLayout.tabContentBorder()
            infoLinks.isOpaque = false
            facts.isOpaque = false
            info.add(infoLinks, ListLayout.GrowPolicy.GROW)
            info.add(facts, ListLayout.GrowPolicy.GROW)
            return info
        }

        private fun scrolled(component: JComponent): JComponent {
            val scroller = JBScrollPane(component)
            scroller.border = JBUI.Borders.empty()
            scroller.isOpaque = false
            scroller.viewport.isOpaque = false
            scroller.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            return scroller
        }

        private fun fact(text: String) {
            facts.add(JBLabel(text).apply { foreground = GRAY })
        }

        private fun header(title: String, trailing: String?) {
            name.text = title
            version.text = trailing.orEmpty()
            links.removeAll()
            infoLinks.removeAll()
            facts.removeAll()
            links.isVisible = false
        }

        fun clear() {
            current = null
            tabs.isVisible = false
            header("", null)
            description.text = ""
            action.isVisible = false
            revalidate(); repaint()
        }

        fun showLoading(extension: RegistryExtension) {
            tabs.isVisible = true
            current = null
            header(extension.label, extension.version)
            description.text = "Loading…"
            action.isVisible = false
            revalidate(); repaint()
        }

        fun showError(extension: RegistryExtension, error: Throwable) {
            tabs.isVisible = true
            current = null
            header(extension.label, extension.version)
            description.text = "Could not load this extension: " + (error.message ?: error.javaClass.simpleName)
            action.isVisible = false
            revalidate(); repaint()
        }

        fun show(detail: RegistryDetail) {
            tabs.isVisible = true
            current = detail
            val extension = detail.extension
            header(extension.label, extension.version)

            if (extension.deprecated) fact("Deprecated")
            if (extension.preview) fact("Preview")

            // The registry's page for this extension, which is where the IDE's own "plugin
            // homepage" link goes -- to the marketplace, not to the vendor's site.
            // As the IDE's author line: the vendor in grey, then the homepage link with its own arrow.
            links.add(JBLabel(extension.namespace).apply { foreground = GRAY })
            links.add(HyperlinkLabel("Extension homepage").apply { setHyperlinkTarget(extension.pageUrl) })
            links.isVisible = links.componentCount > 0

            description.text = extension.description.orEmpty()
            description.caretPosition = 0

            detail.repository?.let { infoLinks.add(HyperlinkLabel("Source code").apply { setHyperlinkTarget(it) }) }
            // Not "Homepage" as well: that word is taken by the header link above, and two links
            // reading the same thing while going to different places is worse than either name.
            detail.homepage?.let { infoLinks.add(HyperlinkLabel("Project website").apply { setHyperlinkTarget(it) }) }

            extension.averageRating?.let { fact("Rating: %.1f (%d reviews)".format(it, extension.reviewCount)) }
            fact("Downloads: " + "%,d".format(extension.downloadCount))
            fact("Version: " + extension.version)
            extension.timestamp?.substringBefore('T')?.let { fact("Published: $it") }
            if (detail.categories.isNotEmpty()) fact("Categories: " + detail.categories.joinToString())
            detail.license?.let { fact("License: $it") }
            detail.engineRange?.let { fact("Requires VS Code: $it") }
            if (detail.extensionKind.isNotEmpty()) fact("Runs as: " + detail.extensionKind.joinToString())
            fact("Identifier: " + extension.id)
            val platform = backendPlatform
            fact(
                when {
                    platform == null -> "Checking package availability?"
                    detail.isPlatformSpecific(platform) -> "Package: a build for $platform"
                    detail.downloadFor(platform) != null -> "Package: universal"
                    else -> "Package: none for " + platform + " (" + detail.platforms().joinToString() + ")"
                }
            )
            if (detail.dependencies.isNotEmpty()) fact("Depends on: " + detail.dependencies.joinToString())

            refreshAction()
            revalidate(); repaint()
        }

        /** Keeps the button honest about what it will do to an extension already installed. */
        fun refreshAction() {
            val detail = current ?: run { action.isVisible = false; return }
            val platform = backendPlatform
            val installed = installedVersions[detail.extension.id.lowercase()]
            action.isVisible = true
            when {
                platform == null -> {
                    action.text = "Checking?"; action.isEnabled = false
                }

                detail.downloadFor(platform) == null -> {
                    action.text = "Unavailable"
                    action.isEnabled = false
                }

                installed == null -> {
                    action.text = "Install"; action.isEnabled = true
                }

                detail.extension.id.lowercase() in bundled -> {
                    action.text = "Bundled"; action.isEnabled = false
                }

                installed == detail.extension.version -> {
                    action.text = "Reinstall"; action.isEnabled = true
                }

                else -> {
                    action.text = "Update"; action.isEnabled = true
                }
            }
        }
    }

    private fun choose(extension: RegistryExtension) {
        showingInstalled = false
        installed.clearSelection()
        (detailCards.layout as CardLayout).show(detailCards, "marketplace")
        selected = extension
        rows.selected = extension
        select(extension)
    }

    /**
     * Builds a row per result from the full model; paging appends to `model` first.
     *
     * Icons survive the rebuild because they are kept by URL in [icons], not in the rows.
     */
    private fun rebuildRows() {
        val suggestions = model.filter { search.text.isNotBlank() || it.id.lowercase() !in installed.installedIds }
        suggestedSection.count = if (search.text.isBlank()) suggestions.size else total
        // Browsing pages through the registry, so the loaded count says nothing; a search reports its total.
        suggestedSection.showCount = search.text.isNotBlank()
        rows.show(suggestions) { MarketplaceRow(it) }
        rows.selected = selected
    }

    /**
     * One search result, as the IDE's marketplace row shows a plugin: Install while it is not
     * installed, and the enable switch with the installed version once it is.
     */
    private inner class MarketplaceRow(private val extension: RegistryExtension) : ListedRow() {
        private val installed = installedVersion(extension)
        private val install = AccentButton("Install", filled = false)

        override val dimmed: Boolean
            get() = installed != null && extension.id.lowercase().let { it in disabled || it in disabledHere }

        init {
            icon(iconFor(extension))
            name(extension.label)
            if (installed == null) {
                install.addActionListener { installFromRow(extension) }
                button(install)
            } else if (extension.id.lowercase() !in bundled) {
                addButtonComponent(EnableCheckBox(extension.id.lowercase() !in disabled) { enabled ->
                    setEnabled(extension.id, enabled)
                    recolour()
                })
            }
            metrics(
                downloads(extension.downloadCount)?.let { Metric(it, AllIcons.Plugins.Downloads) },
                extension.averageRating?.takeIf { it > 0 }?.let { Metric(RATING.format(it), AllIcons.Plugins.Rating) },
                installed?.let { Metric(it) },
                Metric(extension.namespace, trimmable = true),
            )
        }

        override fun activate() {
            if (installed == null) install.doClick()
        }
    }

    /**
     * The icon for a row.
     *
     * A registry icon is neither downloaded yet nor able to know what display it will be painted
     * on, so the answer is a [RegistryIcon]: one stable object that draws the placeholder until the
     * image lands and rasterises itself against the graphics it is actually painted into. The row
     * then needs nothing but a repaint.
     */
    private fun iconFor(extension: RegistryExtension): Icon {
        val url = extension.iconUrl ?: return RegistryIcon(ICON_SIZE) { null }
        val registry = RegistryImages.getInstance()
        fetchIcon(url, extension)
        // Keyed by URL, not by row: paging rebuilds every row, and rebuilding the icons with them
        // would throw away each one's rasterisation on every page.
        return icons.getOrPut(url) { RegistryIcon(ICON_SIZE) { registry.cached(url) } }
    }

    /** Fetches an icon once, off the EDT, and repaints the row it belongs to when it arrives. */
    private fun fetchIcon(url: String, extension: RegistryExtension) {
        val registry = RegistryImages.getInstance()
        if (registry.tried(url) || !requestedIcons.add(url)) return
        if (ApplicationManager.getApplication() == null) return
        Scope.getInstance().scope.launch(Dispatchers.IO) {
            registry.fetch(url)
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                if (!disposed) rows.rowOf(extension)?.repaint()
            }
        }
    }

    /**
     * Where icons are fetched and searches wait: a fetch may outlive the panel that asked, so it ends with
     * the application. A waiting search is cancelled with the panel.
     */
    @Service(Service.Level.APP)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(): Scope = service()
        }
    }

    /** Says [text] above the list, or takes the line away entirely when there is nothing to say. */
    private fun say(text: String?) {
        message.text = text.orEmpty()
        message.isVisible = text != null
    }

    /** The version we have of this extension, or null when it is not installed. */
    private fun installedVersion(extension: RegistryExtension): String? =
        installedVersions[extension.id.lowercase()]

    /** Switches an installed extension on or off; it takes effect the next time a host starts. */
    private fun setEnabled(id: String, enabled: Boolean) {
        if (enabled) disabled.remove(id.lowercase()) else disabled.add(id.lowercase())
        requests.request({ marketplace { setEnabled(id, enabled) } }, { refreshInstalled() }, {
            say("Could not change $id: ${it.message}")
            refreshInstalled()
        })
    }

    /** A row knows only the summary, so fetch the detail before installing, as the IDE does. */
    private fun installFromRow(extension: RegistryExtension) {
        requests.request({ marketplace { detail(extension.namespace, extension.name) } }, { install(it) }, {
            say("Could not load ${extension.id}: ${it.message ?: it.javaClass.simpleName}")
        })
    }
}
