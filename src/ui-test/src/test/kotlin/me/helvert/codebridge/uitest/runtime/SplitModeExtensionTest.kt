package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.editor.completionList
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.popups.documentationHintEditorPane
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.uitest.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A real installed extension, child server, backend and client in one workflow. */
@ExtendWith(RemoteDevRun::class)
class SplitModeExtensionTest {
    @Test
    fun `installed extension serves the client and follows unsaved edits`() {
        val project = Files.createTempDirectory("codebridge-split-extension")
        val file = project.resolve("example.codebridgebundled")
        file.writeText(INITIAL)
        val context = Starter.newContext(
            testName = "split-mode-extension",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.RUNTIME)
        // The runtime also implements VS Code's delayed autosave, independently of the IDE's.
        context.writeUserSettings("""{"files.autoSave":"off"}""")
        // Install before opening the file so detection and onLanguage activation are both exercised.
        // This config directory belongs only to the starter backend, never the developer's IDE.
        val installed = ExtensionStore(context.paths.configDir.resolve("codebridge/extensions"))
            .install(requiredPath("codebridge.probe.vsix"))
        assertTrue(installed.extension.enabled)
        context.disableAutoCompletion()
        context.frontendIDEContext.disableAutoCompletion()
        context.useShortClientSystemDir()
        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = file.fileName.toString(),
        )
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode, "This workflow must run through a JetBrains Client")
            driver.withContext(OnDispatcher.EDT) {
                for (target in listOf(RdTarget.BACKEND, RdTarget.FRONTEND)) {
                    driver.service(GeneralSettingsRef::class, target).apply {
                        setSaveOnFrameDeactivation(false)
                        setAutoSaveIfInactive(false)
                    }
                }
            }
            val editor = driver.ideFrame { }.codeEditor()
            awaitValue("extension diagnostic in the client", { diagnosticCount(editor) }) { it > 0 }
            val initialVersion = documentation(driver, editor, "ERROR original buffer")
            completion(driver, editor, initialVersion)
            definition(driver, editor, initialVersion)

            // One keystroke gives undo a single edit, independent of how typing groups newlines.
            editor.goToPosition(1, 1)
            editor.keyboard { key(KeyEvent.VK_DELETE) }
            awaitValue("the first character deleted", { editor.text }) { it == INITIAL.drop(1) }
            awaitValue("the diagnostic cleared by Delete", { diagnosticCount(editor) }) { it == 0 }
            val deletedVersion = documentation(driver, editor, "RROR original buffer")
            driver.invokeAction($$"$Undo", component = editor.component)
            awaitValue("the original buffer after undo", { editor.text }) { it == INITIAL }
            awaitValue("the diagnostic restored after undo", { diagnosticCount(editor) }) { it > 0 }
            val undoVersion = documentation(driver, editor, "ERROR original buffer")
            assertTrue(undoVersion > deletedVersion, "Undo must create a new document version")
            driver.invokeAction($$"$Redo", component = editor.component)
            awaitValue("the character deleted again after redo", { editor.text }) { it == INITIAL.drop(1) }
            awaitValue("the diagnostic cleared after redo", { diagnosticCount(editor) }) { it == 0 }
            val redoVersion = documentation(driver, editor, "RROR original buffer")
            assertTrue(redoVersion > undoVersion, "Redo must create a new document version")

            // Type through the client's editor. No backend service or diagnostic payload is injected.
            editor.goToPosition(1, 1)
            driver.invokeAction($$"$SelectAll", component = editor.component)
            editor.keyboard {
                typeText(UPDATED)
            }
            awaitValue("edited text in the client", { editor.text }) { it == UPDATED }
            awaitValue("cleared diagnostic in the client", { diagnosticCount(editor) }) { it == 0 }
            val editedVersion = documentation(driver, editor, "updated buffer")
            assertTrue(editedVersion > initialVersion, "The extension still sees document version $initialVersion")
            completion(driver, editor, editedVersion)
            definition(driver, editor, editedVersion)
            assertEquals(INITIAL, file.readText(), "The checks must run against an unsaved buffer")

            acceptCompletion(driver, editor, "version-$editedVersion")
            val completed = "updated buffer\nversion-$editedVersion\n"
            awaitValue("accepted completion text in the client", { editor.text }) { it == completed }
            val completedVersion = documentation(driver, editor, "updated buffer")
            assertTrue(completedVersion > editedVersion, "The accepted completion must reach the server")

            // A live template crosses the split; checking only its label misses broken placeholders.
            acceptCompletion(driver, editor, "wrap", line = 3)
            awaitValue("the snippet's default placeholder selected", { editor.getSelection() }) { it == "value" }
            editor.keyboard { typeText("argument") }
            awaitValue("the edited snippet in the client", { editor.text }) {
                it == "${completed}wrap(argument)"
            }
            editor.keyboard { key(KeyEvent.VK_TAB) }
            awaitValue("the caret after the snippet's final stop", {
                editor.getCaretLine() to editor.getCaretColumn()
            }) { it == (3 to 15) }
            val snippetVersion = documentation(driver, editor, "wrap(argument)", line = 3)
            assertTrue(snippetVersion > completedVersion, "The edited snippet must reach the server")
            assertEquals(INITIAL, file.readText(), "Completion and snippet insertion must remain unsaved")

            val saved = editor.text
            driver.invokeAction("SaveAll", component = editor.component)
            awaitValue("the completed buffer saved to disk", { file.readText() }) { it == saved }
            driver.invokeAction("CloseContent", component = editor.component)
            awaitValue("the client editor closed", { editor.present() }) { !it }
            driver.invokeAction("ReopenClosedTab")
            val reopened = driver.ideFrame { }.codeEditor()
            awaitValue("the saved buffer reopened in the client", { if (reopened.present()) reopened.text else null }) {
                it == saved
            }
            documentation(driver, reopened, "wrap(argument)", line = 3)
            awaitValue("no diagnostic after reopening the clean buffer", { diagnosticCount(reopened) }) { it == 0 }
            reopened.goToPosition(1, 1)
            reopened.keyboard { typeText("ERROR ") }
            awaitValue("diagnostics following edits after reopening", { diagnosticCount(reopened) }) { it > 0 }
            documentation(driver, reopened, "ERROR updated buffer")
            assertEquals(saved, file.readText(), "Edits after reopening must still be served from the unsaved buffer")

            // Changing project enablement restarts the host. Its replacement must receive the open,
            // unsaved document, and providers withdrawn during shutdown must register again.
            val beforeRestart = documentationText(driver, reopened, "ERROR updated buffer")
            val oldPid = Regex("server PID (\\d+)").find(beforeRestart)!!.groupValues[1]
            val extensions = driver.service(
                ProjectExtensionsRef::class, driver.singleProject(RdTarget.BACKEND), RdTarget.BACKEND,
            )
            assertTrue(extensions.setDisabled("codebridge.lsp-bundled-probe", true))
            awaitValue("diagnostics withdrawn with the disabled extension", { diagnosticCount(reopened) }) { it == 0 }
            assertTrue(extensions.setDisabled("codebridge.lsp-bundled-probe", false))
            awaitValue("diagnostics restored by the replacement host", { diagnosticCount(reopened) }) { it > 0 }
            documentationText(driver, reopened, "ERROR updated buffer", previousPid = oldPid)
            val recoveredVersion = documentation(driver, reopened, "ERROR updated buffer")
            // The second line already has text: use a blank prefix on a fresh line for the lookup.
            reopened.goToPosition(3, 15)
            reopened.keyboard { key(KeyEvent.VK_ENTER) }
            val afterRestartVersion = documentation(driver, reopened, "ERROR updated buffer")
            assertTrue(afterRestartVersion > recoveredVersion, "Edits must synchronize after host recovery")
            completion(driver, reopened, afterRestartVersion, line = 4)
            assertEquals(saved, file.readText(), "Host recovery must preserve unsaved edits")

            driver.invokeAction("ActivateCodeBridgeOutputToolWindow")
            awaitValue("extension output tab in the client", {
                driver.ideFrame { }.x("//div[@text='LSP bundled probe' or @accessiblename='LSP bundled probe']")
                    .present()
            }) { it }
        } catch (error: Throwable) {
            failure = error
            val hostRoot = context.paths.systemDir.resolve("codebridge/hosts")
            if (Files.isDirectory(hostRoot)) {
                // The client can only report missing features. Include the host's explanation in
                // the test report when startup, activation or its child language server failed.
                runCatching {
                    Files.walk(hostRoot).use { paths ->
                        paths.filter { it.fileName.toString() == "exthost-stdout.log" }.forEach {
                            println("Extension host output ($it):\n${it.readText().takeLast(8_000)}")
                        }
                    }
                }.onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            try {
                run.closeIdeAndWait()
            } catch (closeError: Throwable) {
                // Keep the failed UI assertion as the primary error if IDE shutdown also fails.
                if (failure == null) throw closeError
                failure.addSuppressed(closeError)
            }
        }
    }

    @Remote("com.intellij.ide.GeneralSettings")
    interface GeneralSettingsRef {
        fun setSaveOnFrameDeactivation(value: Boolean)
        fun setAutoSaveIfInactive(value: Boolean)
    }

    @Remote("me.helvert.codebridge.extensions.ProjectExtensions", plugin = "me.helvert.codebridge.runtime/CodeBridge.backend")
    interface ProjectExtensionsRef {
        fun setDisabled(id: String, disabled: Boolean): Boolean
    }

    private fun documentation(driver: Driver, editor: JEditorUiComponent, expectedLine: String, line: Int = 1): Int {
        val text = documentationText(driver, editor, expectedLine, line)
        return Regex("version (\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            ?: error("No document version in documentation: $text")
    }

    private fun documentationText(
        driver: Driver, editor: JEditorUiComponent, expectedLine: String, line: Int = 1, previousPid: String? = null,
    ): String {
        editor.goToPosition(line, 2)
        driver.invokeAction("QuickJavaDoc", component = editor.component)
        val text = awaitValue("documentation containing '$expectedLine'", {
            val pane = driver.ideFrame { }.documentationHintEditorPane()
            if (pane.present()) pane.getText().replace("&nbsp;", " ").replace("&#160;", " ") else ""
        }) {
            it.contains(expectedLine) && it.contains("server PID") &&
                    (previousPid == null || !it.contains("server PID $previousPid;"))
        }
        editor.keyboard { key(KeyEvent.VK_ESCAPE) }
        return text
    }

    private fun completion(driver: Driver, editor: JEditorUiComponent, version: Int, line: Int = 2) {
        editor.goToPosition(line, 1) // Empty prefix, so both extension items are offered.
        driver.invokeAction("CodeCompletion", component = editor.component)
        awaitValue("completion for document version $version", {
            val list = driver.ideFrame { }.completionList()
            if (list.present()) list.items else emptyList()
        }) { items -> items.any { it.contains("version-$version") } && items.any { it.contains("wrap") } }
        editor.keyboard { key(KeyEvent.VK_ESCAPE) }
    }

    private fun acceptCompletion(driver: Driver, editor: JEditorUiComponent, label: String, line: Int = 2) {
        editor.goToPosition(line, 1)
        driver.invokeAction("CodeCompletion", component = editor.component)
        // Pin the list's remote fixture from the first read until the item is selected.
        driver.withContext {
            val list = ideFrame { }.completionList()
            awaitValue("the '$label' completion", {
                if (list.present()) list.items else emptyList()
            }) { items -> items.any { it.contains(label) } }
            list.selectItem(label, fullMatch = false)
        }
        editor.keyboard { key(KeyEvent.VK_ENTER) }
    }

    private fun definition(driver: Driver, editor: JEditorUiComponent, version: Int) {
        // The fixture's LocationLink selects the column given by its synchronized document version.
        // Typing can advance the version past the line's length; navigation clamps to its end.
        val lineLength = editor.text.lineSequence().first().length
        val targetColumn = minOf(version, lineLength) + 1
        // Start elsewhere so an action that does nothing cannot pass this assertion.
        editor.goToPosition(1, if (targetColumn == 1) lineLength else 1)
        driver.invokeAction("GotoDeclaration", component = editor.component)
        awaitValue("the definition's selection range in the client for version $version", {
            editor.getCaretLine() to editor.getCaretColumn()
        }) { it == (1 to targetColumn) }
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
        const val INITIAL = "ERROR original buffer\n\n"
        const val UPDATED = "updated buffer\n\n"
    }
}
