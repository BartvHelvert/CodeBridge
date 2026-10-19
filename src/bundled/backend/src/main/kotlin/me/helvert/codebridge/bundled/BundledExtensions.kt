package me.helvert.codebridge.bundled

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Transient
import kotlinx.coroutines.CoroutineScope
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.ExtensionSource
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.manifest.ManifestStrings
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Files

/**
 * A VS Code extension another plugin ships, so that plugin can be published as the extension
 * itself:
 *
 * ```xml
 * <depends>me.helvert.codebridge.bundled</depends>
 * <extensions defaultExtensionNs="me.helvert.codebridge">
 *     <bundledExtension path="extension"/>
 * </extensions>
 * ```
 *
 * [path] is the unpacked extension -- the `extension/` folder of its VSIX, with `package.json` at
 * its top -- relative to the providing plugin's directory.
 */
class BundledExtensionBean : PluginAware {
    @Attribute("path")
    @JvmField
    var path: String = ""

    @Transient
    lateinit var plugin: PluginDescriptor
        private set

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        plugin = pluginDescriptor
    }
}

/** What CodeBridge Bundled Extensions gives the runtime: the extensions other plugins ship ([BundledExtensions]). */
internal class BundledExtensionSource : ExtensionSource {
    override fun list(): List<InstalledExtension> = service<BundledExtensions>().list()
}

/**
 * The extensions other plugins ship ([BundledExtensionBean]), read from their plugin directories.
 * A service rather than the source itself, for the scope its change listener lives in.
 */
@Service(Service.Level.APP)
internal class BundledExtensions(scope: CoroutineScope) {

    /** Read once, and again only when a plugin bundling one comes or goes. */
    @Volatile
    private var read: List<InstalledExtension>? = null

    init {
        // The CoroutineScope overload; the inspection reports the deprecated Disposable one of the same name.
        @Suppress("UnstableApiUsage")
        EP_NAME.addChangeListener(scope) {
            read = null
            ExtensionInstallationService.getInstance().sourcesChanged()
        }
    }

    fun list(): List<InstalledExtension> = read ?: readAll().also { read = it }

    /**
     * Every bundled extension that can be read. One that cannot is logged and left out rather than
     * failing the list: it is another plugin's mistake, and the user's own extensions still run.
     */
    private fun readAll(): List<InstalledExtension> = EP_NAME.extensionList.mapNotNull { bean ->
        runCatching { read(bean) }
            .onFailure { LOG.warn("Cannot read the extension ${bean.plugin.pluginId} bundles at '${bean.path}'", it) }
            .getOrNull()
    }.distinctBy { it.id }

    private fun read(bean: BundledExtensionBean): InstalledExtension {
        val root = checkNotNull(bean.plugin.pluginPath) { "The plugin has no directory" }.toAbsolutePath().normalize()
        val directory = root.resolve(bean.path).normalize()
        require(bean.path.isNotBlank() && directory.startsWith(root)) { "The path must be inside the plugin" }
        val parsed = VsixManifestParser.parse(
            Files.readString(directory.resolve("package.json")),
            ManifestStrings.nls(directory),
        )
        val manifest = parsed.manifest ?: error(parsed.problems.joinToString())
        return InstalledExtension(
            manifest, directory, targetPlatform = null, installedAt = "", providedBy = bean.plugin.name,
        )
    }

    private companion object {
        val EP_NAME = ExtensionPointName<BundledExtensionBean>("me.helvert.codebridge.bundledExtension")
        val LOG = logger<BundledExtensions>()
    }
}
