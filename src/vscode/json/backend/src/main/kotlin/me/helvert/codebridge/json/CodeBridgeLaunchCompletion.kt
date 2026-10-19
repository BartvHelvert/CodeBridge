package me.helvert.codebridge.json

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import me.helvert.codebridge.completion.CodeBridgeJsonEntryInsertHandler
import me.helvert.codebridge.language.Snippets
import me.helvert.codebridge.workspace.CodeBridgeWorkspace

/**
 * Completion in `launch.json` -- and a `.code-workspace` file's `launch` -- that the schema cannot give.
 *
 * - **New entries.** Where a `configurations` entry can go, every debugger's `configurationSnippets`
 *   -- "CodeLLDB: Launch" and the like -- as VS Code offers them, inserted with the first
 *   placeholder selected.
 * - **An entry's properties**, narrowed to the shape its `type` and `request` select. The schema
 *   says which shape applies with `if`/`then`, which the IDE validates by but does not complete by:
 *   it offers the properties of every debugger's every request, `pid` from an attach among a
 *   launch's. This leaves the IDE's own completion in place and keeps only the names the selected
 *   shape declares; an entry no installed debugger declares is left as it is.
 */
internal class CodeBridgeLaunchCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!isLaunchJson(parameters)) return
        val schemas = CodeBridgeSchemaFiles.getInstance().current()
        val value = parameters.position.parent as? JsonValue ?: return
        if (isConfigurationsArray(value.parent)) {
            offerSnippets(parameters, value, schemas.launchSnippets, result)
            return
        }
        val allowed = allowedProperties(value, schemas) ?: return
        result.runRemainingContributors(parameters) { completion ->
            if (completion.lookupElement.lookupString.trim('"') in allowed) result.passResult(completion)
        }
        result.stopHere()
    }

    private fun isLaunchJson(parameters: CompletionParameters): Boolean {
        val file = parameters.originalFile as? JsonFile ?: return false
        val virtualFile = file.viewProvider.virtualFile
        return virtualFile.name == "launch.json" && virtualFile.parent?.name == ".vscode" || isWorkspaceFile(file)
    }

    private fun isWorkspaceFile(file: PsiFile?): Boolean =
        file?.originalFile?.viewProvider?.virtualFile?.extension == CodeBridgeWorkspace.EXTENSION

    /**
     * The `configurations` array of the launch configurations: at the top of `launch.json`, in the
     * top-level `launch` of a `.code-workspace` file.
     */
    private fun isConfigurationsArray(element: PsiElement?): Boolean {
        val list = (element as? JsonArray)?.parent as? JsonProperty ?: return false
        if (list.name != "configurations") return false
        val launch = list.parent as? JsonObject ?: return false
        if (!isWorkspaceFile(launch.containingFile)) return launch.parent is JsonFile
        val section = launch.parent as? JsonProperty ?: return false
        return section.name == "launch" && (section.parent as? JsonObject)?.parent is JsonFile
    }

    private fun offerSnippets(parameters: CompletionParameters, value: JsonValue, snippets: List<LaunchSnippet>, result: CompletionResultSet) {
        val file = parameters.originalFile
        // What was typed of the label, without the quote a string starts with.
        val typed = file.text.substring(value.textRange.startOffset, parameters.offset).removePrefix("\"")
        val sink = result.withPrefixMatcher(typed)
        // Laid out as the file lays out JSON, from the indentation of the line it starts on.
        val document = parameters.editor.document
        val line = document.getLineNumber(parameters.offset)
        val indent = document.charsSequence.subSequence(document.getLineStartOffset(line), parameters.offset).takeWhile { it == ' ' || it == '\t' }.toString()
        val options = CodeStyle.getIndentOptions(file)
        val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
        // VS Code's order: by `sortText`, or else the label.
        val ordered = snippets.sortedBy { it.sortText ?: it.label }
        for ((index, snippet) in ordered.withIndex()) {
            val expansion = Snippets.expand(snippet.text(indent, unit))
            // The entry itself is what the lookup inserts, and the handler only tidies around it and
            // selects the first placeholder -- all on the client in split mode, with nothing to redraw.
            // Matched by its label, the other lookup string.
            val element = LookupElementBuilder.create(snippet, expansion.text)
                .withLookupString(snippet.label)
                .withPresentableText(snippet.label)
                .withIcon(AllIcons.Json.Object)
                .withTypeText(snippet.description, true)
                .withInsertHandler(CodeBridgeJsonEntryInsertHandler(expansion.text, stops(expansion).flatMap { listOf(it.first, it.last + 1) }))
            sink.addElement(PrioritizedLookupElement.withPriority(element, (ordered.size - index).toDouble()))
        }
    }

    /**
     * Where each stop falls in [expansion]'s text, in the order VS Code visits them, and `$0` last
     * when the snippet has one. A stop written more than once is where it is first written.
     *
     * Read off the template, in which `$TAB_n$` stands for a stop's text, `$END$` for none and `$$`
     * for a dollar sign.
     */
    internal fun stops(expansion: Snippets.Expansion): List<IntRange> {
        val values = expansion.stops.associate { it.name to it.value }
        val found = HashMap<String, IntRange>()
        val template = expansion.template
        var index = 0
        var offset = 0
        while (index < template.length) {
            if (template[index] != '$') { offset++; index++; continue }
            if (template.getOrNull(index + 1) == '$') { offset++; index += 2; continue }
            val close = template.indexOf('$', index + 1).takeIf { it > index } ?: break
            val name = template.substring(index + 1, close)
            val length = values[name]?.length ?: 0
            found.putIfAbsent(name, offset until offset + length)
            offset += length
            index = close + 1
        }
        return expansion.stops.mapNotNull { found[it.name] } + listOfNotNull(expansion.end?.let { it until it })
    }

    private fun allowedProperties(name: JsonValue, schemas: CodeBridgeSchemaFiles.Schemas): Set<String>? {
        // Only a property's name: a value's completion is the shape's own, and already right.
        val property = name.parent as? JsonProperty ?: return null
        if (property.nameElement != name) return null
        val entry = property.parent as? JsonObject ?: return null
        if (!isConfigurationsArray(entry.parent)) return null
        val type = (entry.findProperty("type")?.value as? JsonStringLiteral)?.value ?: return null
        val request = (entry.findProperty("request")?.value as? JsonStringLiteral)?.value ?: return null
        return WorkspaceSchemas.configurationProperties(schemas.launchSchema, type, request)
    }
}
