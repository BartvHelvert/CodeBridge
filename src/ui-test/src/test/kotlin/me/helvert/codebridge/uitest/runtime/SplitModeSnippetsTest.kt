package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
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
        // Completion is asked for once, explicitly. With autopopup on, the popups started while the
        // prefix was typed are restarted by that request within one session, and the lookup can keep
        // the first popup's item: Enter commits it for a request the backend has dropped, and the
        // backend skips the insert. See "A restarted split-mode lookup can keep an item the backend
        // has dropped" in docs/platform-limitations.md.
        Files.createDirectories(context.frontendIDEContext.paths.configDir.resolve("options")).resolve("editor.xml").writeText(
            """
            <application>
              <component name="CodeInsightSettings">
                <option name="AUTO_POPUP_COMPLETION_LOOKUP" value="false" />
              </component>
            </application>
            """.trimIndent()
        )

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
            // Completion runs on the backend, which gives up on a request whose typing it has not yet
            // received ("Editor did not reach the expected caret/document version"), and the lookup
            // closes with nothing asking again. On CI the extension host's startup once held the
            // backend that long, so the typing has to reach it first.
            awaitProbeValue("the typed prefix on the backend", {
                driver.withContext(OnDispatcher.EDT) {
                    driver.service(SplitModeWorkbenchTest.EditorFactoryRef::class, RdTarget.BACKEND).getAllEditors()
                        .map { it.getDocument().getText() }
                }
            }) { "${CONTENT}probeb" in it }
            driver.invokeAction("CodeCompletion", component = editor.component)
            // Found again each time: a lookup asked again is another list. The item
            // is drawn as its label split where it matched, so it is told by its raw text, and it is
            // the first -- the one selected, which Enter inserts.
            fun lookup(): List<String> = runCatching {
                frame.completionList().let { if (it.present()) it.rawItems else emptyList() }
            }.getOrDefault(emptyList())
            awaitProbeValue("the extension's snippet first in the lookup", ::lookup) { items ->
                items.firstOrNull()?.contains("probeblock") == true
            }

            // The placeholder is selected, so typing replaces it; Tab then goes to the body.
            editor.keyboard { key(KeyEvent.VK_ENTER) }
            awaitProbeValue("the snippet's placeholder selected", { editor.getSelection() }) { it == "name" }
            editor.keyboard { typeText("Widget") }
            // The template runs on the backend, which Tab has to reach after the typing: on CI a Tab
            // sent with it was taken as an indent, after the placeholder, with the template still on.
            awaitProbeValue("the typed placeholder on the backend", {
                driver.withContext(OnDispatcher.EDT) {
                    driver.service(SplitModeWorkbenchTest.EditorFactoryRef::class, RdTarget.BACKEND).getAllEditors()
                        .map { it.getDocument().getText() }
                }
            }) { documents -> documents.any { "PROBE Widget {" in it } }
            // Pressed on its own, and again if it never arrived: a key press is taken out of the
            // queue, and dropped, for as long as the IDE is waiting on a lock. Typing survives
            // that, so a Tab sent with the text left `body` where the placeholder ended.
            // See [pressUntil].
            pressUntil("the snippet's body stop reached", press = { editor.keyboard { key(KeyEvent.VK_TAB) } }) {
                editor.getCaretLine() == BODY_LINE
            }
            editor.keyboard { typeText("body") }
            awaitProbeValue("the snippet expanded and its stops typed into", { editor.text.lines() }) { lines ->
                lines.size >= 4 && lines[0] == "first" && lines[1] == "PROBE Widget {" &&
                        lines[2].isNotEmpty() && lines[2].trim() == "body" && lines[3] == "}"
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                run.closeSplitIdeAndWait()
            } catch (closeError: Throwable) {
                if (failure == null) throw closeError
                failure.addSuppressed(closeError)
            }
        }
    }

    private companion object {
        /** The snippet's body stop, between its braces: the line Tab moves the caret to. */
        const val BODY_LINE = 3

        const val CONTENT = "first\n"
    }
}
