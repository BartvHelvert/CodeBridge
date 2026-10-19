package me.helvert.codebridge.testing

import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * A debugged test run's results, waiting for the debug session the run starts to show them.
 *
 * In IntelliJ a debugged test run is one tab: the debugger's, whose console is the test tree. An
 * extension's debug profile starts its debug session itself, through `vscode.debug.startDebugging`,
 * so the run's results are offered here, and the first session started while they wait takes them
 * as its console. Results no session takes are the run's to show in a tab of their own.
 */
object TestSessionConsoles {

    /** On a debug session's environment: the test results it shows as its console. */
    val CONSOLE: Key<ExecutionConsole> = Key.create("codebridge.tests.sessionConsole")

    /** [console], offered until a session [claim]s it or it is [withdraw]n. */
    class Offer internal constructor(internal val project: Project, internal val console: ExecutionConsole) {
        /** Takes the offer back; false when a session has taken it already. */
        fun withdraw(): Boolean = synchronized(offers) { offers.remove(this) }
    }

    private val offers = ArrayList<Offer>()

    fun offer(project: Project, console: ExecutionConsole): Offer =
        Offer(project, console).also { synchronized(offers) { offers += it } }

    /** The latest results of [project] still waiting, taken by the session starting now; null for none. */
    fun claim(project: Project): ExecutionConsole? = synchronized(offers) {
        offers.lastOrNull { it.project == project }?.also { offers.remove(it) }?.console
    }
}
