package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.*
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import kotlin.test.assertEquals

/**
 * `vscode.window`'s questions, answered by a user in the JetBrains Client.
 *
 * Each is drawn by the frontend from a backend stream, and the answer goes back to an extension
 * waiting on a promise. The probe (`window-probe.js`) records each answer, so every check here is
 * on what the extension received, not just on what the client drew.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeWindowTest {
    @Test
    @Suppress("UnusedExpression") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `status items, messages, pickers and input boxes answer the extension from the client`() =
        withWorkbenchProbe("split-mode-window", "let value = 1;\n") {
            val frame = driver.ideFrame { }
            fun answered(key: String, json: String) = awaitProbeValue("the extension to receive $key = $json", {
                command("probe.windowState")
            }) { it?.contains("\"$key\":$json") == true }

            // The item is created on activation, its icon reference drawn as an icon rather than text.
            fun statusItem(text: String) = frame.x("//div[@visible_text='$text']")
            awaitProbeValue("the extension's status bar item", { statusItem("Probe status 0").present() }) { it }
            statusItem("Probe status 0").click()
            answered("statusClicks", "1")
            awaitProbeValue(
                "the item's new text after its command ran",
                { statusItem("Probe status 1").present() }) { it }
            assertEquals("true", command("probe.hideStatus"))
            awaitProbeValue("the hidden item removed", { statusItem("Probe status 1").present() }) { !it }

            // A message with items is a balloon with a button per item; the one clicked is the answer.
            assertEquals("true", command("probe.message"))
            // The IDE's own notifications may be showing too.
            val balloon = awaitProbeValue("the message balloon", {
                frame.xx(BALLOON).list().firstOrNull { it.hasSubtext("Probe balloon question") }
            }) { it != null }!!
            balloon.x("//div[@visible_text='Probe yes']").click()
            answered("message", "\"Probe yes\"")

            // A modal message is a dialog, whose detail is part of its body.
            assertEquals("true", command("probe.modal"))
            val modal = driver.ui.dialog()
            awaitProbeValue("the modal message dialog", {
                modal.present() && modal.hasSubtext("Probe modal question") && modal.hasSubtext("Probe modal detail")
            }) { it }
            modal.pressButton("Probe accept")
            answered("modal", "\"Probe accept\"")

            // A single choice is a list popup at the caret.
            assertEquals("true", command("probe.pick"))
            // A list reads through a remote fixture it keeps; the context keeps it from being collected.
            driver.withContext {
                awaitList("the quick pick popup", { driver.ui.popup().list() }, "Probe beta")
                    .clickItem("Probe beta", fullMatch = false)
            }
            answered("pick", "\"Probe beta\"")
            // Dismissing it answers too: the extension is waiting on `undefined`.
            assertEquals("true", command("probe.pick"))
            awaitList("the quick pick popup again", { driver.ui.popup().list() }, "Probe alpha")
                .keyboard { key(KeyEvent.VK_ESCAPE) }
            answered("pick", "null")

            // Several choices are a dialog; the extension gets the items ticked, in its own order.
            assertEquals("true", command("probe.pickMany"))
            val many = dialog("Probe pick many")
            driver.withContext {
                val manyList = awaitList("the multi-select list", { many.list() }, "Probe gamma")
                manyList.clickItem("Probe alpha", fullMatch = false)
                manyList.keyboard {
                    @Suppress("OPT_IN_USAGE") // Extends the list selection as a user does; there is no action for it.
                    hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_DOWN)
                }
                awaitProbeValue("two items selected", { manyList.selectedItems }) { it.size == 2 }
            }
            many.okButton.click()
            answered("pickMany", "[\"Probe alpha\",\"Probe beta\"]")

            // An input box is an input dialog, prefilled with the extension's value.
            assertEquals("true", command("probe.input"))
            val input = dialog("Probe input")
            val field = input.textField()
            awaitProbeValue("the extension's initial value", { field.text }) { it == "seed" }
            field.click()
            field.keyboard {
                @Suppress("OPT_IN_USAGE") // Selects the field's text; there is no action for it.
                hotKeyWithDefaultModifierKey(KeyEvent.VK_A)
                typeText("typed in client")
            }
            awaitProbeValue("the typed value", { field.text }) { it == "typed in client" }
            input.okButton.click()
            answered("input", "\"typed in client\"")

            // A live picker: the extension replaces its items as the user types, and the accepted
            // item must be the one it put there for the final value.
            assertEquals("true", command("probe.livePick"))
            val live = driver.ui.popup()
            awaitList("the live picker", { live.list() }, "Probe live start")
            live.textField().keyboard { typeText("abc") }
            awaitProbeValue("the extension's items for the typed value", { live.list().items }) {
                it == listOf("Probe search abc")
            }
            live.textField().keyboard { key(KeyEvent.VK_ENTER) }
            answered("livePick", """{"accepted":["Probe search abc"],"value":"abc"}""")
            Unit
        }

    /** A dialog the frontend opened, by the title the extension gave it. */
    private fun WorkbenchProbe.dialog(title: String): DialogUiComponent {
        val dialog = driver.ui.dialog(title = title)
        awaitProbeValue("the '$title' dialog", { dialog.present() }) { it }
        return dialog
    }

    /**
     * A list, once it is showing [item]. A lookup that finds no popup yet throws after its own wait,
     * which is shorter than this one; on a CI runner the popup has taken longer than that to appear.
     */
    private fun awaitList(description: String, find: () -> JListUiComponent, item: String): JListUiComponent {
        var list: JListUiComponent? = null
        awaitProbeValue(description, {
            runCatching { find() }.getOrNull()?.takeIf(UiComponent::present)?.also { list = it }?.items.orEmpty()
        }) { items -> items.any { it.contains(item) } }
        return list!!
    }

    private companion object {
        const val BALLOON = $$"//div[@javaclass='com.intellij.ui.BalloonImpl$MyComponent']"
    }
}
