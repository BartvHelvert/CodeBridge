package me.helvert.codebridge.uitest.bundled

import com.intellij.driver.client.Remote
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.uitest.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An extension another plugin bundles runs as an installed one does, with nothing in the store.
 *
 * The bundling plugin is `src/test-extensions/bundling-plugin`: a descriptor and the LSP probe, no
 * classes, the shape a vendor would publish their extension to the JetBrains Marketplace in. It is
 * installed with the runtime and CodeBridge Bundled Extensions, as a user who never chose CodeBridge has them, so the project's
 * `.vscode/` -- a teammate's, say -- must not change what the IDE does.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeBundledExtensionTest {
    @Test
    fun `extension bundled by another plugin serves the client`() {
        val project = Files.createTempDirectory("codebridge-split-bundled")
        val file = project.resolve("example.codebridgebundled")
        file.writeText("ERROR original buffer\n")
        // CodeBridge would make this a run configuration; the runtime and Bundled Extensions must not.
        Files.createDirectories(project.resolve(".vscode")).resolve("tasks.json").writeText(
            """{"version":"2.0.0","tasks":[{"label":"$TEAMMATE_TASK","type":"shell","command":"echo"}]}"""
        )
        val context = Starter.newContext(
            testName = "split-mode-bundled-extension",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.BUNDLED_EXTENSIONS)
        // The backend alone: the extension runs where the project is, and the bundling plugin has
        // nothing for a client to load.
        context.pluginConfigurator.installPluginFromDir(requiredPath("codebridge.bundling.plugin"))
        val clientSystem = Files.createTempDirectory(
            Files.createDirectories(Path.of(System.getProperty("codebridge.test.work"))), "c",
        )
        context.frontendIDEContext.applyVMOptionsPatch { addSystemProperty("idea.system.path", clientSystem) }

        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = file.fileName.toString(),
        )
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode)
            val editor = driver.ideFrame { }.codeEditor()
            awaitValue("the bundled extension's diagnostic in the client", { diagnosticCount(editor) }) { it > 0 }
            assertFalse(
                Files.exists(context.paths.configDir.resolve("codebridge/extensions/extensions.json")),
                "The extension must come from the bundling plugin, not the extension store",
            )
            // By now the project's startup activities, which mirror tasks.json in CodeBridge, have run.
            val runManager = driver.service(RunManagerRef::class, driver.singleProject(RdTarget.BACKEND), RdTarget.BACKEND)
            assertNull(runManager.findConfigurationByName(TEAMMATE_TASK), "Without CodeBridge, .vscode/tasks.json must not be read")

            // It is removed with its plugin, not here; disabling it for the workspace still is.
            val installations = driver.service(InstallationsRef::class, RdTarget.BACKEND)
            assertTrue(runCatching { installations.uninstall(ID) }.isFailure, "A bundled extension cannot be uninstalled")
            val extensions = driver.service(
                ProjectExtensionsRef::class, driver.singleProject(RdTarget.BACKEND), RdTarget.BACKEND,
            )
            assertTrue(extensions.setDisabled(ID, true))
            awaitValue("diagnostics withdrawn with the workspace-disabled extension", { diagnosticCount(editor) }) { it == 0 }
            assertTrue(extensions.setDisabled(ID, false))
            awaitValue("diagnostics restored by the replacement host", { diagnosticCount(editor) }) { it > 0 }
        } catch (error: Throwable) {
            failure = error
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

    @Remote("me.helvert.codebridge.extensions.ExtensionInstallationService", plugin = "me.helvert.codebridge.runtime/CodeBridge.backend")
    interface InstallationsRef {
        fun uninstall(id: String): Boolean
    }

    @Remote("com.intellij.execution.RunManager")
    interface RunManagerRef {
        fun findConfigurationByName(name: String): RunnerAndConfigurationSettingsRef?
    }

    @Remote("com.intellij.execution.RunnerAndConfigurationSettings")
    interface RunnerAndConfigurationSettingsRef

    @Remote("me.helvert.codebridge.extensions.ProjectExtensions", plugin = "me.helvert.codebridge.runtime/CodeBridge.backend")
    interface ProjectExtensionsRef {
        fun setDisabled(id: String, disabled: Boolean): Boolean
    }

    private fun diagnosticCount(editor: JEditorUiComponent): Int =
        editor.getAllHighlights()
            .count { it.getDescription()?.contains("Replace ERROR to clear this diagnostic") == true }

    private fun <T> awaitValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var last: T? = null
        while (System.nanoTime() < deadline) {
            last = read()
            if (matches(last)) return last
            Thread.sleep(300)
        }
        error("Timed out waiting for $description; last observed: $last")
    }

    private companion object {
        const val ID = "codebridge.lsp-bundled-probe"
        const val TEAMMATE_TASK = "teammate's build"
    }
}
