package me.helvert.codebridge.webview

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.helvert.codebridge.rpc.WebviewDto
import me.helvert.codebridge.rpc.WebviewEventDto
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * The editor tab of webview panel [handle]: a file of no content, only there to be opened, named by
 * the panel's [title] and drawn with its [icon], once fetched.
 */
internal class CodeBridgeWebviewFile(val handle: String, var title: String) : LightVirtualFile("webview", UnknownFileType.INSTANCE, "") {
    /** The panel's `iconPath` image for the current theme, or null for the generic web icon. */
    @Volatile var icon: Icon? = null
    /** The keys [icon] was fetched for: light and dark. */
    var iconKeys: Pair<String?, String?>? = null

    init {
        isWritable = false
    }

    override fun getPresentableName(): String = title
}

/** Opens a [CodeBridgeWebviewFile] as its page, and as nothing else. */
internal class CodeBridgeWebviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is CodeBridgeWebviewFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = CodeBridgeWebviewEditor(project, file as CodeBridgeWebviewFile)
    override fun getEditorTypeId(): String = "codebridge-webview"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

internal class CodeBridgeWebviewTabTitles : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? = (file as? CodeBridgeWebviewFile)?.title
}

internal class CodeBridgeWebviewIcons : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file is CodeBridgeWebviewFile) file.icon ?: AllIcons.General.Web else null
}

/**
 * A webview panel's page, for as long as its tab is open. A panel waiting to be restored says so
 * until its extension fills it again -- or why it could not -- and the page then takes its place.
 *
 * With `enableFindWidget`, Ctrl/Cmd+F opens a find bar above the page, as VS Code's find widget.
 * Used on the EDT only.
 */
internal class CodeBridgeWebviewEditor(project: Project, private val file: CodeBridgeWebviewFile) : UserDataHolderBase(), FileEditor {
    private val browser = WebviewBrowser(project, CodeBridgeWebviews.getInstance(project).scope, file.handle, inPanel = false)
    private val panel = JPanel(BorderLayout())
    private val message = JBLabel("", SwingConstants.CENTER).apply {
        border = JBUI.Borders.empty(8)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val find = FindBar()
    /** What is in the middle of [panel] now: the page, or [message]. */
    private var showing: JComponent? = null
    private var findWidget = false

    init {
        Disposer.register(this, browser)
        panel.add(find, BorderLayout.NORTH)
        browser.findRequested = { if (findWidget) find.open() }
        action(null, null) { if (findWidget) find.open() }.registerCustomShortcutSet(CommonShortcuts.getFind(), panel, this)
        val webviews = CodeBridgeWebviews.getInstance(project)
        if (webviews.current(file.handle) == null) closed()
        webviews.listen(file.handle, object : CodeBridgeWebviews.Listener {
            override fun updated(webview: WebviewDto?) {
                when {
                    webview == null -> closed()
                    webview.dormant -> say(webview.problem ?: "Restoring '${webview.title.ifEmpty { webview.viewType }}'...")
                    else -> {
                        show(browser.component)
                        browser.show(webview)
                    }
                }
                findWidget = webview?.findWidget == true && !webview.dormant
                if (!findWidget) find.close()
            }

            override fun event(event: WebviewEventDto) = browser.post(event)
        }, this)
    }

    /** The panel went while its tab stayed: reopened from history, or its extension closed it. */
    private fun closed() = say("This panel has been closed by its extension.")

    private fun say(text: String) {
        message.text = text
        show(message)
    }

    private fun show(component: JComponent) {
        if (showing === component) return
        showing?.let(panel::remove)
        showing = component
        panel.add(component, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName(): String = "Webview"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit

    /**
     * Searches the page as the user types: Enter for the next match, Shift+Enter for the one before,
     * Escape to close. Every match is highlighted while the bar is open.
     */
    private inner class FindBar : JPanel(BorderLayout()) {
        private val field = SearchTextField(false)
        private var matchCase = false

        init {
            isVisible = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(2, 4))
            field.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = search(forward = true, next = false)
            })
            field.textEditor.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    when (event.keyCode) {
                        KeyEvent.VK_ENTER -> { search(forward = !event.isShiftDown, next = true); event.consume() }
                        KeyEvent.VK_ESCAPE -> { close(); event.consume() }
                    }
                }
            })
            val actions = DefaultActionGroup(
                action("Previous Match", AllIcons.Actions.PreviousOccurence) { search(forward = false, next = true) },
                action("Next Match", AllIcons.Actions.NextOccurence) { search(forward = true, next = true) },
                object : ToggleAction("Match Case", null, AllIcons.Actions.MatchCase) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(event: AnActionEvent) = matchCase
                    override fun setSelected(event: AnActionEvent, state: Boolean) {
                        matchCase = state
                        search(forward = true, next = false)
                    }
                },
                action("Close", AllIcons.Actions.Close) { close() },
            )
            val toolbar = ActionManager.getInstance().createActionToolbar("CodeBridge.WebviewFind", actions, true)
            toolbar.targetComponent = this
            add(field, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        fun open() {
            isVisible = true
            panel.revalidate()
            field.selectText()
            field.requestFocusInWindow()
            search(forward = true, next = false)
        }

        fun close() {
            if (!isVisible) return
            isVisible = false
            browser.stopFinding()
            panel.revalidate()
            browser.component.requestFocusInWindow()
        }

        private fun search(forward: Boolean, next: Boolean) {
            if (isVisible) browser.find(field.text, forward, matchCase, next)
        }
    }
}

/** `DumbAwareAction.create` without its obsolete `com.intellij.util.Consumer`. */
private fun action(text: String?, icon: Icon?, perform: () -> Unit): AnAction = object : DumbAwareAction(text, null, icon) {
    override fun actionPerformed(event: AnActionEvent) = perform()
}
