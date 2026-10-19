package me.helvert.codebridge.language

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Serves a file as soon as an editor for it exists, however that editor came to be.
 *
 * The tabs a project reopens with are the ones `fileOpened` has been seen to miss (see
 * [HostedLanguageServers.fileShown]), and they are the files a user most expects to work without
 * touching them. A startup activity cannot catch them in split mode: the client restores its tabs
 * only once it has connected, seconds after the project finished opening. Every text editor is
 * created through the editor factory, though -- the backend's own and the client's alike, and at
 * whatever moment they appear -- so this is where a restored tab first becomes visible.
 *
 * Linked editing follows the editor's caret from here too.
 *
 * Only main editors with a file behind them: diff panes, previews and consoles are editors too.
 * Files already served are left alone, so this costs nothing when `fileOpened` did arrive.
 */
internal class CodeBridgeEditorCreatedListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val project = editor.project?.takeIf { !it.isDisposed } ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        project.service<HostedLanguageServers>().fileShown(file)
        CodeBridgeLinkedEditing.getInstance(project).attach(editor)
    }
}
