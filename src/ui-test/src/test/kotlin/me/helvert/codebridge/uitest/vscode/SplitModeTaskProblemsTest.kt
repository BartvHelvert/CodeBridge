package me.helvert.codebridge.uitest.vscode

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.uitest.Installed
import me.helvert.codebridge.uitest.builtAgainst
import me.helvert.codebridge.uitest.install
import me.helvert.codebridge.uitest.runSplitIde
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * A `tasks.json` task's problem matcher, end to end and with no extension host: the task runs on the
 * backend, its output names a problem in the file the client has open, and the client draws it.
 *
 * CodeBridge's, not the runtime's: only with it does the project's `.vscode/tasks.json` become run
 * configurations. Drawing the problem is the runtime's, and `SplitModeDrawingTest` covers that alone.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeTaskProblemsTest {
    @Test
    fun `a problem a task's output names is drawn in the client`() {
        val project = Files.createTempDirectory("codebridge-split-task-problems")
        project.resolve("example.txt").writeText("ERROR replace this word\nsecond line")
        project.resolve(".vscode").createDirectories()
        project.resolve(".vscode/tasks.json").writeText(
            """{"version":"2.0.0","tasks":[{"label":"build","type":"shell","command":"echo example.txt:1:1: $TASK_MESSAGE",""" +
                    """"problemMatcher":{"owner":"cc","source":"cc","pattern":{"regexp":"^(.*):(\\d+):(\\d+): (.*)$","message":4}}}]}"""
        )

        val context = Starter.newContext(
            testName = "split-mode-task-problems",
            testCase = builtAgainst(project),
        )
        (context as IDERemDevTestContext).install(Installed.CODEBRIDGE)

        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = "example.txt",
        )
        try {
            val driver = run.driver
            val opened = backendProject(driver)
            val runManager = driver.service(RunManagerRef::class, opened, RdTarget.BACKEND)
            // The configurations follow tasks.json once the project has opened, which may be after the driver connects.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var build: RunnerAndConfigurationSettingsRef? = null
            while (System.nanoTime() < deadline) {
                build = runManager.findConfigurationByName("build")
                if (build != null) break
                Thread.sleep(500)
            }
            requireNotNull(build) { "tasks.json's 'build' never became a run configuration" }
            driver.withContext(OnDispatcher.EDT) {
                val executor = driver.utility(DefaultRunExecutorRef::class, RdTarget.BACKEND).getRunExecutorInstance()
                driver.utility(ProgramRunnerUtilRef::class, RdTarget.BACKEND).executeConfiguration(build, executor)
            }

            val drawn = await(driver) { it.contains(TASK_MESSAGE) }
            assertTrue(drawn.isNotEmpty(), "The client drew no highlight carrying '$TASK_MESSAGE'")
        } finally {
            run.closeIdeAndWait()
        }
    }

    @Remote("com.intellij.execution.RunManager")
    interface RunManagerRef {
        fun findConfigurationByName(name: String): RunnerAndConfigurationSettingsRef?
    }

    @Remote("com.intellij.execution.RunnerAndConfigurationSettings")
    interface RunnerAndConfigurationSettingsRef

    @Remote("com.intellij.execution.Executor")
    interface ExecutorRef

    @Remote("com.intellij.execution.executors.DefaultRunExecutor")
    interface DefaultRunExecutorRef {
        fun getRunExecutorInstance(): ExecutorRef
    }

    @Remote("com.intellij.execution.ProgramRunnerUtil")
    interface ProgramRunnerUtilRef {
        fun executeConfiguration(configuration: RunnerAndConfigurationSettingsRef, executor: ExecutorRef)
    }

    /** The project, once the backend has opened it; see `SplitModeDrawingTest.backendProject`. */
    private fun backendProject(driver: Driver): Project {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            runCatching { driver.singleProject(RdTarget.BACKEND) }
                .getOrNull()?.takeIf { it.isInitialized() }?.let { return it }
            Thread.sleep(500)
        }
        error("The backend never opened a project")
    }

    /** The highlight descriptions the client's editor holds, once one of them matches. */
    private fun await(driver: Driver, matches: (String) -> Boolean): List<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var seen = emptyList<String>()
        while (System.nanoTime() < deadline) {
            seen = driver.ideFrame { }.codeEditor().getAllHighlights().mapNotNull { it.getDescription() }
            if (seen.any(matches)) return seen
            Thread.sleep(500)
        }
        return seen.filter(matches)
    }

    private companion object {
        const val TASK_MESSAGE = "the build found a problem here"
    }
}
