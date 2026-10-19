package me.helvert.codebridge.language

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.helvert.codebridge.completion.CodeBridgeCommandInsertHandler
import me.helvert.codebridge.completion.CodeBridgeInsertHandler
import me.helvert.codebridge.exthost.protocol.payload.CodeActions
import me.helvert.codebridge.exthost.protocol.payload.CompletionItem
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/**
 * Offers what an extension would complete, drawn as ordinary IntelliJ lookup elements.
 *
 * Computed on the backend and forwarded like any other lookup, rather than through IntelliJ's LSP
 * client -- the same split-mode reason [CodeBridgeDiagnosticsAnnotator] is an annotator.
 *
 * This is the first feature that depends on document synchronization being right. A hover against a
 * stale buffer is wrong now and then; a completion against one is wrong on every keystroke, because
 * the prefix the server is completing is the text the user has just typed.
 */
internal class CodeBridgeCompletionContributor : CompletionContributor() {

    /**
     * Runs on a background thread under a read action, which is what makes the blocking call safe.
     *
     * The request is cancellable, so a keystroke that invalidates this lookup also cancels the
     * provider rather than leaving it to finish into nothing.
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.virtualFile ?: return
        val project = parameters.originalFile.project
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.hasCompletionProviders()) return
        file.getUserData(BORROWED)?.let { borrowed -> return borrow(parameters, result, features, borrowed) }

        // The original document, not the copy the platform inserts its dummy identifier into: the
        // extension host mirrors the real buffer, so every position has to be stated in its terms.
        val document = parameters.editor.document
        val offset = parameters.offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line) + 1

        val items = features.completions(file, line + 1, column)
        if (items.isEmpty()) return
        LOG.debug("An extension offered ${items.size} completion(s)")
        // VS Code offers words from the document only when no provider answered, and so does this:
        // IntelliJ's word completion, and TextMate's, which is the same code, both honour this key.
        // Otherwise a word typed out in full is an exact match, which the lookup puts first ahead of
        // any ranking -- `println` the word above rust-analyzer's `println!`.
        (parameters.process as? UserDataHolder)?.putUserData(BaseCompletionService.FORBID_WORD_COMPLETION, true)

        val lines = DocumentLines(document.charsSequence)
        // Every item in one reply shares the default range, so the prefix is computed once from the
        // first item that named one. Replacing IntelliJ's own prefix matters: for a plain-text file
        // it would otherwise guess the word boundary itself and filter out everything the server
        // meant to offer after a dot or a dash. With no range at all, the prefix is the word before
        // the caret, which is the default range the host would have filled in; left to IntelliJ, a
        // plain-text file has no prefix, and accepting `println` after `prin` gives `prinprintln`.
        val start = items.firstNotNullOfOrNull { it.replace }
            ?.let { span -> lines.offsetOf(span.startLineNumber, span.startColumn) }
            ?: wordStart(document.charsSequence, offset)
        val sink = result.withPrefixMatcher(document.charsSequence.subSequence(start, offset).toString())

        offer(items, sink, commands = features, document = document)
        // Not `stopHere()`: an extension's completions join whatever else the IDE knows about the
        // file rather than replacing it.
    }

    /**
     * Completes a debugger expression -- a breakpoint's condition. While a session is paused the
     * debugger completes it, in the language it evaluates and with the whole expression in view, so
     * `total.ab` gets `abs`. Otherwise it gets what the extension offers in its own file where the
     * breakpoint stops, which is what is in scope there, filtered by the word being typed.
     *
     * Asked at the line's first character of code, not its first column as VS Code's breakpoint
     * widget asks: in the indentation the server sees the end of the statement above, and
     * rust-analyzer then leaves out a `let` on the line just before.
     */
    private fun borrow(
        parameters: CompletionParameters, result: CompletionResultSet,
        features: HostedLanguageFeatures, borrowed: Borrowed
    ) {
        val text = parameters.editor.document.charsSequence
        val offset = parameters.offset.coerceIn(0, text.length)
        borrowed.debugger?.complete(text.toString(), offset)?.takeIf { it.elements.isNotEmpty() }?.let { answer ->
            val sink = result.withPrefixMatcher(text.subSequence(answer.start.coerceIn(0, offset), offset).toString())
            sink.addAllElements(answer.elements.mapIndexed { index, element ->
                PrioritizedLookupElement.withPriority(element, (answer.elements.size - index).toDouble())
            })
            return
        }
        val source = FileDocumentManager.getInstance().getDocument(borrowed.file) ?: return
        if (borrowed.line >= source.lineCount) return
        val start = source.getLineStartOffset(borrowed.line)
        val code = source.charsSequence.subSequence(start, source.getLineEndOffset(borrowed.line))
            .indexOfFirst { !it.isWhitespace() }
        val items = features.completions(borrowed.file, borrowed.line + 1, code.coerceAtLeast(0) + 1)
        LOG.debug("An extension offered ${items.size} completion(s) for an expression at ${borrowed.file.name}:${borrowed.line + 1}")
        if (items.isEmpty()) return
        offer(items, result.withPrefixMatcher(text.subSequence(wordStart(text, offset), offset).toString()))
    }

