package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import me.helvert.codebridge.uitest.runtime.SplitModeWorkbenchTest.EditorExRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent

/**
 * Extend Selection, document links, linked editing, on-type formatting and colours from an
 * extension, as the user meets them in a JetBrains Client: each starts from what the client does
 * -- an action, a keystroke, a click -- and ends in what the client's editor shows.
 *
 * Driven by the workbench probe's providers on `.codebridgeactions` files (`editor-probe.js`).
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeLanguageFeaturesTest {

    @Test
    fun `selection ranges, links, linked editing, on-type formatting and colours work in the client`() =
        withWorkbenchProbe(
            "split-mode-language-features",
            CONTENT,
            settings = """{"files.autoSave":"off","editor.formatOnType":true,"editor.linkedEditing":true}""",
        ) {
            val frame = driver.ideFrame { }
            val editor = frame.codeEditor()
            awaitProbeValue("the probe file in the client", { editor.text }) { it == CONTENT }
            val client = { driver.cast(editor.editor, EditorExRef::class) }

            // Extend Selection: the word, then the extension's `value=1`, which the IDE has no range for.
            editor.goToPosition(1, 6)
            driver.invokeAction("EditorSelectWord", component = editor.component)
            awaitProbeValue("the word selected", { editor.getSelection() }) { it == "value" }
            driver.invokeAction("EditorSelectWord", component = editor.component)
            awaitProbeValue("the extension's selection range selected", { editor.getSelection() }) { it == "value=1" }
            editor.removeSelection()

            // A document link to line 5 of the same file, followed as a declaration is.
            editor.goToPosition(2, 8)
            driver.invokeAction("GotoDeclaration", component = editor.component)
            awaitProbeValue("the caret on the line the link names", { editor.getCaretLine() }) { it == 5 }

            // Linked editing: the two tag names outlined while the caret is in one, and typed into together.
            editor.goToPosition(3, 3)
            awaitProbeValue("the linked tag names outlined in the client", {
                driver.withContext(OnDispatcher.EDT) {
                    client().getFilteredDocumentMarkupModel().getAllHighlighters()
                        .filter { it.getTextAttributes()?.getBackgroundColor()?.getRGB() == LINKED_BACKGROUND }
                        .map { it.getStartOffset() to it.getEndOffset() }.sortedBy { it.first }
                }
            }) { it.size == 2 }
            editor.keyboard { typeText("s") }
            awaitProbeValue("the closing tag typed into with the opening one", { editor.getLineText(3) }) {
                it == "<tsag>x</tsag>"
            }

            // On-type formatting: `;` at the end of a line has the extension mark it.
            editor.goToPosition(5, 4)
            editor.keyboard { typeText(";") }
            awaitProbeValue(
                "the on-type formatter's edit in the client",
                { editor.getLineText(5) }) { it == "end; // typed" }

            // A swatch before the colour, and the picker it opens writing what is chosen back.
            val swatch = awaitProbeValue("a colour swatch on line 4", {
                driver.withContext(OnDispatcher.EDT) { editor.inlaysOnLine(3) }
            }) { it.isNotEmpty() }.single()
            editor.clickInlay(swatch)
            // The picker opens with its red field focused: 255 becomes 0, and #ff0000 black.
            Thread.sleep(1_000)
            frame.keyboard {
                @Suppress("OPT_IN_USAGE") // Selects the picker field's text; there is no action for it.
                hotKeyWithDefaultModifierKey(KeyEvent.VK_A)
                typeText("0")
            }
            awaitProbeValue(
                "the picked colour written by the extension",
                { editor.getLineText(4) }) { it == "color #000000" }
            frame.keyboard { key(KeyEvent.VK_ESCAPE) }
        }

    private companion object {
        const val CONTENT = "let value=1;\nsee example.codebridgeactions#L5\n<tag>x</tag>\ncolor #ff0000\nend\n"

        /** VS Code's `editor.linkedEditingBackground`: red at 30%, as ARGB. */
        const val LINKED_BACKGROUND = 0x4DFF0000
    }
}
