package me.helvert.codebridge.extensions

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.helvert.codebridge.workbench.CodeBridgeWorkspaceChoice
import me.helvert.codebridge.workspace.WorkspaceChoices
import java.nio.file.Path
import java.util.*

/**
 * The extensions this project runs: those enabled, less those disabled for the workspace it is
 * opened with, as VS Code disables an extension for one workspace.
 *
 * Each workspace has its own list, as in VS Code: the project directory alone is one workspace, and
 * each `.code-workspace` file chosen for it another (see [CodeBridgeWorkspaceChoice]). Choosing another
 * workspace changes what the project runs; its host restarts on that choice already.
 *
 * Kept per user, in the workspace file: which extensions someone wants in a workspace is theirs, as
 * VS Code keeps it, not something to commit for the team. What a project runs is its extension host
 * and what that host brings -- views, commands, tasks, debuggers. What the IDE registers once for
 * every project, file types and theme colours, still follows the global switch.
 */
@Service(Service.Level.PROJECT)
@State(name = "CodeBridgeProjectExtensions", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectExtensions(private val project: Project) :
    SerializablePersistentStateComponent<ProjectExtensions.Disabled>(Disabled()), Disposable {

    /** One workspace's disabled extensions: [workspace] is its file's name, empty for the folder alone. */
    data class Workspace(@JvmField val workspace: String = "", @JvmField val ids: List<String> = emptyList())

    data class Disabled(
        @JvmField val workspaces: List<Workspace> = emptyList(),
        /** Extension ids, lower-case, from when the list was the project's; moved to the workspace chosen then. */
        @JvmField val ids: List<String> = emptyList(),
    )

    private val revision = MutableStateFlow(0L)

    /** Counts changes to what this project runs, for what draws from [enabled] to follow. */
    val changes: StateFlow<Long> = revision.asStateFlow()

    init {
        // What the project runs follows the workspace; the host restarts on this topic by itself.
        project.messageBus.connect(this).subscribe(CodeBridgeWorkspaceChoice.TOPIC, CodeBridgeWorkspaceChoice.Listener {
            revision.update { it + 1 }
        })
    }

    override fun loadState(state: Disabled) {
        super.loadState(state)
        if (state.ids.isNotEmpty()) {
            val workspace = workspace()
            updateState { old ->
                Disabled(old.workspaces.filter { it.workspace != workspace } + Workspace(workspace, old.ids))
            }
        }
    }

    /** The installed extensions enabled, and not disabled in this workspace. */
    fun enabled(): List<InstalledExtension> {
        val disabled = disabledIds()
        return ExtensionInstallationService.getInstance().list().filter { it.enabled && key(it.id) !in disabled }
    }

    fun isDisabled(id: String): Boolean = key(id) in disabledIds()

    /**
     * Disables [id] in this workspace, or lifts that, telling this project's listeners on [CHANGED] --
     * its host, which restarts without it. False if nothing changed.
     */
    fun setDisabled(id: String, disabled: Boolean): Boolean {
        val key = key(id)
        val workspace = workspace()
        if ((key in disabledIds()) == disabled) return false
        updateState { old ->
            val ids = old.workspaces.firstOrNull { it.workspace == workspace }?.ids.orEmpty()
            val next = if (disabled) (ids + key).sorted() else ids - key
            val others = old.workspaces.filter { it.workspace != workspace }
            old.copy(workspaces = if (next.isEmpty()) others else others + Workspace(workspace, next))
        }
        revision.update { it + 1 }
        if (!project.isDisposed) project.messageBus.syncPublisher(CHANGED).extensionsChanged()
        return true
    }

    private fun disabledIds(): List<String> {
        val workspace = workspace()
        return state.workspaces.firstOrNull { it.workspace == workspace }?.ids.orEmpty()
    }

    /**
     * The workspace chosen, by its file's name; empty for the folder alone. By the choice, not by the
     * file it resolves to: a file briefly unreadable while edited must not switch the list.
     */
    private fun workspace(): String {
        val base = project.basePath ?: return ""
        return (WorkspaceChoices.of(Path.of(base)) as? WorkspaceChoices.Choice.File)?.name.orEmpty()
    }

    private fun key(id: String) = id.lowercase(Locale.ROOT)

    override fun dispose() {}

    companion object {
        /** What this project runs changed; [ExtensionInstallationService.CHANGED] is for what is installed. */
        @Topic.ProjectLevel
        val CHANGED: Topic<ExtensionInstallationListener> =
            Topic.create("CodeBridge extensions disabled for a project changed", ExtensionInstallationListener::class.java)

        fun getInstance(project: Project): ProjectExtensions = project.service()
    }
}
