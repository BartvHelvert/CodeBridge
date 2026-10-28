package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.editorsSplitters
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * `vscode.tests` as the user meets it in a JetBrains Client: Run in the gutter of each test an
 * extension declared, the IDE's test runner showing what the extension reported, and the gutter
 * following edits and results.
 *
 * The markers are computed on the backend from the extension's test tree and drawn by the client;
 * the run is started from the client and run by the extension (`testing-probe.js`), which records
 * which tests each run asked for.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeTestingTest {
    @Test
    @Suppress("UnusedExpression") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `gutter runs reach the extension, and its results reach the client's test runner and gutter`() =
        withWorkbenchProbe("split-mode-testing", "suite probe\ntest passes\ntest fails\n") {
            val frame = driver.ideFrame { }
            val editor = frame.codeEditor()
            val gutter = frame.editorsSplitters().gutter()

            /**
             * The extension's markers: tooltip by zero-based line. Each gutter icon is a throwaway
             * remote object, which the context keeps from being collected before it is read.
             */
            fun markers(): Map<Int, String> = driver.withContext {
                gutter.icons
                    .mapNotNull { icon -> icon.mark.getTooltipText()?.let { icon.line to it } }
                    .filter { (_, tooltip) -> PROBE_TESTS.any { it in tooltip } }
                    .toMap()
            }

            /** Clicks the marker on zero-based [line] whose tooltip is [tooltip]. */
            fun clickMarker(line: Int, tooltip: String) = driver.withContext {
                gutter.icons.first { it.line == line && it.mark.getTooltipText() == tooltip }.click()
            }

            fun requested() = command("probe.testState")

            awaitProbeValue("a Run marker on the suite and on each test", ::markers) {
                it == mapOf(0 to "Run probe", 1 to "Run passes", 2 to "Run fails")
            }

            // The suite's marker runs both of its tests through the extension's run profile.
            runFromGutter({ clickMarker(0, "Run probe") }, "Run 'probe'")
            awaitProbeValue("the run the extension received", ::requested) {
                it?.contains(""""requested":[["passes","fails"]]""") == true
            }
            val results = frame.tree("//div[@class='SMTRunnerTestTreeView']")
            // Passed tests are hidden by default; the status line counts them.
            awaitProbeValue("the failed test in the client's test runner", {
                if (results.present()) results.expandAll().collectExpandedPathsAsStrings() else emptyList()
            }) { rows -> rows.any { it.endsWith("/fails") || it.contains("/fails ") } }
            val status = frame.x("//div[@class='TestStatusLine']")
            awaitProbeValue("one failed and one passed in the run's status line", {
                if (status.present()) status.getAllTexts().joinToString(" ") { it.text } else ""
            }) { it.contains(Regex("""\b1 tests? failed,\s+1 passed""")) }
            // The console shows the selected test's output: the extension's message and what it wrote.
            results.clickRow { it == "fails" || it.startsWith("fails ") }
            val console = frame.codeEditor("//div[@class='TestsConsoleViewImpl']//div[@class='EditorComponentImpl']")
            awaitProbeValue("the failed test's message and output in the test console", {
                if (console.present()) console.text else "<no console>"
            }) { it.contains("probe failure in fails") && it.contains("PROBE OUTPUT fails") }
            awaitProbeValue("each test's result in its gutter tooltip", ::markers) {
                it[1] == "passes passed" && it[2] == "fails failed"
            }

            // A test typed into the file is discovered by the extension and gets a marker, unsaved.
            editor.goToPosition(4, 1)
            editor.keyboard { typeText("test added") }
            awaitProbeValue("a marker for the test typed into the file", ::markers) { it[3] == "Run added" }
            runFromGutter({ clickMarker(3, "Run added") }, "Run 'added'")
            awaitProbeValue("the new test run by the extension", ::requested) {
                it?.contains(""""requested":[["passes","fails"],["added"]]""") == true
            }
            awaitProbeValue("the new test's result in its gutter tooltip", ::markers) { it[3] == "added passed" }
            Unit
        }

    /** Clicks a marker and chooses [action] from the context menu it opens, as the platform shows a marker's actions. */
    private fun WorkbenchProbe.runFromGutter(click: () -> Unit, action: String) {
        click()
        val menu = driver.ui.popupMenu()
        awaitProbeValue("'$action' in the marker's menu", {
            if (menu.present()) menu.itemsList() else emptyList()
        }) { items -> action in items }
        menu.select(action)
    }

    private companion object {
        val PROBE_TESTS = listOf("probe", "passes", "fails", "added")
    }
}
