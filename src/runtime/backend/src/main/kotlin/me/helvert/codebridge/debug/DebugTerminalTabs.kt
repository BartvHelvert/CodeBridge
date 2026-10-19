package me.helvert.codebridge.debug

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.TerminalExecutionConsoleBuilder
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.workbench.terminalRunContent

/**
 * Where a program run for a debug adapter (`runInTerminal`) is shown: a Run tool window tab whose
 * console is a terminal emulator, typed into as VS Code's integrated terminal is.
 *
 * The tab is brought forward without taking focus, as VS Code reveals its terminal. It stays once
 * the program has ended, with what it printed, and the next program of the same name takes it
 * over, as VS Code reuses a debug terminal.
 */
class DebugTerminalTabs(private val project: Project) {

    /** Starts reading [process], which must not have been started yet, in a tab named [name]. */
    suspend fun show(name: String, process: ProcessHandler) = CodeBridgeTextEditors.getInstance(project).asClient {
        // On a pseudo-terminal the program writes CRLF itself; on pipes, the fallback, it does not.
        val console = TerminalExecutionConsoleBuilder(project).build()
        console.attachToProcess(process)
        val manager = RunContentManager.getInstance(project)
        val ended = manager.allDescriptors.firstOrNull {
            it in shown && it.displayName == name && it.processHandler?.isProcessTerminated != false
        }
        val descriptor = terminalRunContent(name, process, console, activate = true).apply {
            isAutoFocusContent = false
        }
        shown.add(descriptor)
        process.startNotify()
        manager.showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor, ended)
    }

    private companion object {
        /** The tabs shown here, of every session; touched only on the editor thread. */
        val shown: MutableSet<RunContentDescriptor> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    }
}
