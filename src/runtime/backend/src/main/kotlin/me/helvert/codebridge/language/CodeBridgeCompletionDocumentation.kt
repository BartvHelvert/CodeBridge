package me.helvert.codebridge.language

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import com.intellij.xml.util.XmlStringUtil
import me.helvert.codebridge.exthost.protocol.payload.CompletionItem
import me.helvert.codebridge.exthost.protocol.payload.Markdown

/**
 * The documentation of a completion an extension offered, as the workbench's suggest widget shows
 * it beside the item the user settles on: its detail as code, then its documentation.
 *
 * An extension may leave both out of the list and fill them in only when asked about one item
 * (`resolveCompletionItem`) -- rust-analyzer does, for every item -- so an item whose provider
 * resolves is asked first, and what the list carried is the fallback.
 */
internal class CodeBridgeCompletionDocumentation : LookupElementDocumentationTargetProvider {

    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? {
        val item = element.`object` as? CompletionItem ?: return null
        if (item.resolvable == null && item.detail == null && item.documentation == null) return null
        val language = psiFile.virtualFile?.let { HostedLanguageFeatures.getInstance(psiFile.project).languageOf(it) }
        return CodeBridgeCompletionTarget(psiFile.project, item, language)
    }
}

private class CodeBridgeCompletionTarget(
    private val project: Project,
    private val item: CompletionItem,
    /** The VS Code language id the detail is highlighted as, as the workbench renders it. */
    private val language: String?,
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(item.label).presentation()

    /** Asynchronous, since resolving asks the extension host and blocks until it answers. */
    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            val resolved = item.resolvable?.let { HostedLanguageFeatures.getInstance(project).resolveCompletion(it) }
            val detail = resolved?.detail ?: item.detail
            val documentation = resolved?.documentation ?: item.documentation
            val markdown = if (resolved?.documentation != null) resolved.documentationIsMarkdown else item.documentationIsMarkdown
            html(detail, documentation, markdown)?.let(DocumentationResult::documentation)
        }

    private fun html(detail: String?, documentation: String?, markdown: Boolean): String? {
        val parts = listOfNotNull(
            detail?.takeIf { it.isNotBlank() }?.let { HoverMarkdown.toHtml(project, "```${language.orEmpty()}\n$it\n```") },
            documentation?.takeIf { it.isNotBlank() }?.let {
                // Command links are reduced to their labels: none is run from here, trusted or not.
                if (markdown) HoverMarkdown.toHtml(project, Markdown.withoutCommandLinks(it))
                else "<p>" + XmlStringUtil.escapeString(it).replace("\n", "<br>") + "</p>"
            },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("")
    }
}
