package me.helvert.codebridge.workbench

import com.intellij.execution.actions.StopProcessAction
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.terminal.TerminalExecutionConsole
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * A Run tool window tab for [process] shown in [console], a terminal emulator, with a Stop button
 * beside the console's own actions. Not yet shown: the caller hands it to `RunContentManager`.
 * Must run on the editor thread.
 *
 * Unless [stoppable], the tab is not a run: the IDE's Stop actions and the run widget, which count
 * every tab whose process is alive, leave it out, and no run takes the tab over. Its own Stop button
 * still stops [process].
 */
internal fun terminalRunContent(
    name: String,
    process: ProcessHandler,
    console: TerminalExecutionConsole,
    activate: Boolean,
    stoppable: Boolean = true
): RunContentDescriptor {
    val actions = DefaultActionGroup().apply {
        add(StopProcessAction("Stop", "Stop the terminal's process", process))
        addAll(*console.createConsoleActions())
    }
    val toolbar = ActionManager.getInstance().createActionToolbar("CodeBridgeTerminal", actions, false)
    toolbar.targetComponent = console.component
    val panel = JPanel(BorderLayout()).apply {
        add(console.component, BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.WEST)
    }
    return object : RunContentDescriptor(console, process.takeIf { stoppable }, panel, name, AllIcons.Debugger.Console) {
        // A run may take over a tab whose descriptor has no live process, which this one never has.
        override fun isContentReuseProhibited() = !stoppable
    }.apply {
        isActivateToolWindowWhenAdded = activate
        isAutoFocusContent = activate
    }
}
