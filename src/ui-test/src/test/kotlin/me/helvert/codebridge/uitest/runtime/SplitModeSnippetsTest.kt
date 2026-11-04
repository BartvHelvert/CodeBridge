package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.editor.completionList
import com.intellij.driver.sdk.ui.components.common.ideFrame
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
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * A snippet an extension contributes, completed in a JetBrains Client as VS Code completes it: offered
 * by its prefix, inserted with its placeholder selected, and Tab on to where its body goes.
 *
 * The TextMate probe is declarative -- no host ever starts -- as most snippet packs are.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeSnippetsTest {

    @Test
    fun `a contributed snippet completes and its stops are walked in the client`() {
        val project = Files.createTempDirectory("codebridge-split-snippets")
        project.resolve("example.codebridgegrammar").writeText(CONTENT)
        val context = Starter.newContext(
            testName = "split-mode-snippets",
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.RUNTIME)
        ExtensionStore(context.paths.configDir.resolve("codebridge/extensions"))
            .install(requiredPath("codebridge.textmate.vsix"))
        context.useShortClientSystemDir()

        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode(), open = "example.codebridgegrammar")
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode)
            val frame = driver.ideFrame { }
            val editor = frame.codeEditor()
            awaitProbeValue("the file in the client", { editor.text }) { it == CONTENT }

            editor.goToPosition(2, 1)
            editor.keyboard { typeText("probeb") }
            driver.invokeAction("CodeCompletion", component = editor.component)
            // Found again each time: a lookup asked again as more is typed is another list. The item
            // is drawn as its label split where it matched, so it is told by its raw text, and it is
            // the first -- the one selected, which Enter inserts.
            fun lookup(): List<String> = runCatching {
                frame.completionList().let { if (it.present()) it.rawItems else emptyList() }
            }.getOrDefault(emptyList())
            awaitProbeValue("the extension's snippet first in the lookup", ::lookup) { items ->
                items.firstOrNull()?.contains("probeblock") == true
            }

            // The placeholder is selected, so typing replaces it; Tab then goes to the body.
            editor.keyboard {
                key(KeyEvent.VK_ENTER)
                typeText("Widget")
                key(KeyEvent.VK_TAB)
                typeText("body")
            }
            awaitProbeValue("the snippet expanded and its stops typed into", { editor.text.lines() }) { lines ->
                lines.size >= 4 && lines[0] == "first" && lines[1] == "PROBE Widget {" &&
                        lines[2].isNotEmpty() && lines[2].trim() == "body" && lines[3] == "}"
            }
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

    private companion object {
        const val CONTENT = "first\n"
    }
}
