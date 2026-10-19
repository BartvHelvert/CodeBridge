package me.helvert.codebridge.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import me.helvert.codebridge.rpc.TreeEventDto
import me.helvert.codebridge.rpc.TreeViewDto
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * One view in a view container's tool window: a tree view, or a webview view drawn by its
 * extension's page. Used on the EDT only.
 */
interface CodeBridgeViewTab : Disposable {
    val component: JComponent
    val view: TreeViewDto

    /** The view's title actions as they stand now, for its header in the stack or the tool window's. */
    val titleActions: List<AnAction>

    /** Told when [titleActions] changes. */
    var titleActionsChanged: () -> Unit
    fun update(view: TreeViewDto)
    fun changed(event: TreeEventDto)

    /** Whether the view is on screen: its window open and it not collapsed in its stack. */
    fun setShown(shown: Boolean)
}

/**
 * Draws webview views. The webview module provides one, since a page needs the IDE's browser
 * (the Web Browser (JCEF) plugin), which this module does not require.
 */
interface CodeBridgeWebviewViewFactory {
    fun create(project: Project, scope: CoroutineScope, view: TreeViewDto): CodeBridgeViewTab

    companion object {
        val EP: ExtensionPointName<CodeBridgeWebviewViewFactory> =
            ExtensionPointName("me.helvert.codebridge.webviewViewFactory")
    }
}

/** A tree view's tab: its [CodeBridgeTreePanel]. */
internal class TreeViewTab(val panel: CodeBridgeTreePanel) : CodeBridgeViewTab {
    override val component: JComponent get() = panel
    override val view: TreeViewDto get() = panel.view
    override val titleActions: List<AnAction> get() = panel.titleActions
    override var titleActionsChanged: () -> Unit
        get() = panel.titleActionsChanged
        set(value) {
            panel.titleActionsChanged = value
        }

    override fun update(view: TreeViewDto) = panel.update(view)
    override fun changed(event: TreeEventDto) = panel.changed(event)
    override fun setShown(shown: Boolean) = panel.setShown(shown)
    override fun dispose() = Disposer.dispose(panel)
}

/** A webview view's tab when nothing can draw it: says why. */
internal class UndrawableWebviewTab(override var view: TreeViewDto) : CodeBridgeViewTab {
    override val component: JComponent = JBLabel(
        "<html>This view is a web page, which needs the Web Browser (JCEF) plugin.</html>", SwingConstants.CENTER,
    ).apply {
        border = JBUI.Borders.empty(8)
        foreground = UIUtil.getContextHelpForeground()
    }
    override val titleActions: List<AnAction> get() = emptyList()
    override var titleActionsChanged: () -> Unit = {}
    override fun update(view: TreeViewDto) {
        this.view = view
    }

    override fun changed(event: TreeEventDto) = Unit
    override fun setShown(shown: Boolean) = Unit
    override fun dispose() = Unit
}
