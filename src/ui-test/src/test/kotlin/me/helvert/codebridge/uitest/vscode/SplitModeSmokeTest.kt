package me.helvert.codebridge.uitest.vscode

import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.driver.driver.remoteDev.RemoteDevBackgroundRun
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import me.helvert.codebridge.uitest.Installed
import me.helvert.codebridge.uitest.builtAgainst
import me.helvert.codebridge.uitest.install
import me.helvert.codebridge.uitest.runSplitIde
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * Does any of this survive a JetBrains Client?
 *
 * Every other suite runs a `CodeInsightTestFixture`, which is a single in-process IDE. There is no
 * client in it, so nothing in it can answer the one question split mode raises: the plugin's
 * language features are computed on a backend that the client does not load any of this code into,
 * and they reach the user only if the platform forwards them.
 *
 * That has already gone wrong here once. Routed through `intellij.platform.lsp`, everything worked
 * on the backend -- host started, server launched, all of it in `idea.log` -- and the client window
 * rendered nothing, because the module that draws it has no frontend half. The failure was silent
 * and looked like success from every log worth reading.
 *
 * This is the floor: a real backend and a real client, with the plugin installed in both, opening
 * a temporary project. It asserts only that the two processes came up and that the plugin is loaded on
 * each side, because that is where all the risk of the harness itself lives. Asserting on drawn
 * highlights, lookups and tool windows is worth adding *after* this holds.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeSmokeTest {

    @Test
    fun `the plugin loads in both halves of a split IDE`() {
        val project = Files.createTempDirectory("codebridge-split-smoke")
        project.resolve("README.txt").writeText("CodeBridge split-mode smoke test\n")

        val context = Starter.newContext(
            testName = "split-mode-smoke",
            testCase = builtAgainst(project),
        )
        // `pluginInstallationTarget = BOTH` in the plugin build is a claim about where the plugin goes.
        // Installing it into both sandboxes here is the same claim, made where it can be checked.
        (context as IDERemDevTestContext).install(Installed.CODEBRIDGE)

        val run = context.runSplitIde()
        try {
            val backend = (run as RemoteDevBackgroundRun).backendRun
            // The client is what the user looks at, so it is the half worth naming first.
            assertTrue(run.driver.isPluginLoaded(PLUGIN_ID), "The plugin is not loaded in the JetBrains Client")
            assertTrue(backend.driver.isPluginLoaded(PLUGIN_ID), "The plugin is not loaded in the IDE backend")
            for (driver in listOf(run.driver, backend.driver)) {
                assertTrue(driver.isPluginLoaded(RUNTIME_ID), "The runtime CodeBridge depends on is not loaded")
            }
            // Proves the two are actually joined: a frame only appears once the client has attached
            // to the backend's project.
            run.driver.ideFrame { }
        } finally {
            run.closeIdeAndWait()
        }
    }

    private companion object {
        const val PLUGIN_ID = "me.helvert.codebridge"
        const val RUNTIME_ID = "me.helvert.codebridge.runtime"
    }
}
