// Internal API is this module's purpose; each use is listed in docs/platform-limitations.md.
@file:Suppress("UnstableApiUsage")

package me.helvert.codebridge.internal

import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginAdvertiserSuppressor
import com.intellij.openapi.vfs.VirtualFile
import me.helvert.codebridge.language.CodeBridgeLanguageFileTypes

/** Suppress redundant plugin suggestions only while an enabled extension supports the file. */
class CodeBridgePluginAdvertiserSuppressor : PluginAdvertiserSuppressor {
    override fun isSuppressedFor(project: Project, file: VirtualFile): Boolean =
        !file.isDirectory && CodeBridgeLanguageFileTypes.getInstance().supportsName(file.name)
}
