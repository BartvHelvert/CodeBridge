package me.helvert.codebridge.uitest.vscode

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.accessibleList
import com.intellij.driver.sdk.ui.components.elements.dialog
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The VS Code Extensions settings page, driven in the client as a user would: the installed
 * extension's Enable/Disable split button, its per-workspace entry, Uninstall, and the gear menu.
 *
 * What the backend made of each click is read from the backend itself, not from the page.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeStorefrontTest {
    @Test
    @Suppress("UNUSED_EXPRESSION") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `the installed extension is disabled per workspace, everywhere, and uninstalled from its details`() {
        val project = Files.createTempDirectory("codebridge-split-storefront")
        project.resolve("README.md").writeText("storefront\n")
        // A second workspace to switch to, as VS Code's "Open Workspace from File" would.
        project.resolve(WORKSPACE_FILE).writeText("""{"folders":[{"path":"."}]}""")
        val context = Starter.newContext(
            testName = "split-mode-storefront",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.CODEBRIDGE)
        // This config directory belongs only to the starter backend, never the developer's IDE.
        ExtensionStore(context.paths.configDir.resolve("codebridge/extensions")).install(requiredPath("codebridge.probe.vsix"))
        context.useShortClientSystemDir()

        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode())
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode, "The settings page must be driven through a JetBrains Client")
            val backendProject = awaitProbeValue("the backend project", {
                runCatching { driver.singleProject(RdTarget.BACKEND) }.getOrNull()?.takeIf { it.isInitialized() }
            }) { it != null }!!
            val extensions = driver.service(ProjectExtensionsRef::class, backendProject, RdTarget.BACKEND)
            val workspaces = driver.utility(WorkspaceChoiceRef::class, RdTarget.BACKEND)
            // Decided up front, so the suggestion to open the workspace file stays out of the way.
            workspaces.choose(backendProject, null)

            withExtensionsPage(driver) {
                // Under the gear that opened it, not under the search field beside it.
                val gear = x { and(byType(ACTION_BUTTON), byAccessibleName("Extension Settings")) }
                gear.click()
                val menu = menu(driver)
                awaitProbeValue("the gear menu", { menu.present() }) { it }
                val gearBounds = gear.screenBounds()
                val menuBounds = menu.screenBounds()
                assertTrue(
                    gearBounds.centerX.toInt() in menuBounds.x..(menuBounds.x + menuBounds.width) &&
                            menuBounds.y >= gearBounds.y + gearBounds.height - 4,
                    "The gear menu at $menuBounds is not under the gear at $gearBounds",
                )
                assertEquals(listOf("Install Extension from Disk…"), menu.rawItems)
                menu.keyboard { escape() }

                // The registry list pages on scroll and is never clipped to a few rows.
                assertFalse(x { byVisibleText("Show more") }.present(), "The page offers to show more")

                selectProbe()
                awaitProbeValue(
                    "the split button for an enabled extension",
                    { optionButton("Disable").present() }) { it }
                assertEquals(listOf("Disable (Workspace)", "Uninstall"), options(driver))
                chooseOption(driver, "Disable (Workspace)")
                awaitProbeValue("the probe disabled in this workspace", { extensions.isDisabled(PROBE_ID) }) { it }
                awaitProbeValue("the entry to lift that", { options(driver) }) {
                    it == listOf("Enable (Workspace)", "Uninstall")
                }
                closeMenu(driver)
            }

            // Another workspace has its own list, as in VS Code; the folder alone keeps its own.
            workspaces.choose(backendProject, WORKSPACE_FILE)
            assertFalse(extensions.isDisabled(PROBE_ID), "Disabled in the folder, it must run in $WORKSPACE_FILE")
            withExtensionsPage(driver) {
                selectProbe()
                awaitProbeValue("the probe enabled in the other workspace", { options(driver) }) {
                    it == listOf("Disable (Workspace)", "Uninstall")
                }
                closeMenu(driver)
            }
            workspaces.choose(backendProject, null)
            assertTrue(extensions.isDisabled(PROBE_ID), "Back in the folder alone, the probe must be disabled again")

            withExtensionsPage(driver) {
                selectProbe()
                chooseOption(driver, "Enable (Workspace)")
                awaitProbeValue(
                    "the probe running in this workspace again",
                    { extensions.isDisabled(PROBE_ID) }) { !it }

                // The main button switches it everywhere; there is nothing left to disable in a workspace.
                optionButton("Disable").click()
                awaitProbeValue(
                    "the split button for a disabled extension",
                    { optionButton("Enable").present() }) { it }
                awaitProbeValue("only Uninstall in the arrow", { options(driver) }) { it == listOf("Uninstall") }
                closeMenu(driver)
                optionButton("Enable").click()
                awaitProbeValue("the probe enabled everywhere again", { optionButton("Disable").present() }) { it }

                chooseOption(driver, "Uninstall")
                driver.ui.dialog(title = "Uninstall Extension") { pressButton("Yes") }
                awaitProbeValue("the probe gone from the page", { x { byVisibleText(PROBE_NAME) }.present() }) { !it }
                val empty = x { byVisibleText("No extensions installed yet.") }
                awaitProbeValue("the empty installed list", { empty.present() }) { it }
                Unit
            }
        } catch (error: Throwable) {
            failure = error
            // What the client showed when it failed, for a locator that no longer matches.
            runCatching {
                run.driver.ui.robotProvider.saveHierarchy(System.getProperty("codebridge.test.work"), "storefront-ui.html")
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
        frame.settingsDialog {
            openTreeSettingsSection("VS Code Extensions")
            awaitProbeValue("the installed section", { x { byVisibleText("Installed") }.present() }) { it }
            test()
            cancelButton.click()
        }
    }

    /** The installed probe's row: the first of its name on the page, before its details repeat it. */
    private fun SettingsDialogUiComponent.selectProbe() {
        awaitProbeValue("the probe's row", { x { byVisibleText(PROBE_NAME) }.present() }) { it }
        xx { byVisibleText(PROBE_NAME) }.list().first().click()
    }

    /**
     * The details' split button, by the text its main part has now. The button alone: its main part
     * carries the same name, and a click on the button's centre lands on that part anyway.
     */
    private fun UiComponent.optionButton(text: String): UiComponent =
        x { and(byJavaClass(OPTION_BUTTON), byAccessibleName(text)) }

    /**
     * Opens the split button's arrow and reads its entries, leaving the menu open. A menu already open
     * is closed first: its entries are fixed when it opens, and the arrow would only toggle it shut.
     */
    private fun SettingsDialogUiComponent.options(driver: Driver): List<String> {
        if (menu(driver).present()) closeMenu(driver)
        x { byType(ARROW_BUTTON) }.click()
        val menu = menu(driver)
        awaitProbeValue("the split button's menu", { menu.present() }) { it }
        // By the list's own entries: the shared renderer can report a truncated title.
        return menu.rawItems
    }

    /** Closes the menu [options] left open; Escape goes to the popup, which has the focus. */
    private fun closeMenu(driver: Driver) {
        val menu = menu(driver)
        menu.keyboard { escape() }
        awaitProbeValue("the menu closed", { menu.present() }) { !it }
    }

    private fun SettingsDialogUiComponent.chooseOption(driver: Driver, entry: String) {
        val entries = options(driver)
        check(entry in entries) { "'$entry' is not among $entries" }
        menu(driver).clickItem(entry)
    }

    /**
     * The open popup menu's list. By the list, not a `HeavyWeightWindow`: a popup that fits inside the
     * Settings dialog is lightweight, drawn in the dialog's own layered pane.
     */
    private fun menu(driver: Driver) = driver.ui.accessibleList { byClass("MyList") }

    private fun UiComponent.screenBounds(): java.awt.Rectangle =
        java.awt.Rectangle(getLocationOnScreen(), getBounds().size)

    @Remote("me.helvert.codebridge.extensions.ProjectExtensions", plugin = "me.helvert.codebridge.runtime/CodeBridge.backend")
    interface ProjectExtensionsRef {
        fun isDisabled(id: String): Boolean
    }

    @Remote("me.helvert.codebridge.workbench.CodeBridgeWorkspaceChoice", plugin = "me.helvert.codebridge.runtime/CodeBridge.backend")
    interface WorkspaceChoiceRef {
        fun choose(project: Project, name: String?)
    }

    private companion object {
        const val PROBE_ID = "codebridge.lsp-bundled-probe"

        /** The probe has no display name, so the page shows its name. */
        const val PROBE_NAME = "lsp-bundled-probe"
        const val WORKSPACE_FILE = "other.code-workspace"
        const val OPTION_BUTTON = "me.helvert.codebridge.extensions.ui.OptionButton"
        const val ARROW_BUTTON = $$"com.intellij.ui.components.BasicOptionButtonUI$ArrowButton"
        const val ACTION_BUTTON = "com.intellij.openapi.actionSystem.impl.ActionButton"
    }
}
