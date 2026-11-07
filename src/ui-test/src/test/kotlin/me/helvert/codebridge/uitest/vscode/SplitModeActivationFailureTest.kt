package me.helvert.codebridge.uitest.vscode

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.uitest.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * An extension whose `activate()` throws is marked on the VS Code Extensions page, with the host's
 * reason in its details, as VS Code marks it in the Extensions view instead of notifying.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeActivationFailureTest {
    @Test
    @Suppress("UNUSED_EXPRESSION") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `a failed activation is shown on the extension's row and in its details`() {
        val project = Files.createTempDirectory("codebridge-split-activation-failure")
        project.resolve(FILE).writeText("fails\n")
        val context = Starter.newContext(
            testName = "split-mode-activation-failure",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.CODEBRIDGE)
        // This config directory belongs only to the starter backend, never the developer's IDE.
        ExtensionStore(context.paths.configDir.resolve("codebridge/extensions")).install(requiredPath("codebridge.failure.vsix"))
        context.useShortClientSystemDir()

        // Opening the file is what activates the probe, and so what makes it fail.
        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode(), open = FILE)
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode, "The settings page must be driven through a JetBrains Client")
            withExtensionsPage(driver) {
                // By the label's text: the row is too narrow for the name, so what it shows ends in "...".
                awaitProbeValue("the probe's row", { x { byText(PROBE_NAME) }.present() }) { it }
                // The row's metrics line, before the probe is chosen: the list says it on its own.
                awaitProbeValue("the row marked as failed", { x { byVisibleText("Activation failed") }.present() }) { it }
                xx { byText(PROBE_NAME) }.list().first().click()
                awaitProbeValue(
                    "the host's reason in the details",
                    { x { contains(byText(REASON)) }.present() }) { it }
                Unit
            }
        } catch (error: Throwable) {
            failure = error
            // What the client showed when it failed, for a locator that no longer matches.
            runCatching {
                run.driver.ui.robotProvider.saveHierarchy(System.getProperty("codebridge.test.work"), "activation-failure-ui.html")
            }.onFailure(error::addSuppressed)
            throw error
        } finally {
            try {
                run.closeIdeAndWait()
            } catch (closeError: Throwable) {
                if (failure == null) throw closeError
                failure.addSuppressed(closeError)
            }
        }
    }

    /** Opens Settings on the VS Code Extensions page, runs [test] in it, and closes it again. */
    private fun withExtensionsPage(driver: Driver, test: SettingsDialogUiComponent.() -> Unit) {
        val frame = driver.ideFrame { }
        driver.invokeAction("ShowSettings", component = frame.component)
        // Settings navigation polls a remote tree fixture; keep it alive across client GC.
        driver.withContext {
            frame.settingsDialog {
                openTreeSettingsSection("VS Code Extensions")
                awaitProbeValue("the installed section", { x { byVisibleText("Installed") }.present() }) { it }
                test()
                cancelButton.click()
            }
        }
    }

    private companion object {
        const val FILE = "example.codebridgefailing"
        const val PROBE_NAME = "CodeBridge Activation Failure Probe"

        /** What the probe's `activate()` throws. */
        const val REASON = "The failure probe refuses to activate"
    }
}
