package me.helvert.codebridge.language

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.exthost.protocol.payload.Range
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives extensions the problems IntelliJ itself finds, as VS Code gives them the markers no
 * extension owns: through `ExtHostDiagnostics.$acceptMarkersChange`, into the mirror collection that
 * `languages.getDiagnostics` and `onDidChangeDiagnostics` read alongside every extension's own.
 *
 * Without this an extension that presents diagnostics rather than producing them -- Error Lens, which
 * prints each one at the end of its line -- finds nothing in a file IntelliJ's own language support
 * analyses, such as Java, since no extension there reports anything.
 *
 * Read from the daemon's results once a pass finishes, for files the host has in an editor: what is
 * in the markup model then is what the user sees. Problems an extension pushed and
 * [CodeBridgeDiagnosticsAnnotator] drew are left out -- the extension has them already, and the host would
 * list them twice. Below a weak warning is colouring, not a problem, and is left out too.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeIdeProblems(private val project: Project, private val scope: CoroutineScope) {

    /** What each file's problems were last sent as, so a pass that changed nothing sends nothing. */
    private val sent = ConcurrentHashMap<VirtualFile, List<Marker>>()

    /** The read under way for each file; a newer pass replaces it. */
    private val reads = ConcurrentHashMap<VirtualFile, Job>()

    private val source by lazy { ApplicationNamesInfo.getInstance().fullProductName }

    fun daemonFinished(files: Collection<VirtualFile>) {
        for (file in files) refresh(file)
    }

    /** Reads [file]'s problems as they stand and sends them, if the host shows it and they changed. */
    fun refresh(file: VirtualFile) {
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.hostRunning || !features.isShown(file)) return
        val job = scope.launch {
            val markers = readAction { markersOf(file) } ?: return@launch
            // A file the host was never told about has no problems there either.
            if ((sent[file]
                    ?: emptyList()) == markers
            ) return@launch LOG.debug("IntelliJ's problems in ${file.name} unchanged; nothing sent")
            if (features.ideProblemsChanged(file, markers)) sent[file] = markers
            LOG.debug { "Sent ${markers.size} IntelliJ problem(s) in ${file.name}, on lines ${markers.map { it.range.startLineNumber }}" }
        }
        job.invokeOnCompletion { reads.remove(file, job) }
        reads.put(file, job)?.cancel()
    }

    /** Takes back what was said about [file], whose editor has closed. */
    fun withdraw(file: VirtualFile) {
        reads.remove(file)?.cancel()
        if (sent.remove(file).isNullOrEmpty()) return
        HostedLanguageFeatures.getInstance(project).ideProblemsChanged(file, emptyList())
    }

    /** Forgets what was sent; the host that held it has gone. */
    fun hostStopped() {
        reads.values.forEach(Job::cancel)
        reads.clear()
        sent.clear()
    }

    private fun markersOf(file: VirtualFile): List<Marker>? {
        if (project.isDisposed || !file.isValid) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val markers = mutableListOf<Marker>()
        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            HighlightSeverity.WEAK_WARNING,
            0,
            document.textLength
        ) { info ->
            markerOf(document, info)?.let(markers::add)
            true
        }
        // The markup model's order is its tree's, not the document's.
        return markers.sortedWith(compareBy({ it.range.startLineNumber }, { it.range.startColumn }))
    }

    private fun markerOf(document: Document, info: HighlightInfo): Marker? {
        val message = info.description?.takeIf { it.isNotBlank() } ?: return null
        if (info.problemGroup === CodeBridgeProblemGroup) return null
        val start = info.startOffset.coerceIn(0, document.textLength)
        val end = info.endOffset.coerceIn(start, document.textLength)
        return Marker(
            range = Range(
                lineOf(document, start),
                columnOf(document, start),
                lineOf(document, end),
                columnOf(document, end)
            ),
            severity = severityOf(info.severity),
            message = message,
            source = source,
            tags = tagsOf(info),
            code = info.inspectionToolId?.let { Marker.Code(it) },
        )
    }

    private fun lineOf(document: Document, offset: Int) = document.getLineNumber(offset) + 1

    private fun columnOf(document: Document, offset: Int) =
        offset - document.getLineStartOffset(document.getLineNumber(offset)) + 1

    /** A weak warning is VS Code's information: present, but not something to stop for. */
    private fun severityOf(severity: HighlightSeverity): Int = when {
        severity >= HighlightSeverity.ERROR -> Marker.ERROR
        severity >= HighlightSeverity.WARNING -> Marker.WARNING
        else -> Marker.INFO
    }

    /** Unused and deprecated code, by the attributes IntelliJ draws them with. */
    private fun tagsOf(info: HighlightInfo): List<Int> =
        when (info.forcedTextAttributesKey ?: info.type.attributesKey) {
            CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES -> listOf(Marker.UNNECESSARY)
            CodeInsightColors.DEPRECATED_ATTRIBUTES, CodeInsightColors.MARKED_FOR_REMOVAL_ATTRIBUTES -> listOf(Marker.DEPRECATED)
            else -> emptyList()
        }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeIdeProblems::class.java)

        fun getInstance(project: Project): CodeBridgeIdeProblems = project.service()
    }
}

/** Reports each finished daemon pass to [CodeBridgeIdeProblems]. */
internal class CodeBridgeIdeProblemsListener(private val project: Project) : DaemonCodeAnalyzer.DaemonListener {
    override fun daemonFinished(fileEditors: Collection<FileEditor>) {
        if (project.serviceIfCreated<HostedLanguageFeatures>()?.hostRunning != true) return
        CodeBridgeIdeProblems.getInstance(project).daemonFinished(fileEditors.mapNotNull { it.file }.distinct())
    }
}
