package me.helvert.codebridge.rpc

import com.intellij.codeWithMe.ClientId
import com.intellij.ide.ui.icons.IconId
import com.intellij.ide.ui.icons.rpcIdOrNull
import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.rpcId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.project.projectId
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.intellij.util.IconUtil
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.exthost.protocol.payload.StatusBar
import me.helvert.codebridge.language.CodeBridgeCompletionCommands
import me.helvert.codebridge.language.CodeBridgeDocumentColors
import me.helvert.codebridge.language.CodeBridgeEditorDecorations
import me.helvert.codebridge.language.CodeBridgeInlineCompletionLists
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.launch.CodeBridgeCallStackMenu
import me.helvert.codebridge.settings.BundledRuntime
import me.helvert.codebridge.settings.VsCodeRuntime
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.workbench.*

internal class BackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<CodeBridgeApi>()) { BackendCodeBridgeApi() }
    }
}

internal class BackendCodeBridgeApi : CodeBridgeApi {
    private val installations get() = ExtensionInstallationService.getInstance()


    override suspend fun extensionChanges(): Flow<Long> = installations.changes

    override suspend fun contributedIcons(): ContributedIconsDto = withContext(Dispatchers.IO) {
        IconContributions.read(installations.list())
    }

    override suspend fun runtimeStatus(): RuntimeStatusDto = withContext(Dispatchers.IO) {
        val bundled = BundledRuntime.getInstance().installation()
        val supported = BundledRuntime.supported()
        val resolution = VsCodeRuntime.getInstance().resolve()
        RuntimeStatusDto(
            bundled?.nameLong ?: BundledRuntime.NAME, bundled?.version.orEmpty(),
            bundled != null, BundledRuntime.getInstance().installDirectory()?.toString().orEmpty(),
            supported, if (supported) null else BundledRuntime.unsupportedPlatformMessage(),
            resolution.diagnosis.message, resolution.installation != null, resolution.source.name,
        )
    }

