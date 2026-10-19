package me.helvert.codebridge.textmate

import me.helvert.codebridge.extensions.ExtensionInstallationService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider

/** Installed package roots are already VS Code TextMate bundles. */
internal class CodeBridgeTextMateBundles : TextMateBundleProvider {
    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> =
        ExtensionInstallationService.getInstance().list()
            .filter { it.enabled }
            .map { TextMateBundleProvider.PluginBundle(it.manifest.displayName ?: it.id, it.directory) }
}
