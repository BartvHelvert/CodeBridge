package me.helvert.codebridge.settings

import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.manifest.VsixManifest
import me.helvert.codebridge.manifest.VsixManifestParser

/**
 * The VS Code settings CodeBridge's own workbench reads, declared as an extension declares its
 * settings, in `contributes.configuration`.
 *
 * In VS Code the workbench declares its settings itself -- `files.exclude`, `editor.tabSize` and
 * hundreds more -- and none of that ships in the extension host runtime. CodeBridge stands in for the
 * workbench, so it declares the ones it acts on, and no others: offering a setting that does nothing
 * here would only mislead. With them declared, extensions read their defaults and may write them,
 * and `settings.json` offers and checks them, as for any extension's settings.
 */
object WorkbenchSettings {

    /** The declaration, as an extension manifest. */
    val manifest: VsixManifest by lazy {
        val text = checkNotNull(WorkbenchSettings::class.java.getResourceAsStream("/codebridge/workbench-settings.json")) {
            "CodeBridge's workbench settings are missing from the plugin"
        }.use { it.readBytes().decodeToString() }
        checkNotNull(VsixManifestParser.parse(text).manifest) { "CodeBridge's workbench settings do not parse" }
    }

    /** [manifest]'s JSON. */
    val raw: JsonObject get() = manifest.raw
}
