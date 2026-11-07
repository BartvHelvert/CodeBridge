package me.helvert.codebridge.webview

import com.intellij.ide.FileIconProvider
import com.intellij.ide.ui.icons.icon
import com.intellij.ide.vfs.rpcId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.rpc.CodeBridgeApi
import me.helvert.codebridge.rpc.CustomEditorDto
import me.helvert.codebridge.rpc.CustomEditorSetupDto
import me.helvert.codebridge.rpc.CustomEditorTypeDto
import me.helvert.codebridge.rpc.CustomEditorTypes
import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto
import java.awt.BorderLayout
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.UUID
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.time.Duration.Companion.milliseconds

/**
 * The project's custom editors -- extensions' editors for the files their `contributes.customEditors`
 * claim -- and the types that decide which files open in one.
 *
 * As with notebooks, where the file is opened decides how. In one process the IDE asks
 * [CustomEditorProvider] about the file itself. A split-mode backend picks the editors of a project
 * file on its own side and has the client show what it picked: the client then opens a
 * [CustomEditorFile] standing for the file in its place, and closes that. A file a custom editor
 * opens only when asked for (`"priority": "option"`) is opened in one by *Open With*, or by the
 * editor switcher beside the tabs, the same way.
 *
 * The types come from the backend, which reads them off the extensions' manifests, with the user's
 * `workbench.editorAssociations`; until they have, no file opens in one. Used on the EDT.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeCustomEditors(private val project: Project, val scope: CoroutineScope) {
    @Volatile var setup: CustomEditorSetupDto = CustomEditorSetupDto()
        private set
    /** The tab standing for each file in each custom editor, so opening one again shows the tab it has. */
    private val stand = HashMap<Pair<VirtualFile, String>, CustomEditorFile>()
    /** Files the user reopened as text, which then stay text until closed. */
    private val asText = HashSet<VirtualFile>()

    /** The type [file] opens in: the one it stands in for, else, unless reopened as text, [defaultTypeOf] it. */
    fun defaultType(file: VirtualFile): CustomEditorTypeDto? =
        if (file is CustomEditorFile) file.type
        else if (file in asText) null
        else defaultTypeOf(file)

    /** The type [file] opens in by default: the one the user associated with it, or the first that claims it. */
    fun defaultTypeOf(file: VirtualFile): CustomEditorTypeDto? =
        if (file.isDirectory || file is CustomEditorFile) null else CustomEditorTypes.defaultFor(setup, file.name, file.path)

    /** Every type that can open [file], for *Open With*. */
    fun typesFor(file: VirtualFile): List<CustomEditorTypeDto> =
        if (file.isDirectory || file is CustomEditorFile) emptyList() else setup.types.filter { CustomEditorTypes.matches(it, file.name, file.path) }

    fun typesChanged(setup: CustomEditorSetupDto) {
        if (setup != this.setup) LOG.info("Custom editors: ${setup.types.joinToString { it.viewType + " " + it.patterns }}; associations ${setup.associations}")
        this.setup = setup
        FileEditorManager.getInstance(project).openFiles.forEach(::shown)
    }

    /**
     * [file] is open in an editor: one a custom editor opens by default, shown as anything but
     * that, is shown in it instead.
     */
    fun shown(file: VirtualFile) {
        if (file is CustomEditorFile || file is CodeBridgeWebviewFile) return
        val type = defaultType(file) ?: return
        val manager = FileEditorManager.getInstance(project)
        if (manager.getEditors(file).any { it is CustomEditor }) return
        LOG.info("Showing ${file.name} in ${type.viewType}")
        replace(file, type)
    }

    /** Shows [file] in custom editor [type], in place of its tab if it has one. */
    fun openIn(file: VirtualFile, type: CustomEditorTypeDto) = withRealFile(file, type.viewType) { real ->
        asText.remove(real)
        replace(real, type)
    }

    /** Opens [file] as text, which it then stays until closed; its custom editors' tabs stay open. */
    fun openAsText(file: VirtualFile) = withRealFile(file, "the text editor") { real ->
        asText.add(real)
        scope.launch(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).openFile(real, FileEditorOpenRequest.defaults().withRequestFocus(true))
        }
    }

    /** Runs [open] on the EDT with the file [file] names, to be opened in [editor]. */
    private fun withRealFile(file: VirtualFile, editor: String, open: (VirtualFile) -> Unit) {
        if (file !is LightVirtualFile) return open(file)
        // A split-mode client's Project view names each file with a made-up one of its own, which the
        // backend cannot be sent: the file is the backend's at its path.
        scope.launch {
            val real = try {
                CodeBridgeApi.getInstance().fileAt(file.path)?.virtualFile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("Could not find ${file.path} on the backend", failure)
                null
            }
            if (real == null || real is LightVirtualFile) return@launch LOG.info("No file at ${file.path} to open in $editor")
            withContext(Dispatchers.EDT) { open(real) }
        }
    }

    /** The custom editor tab [file] shows its file in: as the tab standing for it, or, in one process, its own; null for none. */
    fun shownIn(file: VirtualFile): CustomEditorTypeDto? =
        (file as? CustomEditorFile)?.type
            ?: FileEditorManager.getInstance(project).getEditors(file).firstNotNullOfOrNull { (it as? CustomEditor)?.type }

    /**
     * Shows what tab [shown] shows -- a file, or the tab standing for one -- in custom editor [type],
     * or as text for null, in its place.
     */
    fun reopen(shown: VirtualFile, type: CustomEditorTypeDto?) {
        if (type?.viewType == shownIn(shown)?.viewType) return
        if (type == null) return reopenAsText(shown)
        val file = (shown as? CustomEditorFile)?.target ?: shown
        asText.remove(file)
        replace(file, type, shown)
    }

    /**
     * Makes [viewType] -- or the text editor, for [CustomEditorTypes.TEXT] -- what files matching
     * [pattern] open in, in the user's settings, as VS Code's *Set Default* does. Tabs already open
     * stay as they are.
     */
    fun setDefault(pattern: String, viewType: String) {
        val projectId = project.projectId()
        scope.launch {
            val problem = try {
                CodeBridgeApi.getInstance().setDefaultEditor(projectId, pattern, viewType)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("Could not set the default editor of $pattern", failure)
                "Could not reach the backend: ${failure.message}"
            }
            if (problem != null) {
                Notification(NOTIFICATIONS, "The default editor for '$pattern' was not changed", problem, NotificationType.WARNING).notify(project)
            }
        }
    }

    /** Shows [file] -- or the file it stands for -- as text, in place of its custom editor. */
    fun reopenAsText(file: VirtualFile) {
        val manager = FileEditorManager.getInstance(project)
        scope.launch(Dispatchers.EDT) {
            if (file is CustomEditorFile) {
                asText.add(file.target)
                manager.openFile(file.target, FileEditorOpenRequest.defaults().withRequestFocus(true))
                manager.closeFile(file)
            } else {
                // Closed first: closing it forgets that it is to be text.
                manager.closeFile(file)
                asText.add(file)
                manager.openFile(file, FileEditorOpenRequest.defaults().withRequestFocus(true))
            }
        }
    }

    /** Opens [file] in [type], then closes the tab that showed it before: its own, or [shown]. */
    private fun replace(file: VirtualFile, type: CustomEditorTypeDto, shown: VirtualFile = file) {
        val manager = FileEditorManager.getInstance(project)
        val standIn = stand.getOrPut(file to type.viewType) { CustomEditorFile(file, type) }
        // Later: closing a file from inside the listener that heard it open is not allowed. The text
        // goes once the editor is open in its place, so the tab strip is never left empty.
        scope.launch(Dispatchers.EDT) {
            if (standIn.icon == null) standIn.icon = withContext(Dispatchers.Default) { iconOf(file) }
            manager.openFile(standIn, FileEditorOpenRequest.defaults().withRequestFocus(true))
            for (previous in setOf(shown, file)) if (previous != standIn && manager.isFileOpen(previous)) manager.closeFile(previous)
        }
    }

    /**
     * The backend's icon for [file], for its stand-in's tab, asked for before the tab opens so that
     * it opens with it. Not waited on for long: a tab late with its icon is better than one late.
     */
    private suspend fun iconOf(file: VirtualFile): Icon? = try {
        withTimeoutOrNull(ICON_WAIT_MILLIS.milliseconds) { CodeBridgeApi.getInstance().fileIcon(project.projectId(), file.rpcId()) }?.icon()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LOG.info("No icon for ${file.name}", failure)
        null
    }

    fun forget(file: VirtualFile) {
        if (file is CustomEditorFile) stand.remove(file.target to file.type.viewType, file)
        else asText.remove(file)
    }

    companion object {
        fun getInstance(project: Project): CodeBridgeCustomEditors = project.getService(CodeBridgeCustomEditors::class.java)
        private val LOG = Logger.getInstance(CodeBridgeCustomEditors::class.java)
        private const val ICON_WAIT_MILLIS = 1_000L
        private const val NOTIFICATIONS = "CodeBridge"
    }
}

