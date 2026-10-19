package me.helvert.codebridge.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Attachment
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Color
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Style
import me.helvert.codebridge.rpc.DecorationsDto
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * The colours an extension finds in a file, each marked with a swatch before it, as VS Code's colour
 * decorator marks them.
 *
 * Drawn as a decoration of CodeBridge's own type, through [CodeBridgeEditorDecorations], since VS Code's
 * swatch is itself one -- a bordered box with the colour as its background -- and decorations are
 * already painted in their own colours on a split-mode client, which an inlay hint cannot be (see
 * docs/platform-limitations.md). Asked for when a file opens, after an edit, and when a provider
 * appears or goes; drawn only if the text is still what was asked about, since an edit meanwhile has
 * asked again. Clicking a swatch opens a colour picker on the frontend, which hands what is chosen
 * to [pick].
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeDocumentColors(private val project: Project, private val scope: CoroutineScope) {

    /** The debounce in front of each file's request; a later edit replaces it. */
    private val jobs = ConcurrentHashMap<VirtualFile, Job>()

    /** Asks for [file]'s colours once it has been quiet for [quietMillis]. */
    fun request(file: VirtualFile, quietMillis: Long): Job {
        val job = scope.launch {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            refresh(file)
        }
        job.invokeOnCompletion { jobs.remove(file, job) }
        jobs.put(file, job)?.cancel()
        return job
    }

    /** Every file shown in an editor, for a provider that has appeared or gone. */
    fun requestEverywhere() {
        HostedLanguageFeatures.getInstance(project).shownFiles().forEach { request(it, REGISTRATION_QUIET_MILLIS) }
    }

    fun forget(file: VirtualFile) {
        jobs.remove(file)?.cancel()
    }

    /** Drops what is waiting; what was drawn goes with the host's decorations. */
    fun hostStopped() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private suspend fun refresh(file: VirtualFile) {
        val features = HostedLanguageFeatures.getInstance(project)
        val text = features.hostTextOf(file) ?: return
        val found = if (!features.hasDocumentColorProviders(file)) emptyList()
        else withContext(Dispatchers.IO) { features.documentColors(file, text) } ?: return
        if (features.hostTextOf(file) != text) return
        CodeBridgeEditorDecorations.getInstance(project).setOwn(file, KEY, TYPE, found.map { color ->
            TextEditorDecorations.Decoration(
                color.range,
                renderOptions = TextEditorDecorations.RenderOptions(
                    Style(
                        before = Attachment(
                            backgroundColor = Color.Css(
                                color.css
                            )
                        )
                    ), null, null
                ),
            )
        })
    }

    /**
     * Writes the colour whose swatch is at [offset] in [file] as [color], as the extension that found
     * it writes colours, VS Code's picker taking the first way it offers. One undo step for every
     * colour chosen in a row in the same file, as dragging through the picker chooses many. Answers
     * whether it did.
     */
    suspend fun pick(file: VirtualFile, offset: Int, color: java.awt.Color): Boolean {
        val features = HostedLanguageFeatures.getInstance(project)
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return false
        val (text, stamp) = readAction { document.text to document.modificationStamp }
        // Asked afresh rather than read off the swatch: a colour just written replaced the text the
        // swatch was on, and while the picker is dragged a new one is chosen before it is redrawn.
        val edits = withContext(Dispatchers.IO) {
            if (!features.awaitMirrored(file, text)) return@withContext null
            val range = features.documentColors(file, text).orEmpty()
                .mapNotNull { CodeBridgeTextEdits.offsets(text, it.range) }
                .firstOrNull { offset == it.startOffset || it.containsOffset(offset) } ?: return@withContext null
            features.colorPresentation(file, text, CodeBridgeTextEdits.rangeOf(text, range), color)
        } ?: return false
        val located = CodeBridgeTextEdits.locate(text, edits) ?: return false
        return withContext(Dispatchers.EDT) {
            if (document.modificationStamp != stamp) return@withContext false
            WriteCommandAction.writeCommandAction(project).withName(PICK_COMMAND)
                .withGroupId("$PICK_COMMAND ${file.path}")
                .run<RuntimeException> {
                    for ((replaced, replacement) in located) document.replaceString(
                        replaced.startOffset,
                        replaced.endOffset,
                        replacement
                    )
                }
            true
        }
    }

    companion object {
        fun getInstance(project: Project): CodeBridgeDocumentColors = project.service()

        private const val KEY = DecorationsDto.DOCUMENT_COLORS

        /**
         * VS Code's swatch: a box a little smaller than a letter, bordered in the editor's foreground,
         * just before the colour's text. It does not grow when typing at either edge.
         */
        private val TYPE = TextEditorDecorations.Type(
            isWholeLine = false,
            rangeBehavior = 1,
            base = Style(
                before = Attachment(
                    contentText = " ",
                    width = "0.8em",
                    margin = "0.1em 0.2em 0 0.2em",
                    border = "0.1em solid"
                )
            ),
            light = Style(before = Attachment(borderColor = Color.Css("#000"))),
            dark = Style(before = Attachment(borderColor = Color.Css("#eee"))),
        )

        private const val REGISTRATION_QUIET_MILLIS = 150L

        private const val PICK_COMMAND = "Pick Colour"
    }
}
