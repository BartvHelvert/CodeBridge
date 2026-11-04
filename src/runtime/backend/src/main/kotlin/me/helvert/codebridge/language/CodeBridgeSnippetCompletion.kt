package me.helvert.codebridge.language

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.completion.CodeBridgeInsertHandler
import me.helvert.codebridge.exthost.protocol.payload.CompletionItem

/**
 * Offers the snippets extensions contribute ([ContributedSnippets]), as VS Code's suggest widget
 * does: in the completion list beside everything else, by their prefix, in any file whose language
 * they are for.
 *
 * Registered for every language, since which languages have snippets is up to the extensions
 * installed, and a snippet for `javascript` goes into a file the IDE reads as JavaScript itself.
 *
 * Where they rank follows `editor.snippetSuggestions` -- `top`, `bottom`, `inline` among the rest,
 * or `none` -- and `editor.suggest.showSnippets` turns them off, as in VS Code. Both are read from the
 * workspace settings the extension host has, so they apply once it is running.
 */
internal class CodeBridgeSnippetCompletion : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile.virtualFile ?: return
        // A breakpoint's condition borrows its file's completions; a snippet is no expression.
        if (file.getUserData(CodeBridgeCompletionContributor.BORROWED) != null) return
        val project = parameters.originalFile.project
        val snippets = ContributedSnippets.getInstance(project)
        val offered = snippets.forFile(file)
        if (offered.isEmpty()) return
        val language = snippets.languageOf(file)
        val servers = project.service<HostedLanguageServers>()
        if ((servers.setting(SHOW, language) as? JsonPrimitive)?.booleanOrNull == false) return
        val placement = (servers.setting(PLACEMENT, language) as? JsonPrimitive)?.contentOrNull ?: "inline"
        if (placement == "none") return

        val editor = parameters.editor
        val document = editor.document
        val offset = parameters.offset.coerceIn(0, document.textLength)
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val before = document.charsSequence.subSequence(lineStart, offset)
        val wordStart = CodeBridgeCompletionContributor.wordStart(document.charsSequence, offset) - lineStart
        val indent = before.takeWhile { it == ' ' || it == '\t' }.toString()
        val indentation = CodeBridgeSnippetTemplates.indentation(project, document)
        val variables = SnippetVariables(project, editor, file, parameters.originalFile, offset)

        val sinks = HashMap<Int, CompletionResultSet>()
        for ((snippet, source) in offered) {
            val body = Snippets.indented(StringUtil.convertLineSeparators(snippet.body), indent, indentation)
            val expansion = Snippets.expand(body, variables)
            for (prefix in snippet.prefixes) {
                val start = start(before, wordStart, prefix) { typed ->
                    sinks.getOrPut(typed) { result.withPrefixMatcher(before.substring(typed)) }.prefixMatcher.prefixMatches(prefix)
                }
                val sink = sinks.getOrPut(start) { result.withPrefixMatcher(before.substring(start)) }
                sink.addElement(ranked(element(snippet, source, prefix, language, expansion), placement))
            }
        }
    }

    private fun element(
        snippet: SnippetFile.Snippet, source: String, prefix: String, language: String, expansion: Snippets.Expansion
    ): LookupElement {
        // The item as the lookup object, for [CodeBridgeCompletionDocumentation] to show what the
        // workbench's details pane does: where the snippet is from, then what it inserts.
        val item = CompletionItem(
            label = prefix, insertText = snippet.body, kind = null, detail = null,
            documentation = "${snippet.description ?: snippet.name} ($source)\n\n```$language\n${expansion.text}\n```",
            documentationIsMarkdown = true, filterText = prefix, sortText = null, snippet = true, replace = null,
        )
        return LookupElementBuilder.create(item, prefix)
            .withPresentableText(prefix)
            .withTailText(" ${snippet.name}", true)
            .withIcon(AllIcons.Nodes.Template)
            .withInsertHandler(
                // As a language server's snippet goes in: only stops to walk need the backend's template.
                if (expansion.stops.isNotEmpty()) CodeBridgeCompletionContributor.template(expansion)
                else CodeBridgeInsertHandler(expansion.text, expansion.end ?: expansion.text.length, expansion.ends.drop(1).map { it.end })
            )
    }

    private fun ranked(element: LookupElement, placement: String): LookupElement = when (placement) {
        "top" -> PrioritizedLookupElement.withPriority(element, TOP)
        "bottom" -> PrioritizedLookupElement.withPriority(element, -TOP)
        else -> element
    }

    companion object {
        private const val SHOW = "editor.suggest.showSnippets"
        private const val PLACEMENT = "editor.snippetSuggestions"

        /** Above any rank a language server's items are given, which count their list's length. */
        private const val TOP = 1e9

        /**
         * Where in [before] -- the line up to the caret -- the text [prefix] completes starts: the
         * furthest back it could, where the typed text starts as [prefix] does and [matches] it, or
         * else the word at the caret ([wordStart]). A prefix need not be a word: `!html` after `!h`.
         */
        fun start(before: CharSequence, wordStart: Int, prefix: String, matches: (Int) -> Boolean): Int {
            for (start in (before.length - prefix.length).coerceAtLeast(0) until wordStart) {
                if (before[start].lowercaseChar() == prefix[0].lowercaseChar() && matches(start)) return start
            }
            return wordStart
        }
    }
}
