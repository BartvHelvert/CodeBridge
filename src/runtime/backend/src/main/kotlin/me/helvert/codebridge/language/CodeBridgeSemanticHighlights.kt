package me.helvert.codebridge.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.SemanticTokens

/**
 * Draws a file's semantic tokens straight onto its document, and takes them off again.
 *
 * Not an annotator, which is how this was first done, because of what an annotator costs to bring
 * an answer in. Annotations are only produced by a highlighting pass, so a new answer meant
 * `DaemonCodeAnalyzer.restart` -- and a restart does not run a pass, it schedules one after the
 * IDE's auto-reparse delay, 300ms by default, and then runs every pass over the file rather than
 * this one. Measured against rust-analyzer, which answers in about 10ms, colour landed ~860ms after
 * the edit: the diagnostics that arrive half a second after every edit restarted the daemon again,
 * cancelling the pass the colours were waiting for and scheduling another 300ms out.
 *
 * Highlighters on the document's markup model need none of that. They are shown as soon as they are
 * added, they move with the text as it is edited, and replacing them disturbs no highlighting pass,
 * so colouring and diagnostics stop costing each other a restart. They are the same kind of thing
 * the daemon's own highlights are -- range highlighters on the document -- which is what carries
 * them to a split-mode client; `SplitModeDrawingTest` is what holds that.
 *
 * Every method here may be called from any thread; the markup model is only touched on the EDT.
 */
internal class CodeBridgeSemanticHighlights(private val project: Project, private val scope: CoroutineScope) {

    /** What this class has put on each document, so the next answer can take exactly that off. EDT only. */
    private val drawn = HashMap<VirtualFile, List<RangeHighlighter>>()

    /**
     * Replaces [file]'s colouring with [tokens], worked out against [text] -- or against the
     * document as it is, when [text] is null.
     *
     * The document can have moved on by the time this runs on the EDT: a keystroke queued ahead of
     * it is applied first. The tokens are then moved onto the text as it is, as the service moves
     * the ones it holds, rather than drawn on the words they used to be over.
     */
    fun draw(file: VirtualFile, tokens: List<SemanticTokens.Token>, text: CharSequence?, onDrawn: (Int) -> Unit = {}) {
        onEdt {
            if (!file.isValid) return@onEdt
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@onEdt
            val current = document.immutableCharSequence
            val placed = if (text == null || current.contentEquals(text)) tokens else SemanticTokens.rebase(
                tokens,
                text,
                current
            )
            val markup = DocumentMarkupModel.forDocument(document, project, true)
            remove(file)
            val added = ArrayList<RangeHighlighter>(placed.size)
            for (token in placed) {
                val range = rangeOf(document, token) ?: continue
                CodeBridgeSemanticTokenColors.keyFor(token)?.let { key ->
                    added += markup.addRangeHighlighter(
                        key,
                        range.startOffset,
                        range.endOffset,
                        LAYER,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                }
                // A second highlighter rather than a combined key: strikethrough has to reach
                // whatever colour the token already has, including one this class did not choose.
                if (DEPRECATED in token.modifiers) {
                    added += markup.addRangeHighlighter(
                        CodeBridgeSemanticTokenColors.DEPRECATED,
                        range.startOffset,
                        range.endOffset,
                        LAYER,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                }
            }
            drawn[file] = added
            onDrawn(added.size)
        }
    }

    /** Takes [file]'s colouring off, for a document closed or a provider gone. */
    fun forget(file: VirtualFile) = onEdt { remove(file) }

    /** Takes every file's colouring off, for a host that stopped. */
    fun forgetAll() = onEdt { drawn.keys.toList().forEach(::remove) }

    /** The highlighters on [file] now, for tests. EDT only. */
    fun drawnForTest(file: VirtualFile): List<RangeHighlighter> = drawn[file].orEmpty().filter { it.isValid }

    private fun remove(file: VirtualFile) {
        drawn.remove(file)?.forEach { it.dispose() }
    }

    /**
     * The offsets a token covers, or null when it covers nothing.
     *
     * LSP's 0-based line and character meet the document here. Clamped to the line it names, so a
     * token left over from before an edit is clipped rather than spilling onto the next line.
     */
    private fun rangeOf(document: Document, token: SemanticTokens.Token): TextRange? {
        if (token.line < 0 || token.line >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(token.line)
        val lineEnd = document.getLineEndOffset(token.line)
        val start = (lineStart + token.character).coerceAtMost(lineEnd)
        val end = (start + token.length).coerceAtMost(lineEnd)
        return if (end > start) TextRange(start, end) else null
    }

    /**
     * Any modality, so a dialog left open does not hold colour back; adding a highlighter needs no
     * write action. Skipped once the project is gone.
     */
    private fun onEdt(action: () -> Unit) {
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) { action() }
    }

    private companion object {
        /**
         * Above the grammar's colours -- the lexer's, and in split mode the highlighters they are
         * forwarded as -- and below errors, search results and the selection. The layer the daemon
         * gave the annotations this replaces.
         */
        const val LAYER = HighlighterLayer.ADDITIONAL_SYNTAX

        const val DEPRECATED = "deprecated"
    }
}
