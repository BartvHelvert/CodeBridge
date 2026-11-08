package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.editor.completionList
import com.intellij.driver.sdk.ui.dragAndDrop
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.accessibleList
import com.intellij.ide.starter.junit5.RemoteDevRun
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.uitest.awaitProbeValue
import me.helvert.codebridge.uitest.enforcePerformanceBudgets
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.awt.Point
import java.awt.event.KeyEvent
import java.time.Duration
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * A notebook as the user meets it in a JetBrains Client: a `.ipynb` file an extension shows opens as
 * its cells, read by the runtime's built-in `ipynb` serializer on the backend; what is typed into a
 * cell reaches the cell's document in the host; Ctrl+Enter runs it with an extension's kernel
 * (`notebook-probe.js`), whose output the client draws; and saving writes that output into the file.
 * Cell edits undo as in VS Code: the notebook's own outside a cell's editor, typing inside one.
 */
@ExtendWith(RemoteDevRun::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("OPT_IN_USAGE") // The keys are what is tested: cells bind run and undo to them, apart from the IDE's actions.
class SplitModeNotebookTest {
    private var session: WorkbenchSession? = null
    private var sessionCount = 0

    @AfterAll
    fun closeSession() {
        val previous = session
        session = null
        previous?.close()
    }

    /** Same installation and settings for every notebook; files and host state are fresh each time. */
    private fun withNotebookProbe(name: String, notebook: String, test: WorkbenchProbe.() -> Unit) {
        // Starter replaces a context's output when its name is reused. Keep a failed session's
        // logs and screenshots when the next scenario starts a fresh pair.
        val running = session ?: startWorkbenchProbe(
            "split-mode-notebooks-${++sessionCount}", "",
            traced = "#jb.focus.requests,#me.helvert.codebridge.notebook.CellView,#me.helvert.codebridge.notebook.CellLanguage",
        ).also { session = it }
        try {
            running.test {
                driver.invokeAction("CloseAllEditors")
                val args = JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(notebook))).toString()
                awaitProbeValue("the previous notebook closed and $name prepared", { command("probe.prepareNotebook", args) }) { it == "true" }
                println("Notebook scenario: $name")
                scenario(name).test()
            }
        } catch (error: Throwable) {
            // A failed scenario must not leave the next test with a damaged session or modal dialog.
            runCatching { closeSession() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    @Test
    fun `a new scenario gets fresh notebook contents and execution state`() {
        withNotebookProbe("split-mode-notebook-isolation-first", NOTEBOOK.replace("hello", "first")) {
            check(command("probe.showNotebook") == "1")
            val frame = driver.ideFrame { }
            val cell = awaitProbeValue("the first scenario's cell", {
                frame.xx("//div[@accessiblename='Notebook example.ipynb']//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            }) { it.size == 1 }.single()
            cell.click()
            cell.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER) }
            awaitProbeValue("the first scenario's notebook recorded", { command("probe.notebookState") }) {
                it?.contains(""""ran":["first"]""") == true && it.contains(""""example.ipynb":["first"]""")
            }
            awaitProbeValue("the first scenario's execution finished", {
                frame.xx("//div[@accessiblename='Notebook example.ipynb']//div[@class='ActionButton' and @accessiblename='Run Cell']").list().size
            }) { it == 1 }
            Unit
        }
        withNotebookProbe("split-mode-notebook-isolation-second", NOTEBOOK) {
            check(command("probe.notebookState") == """{"opened":[],"ran":[],"cells":{}}""")
            check(command("probe.showNotebook") == "1")
            val frame = driver.ideFrame { }
            awaitProbeValue("a new notebook with the second scenario's contents", {
                runCatching {
                    frame.xx("//div[@accessiblename='Notebook example.ipynb']//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java)
                        .list().map { it.text }
                }.getOrDefault(emptyList())
            }) { it == listOf("hello") }
            awaitProbeValue("only the second scenario recorded", { command("probe.notebookState") }) {
                it == """{"opened":["example.ipynb"],"ran":[],"cells":{"example.ipynb":["hello"]}}"""
            }
            Unit
        }
    }

    @Test
    @Suppress("UnusedExpression") // The trailing Unit keeps the last probe's value from becoming the result.
    fun `a notebook opens in the client, runs with an extension's kernel and saves its output`() =
        withNotebookProbe("split-mode-notebook", NOTEBOOK) {
            val frame = driver.ideFrame { }
            // As Jupyter opens one: the client is asked to show the file, and shows it as a notebook.
            assertEquals("1", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun cells(): List<String> = runCatching {
                frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list().map { it.text }
            }.getOrDefault(emptyList())

            fun outputs(): List<String> = runCatching {
                frame.xx("$notebook//div[@class='JBTextArea']", JTextFieldUI::class.java).list().map { it.text }
            }.getOrDefault(emptyList())

            fun state() = command("probe.notebookState")

            awaitProbeValue("the notebook's one cell in the client", ::cells) { it == listOf("hello") }
            awaitProbeValue("the notebook opened in the extension host", ::state) {
                it?.contains(""""opened":["example.ipynb"]""") == true
            }

            // Typed at the end of the cell, then run; the kernel is the only one there is, so it is chosen.
            val cell = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list().first()
            cell.click()
            cell.keyboard {
                key(KeyEvent.VK_END)
                typeText(" world")
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER)
            }
            awaitProbeValue("the typed text, run by the extension's kernel", ::state) {
                it?.contains(""""ran":["hello world"]""") == true
            }
            awaitProbeValue("the kernel's output in the client", ::outputs) { "PROBE RESULT HELLO WORLD" in it }
            // Stop Cell turns back into Run Cell once the run ends, with the mouse and keys at rest:
            // not on the toolbar's next update, which waits for input.
            val ran = System.nanoTime()
            awaitProbeValue("the cell's Run Cell button back", {
                runCatching { frame.x("$notebook//div[@class='ActionButton' and @accessiblename='Run Cell']").present() }.getOrDefault(false)
            }) { it }
            val waited = Duration.ofNanos(System.nanoTime() - ran)
            if (enforcePerformanceBudgets) {
                check(waited < Duration.ofSeconds(3)) { "Run Cell came back only after $waited" }
            }
            // The run's status sits at the top right of the output, beside it rather than above it.
            awaitProbeValue("the run's status right of the output's first line", {
                runCatching {
                    val output = frame.x("$notebook//div[@class='JBTextArea']").component
                    val status = frame.x("$notebook//div[@class='JBLabel' and contains(@visible_text, '✓')]").component
                    val at = output.getLocationOnScreen()
                    val statusAt = status.getLocationOnScreen()
                    statusAt.x >= at.x + output.width && statusAt.y < at.y + output.height
                }.getOrDefault(false)
            }) { it }
            // As in VS Code, the run button and the execution order sit left of the cell's editor: the
            // button level with its top, the order below the button, within the editor's height.
            awaitProbeValue("the run button and execution order left of the cell", {
                runCatching {
                    val editor = cell.component
                    val button = frame.x("$notebook//div[@class='ActionButton' and @accessiblename='Run Cell']").component
                    val order = frame.x("$notebook//div[@class='JBLabel' and @visible_text='[1]']").component
                    val at = editor.getLocationOnScreen()
                    val buttonAt = button.getLocationOnScreen()
                    val orderAt = order.getLocationOnScreen()
                    buttonAt.x + button.width <= at.x && orderAt.x + order.width <= at.x &&
                        buttonAt.y < at.y + editor.height && orderAt.y > buttonAt.y
                }.getOrDefault(false)
            }) { it }

            // Shift+Enter is a new line in the cell, not another run.
            cell.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER) }
            awaitProbeValue("a new line in the cell", ::cells) { it == listOf("hello world\n") }
            awaitProbeValue("the host's copy of the cell, run once", ::state) {
                it?.contains(""""example.ipynb":["hello world\n"]""") == true && it.contains(""""ran":["hello world"]""")
            }

            // Save All writes the notebook through the serializer, output and all.
            cell.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_S) }
            awaitProbeValue("the output saved into the file", { project.resolve("example.ipynb").readText() }) {
                "PROBE RESULT HELLO WORLD" in it && "hello world" in it
            }
            Unit
        }

    @Test
    fun `a notebook opens as its cells without its text showing first`() =
        withNotebookProbe("split-mode-notebook-no-text", NOTEBOOK) {
            val frame = driver.ideFrame { }
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            // The backend opens the file as text and the client puts the notebook in its place: the
            // text editor, named after the file, must not show meanwhile. The swap takes a few
            // hundred milliseconds, so each sample is one query, taken while the extension opens it.
            val text = "//div[@class='EditorComponentImpl' and contains(@accessiblename, 'example.ipynb')]"
            fun textShown(): Boolean = driver.withContext { frame.xx(text).list().any { it.component.isShowing() } }

            fun opened(): Boolean = runCatching { frame.x(notebook).present() }.getOrDefault(false)

            check(command("probe.startShowingNotebook") == "\"started\"")
            val until = System.nanoTime() + Duration.ofMinutes(1).toNanos()
            var samples = 0
            var seen = 0
            while (!opened() && System.nanoTime() < until) {
                samples++
                if (textShown()) seen++
            }
            check(opened()) { "The notebook never opened after $samples samples" }
            println("Sampled the editors $samples times while the notebook opened; its text showed in $seen")
            check(seen == 0) { "The notebook's text showed in $seen of $samples samples before its cells" }

            fun cells(): List<String> = runCatching {
                frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list().map { it.text }
            }.getOrDefault(emptyList())
            awaitProbeValue("the notebook's one cell in the client", ::cells) { it == listOf("hello") }
            check(!textShown()) { "The notebook's text showed beside its cells" }
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a running cell's outline is animated until it is stopped`() =
        withNotebookProbe("split-mode-notebook-running", WAITING) {
            val frame = driver.ideFrame { }
            assertEquals("1", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun animated(): Int = runCatching { frame.xx("$notebook//div[@class='RunningOutline']").list().size }.getOrDefault(-1)

            // Shown for the extension is not yet drawn in the client.
            val cell = awaitProbeValue("the notebook's cell in the client", {
                runCatching { frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list().firstOrNull() }.getOrNull()
            }) { it != null }!!
            awaitProbeValue("the cell, not running, its outline still", ::animated) { it == 0 }
            cell.click()
            cell.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER) }
            awaitProbeValue("the cell running in the extension's kernel", { command("probe.notebookState") }) {
                it?.contains(""""ran":["wait"]""") == true
            }
            awaitProbeValue("the running cell's outline animated", ::animated) { it == 1 }

            // Its status counts the seconds up while it runs, as VS Code's does.
            fun elapsedLabels(): List<String> = frame.xx("$notebook//div[@class='JBLabel']").list()
                .map { label -> label.getAllTexts().joinToString("") { it.text } }
            // The UI formats numbers using the IDE's locale, which can use a decimal comma.
            fun seconds(labels: List<String>): Double? = labels.firstNotNullOfOrNull {
                Regex("""(\d+[.,]\d)s""").matchEntire(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            }
            val firstLabels = awaitProbeValue("the running cell's time", ::elapsedLabels) { seconds(it) != null }
            val first = checkNotNull(seconds(firstLabels))
            awaitProbeValue("the running cell's time counting up from $first", ::elapsedLabels) {
                seconds(it)?.let { elapsed -> elapsed >= first + 1 } == true
            }

            // Its Stop Cell button cancels the run, and the animation stops.
            frame.x("$notebook//div[@class='ActionButton' and @accessiblename='Stop Cell']").click()
            awaitProbeValue("the stopped cell's failed run", {
                runCatching { frame.x("$notebook//div[@class='JBLabel' and contains(@visible_text, '✗')]").present() }.getOrDefault(false)
            }) { it }
            awaitProbeValue("the outline still once the cell stopped", ::animated) { it == 0 }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a deleted cell comes back with undo, while undo in a cell undoes its typing`() =
        withNotebookProbe("split-mode-notebook-undo", TWO_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text } }.getOrDefault(emptyList())
            fun hostCells(cells: String) = awaitProbeValue("the host's copy of the cells: $cells", { command("probe.notebookState") }) {
                it?.contains(""""example.ipynb":$cells""") == true
            }

            awaitProbeValue("the notebook's cells in the client", ::cells) { it == listOf("one", "two") }
            hostCells("""["one","two"]""")

            // Editing the first cell, its Delete Cell button; the notebook keeps the focus, on the cell after it.
            editors().first().click()
            frame.xx("$notebook//div[@class='ActionButton' and @accessiblename='Delete Cell']").list().first().click()
            awaitProbeValue("the cell deleted", ::cells) { it == listOf("two") }
            hostCells("""["two"]""")
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the deleted cell back where it was", ::cells) { it == listOf("one", "two") }
            hostCells("""["one","two"]""")
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) }
            awaitProbeValue("the cell deleted again by redo", ::cells) { it == listOf("two") }
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the cell back again", ::cells) { it == listOf("one", "two") }
            assertNotLogged("UnknownFileEditor", "the backend failed on the notebook's undo")

            // In a cell's editor, undo is the cell's own: it takes back typing, not the notebook's edits.
            val second = editors()[1]
            second.click()
            second.keyboard {
                key(KeyEvent.VK_END)
                typeText("!?")
            }
            awaitProbeValue("the typed text", ::cells) { it == listOf("one", "two!?") }
            second.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the typing undone, the cells kept", ::cells) { it == listOf("one", "two") }
            second.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) }
            awaitProbeValue("the typing redone", ::cells) { it == listOf("one", "two!?") }
            hostCells("""["one","two!?"]""")
            second.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the typing undone again", ::cells) { it == listOf("one", "two") }
            // With nothing left to undo, the keys do nothing: no IDE undo on the backend, which has never seen the cell.
            second.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            second.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the cells as they were", ::cells) { it == listOf("one", "two") }
            hostCells("""["one","two"]""")
            // Typing in a cell isn't reported to the backend either, which would fail to find its editor.
            assertNotLogged("UnknownFileEditor", "the backend failed on undo in a cell")
            assertNotLogged("Editor is not found", "the backend was told of typing in a cell")

            // Esc leaves the cell selected but not edited, where Delete deletes it, as in VS Code.
            second.keyboard {
                key(KeyEvent.VK_ESCAPE)
                key(KeyEvent.VK_DELETE)
            }
            awaitProbeValue("the selected cell deleted with Delete", ::cells) { it == listOf("one") }
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("that cell back too", ::cells) { it == listOf("one", "two") }
            hostCells("""["one","two"]""")
            Unit
        }

    @Test
    fun `the strip between cells adds a code or markdown cell there`() = repeat(3) { round ->
        insertFromStrip("split-mode-notebook-insert-$round")
    }

    @Suppress("UnusedExpression")
    private fun insertFromStrip(name: String) =
        withNotebookProbe(name, TWO_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text } }.getOrDefault(emptyList())
            fun strips() = frame.xx("$notebook//div[@class='CellInserter']").list()
            fun hostCells(cells: String) = awaitProbeValue("the host's copy of the cells: $cells", { command("probe.notebookState") }) {
                it?.contains(""""example.ipynb":$cells""") == true
            }
            /** Rests the mouse on strip [index], above the first cell for 0, and clicks its [button]. */
            fun insert(index: Int, button: String) = driver.withContext {
                val strip = strips()[index]
                fun shown() = runCatching { strip.xx("//div[@accessiblename='$button']").list().any { it.component.isShowing() } }.getOrDefault(false)
                // As in VS Code, the buttons only show with the mouse over the strip; they fade out once it leaves.
                awaitProbeValue("the strip's $button button hidden before the mouse is over it", ::shown) { !it }
                strip.moveMouse()
                awaitProbeValue("the strip's $button button", ::shown) { it }
                strip.xx("//div[@accessiblename='$button']").list().first { it.component.isShowing() }.click()
            }

            awaitProbeValue("the notebook's cells in the client", ::cells) { it == listOf("one", "two") }
            // One strip above the first cell, and one below each cell.
            awaitProbeValue("the notebook's strips", { runCatching { strips().size }.getOrDefault(0) }) { it == 3 }

            // The strip below a cell is not the cell: resting on it shows no cell's toolbar, while no cell is selected.
            driver.withContext {
                strips()[1].moveMouse()
                awaitProbeValue("the strip's buttons", {
                    runCatching { strips()[1].xx("//div[@accessiblename='Add Code Cell']").list().any { it.component.isShowing() } }.getOrDefault(false)
                }) { it }
                val toolbars = frame.xx("$notebook//div[@class='ActionButton' and @accessiblename='Delete Cell']").list().filter { it.component.isShowing() }
                check(toolbars.isEmpty()) { "a cell's toolbar shown with the mouse on the strip below it" }
                editors().first().moveMouse()
            }

            // Between the two cells, a code cell, being edited.
            insert(1, "Add Code Cell")
            awaitProbeValue("the new code cell between the others", ::cells) { it == listOf("one", "", "two") }
            frame.keyboard { typeText("mid") }
            awaitProbeValue("typing in the new cell", ::cells) { it == listOf("one", "mid", "two") }
            hostCells("""["one","mid","two"]""")

            // Above the first, a markdown cell, being edited; Esc renders it. Esc is the new cell's
            // own, though another cell's editor had the focus last.
            insert(0, "Add Markdown Cell")
            awaitProbeValue("the new markdown cell first, being edited", ::cells) { it == listOf("", "one", "mid", "two") }
            frame.keyboard { typeText("# Top") }
            awaitProbeValue("the markdown text and focus ready for Escape", {
                val shown = editors()
                shown.map { it.text } == listOf("# Top", "one", "mid", "two") &&
                        driver.cast(shown.first().component, CellFocusRef::class).isFocusOwner()
            }) { it }
            frame.keyboard { key(KeyEvent.VK_ESCAPE) }
            awaitProbeValue("the markdown cell rendered", ::cells) { it == listOf("one", "mid", "two") }
            hostCells("""["# Top","one","mid","two"]""")
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `cells dragged by the strip left of them move, or with Ctrl are copied, to where they are dropped`() =
        withNotebookProbe("split-mode-notebook-drag", THREE_TALL_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("3", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text.substringBefore('\n') } }.getOrDefault(emptyList())
            // Each code cell's execution order, at the bottom of its run column: on its drag handle,
            // clear of the run button above it in a cell of three lines.
            fun handles() = frame.xx("$notebook//div[@class='JBLabel' and @accessiblename='[ ]']").list()
            fun hostCells(cells: String) = awaitProbeValue("the host's copy of the cells: $cells", { command("probe.notebookState") }) {
                it?.contains(""""example.ipynb":$cells""") == true
            }
            // Which cells are selected, as a screen reader is told.
            fun selected(): List<Boolean> = runCatching {
                driver.withContext {
                    frame.xx("$notebook//div[@accessiblename='Code cell']").list().map { cell ->
                        val context = driver.cast(checkNotNull(cell.component.getAccessibleContext()), AccessibleStatesRef::class)
                        context.getAccessibleStateSet().toArray().any { it.toDisplayString() == "selected" }
                    }
                }
            }.getOrDefault(emptyList())

            // The robot's own drag: it posts the drag events, which pressing and moving the mouse do not.
            // Keys it presses are held in the events it posts.
            val robot = driver.ui.robot
            fun <T> holding(key: Int, action: () -> T): T {
                robot.pressKey(key)
                try {
                    return action()
                } finally {
                    robot.releaseKey(key)
                }
            }

            awaitProbeValue("the notebook's cells in the client", ::cells) { it == listOf("one", "two", "three") }
            awaitProbeValue("a drag handle for each cell", { runCatching { handles().size }.getOrDefault(0) }) { it == 3 }

            // Down: the first cell dropped on the lower half of the last goes after it.
            driver.withContext { driver.ui.dragAndDrop(handles()[0], to = handles()[2]) }
            awaitProbeValue("the first cell moved last", ::cells) { it == listOf("two", "three", "one") }
            hostCells("""["two\n2\n2","three\n3\n3","one\n1\n1"]""")

            // Up: dropped on the top of the first cell's text, it goes before it.
            driver.withContext { driver.ui.dragAndDrop(handles()[2], to = editors()[0], toPoint = Point(10, 1)) }
            awaitProbeValue("the cell moved back first", ::cells) { it == listOf("one", "two", "three") }
            hostCells("""["one\n1\n1","two\n2\n2","three\n3\n3"]""")

            // Dropped where it already is, it stays; a move undoes as one step, in command mode.
            driver.withContext { driver.ui.dragAndDrop(handles()[1], to = handles()[1]) }
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the last move undone", ::cells) { it == listOf("two", "three", "one") }
            hostCells("""["two\n2\n2","three\n3\n3","one\n1\n1"]""")

            // With Ctrl held, the cell is copied where it is dropped instead.
            holding(KeyEvent.VK_CONTROL) { driver.withContext { driver.ui.dragAndDrop(handles()[0], to = handles()[2]) } }
            awaitProbeValue("the first cell copied last", ::cells) { it == listOf("two", "three", "one", "two") }
            hostCells("""["two\n2\n2","three\n3\n3","one\n1\n1","two\n2\n2"]""")

            // The cells selected together are dragged together, by the handle of any of them.
            driver.withContext {
                handles()[0].click()
                holding(KeyEvent.VK_SHIFT) { handles()[1].click() }
            }
            driver.withContext { driver.ui.dragAndDrop(handles()[1], to = handles()[3]) }
            awaitProbeValue("the two selected cells moved last", ::cells) { it == listOf("one", "two", "two", "three") }
            hostCells("""["one\n1\n1","two\n2\n2","two\n2\n2","three\n3\n3"]""")
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the move of both undone as one step", ::cells) { it == listOf("two", "three", "one", "two") }
            hostCells("""["two\n2\n2","three\n3\n3","one\n1\n1","two\n2\n2"]""")

            // Selection follows the edit events, so it cannot be cleared by a late splice.
            awaitProbeValue("both cells selected again by the undo", ::selected) { it == listOf(true, true, false, false) }
            repeat(3) {
                frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) }
                awaitProbeValue("the move of both redone", ::cells) { it == listOf("one", "two", "two", "three") }
                hostCells("""["one\n1\n1","two\n2\n2","two\n2\n2","three\n3\n3"]""")
                awaitProbeValue("both cells selected again by the redo", ::selected) { it == listOf(false, false, true, true) }
                frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
                awaitProbeValue("the move of both undone again", ::cells) { it == listOf("two", "three", "one", "two") }
                hostCells("""["two\n2\n2","three\n3\n3","one\n1\n1","two\n2\n2"]""")
                awaitProbeValue("both cells selected again by the undo", ::selected) { it == listOf(true, true, false, false) }
            }
            driver.withContext { driver.ui.dragAndDrop(handles()[0], to = handles()[3]) }
            awaitProbeValue("both cells, still selected, moved last again", ::cells) { it == listOf("one", "two", "two", "three") }
            hostCells("""["one\n1\n1","two\n2\n2","two\n2\n2","three\n3\n3"]""")
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a code cell's toolbar runs the cells above it, or it and those below`() =
        withNotebookProbe("split-mode-notebook-run-around", TWO_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            /** Edits cell [index], so its toolbar shows, and clicks its [button]. */
            fun click(index: Int, button: String) = driver.withContext {
                editors()[index].click()
                frame.xx("$notebook//div[@class='ActionButton' and @accessiblename='$button']").list().single { it.component.isShowing() }.click()
            }

            awaitProbeValue("the notebook's cells in the client", { runCatching { editors().map { it.text } }.getOrDefault(emptyList()) }) {
                it == listOf("one", "two")
            }
            // The first cell has none above it to run.
            driver.withContext {
                editors()[0].click()
                val above = frame.xx("$notebook//div[@class='ActionButton' and @accessiblename='Run Above Cells']").list().single { it.component.isShowing() }
                check(!above.isEnabled()) { "Run Above Cells enabled on the first cell" }
            }
            click(1, "Run Above Cells")
            awaitProbeValue("the cell above the second run, not the second", { command("probe.notebookState") }) {
                it?.contains(""""ran":["one"]""") == true
            }
            click(0, "Run Cell and Below")
            awaitProbeValue("the first cell and the one below run", { command("probe.notebookState") }) {
                it?.contains(""""ran":["one","one","two"]""") == true
            }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a cell's menu copies, pastes, joins and changes cells, as VS Code's does`() =
        withNotebookProbe("split-mode-notebook-menu", TWO_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text } }.getOrDefault(emptyList())
            fun hostCells(cells: String) = awaitProbeValue("the host's copy of the cells: $cells", { command("probe.notebookState") }) {
                it?.contains(""""example.ipynb":$cells""") == true
            }
            fun shownButtons(name: String) =
                frame.xx("$notebook//div[@class='ActionButton' and @accessiblename='$name']").list().filter { it.component.isShowing() }
            /** Edits the first cell, so its toolbar shows, and chooses [item] from its "..." menu. */
            fun fromMenu(item: String) = driver.withContext {
                editors().first().click()
                shownButtons("More Actions").single().click()
                val menu = driver.ui.accessibleList { byClass("MyList") }
                awaitProbeValue("'$item' in the cell's menu", { runCatching { menu.rawItems }.getOrDefault(emptyList()) }) { items ->
                    items.any { it.startsWith(item) }
                }
                driver.withContext(OnDispatcher.EDT) { menu.component.requestFocus() }
                awaitProbeValue("the cell menu focused", { menu.isFocusOwner() }) { it }
                menu.selectItem(item, fullMatch = false)
                menu.keyboard { key(KeyEvent.VK_ENTER) }
                awaitProbeValue("the cell menu closed", { menu.present() }) { !it }
            }

            awaitProbeValue("the notebook's cells in the client", ::cells) { it == listOf("one", "two") }

            // Copy Cell Down: a copy below, as one step that undo takes back.
            repeat(3) {
                fromMenu("Copy Cell Down")
                awaitProbeValue("the copy below the cell", ::cells) { it == listOf("one", "one", "two") }
                hostCells("""["one","one","two"]""")
                frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
                awaitProbeValue("the copy undone", ::cells) { it == listOf("one", "two") }
            }

            // Join With Next Cell: one cell, their texts a line apart.
            fromMenu("Join With Next Cell")
            awaitProbeValue("the cells joined", ::cells) { it == listOf("one\ntwo") }
            hostCells("""["one\ntwo"]""")
            frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
            awaitProbeValue("the join undone", ::cells) { it == listOf("one", "two") }
            hostCells("""["one","two"]""")

            // In command mode, the menu's keys: copy the first cell, paste it below the second.
            editors().first().click()
            frame.keyboard {
                key(KeyEvent.VK_ESCAPE)
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_C)
                key(KeyEvent.VK_DOWN)
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_V)
            }
            awaitProbeValue("the copied cell pasted below the second", ::cells) { it == listOf("one", "two", "one") }
            hostCells("""["one","two","one"]""")
            // M makes the pasted cell, selected, a markdown cell: rendered, its editor no longer shown.
            frame.keyboard { key(KeyEvent.VK_M) }
            awaitProbeValue("the pasted cell rendered as markdown", ::cells) { it == listOf("one", "two") }
            hostCells("""["one","two","one"]""")

            // Toggle Cell Toolbar Position moves the toolbar from the right of the cell to its left.
            fun toolbarOnLeft() = driver.withContext {
                val editor = editors().first().component
                val button = shownButtons("Delete Cell").single().component
                button.getLocationOnScreen().x < editor.getLocationOnScreen().x + editor.width / 2
            }
            editors().first().click()
            check(!toolbarOnLeft()) { "the cell's toolbar starts on the left" }
            fromMenu("Toggle Cell Toolbar Position")
            awaitProbeValue("the cell's toolbar on the left", { runCatching { toolbarOnLeft() }.getOrDefault(false) }) { it }
            fromMenu("Toggle Cell Toolbar Position")
            awaitProbeValue("the cell's toolbar back on the right", { runCatching { toolbarOnLeft() }.getOrDefault(true) }) { !it }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a markdown cell shows rendered, and is edited in place`() =
        withNotebookProbe("split-mode-notebook-markdown", MARKDOWN) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text } }.getOrDefault(emptyList())
            fun rendered() = frame.xx("$notebook//div[@class='MarkdownCellPane']").list()
            fun renders(vararg texts: String): Boolean = runCatching {
                val panes = rendered()
                panes.size == texts.size && texts.indices.all { panes[it].hasSubtext(texts[it]) }
            }.getOrDefault(false)
            fun hostCells(cells: String) = awaitProbeValue("the host's copy of the cells: $cells", { command("probe.notebookState") }) {
                it?.contains(""""example.ipynb":$cells""") == true
            }

            // As VS Code shows it: the markdown rendered, with no editor; the code cell in its editor.
            awaitProbeValue("only the code cell in an editor", ::cells) { it == listOf("one") }
            awaitProbeValue("the markdown rendered", { renders("Lesson") }) { it }
            // Its long line wraps within the notebook rather than widening every cell past it.
            val width = frame.x(notebook).component.width
            awaitProbeValue("the rendered markdown within the notebook's width", { rendered().first().component.width }) { it in 1..width }

            // A double-click edits it in place; Esc renders it again, with what was typed.
            rendered().first().doubleClick()
            awaitProbeValue("the markdown cell in its editor", ::cells) { it == listOf("# Lesson\n$LONG", "one") }
            frame.keyboard {
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_END)
                key(KeyEvent.VK_ENTER)
                typeText("More")
                key(KeyEvent.VK_ESCAPE)
            }
            awaitProbeValue("the edited markdown rendered again", { renders("More") }) { it }
            awaitProbeValue("no editor left for it", ::cells) { it == listOf("one") }
            hostCells("""["# Lesson\n$LONG\nMore","one"]""")

            // The toolbar's Markdown adds a markdown cell below the selected one, being edited;
            // Ctrl+Enter renders it.
            frame.x("$notebook//div[@class='ActionButtonWithText' and @accessiblename='Markdown']").click()
            awaitProbeValue("the new markdown cell, being edited", ::cells) { it == listOf("", "one") }
            frame.keyboard {
                typeText("## New")
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER)
            }
            awaitProbeValue("both markdown cells rendered", { renders("More", "New") }) { it }
            awaitProbeValue("only the code cell in an editor again", ::cells) { it == listOf("one") }
            hostCells("""["# Lesson\n$LONG\nMore","## New","one"]""")

            // Markdown that renders nothing -- an empty list item -- says how to edit it, rather than
            // leaving the cell a sliver.
            rendered()[1].doubleClick()
            awaitProbeValue("the new markdown cell in its editor", ::cells) { it == listOf("## New", "one") }
            frame.keyboard {
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A)
                typeText("- ")
                key(KeyEvent.VK_ESCAPE)
            }
            awaitProbeValue("the empty cell's placeholder", { renders("More", "Empty markdown cell") }) { it }
            awaitProbeValue("the placeholder a line tall", { rendered()[1].component.height }) { it > 20 }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a completion closes when focus moves to another cell`() =
        withNotebookProbe("split-mode-notebook-completion-focus", TWO_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"))
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"
            val editors = awaitProbeValue("the notebook's two cell editors", {
                frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            }) { it.size == 2 }
            editors[0].click()
            editors[0].keyboard {
                hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A)
                typeText("probeCo")
            }
            awaitProbeValue("the first cell's completion", {
                frame.completionList().let { if (it.present()) it.rawItems else emptyList() }
            }) { items -> items.any { "probeCompletion" in it } }

            // Inserting a cell moves focus programmatically too: no outside mouse click dismisses
            // the old lookup. It must not remain to consume the next cell's Escape or typing.
            val next = driver.cast(editors[1].component, CellFocusRef::class)
            driver.withContext(OnDispatcher.EDT) { editors[1].component.requestFocus() }
            awaitProbeValue("the second cell focused", { next.isFocusOwner() }) { it }
            awaitProbeValue("the previous cell's completion closed", { frame.completionList().present() }) { !it }
            frame.keyboard { typeText("Z") }
            awaitProbeValue("typing in the second cell", { editors.map { it.text } }) { it == listOf("probeCo", "Ztwo") }
            Unit
        }

    @Remote("java.awt.Component")
    interface CellFocusRef {
        fun isFocusOwner(): Boolean
    }

    @Test
    @Suppress("UnusedExpression")
    fun `a hover closes when focus moves to another cell`() =
        withNotebookProbe("split-mode-notebook-hover-focus", TWO_CELLS) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"))
            val editors = awaitProbeValue("the notebook's two cell editors", {
                frame.xx("//div[@accessiblename='Notebook example.ipynb']//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            }) { it.size == 2 }
            fun hoverShown() = driver.ui.xx("//div[@class='JBHtmlPane']").list().any { it.hasSubtext("PROBE HOVER") }
            editors[0].click()
            editors[0].hoverOnPosition(1, 2)
            awaitProbeValue("the first cell's hover", ::hoverShown) { it }

            // Keep the pointer over the old cell: focus transfer alone must clear its popup.
            val next = driver.cast(editors[1].component, CellFocusRef::class)
            driver.withContext(OnDispatcher.EDT) { editors[1].component.requestFocus() }
            awaitProbeValue("the second cell focused", { next.isFocusOwner() }) { it }
            awaitProbeValue("the previous cell's hover closed", ::hoverShown) { !it }
            frame.keyboard { key(KeyEvent.VK_ESCAPE) }
            awaitProbeValue("one Escape leaves the second cell's editor", { next.isFocusOwner() }) { !it }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `typing in a markdown cell closes a hover over another cell`() =
        withNotebookProbe("split-mode-notebook-hover-typing", MARKDOWN) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"))
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"
            val markdown = awaitProbeValue("the rendered markdown cell", {
                frame.xx("$notebook//div[@class='MarkdownCellPane']").list().singleOrNull()
            }) { it != null }!!
            markdown.doubleClick()
            val editors = awaitProbeValue("the markdown and code cell editors", {
                frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            }) { it.size == 2 }
            val focused = driver.cast(editors[0].component, CellFocusRef::class)
            awaitProbeValue("the markdown editor focused", { focused.isFocusOwner() }) { it }
            // Hover after focus has moved, so clearing the old cell's hover on focus loss is not enough.
            editors[1].hoverOnPosition(1, 2)
            fun hoverShown() = driver.ui.xx("//div[@class='JBHtmlPane']").list().any { it.hasSubtext("PROBE HOVER") }
            awaitProbeValue("the unfocused code cell's hover", ::hoverShown) { it }
            check(focused.isFocusOwner()) { "Hovering moved focus out of the markdown cell" }
            frame.keyboard { typeText("X") }
            awaitProbeValue("typing reaches the markdown editor", { editors[0].text }) { 'X' in it }
            awaitProbeValue("the hover closed after typing in markdown", ::hoverShown) { !it }
            frame.keyboard { key(KeyEvent.VK_ESCAPE) }
            awaitProbeValue("one Escape renders the markdown cell", {
                frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list().map { it.text }
            }) { it == listOf("one") }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a code cell has its extension's definitions, hovers, completions and problems`() =
        withNotebookProbe("split-mode-notebook-language", LANGUAGE) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text } }.getOrDefault(emptyList())
            fun hovers(): List<UiComponent> = runCatching { driver.ui.xx("//div[@class='JBHtmlPane']").list() }.getOrDefault(emptyList())

            awaitProbeValue("the notebook's cells in the client", ::cells) { it == listOf("defined = 1", "x = defined") }

            // Go to Declaration's keys on a name the first cell defines: that cell is edited, there.
            val second = editors()[1]
            second.click()
            second.goToPosition(1, 7)
            second.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_B) }
            frame.keyboard { typeText("Z") }
            awaitProbeValue("typing where the definition is", ::cells) { it == listOf("Zdefined = 1", "x = defined") }

            // So does a click with Go to Declaration's modifier.
            editors()[1].keyboard { pressing(KeyEvent.VK_CONTROL) { editors()[1].clickOnPosition(1, 7) } }
            frame.keyboard { typeText("Y") }
            awaitProbeValue("typing where the clicked definition is", ::cells) { it == listOf("ZYdefined = 1", "x = defined") }

            // The mouse resting on a name shows what the extension says of it.
            editors()[1].hoverOnPosition(1, 7)
            awaitProbeValue("the extension's hover", { hovers().any { it.hasSubtext("PROBE HOVER") } }) { it }

            // Typing a word opens the extension's completions, as VS Code's quick suggestions do.
            editors()[1].click()
            editors()[1].keyboard {
                key(KeyEvent.VK_END)
                typeText(" + probeCo")
            }
            // Found again each time: a lookup asked again as more is typed is another list.
            fun lookup(): List<String> = runCatching {
                frame.completionList().let { if (it.present()) it.rawItems else emptyList() }
            }.getOrDefault(emptyList())
            awaitProbeValue("the extension's completion", ::lookup) { items ->
                items.any { "probeCompletion" in it }
            }
            editors()[1].keyboard { key(KeyEvent.VK_ENTER) }
            awaitProbeValue("the completion inserted", ::cells) { it == listOf("ZYdefined = 1", "x = defined + probeCompletion") }

            // A snippet inserts as it expands, with the caret on its stop.
            editors()[1].keyboard { typeText(" + probeCa") }
            awaitProbeValue("the extension's snippet", ::lookup) { items -> items.any { "probeCall" in it } }
            editors()[1].keyboard {
                key(KeyEvent.VK_ENTER)
                typeText("Q")
            }
            awaitProbeValue("typing at the snippet's stop", ::cells) {
                it == listOf("ZYdefined = 1", "x = defined + probeCompletion + probeCall(Qarg)")
            }

            // A problem the extension reports is underlined, and its hover says what it is.
            editors()[1].keyboard {
                key(KeyEvent.VK_END)
                typeText(" oops")
            }
            awaitProbeValue("the problem underlined", { editors()[1].getAllHighlightersTextAttributes() }) { attributes ->
                attributes.any { it.effectType == JEditorUiComponent.EffectTypeValues.WAVE_UNDERSCORE }
            }
            val column = editors()[1].text.indexOf("oops") + 2
            editors()[1].hoverOnPosition(1, column)
            awaitProbeValue("the problem in the hover", { hovers().any { it.hasSubtext("PROBE PROBLEM oops") } }) { it }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `an HTML output is drawn as HTML, in the IDE's browser when it has scripts`() =
        withNotebookProbe("split-mode-notebook-html", HTML) {
            val frame = driver.ideFrame { }
            assertEquals("2", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"

            // A table, as pandas writes a data frame: in the IDE's HTML pane, not as its plain text.
            awaitProbeValue("the table drawn as HTML", {
                runCatching { frame.xx("$notebook//div[@class='HtmlOutputPane']").list().any { it.hasSubtext("PROBE TABLE") } }.getOrDefault(false)
            }) { it }
            // A page whose script makes it 300 pixels tall: in the browser, as tall as it made itself.
            awaitProbeValue("the scripted page as tall as its script made it", {
                runCatching {
                    frame.xx("$notebook//div[@class='JBCefOsrComponent']").list().map { it.component.height.toString() }
                        .ifEmpty { listOf("browsers: " + frame.xx("$notebook//div[contains(@class, 'Cef')]").list().map { it.toString() }) }
                }.getOrElse { listOf("failed: $it") }
            }) { heights -> heights.singleOrNull()?.toIntOrNull()?.let { it >= 300 } == true }
            val plain = runCatching { frame.xx("$notebook//div[@class='JBTextArea']", JTextFieldUI::class.java).list().map { it.text } }.getOrDefault(emptyList())
            check(plain.none { "PLAIN" in it }) { "An HTML output shown as its plain text: $plain" }
            Unit
        }

    @Test
    @Suppress("UnusedExpression")
    fun `a notebook changed on disk is read again, or offered to be when it has changes of its own`() =
        withNotebookProbe("split-mode-notebook-reload", NOTEBOOK) {
            val frame = driver.ideFrame { }
            assertEquals("1", command("probe.showNotebook"), "the notebook shown for the extension")
            val notebook = "//div[@accessiblename='Notebook example.ipynb']"
            val file = project.resolve("example.ipynb")

            fun editors() = frame.xx("$notebook//div[@class='EditorComponentImpl']", JEditorUiComponent::class.java).list()
            fun cells(): List<String> = runCatching { editors().map { it.text } }.getOrDefault(emptyList())
            fun offered(): Boolean = runCatching { frame.x("$notebook//div[contains(@visible_text, 'changed on disk')]").present() }.getOrDefault(false)

            awaitProbeValue("the notebook's one cell in the client", ::cells) { it == listOf("hello") }

            // Changed elsewhere, as another tool or a checkout changes it: the notebook is read again.
            file.writeText(NOTEBOOK.replace("\"hello\"", "\"from disk\""))
            awaitProbeValue("the file's new cell in the client", ::cells) { it == listOf("from disk") }
            awaitProbeValue("the host's copy read again", { command("probe.notebookState") }) {
                it?.contains(""""example.ipynb":["from disk"]""") == true
            }

            // Changed again while the notebook has changes of its own: those stay until the user says.
            val cell = editors().first()
            cell.click()
            cell.keyboard {
                key(KeyEvent.VK_END)
                typeText(" mine")
            }
            awaitProbeValue("the typed text", ::cells) { it == listOf("from disk mine") }
            file.writeText(NOTEBOOK.replace("\"hello\"", "\"again\""))
            awaitProbeValue("the offer to read the file again", ::offered) { it }
            check(cells() == listOf("from disk mine")) { "The notebook's own changes were let go unasked: ${cells()}" }

            // The banner's links are EditorNotificationPanel's own HyperlinkLabel subclass.
            frame.x("$notebook//div[@class='ActionHyperlinkLabel' and @accessiblename='Reload from Disk']").click()
            awaitProbeValue("the file's cell once read again", ::cells) { it == listOf("again") }
            awaitProbeValue("the offer gone", ::offered) { !it }
            Unit
        }

    private companion object {
        /** A table, as pandas writes one, and a page a script draws; each with a plain text the editor is not to show. */
        val HTML = """
            {
             "cells": [
              {"cell_type": "code", "execution_count": 1, "metadata": {}, "source": ["table"], "outputs": [
               {"output_type": "display_data", "metadata": {}, "data": {
                "text/html": ["<table class=\"dataframe\"><tr><th>A</th></tr><tr><td>PROBE TABLE</td></tr></table>"],
                "text/plain": ["PLAIN TABLE"]}}
              ]},
              {"cell_type": "code", "execution_count": 2, "metadata": {}, "source": ["script"], "outputs": [
               {"output_type": "display_data", "metadata": {}, "data": {
                "text/html": ["<div id=\"drawn\"></div><script>document.getElementById('drawn').style.height = '300px';</script>"],
                "text/plain": ["PLAIN SCRIPT"]}}
              ]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        val LANGUAGE = """
            {
             "cells": [
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["defined = 1"]},
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["x = defined"]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        /** A line far wider than the notebook, which must wrap rather than widen it. */
        val LONG = "Some text" + " that goes on".repeat(40)

        val MARKDOWN = """
            {
             "cells": [
              {"cell_type": "markdown", "metadata": {}, "source": ["# Lesson\n", "$LONG"]},
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["one"]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        val NOTEBOOK = """
            {
             "cells": [
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["hello"]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        /** A cell the probe's kernel runs until it is stopped. */
        val WAITING = """
            {
             "cells": [
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["wait"]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        /** Cells of three lines: tall enough that the run button and execution order don't overlap. */
        val THREE_TALL_CELLS = """
            {
             "cells": [
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["one\n", "1\n", "1"]},
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["two\n", "2\n", "2"]},
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["three\n", "3\n", "3"]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()

        val TWO_CELLS = """
            {
             "cells": [
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["one"]},
              {"cell_type": "code", "execution_count": null, "metadata": {}, "outputs": [], "source": ["two"]}
             ],
             "metadata": {"language_info": {"name": "python"}},
             "nbformat": 4,
             "nbformat_minor": 5
            }
        """.trimIndent()
    }
}

@Remote("javax.accessibility.AccessibleContext")
internal interface AccessibleStatesRef {
    fun getAccessibleStateSet(): AccessibleStateSetRef
}

@Remote("javax.accessibility.AccessibleStateSet")
internal interface AccessibleStateSetRef {
    fun toArray(): Array<AccessibleStateRef>
}

@Remote("javax.accessibility.AccessibleState")
internal interface AccessibleStateRef {
    fun toDisplayString(): String
}