    /**
     * [commands] runs the command an item carries once it is inserted into [document]; null where the
     * items were asked of another file than the one they go into, whose commands would act on the
     * wrong one.
     */
    private fun offer(
        items: List<CompletionItem>, sink: CompletionResultSet, commands: HostedLanguageFeatures? = null,
        document: Document? = null
    ) {
        val ranked = items.sortedWith(VS_CODE_ORDER)
        // Added as one batch: a lookup already showing keeps the items it has on top as the rest
        // arrive, so added one by one the first few the server ranked -- `eprint!` -- could stay
        // above those matching from the start, depending on how fast they came through.
        sink.addAllElements(ranked.mapIndexed { index, item ->
            val expansion = if (item.snippet) Snippets.expand(item.insertText) else null
            val text = expansion?.text ?: item.insertText
            // Matched on what VS Code filters by, `filterText` or else the label, and nothing else:
            // IntelliJ matches every lookup string, so the inserted text as one would let `pd` --
            // whose snippet is an `eprintln!` -- through for `print`.
            val filter = item.filterText ?: item.label
            // The item as the lookup object, for its documentation; see [CodeBridgeCompletionDocumentation].
            val element = LookupElementBuilder.create(item, filter)
                .withPresentableText(item.label)
                .withTypeText(item.detail ?: item.documentation?.lineSequence()?.firstOrNull(), true)
                .withIcon(icon(item.kind))
                .withInsertHandler(
                    when {
                        // Only a snippet with stops to walk needs a live template, which only the
                        // backend can start. The rest is text and a caret, which the client can place.
                        expansion != null && expansion.stops.isNotEmpty() -> template(expansion)
                        expansion?.end != null ->
                            CodeBridgeInsertHandler(text, expansion.end, expansion.ends.drop(1).map { it.end })
                        text != filter -> CodeBridgeInsertHandler(text, text.length)
                        else -> null
                    }.let { insert ->
                        val command = item.command
                        when {
                            command == null || commands == null || document == null -> insert
                            // Inserted by the client, as the item would be without a command, which then
                            // asks for the command; see [CodeBridgeCompletionCommands].
                            insert == null || insert is CodeBridgeInsertHandler -> CodeBridgeCommandInsertHandler(
                                insert,
                                CodeBridgeCompletionCommands.getInstance(commands.project)
                                    .register(command, document, commands)
                            )

                            else -> commanding(insert, command, commands)
                        }
                    })
            // In the order VS Code ranks them; IntelliJ would otherwise re-sort them
            // alphabetically and lose the order the server thought was useful. Every priority is
            // above zero, the default, so the IDE's own suggestions -- words found elsewhere in a
            // plain-text file -- come after all of them rather than tying with the best.
            PrioritizedLookupElement.withPriority(element, (items.size - index).toDouble())
        })
    }

    /**
     * A breakpoint's expression: it completes as [file] does where [line], 0-based, starts, unless
     * [debugger] -- asked first -- answers.
     */
    data class Borrowed(val file: VirtualFile, val line: Int, val debugger: DebuggerCompletions? = null)

    /** A paused debugger's completion of an expression; null when there is none to ask. */
    fun interface DebuggerCompletions {
        fun complete(text: String, offset: Int): Answer?
    }

    /** What to offer, in order, and where in the expression the text they replace begins. */
    class Answer(val start: Int, val elements: List<LookupElement>)

