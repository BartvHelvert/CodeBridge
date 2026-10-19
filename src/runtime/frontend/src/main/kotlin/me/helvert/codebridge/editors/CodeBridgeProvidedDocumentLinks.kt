package me.helvert.codebridge.editors

import com.intellij.codeInsight.highlighting.TooltipLinkHandler
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.rpc.CodeBridgeApi

/**
 * Follows a link in a problem's tooltip to a document an extension makes up for a scheme of its
 * own -- rust-analyzer's "Click for full compiler diagnostic" -- by having the backend ask the
 * extension for it and open it.
 *
 * Here rather than on the backend because the tooltip is drawn, and clicked, wherever the editor
 * is: in split mode, that is this side. The link is `#codebridge-provided:<uri>`; the `#` is what makes
 * the tooltip hand it to a handler rather than to the browser.
 */
internal class CodeBridgeProvidedDocumentLinks : TooltipLinkHandler() {

    override fun handleLink(refSuffix: String, editor: Editor): Boolean {
        val project = editor.project ?: return false
        Scope.getInstance(project).scope.launch {
            val opened = try {
                CodeBridgeApi.getInstance().openProvidedDocument(project.projectId(), refSuffix)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not open $refSuffix", failure)
                false
            }
            if (!opened) withContext(Dispatchers.EDT) {
                if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, "Could not open $refSuffix")
            }
        }
        return true
    }

    /** Where an open runs: it outlives the tooltip that asked for it, and ends with the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeProvidedDocumentLinks::class.java)
    }
}
