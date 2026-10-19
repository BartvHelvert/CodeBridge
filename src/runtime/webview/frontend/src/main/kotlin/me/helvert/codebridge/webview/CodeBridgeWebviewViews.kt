package me.helvert.codebridge.webview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.*
import me.helvert.codebridge.views.RpcTreeViewSource
import me.helvert.codebridge.views.TreeIcons
import me.helvert.codebridge.views.CodeBridgeViewTab
import me.helvert.codebridge.views.CodeBridgeWebviewViewFactory
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class CodeBridgeWebviewViews : CodeBridgeWebviewViewFactory {
    override fun create(project: Project, scope: CoroutineScope, view: TreeViewDto): CodeBridgeViewTab = WebviewViewTab(project, scope, view)
}

/**
 * A webview view's tab: its page, made the first time the tab is on screen, since asking for it is
 * what wakes the view's extension, as showing the view does in VS Code. Kept while the tab is open,
 * shown or not, so the page keeps its state as VS Code keeps it with `retainContextWhenHidden`.
 *
 * Should the page go -- its extension's host stopped -- it is made again once a provider registers
 * for the view anew, if the tab is on screen then, or the next time it is. Used on the EDT only.
 */
/**
 * Whether [now] has a provider that [before] did not: one registered since, by the same host or by
 * one that replaced it. The view's generation grows whenever its provider is lost, so a provider
 * registered since is told apart even when the update saying the last one was lost never came --
 * merged on the way with the one saying the new one came.
 */
internal fun registeredAnew(before: TreeViewDto, now: TreeViewDto): Boolean =
    now.registered && (!before.registered || now.generation != before.generation)

private class WebviewViewTab(private val project: Project, parentScope: CoroutineScope, view: TreeViewDto) : CodeBridgeViewTab {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val source = RpcTreeViewSource(project)
    override val component: JComponent = JPanel(BorderLayout())
    private val message = JBLabel("", SwingConstants.CENTER).apply {
        border = JBUI.Borders.empty(8)
        foreground = UIUtil.getContextHelpForeground()
    }

    override var view: TreeViewDto = view
        private set
    override var titleActions: List<AnAction> = emptyList()
        private set
    override var titleActionsChanged: () -> Unit = {}

    private var shown = false
    private var resolving: Job? = null

    /** The view as it was when its page was last asked for; see [registeredAnew]. */
    private var resolvedFrom: TreeViewDto? = null
    /** The webview filling the view, once resolved; [attachment] ends with it. */
    private var handle: String? = null
    private var browser: WebviewBrowser? = null
    private var attachment: Disposable? = null

    init {
        say("")
        titleActions = titleActionsFor(view)
    }

    override fun update(view: TreeViewDto) {
        val previous = this.view
        this.view = view
        if (view.actions != previous.actions) {
            titleActions = titleActionsFor(view)
            titleActionsChanged()
        }
        // A provider registered again: the host that held the last page has been replaced.
        if (registeredAnew(previous, view) && shown && handle == null) resolve()
    }

    override fun changed(event: TreeEventDto) {
        if (event.kind == TreeEventDto.FOCUS) browser?.component?.requestFocusInWindow()
    }

    override fun setShown(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        val handle = handle
        if (handle != null) call { webviewShown(project.projectId(), handle, shown, shown, 0) }
        else if (shown) resolve()
    }

    private fun resolve() {
        if (resolving != null) return
        resolvedFrom = view
        say("Loading...")
        resolving = scope.launch(Dispatchers.EDT) {
            val answer = try {
                withContext(Dispatchers.Default) { CodeBridgeApi.getInstance().resolveWebviewView(project.projectId(), view.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.debug("Could not resolve webview view ${view.id}", error)
                WebviewResolutionDto(problem = error.message ?: "Could not reach the backend")
            } finally {
                resolving = null
            }
            val resolved = answer.handle
            if (resolved == null) say(answer.problem ?: "") else attach(resolved)
        }
    }

    private fun attach(handle: String) {
        this.handle = handle
        val attachment = Disposer.newDisposable("webview $handle").also { this.attachment = it }
        val browser = WebviewBrowser(project, scope, handle, inPanel = true).also { this.browser = it }
        Disposer.register(attachment, browser)
        component.removeAll()
        component.add(browser.component, BorderLayout.CENTER)
        component.revalidate()
        component.repaint()
        // Made visible, as far as the extension knows; it hears otherwise if the tab has since been hidden.
        if (!shown) call { webviewShown(project.projectId(), handle, visible = false, active = false, position = 0) }
        // Not gone before it has been seen: the description may still be on its way from the backend.
        var seen = false
        CodeBridgeWebviews.getInstance(project).listen(handle, object : CodeBridgeWebviews.Listener {
            override fun updated(webview: WebviewDto?) {
                if (webview != null) {
                    seen = true
                    browser.show(webview)
                } else if (seen) detach("The extension that drew this view has stopped.")
            }

            override fun event(event: WebviewEventDto) = browser.post(event)
        }, attachment)
    }

    @Suppress("SameParameterValue")
    private fun detach(why: String) {
        attachment?.let(Disposer::dispose)
        attachment = null
        browser = null
        handle = null
        say(why)
        // The new provider may have registered before the page it replaces was gone.
        if (shown && resolvedFrom?.let { registeredAnew(it, view) } == true) resolve()
    }

    private fun say(text: String) {
        message.text = text
        component.removeAll()
        component.add(message, BorderLayout.CENTER)
        component.revalidate()
        component.repaint()
    }

    /** The view's `view/title` menu, as a tree view shows it: `navigation` entries as buttons, the rest behind "more". */
    private fun titleActionsFor(view: TreeViewDto): List<AnAction> {
        val (buttons, more) = view.actions.partition { it.group == TreeActionDto.NAVIGATION }
        val actions = buttons.map(::action).toMutableList()
        if (more.isNotEmpty()) actions += DefaultActionGroup("More Actions", grouped(more)).apply {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.More
        }
        return actions
    }

    private fun grouped(entries: List<TreeActionDto>): List<AnAction> {
        val result = mutableListOf<AnAction>()
        var group: String? = null
        for (entry in entries) {
            if (group != null && entry.group != group) result += Separator.getInstance()
            group = entry.group
            result += action(entry)
        }
        return result
    }

    private fun action(entry: TreeActionDto): AnAction =
        if (entry.children.isNotEmpty()) DefaultActionGroup(entry.title, grouped(entry.children)).apply {
            isPopup = true
            templatePresentation.icon = TreeIcons.icon(source, scope, entry.icon, entry.iconDark) {}
        }
        else object : DumbAwareAction(entry.title) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = entry.enabled
                event.presentation.icon = TreeIcons.icon(source, scope, entry.icon, entry.iconDark) {}
            }

            override fun actionPerformed(event: AnActionEvent) {
                calls.callApart { runTreeAction(project.projectId(), view.id, entry.id, null, emptyList()) }
            }
        }

    private val calls = WebviewCalls(scope) { CodeBridgeApi.getInstance() }

    private fun call(work: suspend CodeBridgeApi.() -> Unit) = calls.call(work)

    override fun dispose() {
        attachment?.let(Disposer::dispose)
        scope.cancel()
    }

    companion object {
        private val LOG = Logger.getInstance(WebviewViewTab::class.java)
    }
}
