package me.helvert.codebridge.workbench

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Settings files as the IDE holds them: a file loaded in an editor is read and written through its
 * document, and saved, so the editor shows an extension's change at once and can undo it. Any other
 * file is written on disk, and the VFS told.
 */
internal class EditorSettingsFiles(private val project: Project) : HostedConfiguration.SettingsFiles {

    private fun document(file: Path): Document? {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return null
        return FileDocumentManager.getInstance().getCachedDocument(virtual)
    }

    override fun read(file: Path): String? =
        document(file)?.immutableCharSequence?.toString() ?: HostedConfiguration.SettingsFiles.DISK.read(file)

    override fun dirty(file: Path): Boolean =
        document(file)?.let { FileDocumentManager.getInstance().isDocumentUnsaved(it) } ?: false

    override suspend fun write(file: Path, before: String, after: String) {
        val document = document(file)
        if (document == null) {
            HostedConfiguration.SettingsFiles.DISK.write(file, before, after)
            LocalFileSystem.getInstance().refreshNioFiles(listOf(file))
            return
        }
        // Suspended rather than waited for: this starts on the thread that delivers the host's
        // replies, which whatever the EDT is doing may be waiting for.
        withContext(Dispatchers.EDT) {
            check(!project.isDisposed) { "The project has been closed" }
            check(document.immutableCharSequence.contentEquals(before)) { "${file.fileName} changed while the setting was being written" }
            // Only the span that changed, so the caret and folding elsewhere stay put.
            val start = before.commonPrefixWith(after).length
            val end = before.length - before.substring(start).commonSuffixWith(after.substring(start)).length
            WriteCommandAction.runWriteCommandAction(project, "Extension Changed a Setting", null, {
                document.replaceString(start, end, after.substring(start, after.length - (before.length - end)))
            })
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }
}
