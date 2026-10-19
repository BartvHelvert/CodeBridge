package me.helvert.codebridge.workbench

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.workspace.WorkspaceChoices
import java.nio.file.Path

/**
 * The `.code-workspace` file a project is opened with, as the user chose it; see [WorkspaceChoices].
 * What follows the workspace's folders hears of a change on [TOPIC] and takes the new ones up at
 * once, as it does when the file itself is edited.
 */
object CodeBridgeWorkspaceChoice {

    fun interface Listener {
        fun workspaceChosen()
    }

    @Topic.ProjectLevel
    val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

    /** The workspace files to choose from, by name, the one chosen, and whether the user has decided. */
    data class State(val candidates: List<String>, val chosen: String?, val decided: Boolean)

    // Static as well, for the split-mode UI tests, which reach the backend through the driver.
    @JvmStatic
    fun state(project: Project): State? {
        val base = base(project) ?: return null
        val choice = WorkspaceChoices.of(base)
        return State(
            candidates = CodeBridgeWorkspace.candidates(base).map { it.fileName.toString() },
            chosen = (choice as? WorkspaceChoices.Choice.File)?.name,
            decided = choice != WorkspaceChoices.Choice.Undecided,
        )
    }

    /** Opens the project with the workspace file [name], or the folder alone for null. */
    @JvmStatic
    fun choose(project: Project, name: String?) {
        val base = base(project) ?: return
        require(name == null || CodeBridgeWorkspace.candidates(base).any { it.fileName.toString() == name }) {
            "There is no workspace file named '$name' in the project directory"
        }
        val next = name?.let(WorkspaceChoices.Choice::File) ?: WorkspaceChoices.Choice.FolderOnly
        if (WorkspaceChoices.of(base) == next) return
        WorkspaceChoices.choose(base, next)
        project.messageBus.syncPublisher(TOPIC).workspaceChosen()
    }

    private fun base(project: Project): Path? = project.basePath?.let(Path::of)
}
