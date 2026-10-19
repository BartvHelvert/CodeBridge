package me.helvert.codebridge.rpc

import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import me.helvert.codebridge.extensions.ExtensionInstallResult
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.registry.OpenVsxRegistry
import java.nio.file.Files

internal class MarketplaceRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<MarketplaceApi>()) { BackendMarketplaceApi() }
    }
}

internal class BackendMarketplaceApi : MarketplaceApi {
    private val installations get() = ExtensionInstallationService.getInstance()
    private val registry get() = OpenVsxRegistry.getInstance()

    override suspend fun search(query: String, offset: Int) = withContext(Dispatchers.IO) {
        require(offset >= 0)
        registry.search(query, offset)
    }

    override suspend fun detail(namespace: String, name: String) = withContext(Dispatchers.IO) {
        registry.detail(namespace, name)
    }

    override suspend fun targetPlatform(): String = OpenVsxRegistry.targetPlatform()

    @Suppress("BlockingMethodInNonBlockingContext") // operationFlow runs it on Dispatchers.IO
    override suspend fun installRegistry(namespace: String, name: String): Flow<OperationUpdate> =
        operationFlow { progress ->
        val detail = registry.detail(namespace, name)
        val directory = Files.createTempDirectory("codebridge-download")
        try {
            val downloaded = registry.download(detail, OpenVsxRegistry.targetPlatform(), directory, progress)
            progress.text = "Installing ${detail.extension.label}"
            installations.install(downloaded, progress::checkCanceled).toDto()
        } finally {
            deleteTransferDirectory(directory)
        }
    }

    override suspend fun extensions(): List<InstalledExtensionDto> = withContext(Dispatchers.IO) {
        installations.list().map { it.toDto() }
    }

    override suspend fun extensionIcon(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = installations.list().firstOrNull { it.id == id }?.iconFile ?: return@withContext null
        // Package images are optional decoration; bound the amount sent to the client.
        if (Files.size(file) > MAX_ICON_BYTES) return@withContext null
        Files.newInputStream(file).use { stream ->
            stream.readNBytes(MAX_ICON_BYTES + 1).takeIf { it.size <= MAX_ICON_BYTES }
        }
    }

    override suspend fun installVsix(contents: Flow<ByteArray>): Flow<OperationUpdate> = operationFlow { progress ->
        progress.text = "Transferring VSIX to the extension host machine"
        withUploadedVsix(contents, progress::checkCanceled) { file ->
            progress.text = "Installing VS Code extension"
            installations.install(file, progress::checkCanceled).toDto()
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        check(installations.setEnabled(id, enabled)) { "Extension $id is no longer installed" }
    }

    override suspend fun disabledInProject(projectId: ProjectId): List<String> {
        val project = projectId.findProjectOrNull() ?: return emptyList()
        return installations.list().map { it.id }.filter(ProjectExtensions.getInstance(project)::isDisabled)
    }

    override suspend fun setDisabledInProject(projectId: ProjectId, id: String, disabled: Boolean) {
        val project = checkNotNull(projectId.findProjectOrNull()) { "The project is no longer open" }
        withContext(Dispatchers.IO) { ProjectExtensions.getInstance(project).setDisabled(id, disabled) }
    }

    override suspend fun uninstall(id: String): Flow<OperationUpdate> = operationFlow { progress ->
        progress.text = "Uninstalling $id"
        progress.checkCanceled()
        installations.uninstall(id)
        null
    }

    private companion object {
        const val MAX_ICON_BYTES = 8 * 1024 * 1024
    }
}

/** What the settings page shows of an extension: no paths it would read, nothing it could run. */
fun InstalledExtension.toDto() = InstalledExtensionDto(
    id, manifest.name, manifest.publisher, manifest.version, manifest.displayName,
    manifest.description, manifest.categories, manifest.engineRange, manifest.extensionDependencies,
    directory.toString(), targetPlatform, installedAt, enabled, iconFile != null, providedBy,
)

/** Public for the marketplace module, which installs what it downloads the same way. */
fun ExtensionInstallResult.toDto() = InstallResultDto(extension.toDto(), previousVersion, problems)
