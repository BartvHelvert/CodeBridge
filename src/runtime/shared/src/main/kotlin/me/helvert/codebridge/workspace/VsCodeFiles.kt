package me.helvert.codebridge.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Whether VS Code's own files apply: the project's `.vscode/` folders, a `.code-workspace` file
 * chosen for it, and the user's VS Code settings copied in on first use.
 *
 * Only the full CodeBridge plugin says so, by registering [VsCodeFilesBean]. A plugin that bundles a
 * VS Code extension and depends on the runtime alone has the extension behave as one of the IDE's
 * own: what a teammate on VS Code committed under `.vscode/` does not change it.
 */
object VsCodeFiles {
    val EP_NAME = ExtensionPointName<VsCodeFilesBean>("me.helvert.codebridge.vsCodeFiles")

    /**
     * True where the extension point is not even declared -- a unit test without the plugin
     * descriptor -- so the tests of what reads these files need not register it.
     */
    fun apply(): Boolean {
        val area = ApplicationManager.getApplication()?.extensionArea ?: return true
        return !area.hasExtensionPoint(EP_NAME) || EP_NAME.hasAnyExtensions()
    }
}

/** Says, by being registered, that VS Code's own files apply; it has nothing more to say. */
class VsCodeFilesBean
