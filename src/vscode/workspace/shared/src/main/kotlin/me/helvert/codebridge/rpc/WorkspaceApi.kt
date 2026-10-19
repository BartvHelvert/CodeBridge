package me.helvert.codebridge.rpc

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable

/** The project's VS Code workspace files, which are on the backend machine. */
@Rpc
interface WorkspaceApi : RemoteApi<Unit> {
    /**
     * The `.code-workspace` files in the project directory, the one the project is opened with, and
     * whether the user has decided; null for a project with no directory. VS Code opens a workspace
     * file only when asked to, and suggests one it finds.
     */
    suspend fun workspaceChoice(projectId: ProjectId): WorkspaceChoiceDto?

    /** Opens the project with the workspace file [name] from now on, or the folder alone for null. */
    suspend fun chooseWorkspace(projectId: ProjectId, name: String?)

    /**
     * The workspace folders, by name, that a `launch.json`, `tasks.json` or `settings.json` can be
     * opened for in a multi-root workspace -- beside the `.code-workspace` file's own -- or null in a
     * single-folder one, where there is one of each. VS Code asks which.
     */
    suspend fun workspaceTargets(projectId: ProjectId): List<String>?

    /**
     * The debuggers whose `initialConfigurations` a new `launch.json` can start from, or null when
     * [target] -- a folder's name, or null for the workspace's own -- already has launch
     * configurations. VS Code asks which, when there is a choice.
     */
    suspend fun launchStarts(projectId: ProjectId, target: String?): List<LaunchStartDto>?

    /**
     * [target]'s [name] -- `launch.json`, `tasks.json` or `settings.json` -- for the frontend to
     * open; made first if there is none, `launch.json` from the `initialConfigurations` of
     * [debugger], when one is named. [target] is a folder's name, or null for the workspace's own:
     * the project's `.vscode/` file, or in a multi-root workspace the `.code-workspace` file's
     * section. Null for any other name, an unknown folder, or a project with no directory.
     */
    suspend fun workspaceFile(projectId: ProjectId, name: String, debugger: String?, target: String?): WorkspaceFileDto?

    companion object {
        suspend fun getInstance(): WorkspaceApi = RemoteApiProviderService.resolve(remoteApiDescriptor<WorkspaceApi>())
    }
}


/** See [WorkspaceApi.workspaceChoice]. */
@Serializable
data class WorkspaceChoiceDto(val candidates: List<String>, val chosen: String?, val decided: Boolean)

/** A debugger a new `launch.json` can start from: its `type`, and the label it is shown by. */
@Serializable
data class LaunchStartDto(val type: String, val label: String)

/** A workspace file, whether it was [created] or added to just now, and the [offset] of what was asked for in it. */
@Serializable
data class WorkspaceFileDto(val file: VirtualFileId, val created: Boolean, val offset: Int? = null)
