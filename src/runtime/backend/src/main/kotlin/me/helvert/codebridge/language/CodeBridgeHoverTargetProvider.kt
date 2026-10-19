package me.helvert.codebridge.language

import com.intellij.model.Pointer
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.*
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.Markdown
import kotlin.coroutines.cancellation.CancellationException

/**
 * Answers Quick Documentation from the extension, the way the workbench asks for a hover.
 *
 * Ordinary documentation, computed on the backend, rather than IntelliJ's LSP client -- the same
 * split-mode reason [CodeBridgeDiagnosticsAnnotator] is an annotator.
 *
 * The offset matters, which is why this is a target provider and not a `DocumentationProvider`: for
 * a plain-text file the PSI element is the whole file, so the position would otherwise be lost.
 *
 * The hover messages of decorations at the offset come first, as the workbench shows them above
 * the providers' hovers.
 */
internal class CodeBridgeHoverTargetProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val features = HostedLanguageFeatures.getInstance(file.project)
        val decorations = CodeBridgeEditorDecorations.getInstance(file.project)
        val provided = features.hasHoverProviders(virtualFile)
        if (!provided && !decorations.hasHovers(virtualFile)) return emptyList()
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
        if (offset > document.textLength) return emptyList()
        val decorated = decorations.hoversAt(virtualFile, document, offset)
        if (!provided && decorated.isEmpty()) return emptyList()
        val line = document.getLineNumber(offset)
        // VS Code counts lines and columns from one.
        return listOf(
            CodeBridgeHoverTarget(
                file.project,
                virtualFile,
                line + 1,
                offset - document.getLineStartOffset(line) + 1,
                decorated
            )
        )
    }
}

/**
 * Runs the command behind a `command:` link in a hover, as the workbench does when one is clicked.
 *
 * Resolved to the hover itself, which the popup then shows again. Running a command navigates
 * nowhere, but a link has to resolve to something: one no handler resolves is handed to the browser,
 * which tries to open the `command:` URI as an address. A content updater, which would leave the
 * page alone, only works in a monolith -- a split-mode backend forwards resolved targets alone, and
 * never runs an updater at all.
 *
 * Only links in a hover, and only those [Markdown.trustedText] left there: the ones the extension
 * marked as safe to run.
 */
internal class CodeBridgeHoverLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is CodeBridgeHoverTarget) return null
        val command = Markdown.command(url.removePrefix(HoverMarkdown.INTERNAL_LINK_PREFIX)) ?: return null
        LOG.debug("Running ${command.id} from a hover link")
        // Not waited on, for the reason HostedLanguageFeatures.executeCommand gives.
        val features = HostedLanguageFeatures.getInstance(target.project)
        features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                features.executeCommand(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("The command behind a hover link (${command.id}) failed", failure)
            }
        }
        return LinkResolveResult.resolvedTarget(target)
    }

    private companion object {
        val LOG = Logger.getInstance(CodeBridgeHoverLinkHandler::class.java)
    }
}

private class CodeBridgeHoverTarget(
    val project: Project,
    private val file: VirtualFile,
    private val line: Int,
    private val column: Int,
    /** Markdown from the decorations at the position, read when the target was made. */
    private val decorated: List<String>,
) : DocumentationTarget {

    /** Nothing here is tied to a PSI element, so the target survives without restoration. */
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(file.name).presentation()

    /**
     * Asynchronous on purpose: the answer comes from another process.
     *
     * `asyncDocumentation` runs the supplier off the editor thread, which is what makes it safe for
     * [HostedLanguageFeatures.hover] to block on the extension host.
     */
    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            val provided = HostedLanguageFeatures.getInstance(project).hover(file, line, column)
            val text = (decorated + listOfNotNull(provided)).ifEmpty { return@asyncDocumentation null }
                .joinToString(HOVER_SEPARATOR)
            // The text is markdown, plaintext hovers included: the host escapes those into markdown.
            // Command links left in the text are trusted ones; CodeBridgeHoverLinkHandler runs them.
            DocumentationResult.documentation(HoverMarkdown.toHtml(project, text))
        }

    private companion object {
        /** A rule between hovers, as the workbench draws between its hover parts. */
        const val HOVER_SEPARATOR = "\n\n---\n\n"
    }
}
