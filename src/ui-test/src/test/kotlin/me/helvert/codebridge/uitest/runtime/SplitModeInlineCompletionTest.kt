package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent

/**
 * An extension's inline completion as the user meets it in a JetBrains Client: typing asks the
 * backend's provider, the client draws its ghost text, Tab takes it, and the extension hears it was
 * taken through the item's command.
 *
 * Driven by the workbench probe's inline completion provider on `.codebridgeactions` files (`editor-probe.js`).
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeInlineCompletionTest {

    @Test
    fun `typing shows the extension's ghost text, and Tab takes it and runs its command`() =
        // Traced: on CI the ghost text has been missing once, after the bracket the editor closed,
        // with nothing in either log to say which session dropped it.
        withWorkbenchProbe("split-mode-inline-completion", CONTENT, traced = "#com.intellij.codeInsight.inline.completion") {
            val frame = driver.ideFrame { }
            val editor = frame.codeEditor()
            awaitProbeValue("the probe file in the client", { editor.text }) { it == CONTENT }

            editor.goToPosition(1, 6)
            editor.keyboard { typeText("(") }
            awaitProbeValue("the ghost text drawn in the client", {
                driver.withContext(OnDispatcher.EDT) { editor.inlaysOnLine(0).size }
            }) { it > 0 }
            // Typing asks as VS Code does when the user types: `InlineCompletionTriggerKind.Automatic`, 1.
            awaitProbeValue("the provider asked automatically", { command("probe.inlineState") }) {
                it != null && """"asked":[1""" in it
            }

            editor.keyboard { key(KeyEvent.VK_TAB) }
            awaitProbeValue("the proposal inserted", { editor.getLineText(1) }) { it == ACCEPTED }
            awaitProbeValue("the item's command run in the extension", { command("probe.inlineState") }) {
                it != null && """"accepted":["world"]""" in it
            }
            awaitProbeValue<Int>("no ghost text left", {
                driver.withContext(OnDispatcher.EDT) { editor.inlaysOnLine(0).size }
            }) { it == 0 }
        }

    private companion object {
        const val CONTENT = "greet\nend\n"
        const val ACCEPTED = "greet(\"world\")"
    }
}
