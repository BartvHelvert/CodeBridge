package me.helvert.codebridge.textmate

import me.helvert.codebridge.language.CodeBridgeLanguageFileTypes
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider

/**
 * Installed package roots are already VS Code TextMate bundles.
 *
 * The extensions the claims were built from, not the store's latest list: a grammar loaded for an
 * extension whose files are not claimed lets TextMate's content detector call them `textmate`, and
 * the platform keeps that answer after the grammar is gone. See [CodeBridgeLanguageFileTypes].
 */
internal class CodeBridgeTextMateBundles : TextMateBundleProvider {
    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> =
        CodeBridgeLanguageFileTypes.getInstance().extensions()
            .map { TextMateBundleProvider.PluginBundle(it.manifest.displayName ?: it.id, it.directory) }
}
