package me.helvert.codebridge.launch

import com.intellij.diagnostic.logging.AdditionalTabComponent
import com.intellij.execution.configurations.AdditionalTabComponentManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.rpc.TreeViewDto
import me.helvert.codebridge.views.CodeBridgeTreePanel
import me.helvert.codebridge.workbench.HostedTreeViews
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * The views a debugger extension puts in VS Code's Run and Debug side bar, as tabs of its debug
 * sessions: CodeLLDB's Loaded Modules beside the frames and variables, where VS Code shows it.
 *
 * Tabs of the session rather than a tool window of their own, because they are about the session --
 * empty without one -- and a second debug window beside IntelliJ's would only be confusing.
 * Made on the backend, as the platform asks a run configuration for a session's extra tabs there;
 * it carries them to the client in split mode.
 */
internal object CodeBridgeDebugViewTabs {

    /** Adds a tab to the session [manager] belongs to for each Run and Debug view of [debugType]'s extension. */
    fun add(project: Project, debugType: String, manager: AdditionalTabComponentManager) {
        val treeViews = HostedTreeViews.getInstance(project)
        for (view in treeViews.debugViews(debugType)) {
            manager.addAdditionalTabComponent(Tab(treeViews, view), "CodeBridge.view.${view.id}")
        }
    }

    private class Tab(private val treeViews: HostedTreeViews, private var view: TreeViewDto) : AdditionalTabComponent(BorderLayout()) {
        private val scope = CoroutineScope(treeViews.scope.coroutineContext + SupervisorJob(treeViews.scope.coroutineContext[Job]))
        private val panel = CodeBridgeTreePanel(treeViews.source, scope, view)

        init {
            add(panel, BorderLayout.CENTER)
            scope.launch {
                treeViews.views().mapNotNull { views -> views.firstOrNull { it.id == view.id } }.collect { latest ->
                    withContext(Dispatchers.EDT) { view = latest; panel.update(latest) }
                }
            }
            scope.launch {
                treeViews.changes.filter { it.viewId == view.id }.collect { event ->
                    withContext(Dispatchers.EDT) { panel.changed(event) }
                }
            }
            // On screen for as long as the session is: fetching is what tells the extension so.
            scope.launch(Dispatchers.EDT) { panel.setShown(true) }
        }

        override fun getTabTitle(): String = view.tabTitle
        override fun getPreferredFocusableComponent(): JComponent = panel
        override fun getToolbarActions(): ActionGroup? = null
        override fun getSearchComponent(): JComponent? = null
        override fun getToolbarPlace(): String? = null
        override fun getToolbarContextComponent(): JComponent? = null
        override fun isContentBuiltIn(): Boolean = false

        override fun dispose() {
            treeViews.setVisible(view.id, false)
            scope.cancel()
            Disposer.dispose(panel)
        }
    }
}
