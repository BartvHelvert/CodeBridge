package me.helvert.codebridge.uitest.vscode

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.uitest.Installed
import me.helvert.codebridge.uitest.awaitProbeValue
import me.helvert.codebridge.uitest.builtAgainst
import me.helvert.codebridge.uitest.closeSplitIdeAndWait
import me.helvert.codebridge.uitest.install
import me.helvert.codebridge.uitest.runSplitIde
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A `launch.json` entry that attaches has no Run button in the client's toolbar, only Debug: without
 * debugging, attaching does nothing. One that launches has both.
 *
 * CodeBridge's, not the runtime's: only with it does the project's `.vscode/launch.json` become run
 * configurations.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeAttachRunTest {
    @Test
    fun `the toolbar hides Run for a launch json entry that attaches`() {
        val project = Files.createTempDirectory("codebridge-split-attach-run")
        project.resolve("example.txt").writeText("text")
        project.resolve(".vscode").createDirectories()
        project.resolve(".vscode/launch.json").writeText(
            """{"version":"0.2.0","configurations":[""" +
                    """{"name":"$LAUNCH","type":"node","request":"launch","program":"x.js"},""" +
                    """{"name":"$ATTACH","type":"node","request":"attach","port":9229}]}"""
        )

        val context = Starter.newContext(
            testName = "split-mode-attach-run",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.CODEBRIDGE)

        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode(), open = "example.txt")
        try {
            val driver = run.driver
            backendProject(driver)
            // The client's: one selected on the backend is not shown in the client's toolbar, while the client's
            // selection goes to the backend, as the toolbar's own does.
            val runManager = driver.service(RunManagerRef::class, driver.singleProject(RdTarget.FRONTEND), RdTarget.FRONTEND)
            // The configurations follow launch.json once the project has opened, which may be after the driver connects,
            // and reach the client after that.
            val launch = awaitProbeValue("'$LAUNCH' as a run configuration", { runManager.findConfigurationByName(LAUNCH) }) { it != null }!!
            val attach = awaitProbeValue("'$ATTACH' as a run configuration", { runManager.findConfigurationByName(ATTACH) }) { it != null }!!

            val frame = driver.ideFrame { }
            fun showing(icon: String) = runCatching {
                driver.withContext { frame.xx("//div[@myicon='$icon']").list().any { it.component.isShowing() } }
            }.getOrDefault(false)
            fun select(settings: RunnerAndConfigurationSettingsRef) =
                driver.withContext(OnDispatcher.EDT) { runManager.setSelectedConfiguration(settings) }

            select(launch)
            awaitProbeValue("Run and Debug for '$LAUNCH'", { showing("run.svg") to showing("debug.svg") }) { it == (true to true) }
            select(attach)
            awaitProbeValue("only Debug for '$ATTACH'", { showing("run.svg") to showing("debug.svg") }) { it == (false to true) }
            select(launch)
            awaitProbeValue("Run back for '$LAUNCH'", { showing("run.svg") }) { it }
        } finally {
            run.closeSplitIdeAndWait()
        }
    }

    @Remote("com.intellij.execution.RunManager")
    interface RunManagerRef {
        fun findConfigurationByName(name: String): RunnerAndConfigurationSettingsRef?
        fun setSelectedConfiguration(settings: RunnerAndConfigurationSettingsRef?)
    }

    @Remote("com.intellij.execution.RunnerAndConfigurationSettings")
    interface RunnerAndConfigurationSettingsRef

    /** The project, once the backend has opened it; see `SplitModeDrawingTest.backendProject`. */
    private fun backendProject(driver: Driver): Project =
        awaitProbeValue("the backend project", {
            runCatching { driver.singleProject(RdTarget.BACKEND) }.getOrNull()?.takeIf { it.isInitialized() }
        }) { it != null }!!

    private companion object {
        const val LAUNCH = "Launch here"
        const val ATTACH = "Attach here"
    }
}
