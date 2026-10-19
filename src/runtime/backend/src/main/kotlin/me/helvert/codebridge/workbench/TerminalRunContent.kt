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
 */
internal fun terminalRunContent(
    name: String,
    process: ProcessHandler,
    console: TerminalExecutionConsole,
    activate: Boolean
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
    return RunContentDescriptor(console, process, panel, name, AllIcons.Debugger.Console).apply {
        isActivateToolWindowWhenAdded = activate
        isAutoFocusContent = activate
    }
}
