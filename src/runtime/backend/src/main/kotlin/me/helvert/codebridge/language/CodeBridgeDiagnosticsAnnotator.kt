package me.helvert.codebridge.language

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.NamedColorUtil
import com.intellij.xml.util.XmlStringUtil
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.workbench.HostedContentProviders

/**
 * Draws the problems an extension pushed.
 *
 * An annotator rather than IntelliJ's LSP client: annotations are ordinary highlighting, computed
 * on the backend and forwarded to the frontend like any other, so they survive split mode. Why the
 * LSP client cannot: `docs/runtime-and-lsp.md`, "Why not IntelliJ's LSP client".
 *
 * Nothing is computed here. Diagnostics are pushed by the extension and already held by
 * [HostedLanguageFeatures]; this only places them, and that service restarts the daemon when a new
 * push arrives.
 *
 * A plain [Annotator], not an `ExternalAnnotator`, because there is nothing external to wait for.
 * The external pass is for slow tools: the platform runs it after every other pass has finished and
 * then only through a 300ms merging queue, so a problem that was already here waited the better part
 * of a second to be drawn -- after every keystroke, and after every quick fix. Here it is drawn with
 * the file's own highlighting, in the same pass.
 *
 * Asked once per file, on the file element: the problems are about the document, not about any one
 * PSI element, and a file whose PSI is a single plain-text leaf has no other element to hang them on.
 */
internal class CodeBridgeDiagnosticsAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        val virtualFile = file.virtualFile ?: return
        val features = HostedLanguageFeatures.getInstance(file.project)
        val problems = features.problemsFor(virtualFile)
        if (problems.isEmpty()) return features.drawn(virtualFile, HostedLanguageFeatures.Redraw.DIAGNOSTICS)
        // Debug, not info: this runs on every highlighting pass.
        LOG.debug("Annotating ${problems.size} problem(s) in ${virtualFile.name}")
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        val lines = DocumentLines(document.charsSequence)
        for (problem in problems) {
            val range = lines.rangeOf(problem) ?: continue
            val annotation = holder.newAnnotation(severity(problem.severity), message(problem))
                .range(range)
                .tooltip(tooltip(file.project, problem))
                .problemGroup(CodeBridgeProblemGroup)
            val unnecessary = Marker.UNNECESSARY in problem.tags
            // VS Code fades unnecessary code *and* keeps the severity's squiggle; only a hint is drawn
            // as the fade alone. One annotation cannot do both here -- the unused-symbol attributes
            // replace the squiggle -- so a warning or error gets the fade as a second, silent layer.
            if (unnecessary && problem.severity <= HINT) {
                annotation.highlightType(ProblemHighlightType.LIKE_UNUSED_SYMBOL)
                    .textAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)
            }
            annotation.create()
            if (unnecessary && problem.severity > HINT) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(range)
                    .textAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)
                    .create()
            }
        }
        features.drawn(virtualFile, HostedLanguageFeatures.Redraw.DIAGNOSTICS)
    }

    /**
     * Info and Hint are both weak warnings, as IntelliJ's own LSP client draws LSP's Information and
     * Hint (`LspDiagnosticsSupport.getHighlightSeverity`): `INFORMATION` would draw no underline at
     * all, and [CodeBridgeIdeProblems] sends a weak warning back as Info, so the two directions agree.
     */
    private fun severity(value: Int): HighlightSeverity = when (value) {
        8 -> HighlightSeverity.ERROR
        4 -> HighlightSeverity.WARNING
        else -> HighlightSeverity.WEAK_WARNING
    }

    /** Extensions often report a bare sentence; the source says which of them said it. */
    private fun message(problem: Marker): String =
        problem.source?.takeIf { it.isNotBlank() }?.let { "$it: ${problem.message}" } ?: problem.message

    /**
     * The hover, laid out as VS Code lays it out: the message with its own line breaks, then who
     * reported it and under what code, dimmed, as `source(code)`.
     *
     * The line breaks matter more than they look. rustc and friends put the headline on the first
     * line and the explanation on the next, and run together they read as one garbled sentence.
     */
    private fun tooltip(project: Project, problem: Marker): String {
        val body = XmlStringUtil.escapeString(problem.message).replace("\n", "<br>")
        val source = problem.source?.takeIf { it.isNotBlank() }?.let(XmlStringUtil::escapeString)
        val code = problem.code
        if (source == null && code == null) return XmlStringUtil.wrapInHtml(body)
        val color = ColorUtil.toHtmlColor(NamedColorUtil.getInactiveTextColor())
        fun dimmed(text: String) = "<span style=\"color:$color\">$text</span>"
        val suffix = StringBuilder(dimmed(source.orEmpty()))
        if (code != null) {
            val value = XmlStringUtil.escapeString(code.value)
            val href = code.target?.let { target ->
                when {
                    target.scheme == "http" || target.scheme == "https" -> target.uri
                    // A page the extension makes up itself, which VS Code opens by asking it; the
                    // frontend's link handler has the backend do the same.
                    HostedContentProviders.getInstance(project).provides(target.scheme) -> PROVIDED_LINK + target.uri
                    else -> null
                }
            }
            // The link outside the dimmed span: Swing lets the span's colour override the link's,
            // which left it looking like plain text.
            if (href == null) suffix.append(dimmed("($value)"))
            else suffix.append(dimmed("(")).append("<a href=\"${XmlStringUtil.escapeString(href)}\">$value</a>").append(dimmed(")"))
        }
        return XmlStringUtil.wrapInHtml("$body $suffix")
    }

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeDiagnosticsAnnotator::class.java)

        /** Handled by the frontend's `CodeBridgeProvidedDocumentLinks`, registered under this prefix. */
        private const val PROVIDED_LINK = "#codebridge-provided:"

        /** `MarkerSeverity.Hint`. */
        private const val HINT = 1
    }
}

