package me.helvert.codebridge.rpc

import com.intellij.ide.vfs.rpcId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.helvert.codebridge.workbench.CodeBridgeWorkspaceChoice
import java.nio.file.Path

internal class WorkspaceRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<WorkspaceApi>()) { BackendWorkspaceApi() }
    }
}

internal class BackendWorkspaceApi : WorkspaceApi {
    override suspend fun workspaceChoice(projectId: ProjectId): WorkspaceChoiceDto? = withContext(Dispatchers.IO) {
        val project = projectId.findProjectOrNull() ?: return@withContext null
        CodeBridgeWorkspaceChoice.state(project)?.let { WorkspaceChoiceDto(it.candidates, it.chosen, it.decided) }
    }

    override suspend fun chooseWorkspace(projectId: ProjectId, name: String?) = withContext(Dispatchers.IO) {
        val project = projectId.findProjectOrNull() ?: return@withContext
        CodeBridgeWorkspaceChoice.choose(project, name)
    }

    override suspend fun workspaceTargets(projectId: ProjectId): List<String>? = withContext(Dispatchers.IO) {
        val base = projectId.findProjectOrNull()?.basePath?.let(Path::of) ?: return@withContext null
        WorkspaceFiles.targets(base)
    }

    override suspend fun launchStarts(projectId: ProjectId, target: String?): List<LaunchStartDto>? =
        withContext(Dispatchers.IO) {
            val base = projectId.findProjectOrNull()?.basePath?.let(Path::of) ?: return@withContext null
            if (WorkspaceFiles.exists(base, WorkspaceFiles.LAUNCH, target)) return@withContext null
            WorkspaceFiles.launchStarts().map { (type, label) -> LaunchStartDto(type, label) }
        }

    override suspend fun workspaceFile(
        projectId: ProjectId,
        name: String,
        debugger: String?,
        target: String?
    ): WorkspaceFileDto? =
        withContext(Dispatchers.IO) {
            val base = projectId.findProjectOrNull()?.basePath?.let(Path::of) ?: return@withContext null
            val opened = WorkspaceFiles.open(base, name, debugger, target) ?: return@withContext null
            WorkspaceFileDto(opened.file.rpcId(), opened.created, opened.offset)
        }
}
