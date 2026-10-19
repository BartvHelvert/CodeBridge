package me.helvert.codebridge.workbench

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * Draws the extensions' output channels, one tab each.
 *
 * A console rather than a plain text area, because this is where a language server's log lands and
 * a log is what a console is for: scrollback, search, and a fixed-width gutter that keeps a stack
 * trace readable.
 *
 * Selecting a tab is not a local affair. The host only flushes the channel it thinks is visible, so
 * the selection is pushed back into it -- see [HostedOutputChannels.show]. That also means the tab
 * the user is looking at is the only one costing anything.
 */
internal class CodeBridgeOutputToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OutputPanel(project, toolWindow)
        // The tool window owns the consoles, not the project service. In split mode a console is
        // created for the client that opened the window, and the platform refuses to touch its
        // document once that client's session has ended; tied to the service, the consoles lived
        // until the project closed and each one logged an error when it was finally disposed.
        Disposer.register(toolWindow.disposable, panel)
        HostedOutputChannels.getInstance(project).attach(panel)
    }
}

private class OutputPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : HostedOutputChannels.View, Disposable {

    private val consoles = mutableMapOf<String, ConsoleView>()

    init {
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation != ContentManagerEvent.ContentOperation.add) return
                val id = consoles.entries.firstOrNull { it.value.component === event.content.component }?.key
                HostedOutputChannels.getInstance(project).show(id)
            }
        })
    }

    /**
     * Brings the tabs in line with the channels that exist.
     *
     * Called for every registration and disposal, so it has to be cheap when nothing moved and must
     * not disturb the selected tab -- changing it would re-point the host at another channel.
     */
    override fun refresh(channels: List<HostedOutputChannels.Channel>) {
        if (project.isDisposed || toolWindow.isDisposed) return
        val manager = toolWindow.contentManager
        val wanted = channels.associateBy { it.id }
        for (id in consoles.keys.toList() - wanted.keys) {
            val console = consoles.remove(id) ?: continue
            manager.contents.firstOrNull { it.component === console.component }
                ?.let { manager.removeContent(it, false) }
            Disposer.dispose(console)
        }
        for (channel in channels) {
            if (consoles.containsKey(channel.id)) continue
            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            Disposer.register(this, console)
            consoles[channel.id] = console
            val content = ContentFactory.getInstance().createContent(console.component, channel.name, false)
            content.isCloseable = false
            manager.addContent(content)
            // The first channel to arrive is the one the panel opens on, so something is selected
            // and the host has somewhere to flush to without the user choosing first.
            @Suppress("UsePropertyAccessSyntax") // `selectedContent` is read-only
            if (manager.contents.size == 1) manager.setSelectedContent(content)
        }
    }

    @Suppress("UsePropertyAccessSyntax") // `selectedContent` is read-only
    override fun select(channelId: String) {
        val console = consoles[channelId] ?: return
        val content = toolWindow.contentManager.contents.firstOrNull { it.component === console.component } ?: return
        toolWindow.contentManager.setSelectedContent(content)
    }

    override fun append(channel: HostedOutputChannels.Channel, text: String, reset: Boolean) {
        val console = consoles[channel.id] ?: return
        if (reset) console.clear()
        if (text.isNotEmpty()) console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    override fun dispose() {
        // The service may already be gone when the project closes; only detach this panel.
        if (!project.isDisposed) HostedOutputChannels.getInstance(project).detach(this)
        consoles.clear()
    }
}
