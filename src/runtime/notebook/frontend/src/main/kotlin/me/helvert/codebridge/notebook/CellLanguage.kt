package me.helvert.codebridge.notebook

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.lookup.LookupArranger
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.completion.VsCodeCompletions
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.text.View
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A code cell's language features, as VS Code has them in a notebook: what the extensions that
 * claim the cell's text document -- by its language and its notebook's type -- answer about it.
 * Problems are drawn under the text they are about; a hover shows them and what the extension says
 * of the symbol; Go to Declaration -- its keys, or a click with them -- follows a definition, into
 * a file or another cell; completions open as a word is typed, or with completion's keys.
 *
 * The cell's editor and document are the frontend's own, which no IDE feature reaches in a split-mode
 * client -- completion included, which the client hands to the backend -- so each is drawn here, from
 * what the backend asks the host once the edits made before the question have reached it. Used on
 * the EDT, apart from what is asked.
 */
internal class CellLanguage(private val project: Project, private val cell: CellView, private val editor: EditorEx) : Disposable {
    private val scope = CodeBridgeNotebooks.getInstance(project).scope
    private var problems: List<CellProblemDto> = emptyList()
    private val highlighters = mutableListOf<RangeHighlighter>()
    private var hoverJob: Job? = null
    /** The text the mouse rests on, which a hover is shown or on its way for. */
    private var hovered: TextRange? = null
    private var popup: JBPopup? = null

