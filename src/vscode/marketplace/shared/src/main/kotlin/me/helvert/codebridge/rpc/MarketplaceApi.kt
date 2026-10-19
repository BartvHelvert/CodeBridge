package me.helvert.codebridge.rpc

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import me.helvert.codebridge.registry.RegistryDetail
import me.helvert.codebridge.registry.RegistrySearch

/**
 * Open VSX, as the backend sees it: the registry is reached from the extension host machine, which
 * is also where what it downloads is installed.
 */
@Rpc
interface MarketplaceApi : RemoteApi<Unit> {
    suspend fun search(query: String, offset: Int): RegistrySearch
    suspend fun detail(namespace: String, name: String): RegistryDetail
    suspend fun targetPlatform(): String
    suspend fun installRegistry(namespace: String, name: String): Flow<OperationUpdate>

    /** The extensions the IDE has, those other plugins bundle included. */
    suspend fun extensions(): List<InstalledExtensionDto>
    suspend fun extensionIcon(id: String): ByteArray?
    suspend fun installVsix(contents: Flow<ByteArray>): Flow<OperationUpdate>
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * The ids of the extensions disabled in the workspace [projectId] is opened with, lower-case; none
     * for a project not open.
     */
    suspend fun disabledInProject(projectId: ProjectId): List<String>

    /** Disables [id] in [projectId]'s workspace alone, or lifts that; the project's host restarts on a change. */
    suspend fun setDisabledInProject(projectId: ProjectId, id: String, disabled: Boolean)
    suspend fun uninstall(id: String): Flow<OperationUpdate>

    companion object {
        suspend fun getInstance(): MarketplaceApi = RemoteApiProviderService.resolve(remoteApiDescriptor<MarketplaceApi>())
    }
}

/** Presentation snapshot: no local filesystem access, executable callbacks, or Swing objects. */
@Serializable
data class InstalledExtensionDto(
    val id: String,
    val name: String,
    val publisher: String,
    val version: String,
    val displayName: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val engineRange: String? = null,
    val dependencies: List<String> = emptyList(),
    val directory: String = "",
    val targetPlatform: String? = null,
    val installedAt: String = "",
    val enabled: Boolean = true,
    val hasIcon: Boolean = false,
    /** The IntelliJ plugin this extension comes with; installed, disabled and removed only with it. */
    val providedBy: String? = null,
) {
    val label: String get() = displayName ?: name
    fun missingDependencies(installedIds: Set<String>): List<String> =
        dependencies.filter { it.lowercase(java.util.Locale.ROOT) !in installedIds }
}

@Serializable
data class InstallResultDto(
    val extension: InstalledExtensionDto,
    val previousVersion: String? = null,
    val problems: List<String> = emptyList(),
)

/** The final update has completed=true or error!=null. Cancellation terminates the flow. */
@Serializable
data class OperationUpdate(
    val text: String = "",
    val fraction: Double? = null,
    val completed: Boolean = false,
    val result: InstallResultDto? = null,
    val error: String? = null,
)
