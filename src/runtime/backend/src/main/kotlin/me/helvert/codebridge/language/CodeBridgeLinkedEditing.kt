package me.helvert.codebridge.language

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import me.helvert.codebridge.exthost.protocol.payload.LinkedEditing
import me.helvert.codebridge.exthost.protocol.payload.Position
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import kotlin.time.Duration.Companion.milliseconds

/**
 * Linked editing: typing in one of the ranges an extension names -- an HTML element's opening tag
 * name -- types the same into the others, as VS Code does with `editor.linkedEditing` on.
 *
 * Asked for as the caret settles, as VS Code asks, so the ranges are known before the first
 * keystroke. They are held, and outlined, as highlighters that grow with typing at their edges. An edit inside one is
 * copied to the others before the command that made it finishes, so one Undo takes back both; an
 * edit anywhere else, or text the extension's word pattern does not match, ends the session until
 * the caret settles again.
 *
 * Everything here runs on the editor thread, except the request itself.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeLinkedEditing(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** The ranges being edited together in [document], and what text they may hold. */
    private class Session(val document: Document, val markers: List<RangeHighlighter>, val pattern: Regex?) :
        Disposable {
        override fun dispose() = markers.forEach(RangeHighlighter::dispose)
    }

    private var session: Session? = null

    /** The range an edit landed in, copied to the others when its command finishes. */
    private var edited: RangeHighlighter? = null

    /** Set while the copies are made, which are edits too. */
    private var copying = false

    private var asking: Job? = null

    init {
        project.messageBus.connect(this).subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun beforeCommandFinished(event: CommandEvent) = copy()
        })
    }

    /** Follows [editor]'s caret, for as long as the editor lives. */
    fun attach(editor: Editor) {
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = caretMoved(editor)
        })
    }

    private fun caretMoved(editor: Editor) {
        val current = session
        val offset = editor.caretModel.offset
        if (current != null && current.document == editor.document &&
            current.markers.any { it.isValid && offset in it.startOffset..it.endOffset }
        ) return
        end()
        ask(editor, DEBOUNCE_MILLIS)
    }

    /** Asks about the caret's place once it has been still for [quietMillis]. */
    private fun ask(editor: Editor, quietMillis: Long): Job? {
        asking?.cancel()
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.isMirrored(file) || !features.editsLinked(file)) return null
        val text = document.text
        val stamp = document.modificationStamp
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val position = Position(line + 1, offset - document.getLineStartOffset(line) + 1)
        return scope.launch {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            val ranges =
                withContext(Dispatchers.IO) { features.linkedEditingRanges(file, text, position) } ?: return@launch
            withContext(Dispatchers.EDT) {
                if (editor.isDisposed || document.modificationStamp != stamp || editor.caretModel.offset != offset) return@withContext
                start(document, text, ranges, offset)
            }
        }.also { asking = it }
    }

    private fun start(document: Document, text: String, answer: LinkedEditing.Ranges, caret: Int) {
        val ranges = answer.ranges.mapNotNull { CodeBridgeTextEdits.offsets(text, it) }
        if (ranges.size < 2 || ranges.none { caret in it.startOffset..it.endOffset }) return
        end()
        // Highlighters rather than bare markers, so the ranges are outlined as VS Code outlines them,
        // on a split-mode client too: a document's highlighters reach it.
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        val markers = ranges.map { range ->
            markup.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                HighlighterLayer.SELECTION - 1,
                LOOK,
                HighlighterTargetArea.EXACT_RANGE
            ).apply {
                isGreedyToLeft = true
                isGreedyToRight = true
            }
        }
        val next = Session(document, markers, answer.wordPattern?.let(::regex))
        Disposer.register(this, next)
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = changed(next, event)
        }, next)
        session = next
    }

    private fun changed(current: Session, event: DocumentEvent) {
        if (copying || session !== current) return
        val start = event.offset
        val end = event.offset + event.newLength
        val inside = current.markers.firstOrNull { it.isValid && start >= it.startOffset && end <= it.endOffset }
        if (inside == null) {
            end()
            return
        }
        edited = inside
        // Every editor edit is a command; anything else is copied once it is done.
        if (!CommandProcessor.getInstance().isCommandInProgress)
            scope.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
                WriteCommandAction.runWriteCommandAction(project) { copy() }
            }
    }

    /** Copies what the edited range now holds to the others, changing only what differs. */
    private fun copy() {
        val from = edited ?: return
        edited = null
        val current = session ?: return
        if (!from.isValid) return end()
        val document = current.document
        val text = document.getText(from.textRange)
        if (current.pattern != null && text.isNotEmpty() && !current.pattern.matches(text)) return end()
        copying = true
        try {
            WriteAction.run<RuntimeException> {
                for (marker in current.markers) {
                    if (marker === from || !marker.isValid) continue
                    val old = document.getText(marker.textRange)
                    if (old == text) continue
                    val prefix = old.commonPrefixWith(text).length
                    val suffix = old.substring(prefix).commonSuffixWith(text.substring(prefix)).length
                    document.replaceString(
                        marker.startOffset + prefix,
                        marker.endOffset - suffix,
                        text.substring(prefix, text.length - suffix)
                    )
                }
            }
        } finally {
            copying = false
        }
    }

    private fun end() {
        session?.let(Disposer::dispose)
        session = null
        edited = null
    }

    override fun dispose() = end()

    /** Asks about [editor]'s caret straight away, for a test to wait on. */
    @TestOnly
    fun askNow(editor: Editor): Job? = ask(editor, 0)

    /** The ranges being edited together, for a test. */
    @TestOnly
    fun rangesForTest() = session?.markers?.filter { it.isValid }?.map { it.textRange }.orEmpty()

    companion object {
        /** VS Code's `editor.linkedEditingBackground`, red at 30%, in either theme. */
        private val LOOK = TextAttributes().apply { backgroundColor = Color(255, 0, 0, 77) }

        /** About what VS Code waits after the caret moves. */
        private const val DEBOUNCE_MILLIS = 100L

        private val LOG = Logger.getInstance(CodeBridgeLinkedEditing::class.java)

        fun getInstance(project: Project): CodeBridgeLinkedEditing = project.service()

        /** A JavaScript regular expression as Java reads one; null for one it cannot, which then lets anything through. */
        private fun regex(pattern: LinkedEditing.Pattern): Regex? = runCatching {
            val options = buildSet {
                if ('i' in pattern.flags) add(RegexOption.IGNORE_CASE)
                if ('m' in pattern.flags) add(RegexOption.MULTILINE)
                if ('s' in pattern.flags) add(RegexOption.DOT_MATCHES_ALL)
            }
            Regex(pattern.source, options)
        }.onFailure { LOG.debug("Cannot read the word pattern /${pattern.source}/${pattern.flags}", it) }.getOrNull()
    }
}
