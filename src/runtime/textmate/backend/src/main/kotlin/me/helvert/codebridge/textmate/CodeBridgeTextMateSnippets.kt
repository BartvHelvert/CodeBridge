package me.helvert.codebridge.textmate

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.template.impl.CustomLiveTemplateLookupElement
import me.helvert.codebridge.language.ContributedSnippets

/**
 * Keeps TextMate's copy of an extension's snippet out of the completion list, where CodeBridge
 * already offers the snippet as VS Code would.
 *
 * Installed packages are TextMate bundles ([CodeBridgeTextMateBundles]), so the TextMate plugin reads
 * their snippets too, and in a TextMate file lists each as a custom live template. Left in, every
 * snippet would show twice. The only custom live template a TextMate file has is TextMate's, so an
 * item of one whose key is a prefix CodeBridge offers here is that copy.
 *
 * Typing the prefix and pressing Tab still expands TextMate's, which VS Code does only with
 * `editor.tabCompletion` on; nothing about it gets in the way.
 */
internal class CodeBridgeTextMateSnippets : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.virtualFile ?: return
        val prefixes = ContributedSnippets.getInstance(parameters.originalFile.project).prefixes(file)
        if (prefixes.isEmpty()) return
        result.runRemainingContributors(parameters) { completion ->
            val element = completion.lookupElement
            if (element !is CustomLiveTemplateLookupElement || element.lookupString !in prefixes) result.passResult(completion)
        }
    }
}