/** A file's tab in a custom editor the IDE did not pick for it: see [CodeBridgeCustomEditors]. */
internal class CustomEditorFile(val target: VirtualFile, val type: CustomEditorTypeDto) : LightVirtualFile(target.name, target.fileType, "") {
    init {
        isWritable = false
    }

    /**
     * The icon the Project view draws for [target], asked of the backend before the tab opens. The
     * backend's own: the IDE there may give the file a type -- an extension's language -- that this
     * side never sees, and would then draw the plain text icon.
     */
    var icon: Icon? = null

    override fun getPresentableName(): String = target.presentableName
    override fun isValid(): Boolean = target.isValid
}

/** A [CustomEditorFile]'s tab icon: the backend's for its file -- see [CustomEditorFile.icon]. */
internal class CustomEditorIcons : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = (file as? CustomEditorFile)?.icon
}

/** Follows the custom editor types from the backend, reconnecting as the tree views do. */
internal class CodeBridgeCustomEditorsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val customEditors = CodeBridgeCustomEditors.getInstance(project)
        project.messageBus.connect(customEditors.scope).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            // At once, in the event that opened the file: its text is not painted yet.
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = customEditors.shown(file)

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (!source.isFileOpen(file)) customEditors.forget(file)
            }
        })
        while (currentCoroutineContext().isActive) {
            try {
                CodeBridgeApi.getInstance().customEditorTypes(project.projectId()).collect { types ->
                    withContext(Dispatchers.EDT) { customEditors.typesChanged(types) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LOG.info("Lost the custom editor types; retrying", error)
            }
            delay(RETRY.milliseconds)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeCustomEditorsActivity::class.java)
        private const val RETRY = 2_000L
    }
}

