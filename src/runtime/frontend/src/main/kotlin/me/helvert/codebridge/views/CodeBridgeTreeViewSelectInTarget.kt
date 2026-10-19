package me.helvert.codebridge.views

import com.intellij.ide.SelectInContext
import com.intellij.ide.SelectInTarget
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Select In > Extension View: the file in an extension's tree view whose items stand for files,
 * found by their `resourceUri`s; see [CodeBridgeTreePanel.select]. Library files are outside the project,
 * so the Project view has no node for them, but a dependency view such as rust-analyzer's does.
 *
 * Offered once a view has been opened and fetched an item that is the file or a folder holding it,
 * since finding out otherwise means waking extensions and fetching their trees.
 */
internal class CodeBridgeTreeViewSelectInTarget(private val project: Project) : SelectInTarget {
    override fun toString(): String = "Extension View"

    override fun isDumbAware(): Boolean = true

    /**
     * By path alone: in split mode the editor's file is this client's copy of the backend's, not a
     * file of the local file system, so only its path is compared.
     */
    override fun canSelect(context: SelectInContext): Boolean {
        val file = context.virtualFile
        val held = windows()?.mayHold(file.path) == true
        if (!held) LOG.debug("No tree view item leads to ${file.path} (${file.fileSystem.protocol})")
        return held
    }

    override fun selectIn(context: SelectInContext, requestFocus: Boolean) {
        windows()?.select(context.virtualFile.path, requestFocus)
    }

    /** After the IDE's own views. */
    override fun getWeight(): Float = 100f

    private fun windows(): TreeViewWindows? = CodeBridgeTreeViewsScope.getInstance(project).windows

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeTreeViewSelectInTarget::class.java)
    }
}
