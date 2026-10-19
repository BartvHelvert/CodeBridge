package me.helvert.codebridge.language

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.openapi.vfs.encoding.EncodingProjectManager
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.Documents
import me.helvert.codebridge.exthost.protocol.payload.Encodings
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

/**
 * `MainThreadDocuments`: documents an extension opens for itself, with `workspace.openTextDocument`.
 *
 * The host answers `openTextDocument` from its own copies when it can, and asks here only for a file
 * no editor has open. It then looks the document up by the resource it is answered with, so the
 * document has to be in the host *before* the answer: an answer alone fails the extension's call.
 * `window.showTextDocument(uri)` goes through the same request before it shows anything.
 *
 * A file opened this way is held without an editor or a tab, as VS Code holds it, and released once
 * the extension has not asked for it for [HOLD_MINUTES] -- VS Code's own bound on the models it keeps
 * for extensions. Released, it is withdrawn from the host unless an editor has it open by then.
 * An untitled buffer, which has no file behind it, is stood in for by an in-memory one, and kept
 * until the editor showing it is closed.
 *
 * Runs on the worker that opens and syncs editor documents, so an edit is never sent for a document
 * the host has not been given yet.
 */
internal class HostedDocuments(
    private val project: Project,
    /** Where holds are timed; ends with the host's project. */
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    /** The language an enabled extension contributes for a file name, as opening it in an editor uses. */
    private val languageOf: (VirtualFile) -> String?,
    /** Activates the extensions a document of this language wakes, as opening it in an editor does. */
    private val activate: (String) -> Unit,
    /** A file extension, dot included, that an enabled extension contributes for a language id. */
    private val extensionOf: (String) -> String? = { null },
    /**
     * Where the user wants an untitled buffer saved, or null if they cancelled. Asked on the editor
     * thread; a parameter so the fixture can answer without a dialog.
     */
    private val chooseSaveTarget: (VirtualFile) -> Path? = { buffer ->
        val descriptor = FileSaverDescriptor("Save As", "Choose where to save ${buffer.name}, which an extension created")
        FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(project.guessProjectDir(), buffer.name)?.file?.toPath()
    },
) {

    private val features get() = HostedLanguageFeatures.getInstance(project)

    /**
     * Files held for extensions, least recently requested first.
     *
     * The IntelliJ document is kept as well: the platform only reloads a document from disk while it
     * is cached, and only a reloaded document raises the edit that keeps the host's copy current.
     */
    private val held = LinkedHashMap<VirtualFile, Hold>()

    private class Hold(@Suppress("unused") val document: Document, val release: Job)

    suspend fun handle(call: MainThreadCall): MainThreadResult = when (call.method) {
        Documents.TRY_OPEN -> withContext(worker) { open(Documents.open(call.argsJson)) }
        Documents.TRY_CREATE -> withContext(worker) { create(Documents.untitled(call.argsJson)) }
        Documents.TRY_SAVE -> save(Args.of(call.method, call.argsJson).uri(0))
        else -> throw MainThreadException("Unavailable", "Unsupported document method ${call.method}")
    }

    /** Forgets every hold; the host they were for has gone. */
    @Synchronized fun hostStopped() {
        held.values.forEach { it.release.cancel() }
        held.clear()
        untitled.clear()
    }

    /**
     * Untitled buffers, with their documents.
     *
     * Held strongly: nothing else refers to an in-memory file, and in split mode the platform keeps
     * the ones it has sent to a client only softly.
     */
    private val untitled = HashMap<VirtualFile, Document>()

    /** Numbers untitled buffers, which only need to be unique while the project is open. */
    private val untitledCount = AtomicLong()

    private fun open(request: Documents.Open): MainThreadResult {
        val resource = request.resource
        if (resource.scheme == UriComponents.UNTITLED) {
            // Asked for when the host lost track of a buffer it was given, or never had it -- or for
            // another encoding, which means nothing to text that was never bytes, so it is not changed.
            if (features.fileOf(resource.uri) != null) return answer(resource.uri)
            throw cannotOpen(resource, "no such untitled buffer")
        }
        if (resource.scheme != UriComponents.FILE) throw cannotOpen(resource, "no provider for the '${resource.scheme}' scheme")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resource.toPath())
            ?: throw cannotOpen(resource, "the file does not exist")
        if (file.isDirectory) throw cannotOpen(resource, "it is a directory")
        val document = ReadAction.computeBlocking<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file) }
            // Null for content IntelliJ will not load as text, which is what VS Code refuses too.
            ?: throw cannotOpen(resource, "the file seems to be binary")
        request.encoding?.let { reopenIn(file, document, resource, it) }
        val snapshot = snapshot(document)
        // What VS Code calls a file no extension claims, and with no extension waiting for it.
        val language = features.languageOf(file) ?: languageOf(file) ?: PLAIN_TEXT
        val uri = features.documentHeld(file, language, snapshot)
        hold(file, document)
        LOG.info("Opened ${file.name} in the extension host for an extension, as '$language'")
        if (language != PLAIN_TEXT) activate(language)
        return answer(uri)
    }

    /**
     * Decodes [file] in the encoding an extension asked for, as `openTextDocument(uri, { encoding })`
     * does in VS Code -- which asks for that even of a document it already has, when its encoding
     * differs.
     *
     * Recorded as the file's encoding, as choosing one in the status bar records it, so IntelliJ
     * remembers the choice for the file. Refused for a document with unsaved changes, as VS Code
     * refuses it: reading the file again would throw them away.
     */
    private fun reopenIn(file: VirtualFile, document: Document, resource: UriComponents, encoding: String) {
        val charset = Encodings.charsetOf(encoding) ?: throw cannotOpen(resource, "unknown encoding '$encoding'")
        if (charset == file.charset) return
        val (unsaved, fixedBy) = ReadAction.computeBlocking<Pair<Boolean, String?>, RuntimeException> {
            FileDocumentManager.getInstance().isDocumentUnsaved(document) to fixedEncoding(file, document)
        }
        if (unsaved) throw MainThreadException("Error",
            "cannot open ${resource.uri} with encoding '$encoding': it has unsaved changes. Save it first.")
        if (fixedBy != null) throw cannotOpen(resource, "its encoding is fixed by its $fixedBy")
        val expected = Documents.normalize(String(file.contentsToByteArray(), charset).removePrefix("\uFEFF"))
        ApplicationManager.getApplication().invokeAndWait {
            // Else the reload would guess from the bytes again, rather than read them in [charset].
            LoadTextUtil.clearCharsetAutoDetectionReason(file)
            EncodingProjectManager.getInstance(project).setEncoding(file, charset)
        }
        // The reload may land on the editor thread after this returns; the host is answered only
        // once the document holds the text the new encoding gives.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RELOAD_SECONDS)
        while (Documents.normalize(ReadAction.computeBlocking<String, RuntimeException> { document.text }) != expected) {
            if (System.nanoTime() > deadline) throw cannotOpen(resource, "it was not reloaded in '$encoding' in time")
            Thread.sleep(10)
        }
        // Sent now rather than by the edit the reload raised, which is queued behind this request:
        // the host has to hold the new text and encoding before the extension reads either.
        if (features.isMirrored(file)) features.documentChanged(file, snapshot(document))
        LOG.info("Reopened ${file.name} in '$encoding' for an extension")
    }

    /**
     * What fixes [file]'s encoding so that reading it in another would still read it as it is, or
     * null when nothing does; the checks IntelliJ's "Reload in another encoding" makes.
     *
     * A charset IntelliJ only guessed from the bytes is what an extension asking for one is there to
     * correct. IntelliJ's own action declines to reload such a file, but the reload works for one the
     * guess cannot read -- text that is not valid UTF-8 is then read in the encoding recorded for it.
     * Where IntelliJ keeps its guess anyway, the wait in [reopenIn] gives up and the extension is told
     * so. A BOM, or a charset the file declares itself, is the file's own.
     */
    private fun fixedEncoding(file: VirtualFile, document: Document): String? {
        LoadTextUtil.getCharsetAutoDetectionReason(file)?.let { detected ->
            return if (detected == LoadTextUtil.AutoDetectionReason.FROM_BOM) "byte order mark" else null
        }
        val declared = EncodingManager.getInstance().getCachedCharsetFromContent(document)
            ?: LoadTextUtil.charsetFromContentOrNull(project, file, document.immutableCharSequence)
        return when {
            declared != null -> "content"
            file.fileType.isBinary -> "file type, which is binary"
            file.fileType.isCharsetHardcoded -> "file type"
            else -> null
        }
    }

    private fun snapshot(document: Document) = ReadAction.computeBlocking<HostedLanguageFeatures.Snapshot, RuntimeException> {
        HostedLanguageFeatures.Snapshot(document.text, FileDocumentManager.getInstance().isDocumentUnsaved(document))
    }

    /** Holds [file] for another [HOLD_MINUTES], letting go of the oldest hold beyond [MAX_HELD]. */
    @Synchronized private fun hold(file: VirtualFile, document: Document) {
        held.remove(file)?.release?.cancel()
        held[file] = Hold(document, scope.launch(worker) { delay(HOLD_MINUTES.minutes); release(file) })
        while (held.size > MAX_HELD) release(held.keys.first())
    }

    @Synchronized private fun release(file: VirtualFile) {
        val hold = held.remove(file) ?: return
        hold.release.cancel()
        features.documentReleased(file)
    }

    /**
     * A new untitled buffer, for `openTextDocument({ language, content })`, answered with its resource.
     *
     * Stood in for by an in-memory file, so `showTextDocument` can open it in an editor and edits to it
     * reach the host the way a real file's do. The host knows it as `untitled:Untitled-N`, as VS Code
     * names it; the in-memory file's own name carries an extension of its language where one is
     * contributed, which is what gives IntelliJ a file type to highlight it with.
     */
    private fun create(untitled: Documents.Untitled): MainThreadResult {
        val language = untitled.languageId ?: PLAIN_TEXT
        val name = "Untitled-${untitledCount.incrementAndGet()}"
        val text = Documents.normalize(untitled.content)
        val charset = untitled.encoding?.let { Encodings.charsetOf(it) ?: throw MainThreadException("Error", "unknown encoding '$it'") }
            ?: Charsets.UTF_8
        val file = extensionOf(language)?.let { LightVirtualFile(name + it, text) }
            ?: LightVirtualFile(name, fileTypeOf(language), text)
        // Bytes only once it is saved, but the extension is told what they will be.
        file.charset = charset
        val document = ReadAction.computeBlocking<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file) }
            ?: throw MainThreadException("Error", "cannot create an untitled document")
        val uri = features.documentHeld(file, language, HostedLanguageFeatures.Snapshot(text, dirty = false),
            untitled = UriComponents(UriComponents.UNTITLED, "", name, "", "").uri)
        synchronized(this) { this.untitled[file] = document }
        LOG.info("Created $name in the extension host for an extension, as '$language'")
        if (language != PLAIN_TEXT) activate(language)
        return answer(uri)
    }

    /**
     * IntelliJ's own file type for a VS Code language id, matched by name, for a buffer no extension's
     * contribution names a file extension for. Plain text when IntelliJ has no such language.
     */
    private fun fileTypeOf(language: String): FileType =
        Language.getRegisteredLanguages().firstOrNull { it.id.equals(language, ignoreCase = true) }?.associatedFileType
            ?: PlainTextFileType.INSTANCE

    /**
     * Lets go of an untitled buffer once its editor is closed, which is when VS Code discards one the
     * user chose not to save. A file an extension opened is left to its own timer.
     */
    fun editorClosed(file: VirtualFile) {
        if (synchronized(this) { untitled.remove(file) } == null) return
        features.documentReleased(file)
    }

    /**
     * `TextDocument.save()`, answered with whether it was saved.
     *
     * An untitled buffer is saved where the user chooses; see [saveAs].
     */
    private suspend fun save(resource: UriComponents): MainThreadResult {
        if (resource.scheme == UriComponents.UNTITLED) return saveAs(resource)
        if (resource.scheme != UriComponents.FILE) return MainThreadResult("false")
        val file = LocalFileSystem.getInstance().findFileByNioFile(resource.toPath()) ?: return MainThreadResult("false")
        return withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
            if (document != null) CodeBridgeWorkspaceEdits.save(listOf(document))
            MainThreadResult((document != null && !FileDocumentManager.getInstance().isDocumentUnsaved(document)).toString())
        }
    }

    /**
     * Saves an untitled buffer as a file the user picks, as VS Code does when one is saved.
     *
     * The buffer is done with afterwards: the host is given the file in its place, so the extension's
     * untitled `TextDocument` closes and `onDidOpenTextDocument` fires for the file, and an editor
     * showing the buffer is replaced by one showing the file. Answered false when the user cancels.
     */
    private suspend fun saveAs(resource: UriComponents): MainThreadResult {
        val buffer = features.fileOf(resource.uri)
        val document = buffer?.let { synchronized(this) { untitled[it] } } ?: return MainThreadResult("false")
        val (saved, shown) = withContext(Dispatchers.EDT) {
            val saved = runCatching { chooseSaveTarget(buffer)?.let { write(it, document.text, buffer.charset) } }
                .onFailure { LOG.warn("Could not save ${buffer.name}", it) }
                .getOrNull()
            saved to features.isShown(buffer)
        }
        if (saved == null) return MainThreadResult("false")
        withContext(worker) {
            open(Documents.Open(UriComponents.of(saved.toNioPath()), null))
            if (synchronized(this@HostedDocuments) { untitled.remove(buffer) } != null) features.documentReleased(buffer)
            LOG.info("Saved ${buffer.name} as ${saved.name} for an extension")
        }
        if (shown) {
            withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(buffer) }
            CodeBridgeTextEditors.getInstance(project).reveal(saved, null, focus = true)
        }
        return MainThreadResult("true")
    }

    /** Writes [text] to [target] in [charset], creating it and its directory as needed. */
    private fun write(target: Path, text: String, charset: Charset): VirtualFile = WriteAction.compute<VirtualFile, IOException> {
        val parent = VfsUtil.createDirectoryIfMissing(target.parent.toString())
            ?: throw IOException("Could not create ${target.parent}")
        val name = target.fileName.toString()
        val file = parent.findChild(name) ?: parent.createChildData(this, name)
        file.setBinaryContent(text.toByteArray(charset))
        // Read back the way it was written, where that is not what IntelliJ would assume.
        if (file.charset != charset) EncodingProjectManager.getInstance(project).setEncoding(file, charset)
        file
    }

    private fun answer(uri: String) = MainThreadResult(UriComponents.of(uri).toJson().toString())

    /** VS Code's own wording, which extensions surface to users as it is. */
    private fun cannotOpen(resource: UriComponents, why: String) =
        MainThreadException("Error", "cannot open ${resource.uri}. Detail: $why")

    private companion object {
        val LOG = Logger.getInstance(HostedDocuments::class.java)

        const val PLAIN_TEXT = "plaintext"

        /** How long a document an extension opened stays in the host after it last asked for it. */
        const val HOLD_MINUTES = 3L

        /** How many such documents are held at once, which is VS Code's bound too. */
        const val MAX_HELD = 50

        /** How long reading a file in another encoding may take to reach its document. */
        const val RELOAD_SECONDS = 5L
    }
}
