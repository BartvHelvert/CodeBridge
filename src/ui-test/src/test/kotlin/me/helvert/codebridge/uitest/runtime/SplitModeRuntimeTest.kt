package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.client.impl.RefWrapper
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(RemoteDevRun::class)
class SplitModeRuntimeTest {
    @Test
    @Suppress("UnusedExpression") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `extension tasks can be stopped and rerun and debugger controls reach the adapter`() =
    // Traced: on the Windows runner the first Stop never ended the task, with nothing logged
        // about how it tried to.
        withWorkbenchProbe(
            "split-mode-runtime", "let value = 1;\nvalue;\n",
            traced = "#com.intellij.execution.process,#me.helvert.codebridge.task",
        ) {
            val frame = driver.ideFrame { }
            assertEquals("true", command("probe.startTask"))
            fun stateContains(key: String, value: Int) =
                command("probe.runtimeState")?.contains("\"$key\":$value") == true
            awaitProbeValue("the extension's task process started", { stateContains("taskOpened", 1) }) { it }
            val console = frame.codeEditor("//div[@class='ConsoleViewImpl']//div[@class='EditorComponentImpl']")
            awaitProbeValue(
                "task output in the client's Run console",
                { if (console.present()) console.text else "" }) {
                it.contains("PROBE TASK READY")
            }
            // Each action is given the component it acts from, as a click on a toolbar would: the
            // focus a click leaves is not to be relied on, least of all on a display with no window manager.
            driver.act("Stop", console)
            awaitProbeValue("the task stop reported to the extension", { stateContains("taskClosed", 1) }) { it }
            driver.act("Rerun", console)
            awaitProbeValue("the same task rerun from the client", { stateContains("taskOpened", 2) }) { it }
            awaitProbeValue("output after rerunning the task", { console.text }) { it.contains("PROBE TASK READY") }
            driver.act("Stop", console)
            awaitProbeValue("the rerun task stopped", { stateContains("taskClosed", 2) }) { it }

            // A launch the extension's own provider cancels, after reading what was substituted, does
            // not start and is no error -- Oracle's Java extension starts one per batch of settings.
            assertEquals("false", command("probe.startCanceledDebug"))
            val state = command("probe.runtimeState").orEmpty()
            assertTrue(Regex("\"substituted\":\"[^\"$]+\"").containsMatchIn(state), "provider never saw the substituted program: $state")
            repeat(6) {
                assertTrue(frame.xx(BALLOON).list().none { it.hasSubtext("Error running") }, "an error shown for a canceled launch")
                Thread.sleep(500)
            }

            assertEquals("true", command("probe.startDebug"))
            // The variables view is not always the only debugger tree on screen; the rows of all of them
            // are read, and the first is what the debugger's actions are invoked from.
            val trees = frame.xx("//div[@class='XDebuggerTree']", JTreeUiComponent::class.java)
            val variables = frame.tree("(//div[@class='XDebuggerTree'])[1]")
            fun variableRows() = trees.list().flatMap { it.collectExpandedPathsAsStrings() }
            awaitProbeValue("the stopped session's variable in the client", ::variableRows) { rows ->
                rows.any { it.contains("probeValue") && it.contains("41") }
            }
            driver.act("StepOver", variables)
            awaitProbeValue("step over updating variables in the client", ::variableRows) { rows ->
                rows.any { it.contains("probeValue") && it.contains("42") }
            }
            awaitProbeValue("the adapter received step over", { stateContains("steps", 1) }) { it }
            driver.act("Resume", variables)
            awaitProbeValue("the adapter received resume", { stateContains("continues", 1) }) { it }
            driver.act("Pause", variables)
            awaitProbeValue("the adapter received pause", { stateContains("pauses", 1) }) { it }
            awaitProbeValue("variables shown again after pause", ::variableRows) { rows ->
                rows.any { it.contains("probeValue") && it.contains("42") }
            }
            driver.act("Stop", variables)
            awaitProbeValue("the adapter session stopped", { stateContains("debugStopped", 1) }) { it }
            Unit
        }
}

/**
 * Invokes action [id] from [ui], in the process that drew it: in split mode the Run console is the
 * backend's, the debugger's variables the client's, and an action asked for in the other one is refused.
 */
private fun Driver.act(id: String, ui: UiComponent) {
    val component = ui.component
    invokeAction(
        id,
        component = component,
        rdTarget = (component as? RefWrapper)?.getRef()?.rdTarget() ?: RdTarget.DEFAULT
    )
}