    companion object {
        /** Set on a debugger expression's scratch file, which is in no extension host. */
        val BORROWED: Key<Borrowed> = Key.create("codebridge.completion.borrowed")

        private val LOG = Logger.getInstance(CodeBridgeCompletionContributor::class.java)

        /**
         * VS Code's `defaultComparator`: `sortText` ignoring case, then the label, then the kind.
         *
         * Not the order of the reply: rust-analyzer states its relevance in `sortText` and sends the
         * items in whatever order it collected them. VS Code compares `sortText` only when both items
         * have one, which is not a consistent order when only some do; an item without one is ranked
         * by its label in that place instead. Before any of this VS Code ranks by how well the typed
         * prefix matches, which IntelliJ's own matching still decides here.
         */
        val VS_CODE_ORDER: Comparator<CompletionItem> =
            compareBy<CompletionItem> { (it.sortText ?: it.label).lowercase() }
                .thenBy { it.label }
                .thenBy { it.kind ?: 0 }

        /**
         * Inserts as [insert] does, then runs the item's [command], as the workbench does once an
         * item is accepted: CodeLLDB's "CodeLLDB: Cargo ..." inserts nothing and writes its launch
         * configuration from the command. A backend handler, so the command starts here, after the
         * host has been sent the insertion it reads the document past.
         */
        fun commanding(
            insert: InsertHandler<LookupElement>?,
            command: CodeActions.Command,
            features: HostedLanguageFeatures
        ) =
            InsertHandler<LookupElement> { context, item ->
                insert?.handleInsert(context, item)
                context.project.service<HostedLanguageServers>().afterSync(context.document) {
                    features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            features.executeCommand(command)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            LOG.warn("Could not run the command of completion '${item.lookupString}'", failure)
                        }
                    }
                }
            }

        /**
         * Replaces the filter text, just inserted, with a live template of the snippet.
         *
         * The lookup has to insert something to have a start and end; the template then puts the
         * snippet's text there with its stops live, so Tab walks them as it would in VS Code and the
         * caret finishes on `$0` -- on every `$0`, if there are several.
         */
        fun template(expansion: Snippets.Expansion) = InsertHandler<LookupElement> { context, _ ->
            context.document.deleteString(context.startOffset, context.tailOffset)
            context.editor.caretModel.moveToOffset(context.startOffset)
            val manager = TemplateManager.getInstance(context.project)
            val template = manager.createTemplate("", "", expansion.template)
            // Inserted as the extension wrote it; the language's formatter was not asked.
            template.isToReformat = false
            template.setToIndent(false)
            for ((name, text, choices) in expansion.stops) {
                val value = ConstantNode(text).withLookupStrings(choices)
                template.addVariable(name, value, value, true)
            }
            CodeBridgeSnippetTemplates.addFinals(template, expansion.finals)
            manager.startTemplate(
                context.editor,
                template,
                CodeBridgeSnippetTemplates.caretsOnFinals(context.editor, expansion.finals)
            )
        }

        /**
         * Where the word ending at [offset] begins, by VS Code's default word definition: anything but
         * whitespace and the punctuation it lists.
         */
        fun wordStart(text: CharSequence, offset: Int): Int {
            var start = offset
            while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] !in SEPARATORS) start--
            return start
        }

        /** The punctuation in VS Code's `DEFAULT_WORD_REGEXP`, which no word contains. */
        private const val SEPARATORS = "`~!@#$%^&*()-=+[{]}\\|;:'\",.<>/?"

        /**
         * VS Code's `CompletionItemKind`, as the editor numbers it.
         *
         * The numbering is the workbench's own and does not match LSP's -- the host has already
         * converted it. Only the kinds with an obvious IntelliJ icon are mapped; the rest get none,
         * which reads better than a misleading one.
         */
        fun icon(kind: Int?): Icon? = when (kind) {
            0 -> AllIcons.Nodes.Method
            1 -> AllIcons.Nodes.Function
            2 -> AllIcons.Nodes.Method
            3 -> AllIcons.Nodes.Field
            4 -> AllIcons.Nodes.Variable
            5 -> AllIcons.Nodes.Class
            6 -> AllIcons.Nodes.Static
            7 -> AllIcons.Nodes.Interface
            8 -> AllIcons.Nodes.Module
            9 -> AllIcons.Nodes.Property
            14 -> AllIcons.Nodes.Enum
            15 -> AllIcons.Nodes.Enum
            18 -> AllIcons.Nodes.Tag
            27 -> AllIcons.Nodes.Folder
            else -> null
        }
    }
}