    override suspend fun userSettings(): UserSettingsDto = withContext(Dispatchers.IO) {
        CodeBridgeUserSettings.ensure()?.let { LOG.info("Copied the VS Code user settings from $it") }
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(CodeBridgeUserSettings.file)) {
            "Could not find ${CodeBridgeUserSettings.file}"
        }
        UserSettingsDto(file.rpcId())
    }

    override suspend fun statusItems(projectId: ProjectId): Flow<List<StatusItemDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        val statusBar = HostedStatusBar.getInstance(project)
        return statusBar.items.map { entries -> entries.values.map { it.toDto(statusBar.tooltip(it)) } }
    }

    override suspend fun runStatusItem(projectId: ProjectId, entryId: String) {
        val project = projectId.findProjectOrNull() ?: return
        HostedStatusBar.getInstance(project).run(entryId)
    }

    override suspend fun runStatusAction(projectId: ProjectId, entryId: String, index: Int) {
        val project = projectId.findProjectOrNull() ?: return
        HostedStatusBar.getInstance(project).runAction(entryId, index)
    }

    override suspend fun runCompletionCommand(projectId: ProjectId, id: Long, length: Int, hash: Int) {
        val project = projectId.findProjectOrNull() ?: return
        CodeBridgeCompletionCommands.getInstance(project).run(id, length, hash)
    }

    override suspend fun runInlineCompletionCommand(projectId: ProjectId, length: Int, hash: Int) {
        val project = projectId.findProjectOrNull() ?: return
        CodeBridgeInlineCompletionLists.getInstance(project).accepted(length, hash)
    }

    override suspend fun clientRequests(projectId: ProjectId): Flow<List<ClientRequestDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        return HostedClient.getInstance(project).pending
    }

    override suspend fun answerClientRequest(projectId: ProjectId, id: Long, done: Boolean, text: String?) {
        val project = projectId.findProjectOrNull() ?: return
        HostedClient.getInstance(project).answer(id, done, text)
    }

    override suspend fun appLinkPort(port: Int, schemes: List<String>): Flow<Unit> = flow {
        // Collected until the client goes, so its links stop being given out with it.
        val serving = HostedUrls.serving(port, schemes.toSet())
        try {
            awaitCancellation()
        } finally {
            serving.close()
        }
    }

    override suspend fun appUriScheme(): String? = withContext(Dispatchers.IO) {
        VsCodeRuntime.getInstance().installationOrNull()?.uriScheme
    }

    override suspend fun openAppLink(requestUri: String, focused: ProjectId?): Flow<AppLinkOutcomeDto> = flow {
        val delivery = HostedUrls.deliver(requestUri, focused?.findProjectOrNull())
        if (delivery == null) {
            emit(AppLinkOutcomeDto(problem = "No project has its extensions running."))
            return@flow
        }
        val projectId = (delivery.owner as? Project)?.projectId()
        emit(
            try {
                AppLinkOutcomeDto(extension = delivery.extension.await(), projectId = projectId)
            } catch (_: HostedUrls.Declined) {
                AppLinkOutcomeDto(projectId = projectId, declined = true)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                LOG.warn("Could not deliver a link into the editor", failure)
                AppLinkOutcomeDto(projectId = projectId, problem = failure.message)
            }
        )
    }

    override suspend fun openProvidedDocument(projectId: ProjectId, uri: String): Boolean {
        val project = projectId.findProjectOrNull() ?: return false
        return HostedContentProviders.getInstance(project).open(uri)
    }

    override suspend fun messages(projectId: ProjectId): Flow<List<MessageDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        return HostedMessages.getInstance(project).pending
    }

    override suspend fun answerMessage(projectId: ProjectId, id: Long, choice: Int?) {
        val project = projectId.findProjectOrNull() ?: return
        HostedMessages.getInstance(project).answer(id, choice)
    }

    override suspend fun quickInputs(projectId: ProjectId): Flow<List<QuickInputDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        return HostedQuickInputs.getInstance(project).pending
    }

    override suspend fun answerQuickInput(projectId: ProjectId, id: Long, chosen: List<Int>?, value: String?) {
        val project = projectId.findProjectOrNull() ?: return
        HostedQuickInputs.getInstance(project).answer(id, chosen, value)
    }

    override suspend fun changeQuickInput(projectId: ProjectId, id: Long, event: QuickInputEventDto) {
        val project = projectId.findProjectOrNull() ?: return
        HostedQuickInputs.getInstance(project).changed(id, event)
    }

    override suspend fun openRequests(projectId: ProjectId): Flow<OpenFileDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return CodeBridgeTextEditors.getInstance(project).openRequestsFor(ClientId.currentOrNull)
    }

    override suspend fun decorations(projectId: ProjectId): Flow<DecorationsDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return CodeBridgeEditorDecorations.getInstance(project).published()
    }

    override suspend fun decorationIcon(projectId: ProjectId, key: String): ByteArray? = withContext(Dispatchers.IO) {
        projectId.findProjectOrNull()?.let { CodeBridgeEditorDecorations.getInstance(it).gutterIconBytes(key) }
    }

    override suspend fun pickDocumentColor(projectId: ProjectId, path: String, offset: Int, rgba: Int): Boolean {
        val project = projectId.findProjectOrNull() ?: return false
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return false
        return CodeBridgeDocumentColors.getInstance(project).pick(file, offset, java.awt.Color(rgba, true))
    }

    override suspend fun terminalRequests(projectId: ProjectId): Flow<TerminalRequestDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return CodeBridgeToolWindowTerminals.getInstance(project).requests
    }

    override suspend fun terminalEvent(projectId: ProjectId, event: TerminalEventDto) {
        val project = projectId.findProjectOrNull() ?: return
        CodeBridgeToolWindowTerminals.getInstance(project).reported(event)
    }

    override suspend fun treeViews(projectId: ProjectId): Flow<List<TreeViewDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        return HostedTreeViews.getInstance(project).views().flowOn(Dispatchers.IO)
    }

    override suspend fun treeEvents(projectId: ProjectId): Flow<TreeEventDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return HostedTreeViews.getInstance(project).changes
    }

    override suspend fun treeChildren(projectId: ProjectId, viewId: String, handle: String?): TreeChildrenDto {
        val project = projectId.findProjectOrNull() ?: return TreeChildrenDto()
        return withContext(Dispatchers.IO) { HostedTreeViews.getInstance(project).children(viewId, handle) }
    }

    override suspend fun treeViewVisible(projectId: ProjectId, viewId: String, visible: Boolean) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).setVisible(viewId, visible)
    }

    override suspend fun treeItemExpanded(projectId: ProjectId, viewId: String, handle: String, expanded: Boolean) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).setExpanded(viewId, handle, expanded)
    }

    override suspend fun treeSelection(projectId: ProjectId, viewId: String, handles: List<String>, focus: String?) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).setSelection(viewId, handles, focus)
    }

    override suspend fun runTreeItem(projectId: ProjectId, viewId: String, handle: String) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).run(viewId, handle)
    }

    override suspend fun treeIcon(projectId: ProjectId, key: String): ByteArray? = withContext(Dispatchers.IO) {
        projectId.findProjectOrNull()?.let { HostedTreeViews.getInstance(it).icon(key) }
    }

    override suspend fun treeItemActions(projectId: ProjectId, viewId: String, handle: String): List<TreeActionDto> {
        val project = projectId.findProjectOrNull() ?: return emptyList()
        return withContext(Dispatchers.IO) { HostedTreeViews.getInstance(project).itemActions(viewId, handle) }
    }

    override suspend fun runTreeAction(
        projectId: ProjectId,
        viewId: String,
        actionId: Int,
        handle: String?,
        selection: List<String>
    ) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).runAction(viewId, actionId, handle, selection)
    }

    override suspend fun runWelcomeLink(projectId: ProjectId, viewId: String, link: Int) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).runWelcomeLink(viewId, link)
    }

    override suspend fun treeCheckboxes(projectId: ProjectId, viewId: String, states: Map<String, Boolean>) {
        val project = projectId.findProjectOrNull() ?: return
        HostedTreeViews.getInstance(project).setCheckboxes(viewId, states)
    }

    override suspend fun callStackActions(projectId: ProjectId): List<TreeActionDto> {
        val project = projectId.findProjectOrNull() ?: return emptyList()
        return withContext(Dispatchers.IO) { CodeBridgeCallStackMenu.actions(project) }
    }

    override suspend fun runCallStackAction(projectId: ProjectId, actionId: Int) {
        val project = projectId.findProjectOrNull() ?: return
        CodeBridgeCallStackMenu.run(project, actionId)
    }

    override suspend fun webviews(projectId: ProjectId): Flow<List<WebviewDto>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        return HostedWebviews.getInstance(project).views()
    }

    override suspend fun webviewEvents(projectId: ProjectId): Flow<WebviewEventDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return HostedWebviews.getInstance(project).changes
    }

    override suspend fun resolveWebviewView(projectId: ProjectId, viewId: String): WebviewResolutionDto {
        val project = projectId.findProjectOrNull() ?: return WebviewResolutionDto(problem = "The project is closed")
        return withContext(Dispatchers.IO) { HostedWebviews.getInstance(project).resolveView(viewId) }
    }

    override suspend fun webviewMessage(projectId: ProjectId, handle: String, json: String, buffers: List<ByteArray>) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWebviews.getInstance(project).message(handle, json, buffers)
    }

    override suspend fun webviewState(projectId: ProjectId, handle: String, state: String?) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWebviews.getInstance(project).saveState(handle, state)
    }

    override suspend fun webviewResource(projectId: ProjectId, handle: String, url: String, cachedVersion: String?): WebviewResourceDto? =
        withContext(Dispatchers.IO) {
            projectId.findProjectOrNull()?.let { HostedWebviews.getInstance(it).resource(handle, url, cachedVersion) }
        }

    override suspend fun workerHosts(projectId: ProjectId): Flow<List<String>> {
        val project = projectId.findProjectOrNull() ?: return flowOf(emptyList())
        return HostedWorkerHosts.getInstance(project).hosts()
    }

    override suspend fun workerHostOutbox(projectId: ProjectId, id: String): Flow<List<ByteArray>> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return HostedWorkerHosts.getInstance(project).outbox(id)
    }

    override suspend fun workerHostInbox(projectId: ProjectId, id: String, messages: List<ByteArray>) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWorkerHosts.getInstance(project).inbox(id, messages)
    }

    override suspend fun workerHostFailed(projectId: ProjectId, id: String, reason: String) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWorkerHosts.getInstance(project).failed(id, reason)
    }

    override suspend fun workerHostResource(projectId: ProjectId, id: String, path: String): WebviewResourceDto? =
        withContext(Dispatchers.IO) {
            projectId.findProjectOrNull()?.let { HostedWorkerHosts.getInstance(it).resource(id, path) }
        }

    override suspend fun webviewShown(
        projectId: ProjectId,
        handle: String,
        visible: Boolean,
        active: Boolean,
        position: Int
    ) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWebviews.getInstance(project).shown(handle, visible, active, position)
    }

    override suspend fun closeWebview(projectId: ProjectId, handle: String) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWebviews.getInstance(project).close(handle)
    }

    override suspend fun runWebviewLink(projectId: ProjectId, handle: String, uri: String) {
        val project = projectId.findProjectOrNull() ?: return
        HostedWebviews.getInstance(project).runLink(handle, uri)
    }

    override suspend fun customEditorTypes(projectId: ProjectId): Flow<CustomEditorSetupDto> {
        val project = projectId.findProjectOrNull() ?: return flowOf(CustomEditorSetupDto())
        return CustomEditorDefaults.getInstance(project).changes()
    }

    override suspend fun setDefaultEditor(projectId: ProjectId, pattern: String, viewType: String): String? {
        val project = projectId.findProjectOrNull() ?: return "The project is closed."
        return CustomEditorDefaults.getInstance(project).setDefault(pattern, viewType)
    }

    override suspend fun openCustomEditor(
        projectId: ProjectId, file: VirtualFileId, viewType: String, editorId: String, position: Int,
    ): Flow<CustomEditorDto> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow<CustomEditorDto>().also { LOG.info("No project for the $viewType editor") }
        val virtualFile = file.virtualFile()
            ?: return flowOf(CustomEditorDto(message = "The file is not on the backend.")).also { LOG.info("No backend file for the $viewType editor: $file") }
        return HostedCustomEditors.getInstance(project).open(virtualFile, viewType, editorId, position)
    }

    override suspend fun fileIcon(projectId: ProjectId, file: VirtualFileId): IconId? {
        val project = projectId.findProjectOrNull() ?: return null
        val virtualFile = file.virtualFile() ?: return null
        return try {
            readAction { IconUtil.computeFileIcon(virtualFile, 0, project) }.rpcIdOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.debug("No icon for ${virtualFile.name}", failure)
            null
        }
    }

    override suspend fun fileAt(path: String): VirtualFileId? = withContext(Dispatchers.IO) {
        LocalFileSystem.getInstance().findFileByPath(path)?.rpcId()
    }

    override suspend fun saveCustomEditor(projectId: ProjectId, editorId: String) {
        projectId.findProjectOrNull()?.let { HostedCustomEditors.getInstance(it).save(editorId) }
    }

    override suspend fun undoCustomEditor(projectId: ProjectId, editorId: String) {
        projectId.findProjectOrNull()?.let { HostedCustomEditors.getInstance(it).undo(editorId) }
    }

    override suspend fun redoCustomEditor(projectId: ProjectId, editorId: String) {
        projectId.findProjectOrNull()?.let { HostedCustomEditors.getInstance(it).redo(editorId) }
    }

    companion object {
        private val LOG = Logger.getInstance(BackendCodeBridgeApi::class.java)
    }
}

internal fun StatusBar.Entry.toDto(tooltip: List<StatusTooltipBlockDto>? = null) = StatusItemDto(
    entryId, extensionId, name, text, tooltip, command != null,
    severity = background?.lowercase()?.let { colour ->
        when {
            "error" in colour -> "error"
            "warning" in colour -> "warning"
            else -> null
        }
    },
    alignLeft = alignLeft, priority = priority,
)

