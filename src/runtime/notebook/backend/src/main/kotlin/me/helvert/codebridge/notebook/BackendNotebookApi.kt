package me.helvert.codebridge.notebook

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.project.Project
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import me.helvert.codebridge.extensions.ExtensionInstallationListener
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.ProjectExtensions

internal class NotebookRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<NotebookApi>()) { BackendNotebookApi() }
    }
}

internal class BackendNotebookApi : NotebookApi {
    override suspend fun notebookTypes(projectId: ProjectId): Flow<List<NotebookTypeDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        // Read again whenever what the project runs changes, as the host is restarted then too.
        return callbackFlow {
            val listener = ExtensionInstallationListener { trySend(NotebookContributions.types(project)) }
            val connection = project.messageBus.connect()
            connection.subscribe(ExtensionInstallationService.CHANGED, listener)
            connection.subscribe(ProjectExtensions.CHANGED, listener)
            send(NotebookContributions.types(project))
            awaitClose { connection.disconnect() }
        }.distinctUntilChanged()
    }

    override suspend fun open(projectId: ProjectId, file: VirtualFileId, viewType: String, editorId: String): Flow<NotebookEventDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        val virtualFile = file.virtualFile() ?: return flowOf(NotebookEventDto.Failed("The file is not on the backend"))
        return notebooks(project).open(virtualFile, viewType, editorId)
    }

    override suspend fun setText(projectId: ProjectId, editorId: String, handle: Int, text: String) {
        projectId.findProjectOrNull()?.let { notebooks(it).setText(editorId, handle, text) }
    }

    override suspend fun insertCell(projectId: ProjectId, editorId: String, index: Int, kind: Int) {
        projectId.findProjectOrNull()?.let { notebooks(it).insertCell(editorId, index, kind) }
    }

    override suspend fun deleteCell(projectId: ProjectId, editorId: String, handle: Int) {
        projectId.findProjectOrNull()?.let { notebooks(it).deleteCell(editorId, handle) }
    }

    override suspend fun moveCell(projectId: ProjectId, editorId: String, handle: Int, delta: Int) {
        projectId.findProjectOrNull()?.let { notebooks(it).moveCell(editorId, handle, delta) }
    }

    override suspend fun moveCells(projectId: ProjectId, editorId: String, handles: List<Int>, gap: Int) {
        projectId.findProjectOrNull()?.let { notebooks(it).moveCells(editorId, handles, gap) }
    }

    override suspend fun clearOutputs(projectId: ProjectId, editorId: String, handle: Int?) {
        projectId.findProjectOrNull()?.let { notebooks(it).clearOutputs(editorId, handle) }
    }

    override suspend fun copyCells(projectId: ProjectId, editorId: String, handles: List<Int>, cut: Boolean) {
        projectId.findProjectOrNull()?.let { notebooks(it).copyCells(editorId, handles, cut) }
    }

    override suspend fun pasteCells(projectId: ProjectId, editorId: String, index: Int): List<Int> =
        projectId.findProjectOrNull()?.let { notebooks(it).pasteCells(editorId, index) }.orEmpty()

    override suspend fun duplicateCells(projectId: ProjectId, editorId: String, handles: List<Int>): List<Int> =
        projectId.findProjectOrNull()?.let { notebooks(it).duplicateCells(editorId, handles) }.orEmpty()

    override suspend fun copyCellsTo(projectId: ProjectId, editorId: String, handles: List<Int>, index: Int): List<Int> =
        projectId.findProjectOrNull()?.let { notebooks(it).copyCellsTo(editorId, handles, index) }.orEmpty()

    override suspend fun joinCells(projectId: ProjectId, editorId: String, handles: List<Int>): Int? =
        projectId.findProjectOrNull()?.let { notebooks(it).joinCells(editorId, handles) }

    override suspend fun changeCellKind(projectId: ProjectId, editorId: String, handle: Int, kind: Int): Int? =
        projectId.findProjectOrNull()?.let { notebooks(it).changeCellKind(editorId, handle, kind) }

    override suspend fun undo(projectId: ProjectId, editorId: String): List<Int> =
        projectId.findProjectOrNull()?.let { notebooks(it).undo(editorId) }.orEmpty()

    override suspend fun redo(projectId: ProjectId, editorId: String): List<Int> =
        projectId.findProjectOrNull()?.let { notebooks(it).redo(editorId) }.orEmpty()

    override suspend fun run(projectId: ProjectId, editorId: String, handles: List<Int>): Boolean =
        projectId.findProjectOrNull()?.let { notebooks(it).run(editorId, handles) } ?: false

    override suspend fun interrupt(projectId: ProjectId, editorId: String, handles: List<Int>) {
        projectId.findProjectOrNull()?.let { notebooks(it).interrupt(editorId, handles) }
    }

    override suspend fun selectKernel(projectId: ProjectId, editorId: String, kernelId: String) {
        projectId.findProjectOrNull()?.let { notebooks(it).selectKernel(editorId, kernelId) }
    }

    override suspend fun save(projectId: ProjectId, editorId: String): Boolean =
        projectId.findProjectOrNull()?.let { notebooks(it).save(editorId) } ?: false

    override suspend fun reload(projectId: ProjectId, editorId: String) {
        projectId.findProjectOrNull()?.let { notebooks(it).reload(editorId) }
    }

    override suspend fun completions(projectId: ProjectId, editorId: String, handle: Int, line: Int, column: Int): List<CellCompletionDto> =
        projectId.findProjectOrNull()?.let { notebooks(it).completions(editorId, handle, line, column) }.orEmpty()

    override suspend fun hover(projectId: ProjectId, editorId: String, handle: Int, line: Int, column: Int): String? =
        projectId.findProjectOrNull()?.let { notebooks(it).hover(editorId, handle, line, column) }

    override suspend fun goToDefinition(projectId: ProjectId, editorId: String, handle: Int, line: Int, column: Int): Boolean =
        projectId.findProjectOrNull()?.let { notebooks(it).goToDefinition(editorId, handle, line, column) } ?: false

    private fun notebooks(project: Project) = HostedNotebooks.getInstance(project)
}