/** Opens a file a custom editor claims by default in that editor, instead of as text. */
internal class CustomEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = CodeBridgeCustomEditors.getInstance(project).defaultType(file) != null

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val type = checkNotNull(CodeBridgeCustomEditors.getInstance(project).defaultType(file))
        return CustomEditor(project, file, (file as? CustomEditorFile)?.target ?: file, type)
    }

    override fun getEditorTypeId(): String = "codebridge-custom-editor"

    /**
     * The extension draws the file as it means it to be seen, in place of every other editor -- its
     * text, or the IDE's image viewer for an image -- as VS Code's editor for it replaces its own.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS
}

/**
 * Hides the text editor of a file a custom editor opens by default as it is made, before it is
 * first painted: a split-mode client shows the custom editor in its place a moment later.
 */
internal class CustomEditorTextHider : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file is CustomEditorFile) return
        if (project.serviceIfCreated<CodeBridgeCustomEditors>()?.defaultType(file) != null) editor.component.isVisible = false
    }
}

/**
 * One custom editor: the page its extension draws for [target], open for as long as the editor
 * is. Until the page is there, says what it waits for, or why it could not open.
 *
 * Saving, undoing and redoing are the document's, which the backend keeps: asked for with the
 * page's keys (Ctrl/Cmd+S, Z, Shift+Z and Y), which the page itself gets first. Its changes show
 * as the tab's modified mark.
 */