/**
 * Marks the problems [CodeBridgeDiagnosticsAnnotator] drew, so [CodeBridgeIdeProblems] can tell them from the
 * IDE's own without the highlight's internal tool id. Nameless, so the platform files them under no
 * inspection and offers no options for them beyond what it would without a group.
 */
internal object CodeBridgeProblemGroup : ProblemGroup {
    override fun getProblemName(): String? = null
}

/**
 * Turns VS Code's 1-based line and column markers into IntelliJ offsets.
 *
 * Separate from the annotator and built on plain text, so the arithmetic can be tested without a
 * project or a live document. It is the last step between "a diagnostic arrived" and "the user sees
 * it", and the only step with any arithmetic in it.
 */
internal class DocumentLines(private val text: CharSequence) {

    /** Offset of the first character of each line. */
    private val starts: IntArray = buildList {
        add(0)
        for (index in text.indices) if (text[index] == NEWLINE) add(index + 1)
    }.toIntArray()

    /**
     * The range a problem covers, or null when it cannot be shown.
     *
     * Positions are clamped rather than trusted: diagnostics are pushed asynchronously, so one can
     * outlive the edit that invalidated it and name a line that is now shorter, or gone. An empty
     * range is widened onto a real character, because a zero-width annotation draws nothing.
     */
    fun rangeOf(problem: Marker): TextRange? {
        // A line the document no longer has. Every offset below is clamped, so without this a stale
        // diagnostic for line 90 of a file now ten lines long would collapse to the end of the text
        // and be drawn there -- attached to whatever the user has since typed.
        if (problem.range.startLineNumber > starts.size) return null
        val start = offsetOf(problem.range.startLineNumber, problem.range.startColumn)
        val end = offsetOf(problem.range.endLineNumber, problem.range.endColumn)
        if (start > end) return null
        if (start != end) return TextRange(start, end)
        // Zero width, which is ordinary rather than exceptional: "expected `>`" and every other
        // problem about something *missing* points at a position rather than spanning any text. A
        // zero-width highlight draws nothing, so it has to borrow a character -- and which one
        // matters. Forward is right mid-line; at the end of a line the only thing forward is the
        // newline, and underlining that puts a wedge in the margin instead of a squiggle under the
        // code the problem is about, which is the version of this that looked broken.
        if (end < text.length && text[end] != NEWLINE) return TextRange(start, end + 1)
        if (start > 0 && text[start - 1] != NEWLINE) return TextRange(start - 1, start)
        // An empty line: there is no character on it to mark, so the newline is all there is.
        return if (end < text.length) TextRange(start, end + 1) else null
    }

    fun offsetOf(line: Int, column: Int): Int {
        val index = (line - 1).coerceIn(0, starts.size - 1)
        val lineStart = starts[index]
        val lineEnd = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
        // Clamped before adding: a whole-line problem ends at column Int.MAX_VALUE, as VS Code's do.
        return lineStart + (column - 1).coerceIn(0, lineEnd - lineStart)
    }

    private companion object {
        private const val NEWLINE = '\n'
    }
}
