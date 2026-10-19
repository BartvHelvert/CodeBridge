package me.helvert.codebridge.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.CodeBridgeApi
import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto
import me.helvert.codebridge.views.RpcTreeViewSource
import me.helvert.codebridge.views.TreeIcons
import javax.swing.SwingConstants
import kotlin.time.Duration.Companion.milliseconds

/**
 * Follows the project's webviews from the backend, as [CodeBridgeWebviews] keeps them. Reconnects, as the
 * tree views do: a backend that restarts ends the flows.
 */
internal class CodeBridgeWebviewsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val webviews = CodeBridgeWebviews.getInstance(project)
        coroutineScope {
            launch {
                retrying("webviews") {
                    CodeBridgeApi.getInstance().webviews(project.projectId()).collect { all ->
                        withContext(Dispatchers.EDT) { drawing("webviews") { webviews.update(all) } }
                    }
                }
            }
            launch {
                retrying("webview messages") {
                    CodeBridgeApi.getInstance().webviewEvents(project.projectId()).collect { event ->
                        withContext(Dispatchers.EDT) { drawing("a webview message") { webviews.changed(event) } }
                    }
                }
            }
        }
    }

    private suspend fun retrying(what: String, block: suspend () -> Unit) = coroutineScope {
        while (isActive) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.debug("Lost the $what; retrying", error)
            }
            delay(RETRY_MILLIS.milliseconds)
        }
    }

    /** A failure to draw is not a lost connection, and must not be retried as one; see `CodeBridgeTreeViewsActivity`. */
    private fun drawing(what: String, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LOG.warn("Could not draw $what", error)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeWebviewsActivity::class.java)
        private const val RETRY_MILLIS = 2_000L
    }
}

/**
 * The project's webviews as the backend last described them, and who draws each: the editor of a
 * panel, the tool window tab of a view. Panels are opened and closed here as they come and go.
 * Used on the EDT only.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeWebviews(private val project: Project, val scope: CoroutineScope) : Disposable {

    /** Told what becomes of one webview: its new description, or null once it has gone; and messages for its page. */
    interface Listener {
        fun updated(webview: WebviewDto?)
        fun event(event: WebviewEventDto)
    }

    private val listeners = WebviewListeners()
    private val all get() = listeners.all
    /** The editor tab file of each panel open. */
    private val panels = mutableMapOf<String, CodeBridgeWebviewFile>()

    /**
     * Panels the user closed that the backend may still list, until it has heard: a list already on
     * its way would otherwise open them again.
     */
    private val closing = mutableSetOf<String>()
    /** Where each panel was last reported to be; see [reportPanels]. */
    private val reported = mutableMapOf<String, Placement>()

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file !is CodeBridgeWebviewFile || panels[file.handle] !== file) return
                // Still open in another window: not closed yet.
                if (source.isFileOpen(file)) return
                panels.remove(file.handle)
                reported.remove(file.handle)
                closing += file.handle
                call { closeWebview(project.projectId(), file.handle) }
            }

            override fun selectionChanged(event: FileEditorManagerEvent) = reportPanels()
        })
    }

    fun current(handle: String): WebviewDto? = all[handle]

    /**
     * Has [listener] told about webview [handle] until [parent] is disposed: what it is now, if it
     * is known yet, and the messages that waited for it.
     */
    fun listen(handle: String, listener: Listener, parent: Disposable) {
        val stop = listeners.listen(handle, listener)
        Disposer.register(parent) { stop() }
    }

    fun update(webviews: List<WebviewDto>) {
        if (project.isDisposed) return
        listeners.update(webviews)
        syncPanels()
    }

    fun changed(event: WebviewEventDto) {
        if (event.kind == WebviewEventDto.REVEAL) return reveal(event.handle, !event.preserveFocus, event.column)
        listeners.event(event)
    }

    /** Opens an editor tab for each new panel, keeps the rest's title and icon, and closes those that have gone. */
    private fun syncPanels() {
        val manager = FileEditorManager.getInstance(project)
        closing.retainAll(all.keys)
        for ((handle, file) in panels.toList()) {
            val panel = all[handle]
            if (panel == null) {
                panels.remove(handle)
                reported.remove(handle)
                manager.closeFile(file)
                continue
            }
            if (panel.title != file.title) {
                file.title = panel.title
                FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
            }
            showIcon(file, panel)
        }
        for (panel in all.values) {
            if (panel.kind != WebviewDto.PANEL || panel.handle in panels || panel.handle in closing) continue
            val file = CodeBridgeWebviewFile(panel.handle, panel.title)
            panels[panel.handle] = file
            showIcon(file, panel)
            open(file, panel.column, !panel.preserveFocus)
        }
        reportPanels()
    }

    /** The panel's `iconPath` on its tab, once fetched; the generic web icon until then, and without one. */
    private fun showIcon(file: CodeBridgeWebviewFile, panel: WebviewDto) {
        val wanted = panel.icon to panel.iconDark
        if (file.iconKeys == wanted && file.icon != null) return
        file.iconKeys = wanted
        file.icon = TreeIcons.icon(iconSource, scope, panel.icon, panel.iconDark) {
            if (file.iconKeys != wanted || project.isDisposed) return@icon
            file.icon = TreeIcons.icon(iconSource, scope, panel.icon, panel.iconDark) {}
            FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        }
        FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
    }

    private val iconSource = RpcTreeViewSource(project)

    private fun reveal(handle: String, focus: Boolean, column: Int) {
        val file = panels[handle] ?: return
        open(file, column, focus)
    }

    /**
     * Opens [file] in the editor group [column] names, as VS Code places a panel by its view column:
     * the group in front, the one beside it -- made by splitting, if there is none -- or the group of
     * that index, falling back to the one in front when there are not so many.
     */
    private fun open(file: CodeBridgeWebviewFile, column: Int, focus: Boolean) {
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val windows = manager.windows.toList()
        val current = manager.currentWindow
        val target: EditorWindow? = when {
            column == WebviewDto.SIDE_GROUP && current != null -> {
                windows.getOrNull(windows.indexOf(current) + 1) ?: run {
                    // Already open in the group in front: split it, as VS Code moves the panel beside.
                    if (current.split(SwingConstants.VERTICAL, true, file, focus) != null) return
                    null
                }
            }
            column >= 0 -> windows.getOrNull(column)
            else -> null
        }
        // A file opens in the current group, so the target is made the current one first.
        if (target != null && target != current) manager.currentWindow = target
        manager.openFile(file, focus)
    }

    /**
     * Tells each panel's extension whether it is on screen, whether it is the editor in front, and
     * which editor group it is in, when that changed.
     */
    private fun reportPanels() {
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val selected = manager.selectedFiles.toSet()
        val front = manager.selectedEditor?.file
        val windows = manager.windows.toList()
        for ((handle, file) in panels) {
            val position = windows.indexOfFirst { it.isFileOpen(file) }.coerceAtLeast(0)
            val now = Placement(file in selected, file == front, position)
            if (reported.put(handle, now) == now) continue
            call { webviewShown(project.projectId(), handle, now.visible, now.active, now.position) }
        }
    }

    private data class Placement(val visible: Boolean, val active: Boolean, val position: Int)

    private val calls = WebviewCalls(scope) { CodeBridgeApi.getInstance() }

    private fun call(work: suspend CodeBridgeApi.() -> Unit) = calls.call(work)

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): CodeBridgeWebviews = project.getService(CodeBridgeWebviews::class.java)
    }
}
