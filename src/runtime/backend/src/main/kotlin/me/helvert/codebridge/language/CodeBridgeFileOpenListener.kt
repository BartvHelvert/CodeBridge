package me.helvert.codebridge.language

import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * Starts language support when a file an extension claims is opened.
 *
 * Previously this came from `LspServerSupportProvider.fileOpened`, which tied the trigger to
 * `intellij.platform.lsp` -- a module only some IDEs have, and one with no frontend half, so it
 * could not be relied on in split mode. An editor listener is the platform's own signal and carries
 * no such dependency.
 *
 * Every opened file arrives here; [HostedLanguageServers] decides which ones match an installed
 * extension, and does nothing for the rest.
 */
internal class CodeBridgeFileOpenListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        source.project.service<HostedLanguageServers>().fileOpened(file)
    }

    /**
     * Makes the file the host's active editor, and is the backstop for an open that was never
     * reported; see [HostedLanguageServers.fileShown].
     */
    override fun selectionChanged(event: FileEditorManagerEvent) {
        val file = event.newFile ?: return
        event.manager.project.service<HostedLanguageServers>().fileShown(file, selected = true)
    }

    /**
     * Withdraws the document once the file is really gone from the editor.
     *
     * Closing one of two splits fires this while the other still shows the file, so the answer to
     * "is it still open" has to be asked rather than assumed. Nothing is started here, which is why
     * this asks for the service only if a file was opened earlier and created it.
     *
     * Asked of every client, not just [source]. In split mode the backend keeps an editor manager
     * of its own besides the one mirroring each client's tabs, and an editor opening and closing
     * there -- one opened programmatically, say -- says nothing about whether the user still has
     * the file in front of them. Withdrawing it on that basis took the document away from the
     * extension while it was being edited.
     */
    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        if (source.isFileOpen(file) || source.isFileOpenWithRemotes(file)) return
        if (FileEditorManager.getInstance(source.project).isFileOpenWithRemotes(file)) return
        source.project.serviceIfCreated<HostedLanguageServers>()?.fileClosed(file)
    }
}
