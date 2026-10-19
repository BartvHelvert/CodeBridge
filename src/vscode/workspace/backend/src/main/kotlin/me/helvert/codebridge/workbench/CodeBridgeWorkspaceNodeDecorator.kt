package me.helvert.codebridge.workbench

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import java.nio.file.Path

/**
 * Says in the Project view which VS Code workspace the project is opened with, as VS Code's
 * Explorer heads its folders "rust-sandbox (Workspace)": the project's own directory is marked
 * with the workspace's name, and the other workspace folders in it as such. Those outside it are
 * listed under External Libraries already; see [CodeBridgeWorkspaceFolderRoots].
 */
internal class CodeBridgeWorkspaceNodeDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val directory = node.virtualFile?.takeIf { it.isDirectory } ?: return
        val layout = project.service<CodeBridgeWorkspaceFolders>().layout()?.takeIf { it.isMultiRoot } ?: return
        val path = runCatching { Path.of(directory.path).toAbsolutePath().normalize() }.getOrNull() ?: return
        val note = when {
            path == layout.base -> "VS Code workspace: ${layout.name.removeSuffix(" (Workspace)")}"
            layout.folders.any { it.path == path } -> "VS Code workspace folder"
            else -> return
        }
        data.locationString = listOfNotNull(data.locationString?.takeIf { it.isNotBlank() }, note).joinToString("  ·  ")
    }
}