internal class CustomEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val target: VirtualFile,
    val type: CustomEditorTypeDto,
) : UserDataHolderBase(), FileEditor {
    private val customEditors = CodeBridgeCustomEditors.getInstance(project)
    private val id = UUID.randomUUID().toString()
    private val scope = CoroutineScope(customEditors.scope.coroutineContext + SupervisorJob(customEditors.scope.coroutineContext[Job]))
    private val panel = JPanel(BorderLayout())
    private val message = JBLabel("Opening ${target.name} in ${type.displayName}...", SwingConstants.CENTER).apply {
        border = JBUI.Borders.empty(8)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val listeners = PropertyChangeSupport(this)
    private var page: WebviewBrowser? = null
    private var pageParent: Disposable? = null
    private var state = CustomEditorDto()
    private var selected = false

    init {
        panel.getAccessibleContext().accessibleName = "${type.displayName} ${target.name}"
        panel.add(message, BorderLayout.CENTER)
        scope.launch { follow() }
    }

    private suspend fun follow() {
        val position = withContext(Dispatchers.EDT) { FileEditorManagerEx.getInstanceEx(project).let { it.windows.indexOf(it.currentWindow) }.coerceAtLeast(0) }
        try {
            CodeBridgeApi.getInstance().openCustomEditor(project.projectId(), target.rpcId(), type.viewType, id, position).collect { state ->
                withContext(Dispatchers.EDT) { apply(state) }
            }
            // The backend's last word stays: why the editor could not open, rather than that it ended.
            withContext(Dispatchers.EDT) { if (state.handle != null || state.message == null) say("The connection to ${type.displayName} was lost.") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("Lost the custom editor of ${target.name}", failure)
            withContext(Dispatchers.EDT) { say("Could not open ${target.name} in ${type.displayName}: ${failure.message}") }
        }
    }

    private fun apply(next: CustomEditorDto) {
        val previous = state
        state = next
        if (next.handle != previous.handle) {
            closePage()
            next.handle?.let(::openPage)
        }
        if (next.handle == null) say(next.message ?: "Opening ${target.name} in ${type.displayName}...")
        if (next.dirty != previous.dirty) listeners.firePropertyChange(PropertyChangeEvent(this, FileEditor.getPropModified(), previous.dirty, next.dirty))
    }

    /** Draws webview [handle], once the backend lists it; closes the editor if its extension disposes of it. */
    private fun openPage(handle: String) {
        val parent = Disposer.newDisposable(this, "custom editor page")
        val browser = WebviewBrowser(project, scope, handle, inPanel = false)
        Disposer.register(parent, browser)
        browser.keyCommand = ::command
        page = browser
        pageParent = parent
        var seen = false
        CodeBridgeWebviews.getInstance(project).listen(handle, object : CodeBridgeWebviews.Listener {
            override fun updated(webview: WebviewDto?) {
                if (page !== browser) return
                if (webview == null) {
                    // Gone after it was there: its extension disposed of it, which closes its editor in VS Code.
                    // Later: the editor goes with its tab, and this listener with it.
                    if (seen) scope.launch(Dispatchers.EDT) { if (page === browser) FileEditorManager.getInstance(project).closeFile(file) }
                    return
                }
                if (!seen) {
                    seen = true
                    show(browser.component)
                    report()
                }
                browser.show(webview)
            }

            override fun event(event: WebviewEventDto) = browser.post(event)
        }, parent)
    }

    private fun closePage() {
        pageParent?.let(Disposer::dispose)
        pageParent = null
        page = null
    }

    private fun command(name: String) {
        if (!state.editable) return
        val projectId = project.projectId()
        scope.launch {
            try {
                when (name) {
                    WebviewBrowser.SAVE -> CodeBridgeApi.getInstance().saveCustomEditor(projectId, id)
                    WebviewBrowser.UNDO -> CodeBridgeApi.getInstance().undoCustomEditor(projectId, id)
                    WebviewBrowser.REDO -> CodeBridgeApi.getInstance().redoCustomEditor(projectId, id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.info("Could not $name ${target.name}", failure)
            }
        }
    }

    private fun say(text: String) {
        message.text = text
        show(message)
    }

    private fun show(component: JComponent) {
        if (panel.componentCount == 1 && panel.getComponent(0) === component) return
        panel.removeAll()
        panel.add(component, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    /** Tells the extension whether its page is on screen, as it is told of a panel's. */
    private fun report() {
        val handle = state.handle ?: return
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val position = manager.windows.indexOfFirst { it.isFileOpen(file) }.coerceAtLeast(0)
        val active = manager.selectedEditor === this
        val projectId = project.projectId()
        scope.launch {
            runCatching { CodeBridgeApi.getInstance().webviewShown(projectId, handle, selected, active, position) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    override fun selectNotify() {
        selected = true
        report()
    }

    override fun deselectNotify() {
        selected = false
        report()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = page?.component ?: panel
    override fun getName(): String = type.displayName
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = state.dirty
    override fun isValid(): Boolean = target.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = listeners.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = listeners.removePropertyChangeListener(listener)

    /** Letting the flow go closes the editor on the backend, which saves the document's changes if it was the last. */
    override fun dispose() {
        closePage()
        scope.cancel()
    }

    companion object {
        private val LOG = Logger.getInstance(CustomEditor::class.java)
    }
}

/**
 * *Open With*, a submenu as VS Code's editor picker is one list: the editors that can open the
 * selected file, in its order -- the custom editors that open such files by default, the text
 * editor, then those that open them only when asked for. Only for a file a custom editor can open:
 * otherwise the text editor would be all it offers.
 *
 * On an editor tab, the editor the tab shows is checked, and the one chosen takes the tab's place.
 */
internal class OpenWithCustomEditorGroup : ActionGroup(), DumbAware {
    init {
        templatePresentation.isPopupGroup = true
        templatePresentation.text = "Open With"
    }

    /** The EDT: it asks the editor manager what shows the file. */
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = Selection.of(event) != null
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val selection = event?.let(Selection::of) ?: return emptyArray()
        val customEditors = selection.customEditors
        val (byDefault, options) = selection.types.partition { it.byDefault }
        val editors: List<CustomEditorTypeDto?> = byDefault + null + options
        val tab = selection.tab
        return editors.map { type ->
            val name = type?.displayName ?: "Text Editor"
            if (tab == null) {
                object : DumbAwareAction(name) {
                    override fun actionPerformed(event: AnActionEvent) =
                        if (type == null) customEditors.openAsText(selection.file) else customEditors.openIn(selection.file, type)
                }
            } else {
                val shownIn = customEditors.shownIn(tab)?.viewType
                object : DumbAwareToggleAction(name) {
                    init {
                        templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun isSelected(event: AnActionEvent) = type?.viewType == shownIn
                    override fun setSelected(event: AnActionEvent, state: Boolean) = customEditors.reopen(tab, type)
                }
            }
        }.toTypedArray()
    }

    /**
     * The file the menu is for and the [types] that can open it; [tab] is the editor tab showing it
     * -- the file, or the tab standing for it -- when the menu is the tab's, else null.
     */
    private class Selection(val customEditors: CodeBridgeCustomEditors, val file: VirtualFile, val tab: VirtualFile?, val types: List<CustomEditorTypeDto>) {
        companion object {
            fun of(event: AnActionEvent): Selection? {
                val project = event.project ?: return null
                val selected = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
                val customEditors = project.serviceIfCreated<CodeBridgeCustomEditors>() ?: return null
                val file = (selected as? CustomEditorFile)?.target ?: selected
                val types = customEditors.typesFor(file)
                if (types.isEmpty()) return null
                val tab = selected.takeIf { event.place == ActionPlaces.EDITOR_TAB_POPUP }
                return Selection(customEditors, file, tab, types)
            }
        }
    }
}

/**
 * The editor switcher beside the editor tabs, as VS Code has one above its editors: it names the
 * editor the selected tab shows its file in, offers the others that can -- the text editor among
 * them -- and makes one the default for files like it (*Set Default for '*.ext'*).
 *
 * Only for a file a custom editor opens by default, or one shown in one: with an editor that can
 * open anything as an option (the Hex Editor's `*`), every tab would otherwise have it.
 */
internal class CustomEditorSwitcher : ActionGroup(), DumbAware {
    init {
        templatePresentation.isPopupGroup = true
        templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    /** The EDT: it asks the editor manager what shows the file. */
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val tab = Tab.of(event)
        event.presentation.isEnabledAndVisible = tab != null
        if (tab == null) return
        event.presentation.text = name(tab.shownIn)
        event.presentation.description = "Reopen ${tab.file.name} with another editor"
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val tab = event?.let(Tab::of) ?: return emptyArray()
        val customEditors = tab.customEditors
        // VS Code's order: the editors that open such files by default, the text editor, then the rest.
        val (byDefault, options) = customEditors.typesFor(tab.file).partition { it.byDefault }
        val editors: List<CustomEditorTypeDto?> = byDefault + null + options
        val pattern = CustomEditorTypes.patternFor(tab.file.name)
        val default = customEditors.defaultTypeOf(tab.file)
        val setDefault = DefaultActionGroup(editors.map { type ->
            choice(type, type?.viewType == default?.viewType) { customEditors.setDefault(pattern, type?.viewType ?: CustomEditorTypes.TEXT) }
        }).apply {
            templatePresentation.isPopupGroup = true
            templatePresentation.text = "Set Default for '$pattern'"
        }
        return (editors.map { type -> choice(type, type?.viewType == tab.shownIn?.viewType) { customEditors.reopen(tab.tab, type) } } +
                Separator.getInstance() + setDefault).toTypedArray()
    }

    private fun choice(type: CustomEditorTypeDto?, selected: Boolean, choose: () -> Unit): AnAction =
        object : DumbAwareToggleAction(name(type)) {
            init {
                // Choosing an editor replaces the tab, so its menu must close even when ordinary
                // checkbox menus are configured to stay open.
                templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
            }

            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun isSelected(event: AnActionEvent) = selected
            override fun setSelected(event: AnActionEvent, state: Boolean) = choose()
        }

    private fun name(type: CustomEditorTypeDto?): String = type?.displayName ?: "Text Editor"

    /** The selected tab, [tab], showing [file] -- in custom editor [shownIn], or another for null. */
    private class Tab(val customEditors: CodeBridgeCustomEditors, val tab: VirtualFile, val file: VirtualFile, val shownIn: CustomEditorTypeDto?) {
        companion object {
            fun of(event: AnActionEvent): Tab? {
                val project = event.project ?: return null
                // The tab strip's own window, not the file the context names: that can be the
                // Project view's selection, or a stand-in's file rather than the stand-in.
                val window = event.getData(EditorWindow.DATA_KEY) ?: FileEditorManagerEx.getInstanceEx(project).currentWindow
                val tab = window?.selectedFile ?: return null
                // A webview panel's tab, or any other made-up file, is no file an editor opens.
                if (tab is LightVirtualFile && tab !is CustomEditorFile) return null
                val customEditors = project.serviceIfCreated<CodeBridgeCustomEditors>() ?: return null
                val file = (tab as? CustomEditorFile)?.target ?: tab
                val shownIn = customEditors.shownIn(tab)
                if (shownIn == null && customEditors.typesFor(file).none { it.byDefault } && customEditors.defaultTypeOf(file) == null) return null
                return Tab(customEditors, tab, file, shownIn)
            }
        }
    }
}
