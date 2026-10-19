package me.helvert.codebridge.workbench

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * "VS Code Workspace ▸": the folder alone, or one of the project directory's `.code-workspace`
 * files, the one in use ticked -- VS Code's "Open Folder" and "Open Workspace from File", for one
 * project. A submenu where the Refactor menu's are, so the choice is a click away and the current
 * one plain to see.
 *
 * On the backend: in split mode that is where the Project view's context menu is made, and backend
 * actions reach the client's main menu too. Shown only for a project with workspace files to choose.
 */
internal abstract class CodeBridgeWorkspaceMenu : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** Whether the menu belongs where [event] asks for it. */
    protected abstract fun applies(event: AnActionEvent, project: Project): Boolean

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isPopupGroup = true
        event.presentation.isEnabledAndVisible = project != null && applies(event, project) &&
                CodeBridgeWorkspaceChoice.state(project)?.candidates?.isNotEmpty() == true
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val project = event?.project ?: return EMPTY_ARRAY
        val state = CodeBridgeWorkspaceChoice.state(project) ?: return EMPTY_ARRAY
        return (listOf(Choice(project, null, "This Folder Only"), Separator.getInstance()) +
                state.candidates.map { Choice(project, it, it) }).toTypedArray()
    }

    /**
     * One way to open the project: [name], or the folder alone for null. Closes the menu once picked:
     * a toggle keeps it open by default, for flipping several in a row, but switching restarts the
     * extension host, and the menu stood there with the old tick.
     */
    private class Choice(private val project: Project, private val name: String?, text: String) :
        DumbAwareToggleAction(text) {
        init {
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        /** As it stands now, not as it stood when the menu opened. */
        override fun isSelected(event: AnActionEvent): Boolean {
            val state = CodeBridgeWorkspaceChoice.state(project) ?: return false
            return if (name == null) state.decided && state.chosen == null else state.chosen == name
        }

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            if (!isSelected(event)) CodeBridgeWorkspaceChoice.choose(project, name)
        }
    }

    /** In the Project view's context menu, on the project's own directory, where VS Code names the workspace. */
    class OnProjectNode : CodeBridgeWorkspaceMenu() {
        override fun applies(event: AnActionEvent, project: Project): Boolean {
            val selected = event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory } ?: return false
            val base = project.basePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
                ?: return false
            return runCatching { Path.of(selected.path).toAbsolutePath().normalize() }.getOrNull() == base
        }
    }

    /** In the Tools menu, wherever the project is. */
    class InTools : CodeBridgeWorkspaceMenu() {
        override fun applies(event: AnActionEvent, project: Project): Boolean = true
    }
}
