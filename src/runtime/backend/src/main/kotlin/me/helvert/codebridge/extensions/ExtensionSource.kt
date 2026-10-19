package me.helvert.codebridge.extensions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Extensions another plugin brings, beside those the user installed. They come and go with that
 * plugin: the user cannot install, disable or uninstall them here, only disable them for a workspace.
 *
 * Each one's [InstalledExtension.providedBy] names the plugin, for the user to be told where to go.
 * [list] is asked on every [ExtensionInstallationService.list], which is hot, so a source keeps what
 * it read and says when that changed with [ExtensionInstallationService.sourcesChanged].
 */
interface ExtensionSource {
    fun list(): List<InstalledExtension>

    companion object {
        val EP_NAME = ExtensionPointName<ExtensionSource>("me.helvert.codebridge.extensionSource")

        /** False where this plugin's descriptor is not loaded: the platform tests. */
        internal fun registered(): Boolean = ApplicationManager.getApplication().extensionArea.hasExtensionPoint(EP_NAME)
    }
}