    init {
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) = moved(event)
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(event: EditorMouseEvent) = leave(keepPopupUnderMouse = true)
        }, this)
        goToDefinitionKeys()
        hoverKeys()
        completionKeys()
        // As VS Code's quick suggestions open: on a word typed, or a `.`, unless a lookup is open to filter by it.
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!editor.contentComponent.isFocusOwner || event.oldLength != 0 || event.newLength != 1) return
                val typed = event.newFragment[0]
                if (!Character.isJavaIdentifierPart(typed) && typed != '.') return
                // Once the caret has moved past what was typed.
                SwingUtilities.invokeLater { if (!editor.isDisposed && LookupManager.getActiveLookup(editor) == null) complete() }
            }
        }, this)
    }

    // ------------------------------------------------------------------------------------- completion

    private var completionJob: Job? = null

    /** Completion's keys ask for completions here, whatever was typed. */
    private fun completionKeys() {
        val keys = ActionManager.getInstance().getAction(IdeActions.ACTION_CODE_COMPLETION)?.shortcutSet ?: return
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) = complete()
        }.registerCustomShortcutSet(keys, editor.contentComponent, this)
    }

    /** Asks for completions at the caret, then shows them; a newer question withdraws this one. */
    private fun complete() {
        completionJob?.cancel()
        val (line, column) = positionOf(editor.caretModel.offset)
        completionJob = scope.launch {
            val items = try {
                cell.ask { projectId, editorId -> completions(projectId, editorId, cell.handle, line, column) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (items.isNotEmpty()) withContext(Dispatchers.EDT) { showCompletions(items) }
        }
    }

    /**
     * [items] in the IDE's lookup, in the order VS Code ranks them, filtered by what has been typed of
     * the text they replace -- by then, which may be more than when they were asked for.
     */
    private fun showCompletions(items: List<CellCompletionDto>) {
        if (editor.isDisposed || !editor.contentComponent.isFocusOwner || LookupManager.getActiveLookup(editor) != null) return
        val document = editor.document
        val caret = editor.caretModel.offset
        val text = document.charsSequence
        val start = items.firstNotNullOfOrNull { item -> item.replaceLine?.let { offsetOf(document, it, item.replaceColumn ?: 1) } }
            ?.takeIf { it <= caret } ?: VsCodeCompletions.wordStart(text, caret)
        val elements = items.map { item ->
            LookupElementBuilder.create(item, item.filter)
                .withPresentableText(item.label)
                .withTypeText(item.detail, true)
                .withIcon(VsCodeCompletions.icon(item.kind))
        }
        val lookup = LookupManager.getInstance(project)
            .showLookup(editor, elements.toTypedArray(), text.subSequence(start, caret).toString(), LookupArranger.DefaultArranger())
            ?: return
        lookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                (event.item?.`object` as? CellCompletionDto)?.let(::inserted)
            }
        })
    }

    /**
     * The lookup has put [item]'s filter text before the caret, which is all a lookup inserts; the
     * item's own text goes there in its place -- a snippet as it expands -- with the caret where it says.
     */
    private fun inserted(item: CellCompletionDto) {
        if (item.text == item.filter && item.caret == item.text.length && item.carets.isEmpty()) return
        val end = editor.caretModel.offset
        val start = end - item.filter.length
        if (start < 0 || editor.document.charsSequence.subSequence(start, end).toString() != item.filter) return
        WriteCommandAction.runWriteCommandAction(project) { editor.document.replaceString(start, end, item.text) }
        editor.caretModel.moveToOffset(start + item.caret.coerceIn(0, item.text.length))
        for (at in item.carets) editor.caretModel.addCaret(editor.offsetToVisualPosition(start + at.coerceIn(0, item.text.length)), false)
    }

    // --------------------------------------------------------------------------------------- problems

    /** Draws [list], replacing what was drawn: under each problem's text, coloured by its severity. */
    fun setProblems(list: List<CellProblemDto>) {
        problems = list
        highlighters.forEach { it.dispose() }
        highlighters.clear()
        val document = editor.document
        for (problem in list) {
            val key = attributes(problem.severity) ?: continue
            val range = rangeOf(document, problem) ?: continue
            // The scheme's attributes themselves rather than their key, as the highlighter's own.
            val attributes = editor.colorsScheme.getAttributes(key)
            highlighters += editor.markupModel.addRangeHighlighter(range.startOffset, range.endOffset, HighlighterLayer.ERROR, attributes, HighlighterTargetArea.EXACT_RANGE)
                .apply { errorStripeTooltip = problem.message }
        }
    }

    /** What a problem of [severity] looks like in the IDE: VS Code's hints, which it draws as three dots, are not drawn. */
    private fun attributes(severity: Int): TextAttributesKey? = when (severity) {
        CellProblemDto.ERROR -> CodeInsightColors.ERRORS_ATTRIBUTES
        CellProblemDto.WARNING -> CodeInsightColors.WARNINGS_ATTRIBUTES
        CellProblemDto.INFO -> CodeInsightColors.WEAK_WARNING_ATTRIBUTES
        CellProblemDto.HINT -> null
        else -> null
    }

    /** The problems drawn at [offset]: what a hover there says first, as VS Code's does. */
    private fun problemsAt(offset: Int): List<CellProblemDto> = problems.filter { problem ->
        attributes(problem.severity) != null && rangeOf(editor.document, problem)?.let { offset in it.startOffset..it.endOffset } == true
    }

    // ------------------------------------------------------------------------------------------ hover

    private fun moved(event: EditorMouseEvent) {
        if (event.area != EditorMouseEventArea.EDITING_AREA || !event.isOverText) return leave(keepPopupUnderMouse = false)
        val offset = event.offset
        val text = editor.document.charsSequence
        val start = VsCodeCompletions.wordStart(text, offset.coerceAtMost(text.length))
        val range = TextRange(start, wordEnd(text, offset).coerceAtLeast(offset + 1).coerceAtMost(text.length))
        if (range == hovered) return
        hover(offset, range, event.mouseEvent.point, HOVER_DELAY)
    }

    /** Shows the hover for [offset] at [point] after [wait], unless the mouse has moved off [range] by then. */
    private fun hover(offset: Int, range: TextRange, point: Point, wait: Duration) {
        leave(keepPopupUnderMouse = false)
        hovered = range
        val (line, column) = positionOf(offset)
        hoverJob = scope.launch {
            delay(wait)
            val html = try {
                cell.ask { projectId, editorId -> hover(projectId, editorId, cell.handle, line, column) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.EDT) {
                if (hovered == range && !editor.isDisposed) show(offset, html, point)
            }
        }
    }

    private fun show(offset: Int, hover: String?, point: Point) {
        val parts = problemsAt(offset).map { problem ->
            val source = problem.source?.let { " <span style='color:gray'>${StringUtil.escapeXmlEntities(it)}</span>" }.orEmpty()
            "<p>${StringUtil.escapeXmlEntities(problem.message).replace("\n", "<br/>")}$source</p>"
        } + listOfNotNull(hover)
        if (parts.isEmpty()) return
        val pane = JBHtmlPane(JBHtmlPaneStyleConfiguration(), JBHtmlPaneConfiguration.builder().build())
        pane.text = parts.joinToString("<hr/>")
        pane.border = JBUI.Borders.empty(6, 8)
        val scroll = JBScrollPane(pane).apply { border = JBUI.Borders.empty() }
        scroll.preferredSize = fitted(pane)
        val shown = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setShowBorder(true)
            .createPopup()
        popup = shown
        shown.show(RelativePoint(editor.contentComponent, Point(point.x, point.y + editor.lineHeight)))
    }

    /** Quick Documentation's keys show the hover at the caret, as VS Code's Show Hover does. */
    private fun hoverKeys() {
        val keys = ActionManager.getInstance().getAction(IdeActions.ACTION_QUICK_JAVADOC)?.shortcutSet ?: return
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) {
                val offset = editor.caretModel.offset
                hover(offset, TextRange(offset, offset), editor.visualPositionToXY(editor.caretModel.visualPosition), Duration.ZERO)
            }
        }.registerCustomShortcutSet(keys, editor.contentComponent, this)
    }

    /** As wide as the text wants, up to a limit, and as tall as it then is, up to another. */
    private fun fitted(pane: JBHtmlPane): Dimension {
        val insets = pane.insets
        val width = min(pane.preferredSize.width, JBUI.scale(HOVER_WIDTH))
        val view = pane.getUI().getRootView(pane)
        view.setSize((width - insets.left - insets.right).toFloat(), 0f)
        val height = view.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        return Dimension(width + JBUI.scale(2), min(height, JBUI.scale(HOVER_HEIGHT)) + JBUI.scale(2))
    }

    /** Forgets the text hovered; its hover goes too, unless [keepPopupUnderMouse] and the mouse moved onto it. */
    private fun leave(keepPopupUnderMouse: Boolean) {
        hoverJob?.cancel()
        hoverJob = null
        hovered = null
        val shown = popup ?: return
        if (keepPopupUnderMouse && shown.content.mousePosition != null) return
        shown.cancel()
        popup = null
    }

    // ----------------------------------------------------------------------------- go to definition

    /**
     * Go to Declaration's keys and its click, in the cell: ahead of the IDE's own action, which has no
     * declaration to go to in a cell, as the platform asks a component's own actions first.
     */
    private fun goToDefinitionKeys() {
        val keys = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION)?.shortcutSet ?: return
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) {
                val mouse = event.inputEvent as? MouseEvent
                val offset = if (mouse != null) editor.logicalPositionToOffset(editor.xyToLogicalPosition(mouse.point)) else editor.caretModel.offset
                if (mouse != null) editor.caretModel.moveToOffset(offset)
                goToDefinition(offset)
            }
        }.registerCustomShortcutSet(keys, editor.contentComponent, this)
    }

    private fun goToDefinition(offset: Int) {
        leave(keepPopupUnderMouse = false)
        val (line, column) = positionOf(offset)
        scope.launch {
            val found = try {
                cell.ask { projectId, editorId -> goToDefinition(projectId, editorId, cell.handle, line, column) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!found) withContext(Dispatchers.EDT) {
                if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, "Cannot find declaration to go to")
            }
        }
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** [offset] as VS Code's 1-based line and column in the cell. */
    private fun positionOf(offset: Int): Pair<Int, Int> = positionOf(editor.document, offset)

    override fun dispose() {
        completionJob?.cancel()
        leave(keepPopupUnderMouse = false)
        highlighters.forEach { it.dispose() }
    }

    companion object {
        private val HOVER_DELAY = 500.milliseconds
        private const val HOVER_WIDTH = 560
        private const val HOVER_HEIGHT = 360

        fun positionOf(document: Document, offset: Int): Pair<Int, Int> {
            val at = offset.coerceIn(0, document.textLength)
            val line = document.getLineNumber(at)
            return line + 1 to at - document.getLineStartOffset(line) + 1
        }

        /** VS Code's 1-based [line] and [column] in [document], clamped to its text. */
        fun offsetOf(document: Document, line: Int, column: Int): Int {
            if (document.lineCount == 0) return 0
            val index = (line - 1).coerceIn(0, document.lineCount - 1)
            val start = document.getLineStartOffset(index)
            return (start + (column - 1).coerceAtLeast(0)).coerceAtMost(document.getLineEndOffset(index))
        }

        /** [problem]'s text, at least one character of it, so that a problem at a line's end shows. */
        fun rangeOf(document: Document, problem: CellProblemDto): TextRange? {
            if (document.textLength == 0) return null
            val start = offsetOf(document, problem.startLine, problem.startColumn)
            val end = offsetOf(document, problem.endLine, problem.endColumn)
            return when {
                end > start -> TextRange(start, end)
                start < document.textLength -> TextRange(start, start + 1)
                else -> TextRange(start - 1, start)
            }
        }

        private fun wordEnd(text: CharSequence, offset: Int): Int {
            var end = offset
            while (end < text.length && (Character.isJavaIdentifierPart(text[end]))) end++
            return end
        }
    }
}
