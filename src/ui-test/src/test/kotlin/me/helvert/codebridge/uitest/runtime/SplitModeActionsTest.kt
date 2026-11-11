package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.editor.intentionList
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent

@ExtendWith(RemoteDevRun::class)
class SplitModeActionsTest {
    @Test
    @Suppress("UnusedExpression") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `rename formatting and quick fixes from an extension edit the client document`() =
        withWorkbenchProbe("split-mode-actions", "let value=1;\nvalue;\nERROR\n") {
            val frame = driver.ideFrame { }
            val editor = frame.codeEditor()
            awaitProbeValue("the probe file in the client", { editor.text }) { it == "let value=1;\nvalue;\nERROR\n" }

            editor.goToPosition(1, 6)
            driver.invokeAction("RenameElement", component = editor.component)
            awaitProbeValue("the rename placeholder selected", { editor.getSelection() }) { it == "value" }
            editor.keyboard { typeText("renamed") }
            // The rename template runs on the backend, which Enter has to reach after the typing,
            // as the snippet test's Tab does. An Enter that arrives first finds no template to
            // finish and is taken as a plain newline: `let renamed`, `=1;`, and the other
            // occurrence left alone.
            awaitProbeValue("the typed name on the backend", {
                driver.withContext(OnDispatcher.EDT) {
                    driver.service(SplitModeWorkbenchTest.EditorFactoryRef::class, RdTarget.BACKEND).getAllEditors()
                        .map { it.getDocument().getText() }
                }
            }) { documents -> documents.any { "let renamed=1;" in it } }
            editor.keyboard { key(KeyEvent.VK_ENTER) }
            awaitProbeValue(
                "all occurrences renamed in the client",
                { editor.text }) { it == "let renamed=1;\nrenamed;\nERROR\n" }

            driver.invokeAction("ReformatCode", component = editor.component)
            awaitProbeValue("the extension formatter's edits in the client", { editor.text }) {
                it == "let renamed = 1;\nrenamed;\nERROR\n"
            }

            editor.goToPosition(3, 2)
            driver.invokeAction("ShowIntentionActions", component = editor.component)
            // Pin the remote list fixture while the intention popup is refreshed asynchronously.
            driver.withContext {
                val intentions = frame.intentionList()
                // By the list's own entries, not their rendered text: the renderer is shared between
                // rows, and read outside painting it keeps the width it last had, so a long title
                // comes back as "Probe: replace ERR..." though the row shows it whole.
                val entries = awaitProbeValue("the extension quick fix in the intention menu", {
                    if (intentions.present()) intentions.rawItems else emptyList()
                }) { it.contains(QUICK_FIX) }
                intentions.clickItemAtIndex(entries.indexOf(QUICK_FIX))
            }
            awaitProbeValue("the chosen quick fix applied in the client", { editor.text }) {
                it == "let renamed = 1;\nrenamed;\nOK\n"
            }
            Unit
        }

    private companion object {
        const val QUICK_FIX = "Probe: replace ERROR with OK"
    }
}
