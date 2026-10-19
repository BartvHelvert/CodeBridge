package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Dialogs
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.CodeBridgeTextEditors
import java.nio.file.Path

/**
 * `MainThreadDiaglogs`: `window.showOpenDialog` and `window.showSaveDialog`, as IntelliJ's own file
 * chooser and save dialog.
 *
 * Opened on the backend, as the client: the chooser is a per-client service, so in split mode the
 * dialog is drawn on the user's screen while browsing the backend's files -- which are the ones
 * the extension can read. A frontend chooser would offer the frontend machine's.
 *
 * VS Code's filter list is a choice of several; IntelliJ's chooser takes one, so the first is used,
 * which is the one VS Code selects. A filter of `*` is every file.
 */
class HostedDialogs(private val project: Project) {

    suspend fun handle(call: MainThreadCall): MainThreadResult = when (call.method) {
        Dialogs.SHOW_OPEN -> MainThreadResult(Dialogs.opened(open(read(call, Dialogs::open))))
        Dialogs.SHOW_SAVE -> MainThreadResult(Dialogs.saved(save(read(call, Dialogs::save))))
        else -> throw MainThreadException("Unavailable", "Unsupported dialog method ${call.method}")
    }

    private fun <T> read(call: MainThreadCall, parse: (String) -> T): T = try {
        parse(call.argsJson)
    } catch (failure: Exception) {
        LOG.warn("Could not read ${call.service}.${call.method}", failure)
        throw failure
    }

    private suspend fun open(options: Dialogs.Open): List<UriComponents>? {
        val descriptor = when {
            options.files && options.folders -> if (options.many) FileChooserDescriptorFactory.multiFilesOrDirs() else FileChooserDescriptorFactory.singleFileOrDir()
            options.folders -> if (options.many) FileChooserDescriptorFactory.multiDirs() else FileChooserDescriptorFactory.singleDir()
            else -> if (options.many) FileChooserDescriptorFactory.multiFiles() else FileChooserDescriptorFactory.singleFile()
        }.withTitle(options.title ?: if (options.folders && !options.files) "Select Folder" else "Open")
        filter(descriptor, options.filters)
        // Where to start: the file or folder the extension named, else the project.
        val start = options.defaultUri?.let(::fileOf) ?: project.guessProjectDir()
        return CodeBridgeTextEditors.getInstance(project).asClient {
            FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
                .choose(project, *listOfNotNull(start).toTypedArray())
                .map { UriComponents.of(it.toNioPath()) }.ifEmpty { null }
        }
    }

    private suspend fun save(options: Dialogs.Save): UriComponents? {
        val descriptor = FileSaverDescriptor(options.title ?: "Save As", "")
        filter(descriptor, options.filters)
        // `defaultUri` names the file to save as, or a folder to save in.
        val default = options.defaultUri?.takeIf { it.scheme == UriComponents.FILE }?.toPath()
        val existing = default?.let(::fileOf)
        val folder =
            if (existing?.isDirectory == true) existing else default?.parent?.let(::fileOf) ?: project.guessProjectDir()
        val name = if (existing?.isDirectory == true) null else default?.fileName?.toString()
        return CodeBridgeTextEditors.getInstance(project).asClient {
            FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(folder, name)
                ?.file?.toPath()?.let(UriComponents::of)
        }
    }

    private fun filter(descriptor: FileChooserDescriptor, filters: List<Dialogs.Filter>) {
        val first = filters.firstOrNull() ?: return
        if (first.extensions.isEmpty() || "*" in first.extensions) return
        descriptor.withExtensionFilter(first.name, *first.extensions.toTypedArray())
    }

    private fun fileOf(resource: UriComponents): VirtualFile? =
        if (resource.scheme != UriComponents.FILE) null else fileOf(resource.toPath())

    private fun fileOf(path: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

    private companion object {
        private val LOG = Logger.getInstance(HostedDialogs::class.java)
    }
}
