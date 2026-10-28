package me.helvert.codebridge.extensions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.helvert.codebridge.language.CodeBridgeLanguageFileTypes
import me.helvert.codebridge.manifest.VsixArchive
import java.nio.file.Path
import java.util.*

fun interface ExtensionInstallationListener {
    fun extensionsChanged()
}

/**
 * The extensions the IDE has: those the user installed ([ExtensionStore]) and those other plugins
 * bring ([ExtensionSource]). One a plugin brings takes the place of an installed one with the same
 * identifier, and comes and goes with its plugin: it cannot be installed, disabled or removed here.
 */
@Service(Service.Level.APP)
class ExtensionInstallationService(scope: CoroutineScope) {
    private val store = ExtensionStore(PathManager.getConfigDir().resolve("codebridge/extensions"))
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    init {
        // A plugin with a source loaded or unloaded without a restart. The CoroutineScope overload;
        // the inspection reports the deprecated Disposable one of the same name.
        @Suppress("UnstableApiUsage")
        if (ExtensionSource.registered()) ExtensionSource.EP_NAME.addChangeListener(scope, ::changed)
    }

    private fun changed() {
        revision.update { it + 1 }
        // File types first, and not through the topic: see CodeBridgeLanguageFileTypes.extensionsChanged.
        CodeBridgeLanguageFileTypes.getInstance().extensionsChanged()
        ApplicationManager.getApplication().messageBus.syncPublisher(CHANGED).extensionsChanged()
    }

    /** What an [ExtensionSource] lists has changed. */
    fun sourcesChanged() = changed()

    private fun provided(): List<InstalledExtension> =
        if (!ExtensionSource.registered()) emptyList()
        else ExtensionSource.EP_NAME.extensionList.flatMap { it.list() }.distinctBy { it.id }

    fun list(): List<InstalledExtension> {
        val provided = provided()
        if (provided.isEmpty()) return store.list()
        val ids = provided.mapTo(HashSet()) { it.id }
        return (provided + store.list().filter { it.id !in ids }).sortedBy { it.id }
    }

    fun install(path: Path, checkCanceled: () -> Unit): ExtensionInstallResult {
        // A copy installed here would only be hidden behind the one a plugin brings.
        VsixArchive.readManifest(path).manifest?.let { refuseProvided(it.id, "installed") }
        val result = store.install(path, checkCanceled)
        changed()
        return result
    }

    /** Whether an installed extension is loaded into an extension host. */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        refuseProvided(id, if (enabled) "enabled" else "disabled")
        val changed = store.setEnabled(id, enabled)
        if (changed) changed()
        return changed
    }

    fun uninstall(id: String): Boolean {
        refuseProvided(id, "uninstalled")
        val removed = store.uninstall(id)
        if (removed) changed()
        return removed
    }

    private fun refuseProvided(id: String, what: String) {
        val plugin = provided().firstOrNull { it.id == id.lowercase(Locale.ROOT) }?.providedBy ?: return
        throw IllegalStateException("$id comes with the plugin $plugin and is $what with it, in Settings | Plugins")
    }

    companion object {
        val CHANGED = Topic.create("CodeBridge extension installation changed", ExtensionInstallationListener::class.java)
        fun getInstance(): ExtensionInstallationService =
            ApplicationManager.getApplication().getService(ExtensionInstallationService::class.java)
    }
}
