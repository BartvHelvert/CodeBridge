package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.EditorTabsRef
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.JCefUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.jcef
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.PopupMenuUiComponent
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import me.helvert.codebridge.uitest.enforcePerformanceBudgets
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.awt.event.KeyEvent
import kotlin.io.path.readText
import kotlin.test.assertEquals

/**
 * Custom editors as the user meets them in a JetBrains Client: a file an extension's
 * `contributes.customEditors` claims opens in the page the extension draws for it rather than as
 * text (`custom-editor-probe.js`). The page's keys save, undo and redo the document: the file's text
 * for a text editor provider, the extension's own document -- whose edits it undoes itself -- for a
 * custom one, which is saved when its editor closes with changes.
 *
 * Each file is opened as an extension opens it, with `vscode.open`: the driver's own opening waits
 * for the file's text editor, which a custom editor takes the place of.
 */
@ExtendWith(RemoteDevRun::class)
@Suppress("OPT_IN_USAGE") // The keys are what is tested: the page hands them to the workbench.
class SplitModeCustomEditorTest {
    @Test
    fun `a text file opens in its extension's editor, whose edits undo and save`() =
        withWorkbenchProbe("split-mode-custom-text-editor", "", files = mapOf("example.probetext" to "hello\n")) {
            textEditor()
        }

    @Test
    fun `an extension's own document undoes its edits, saves, and is saved when closed with changes`() =
        withWorkbenchProbe("split-mode-custom-editor", "", files = mapOf("example.probecounter" to "0\n")) {
            counterEditor()
        }

    @Test
    fun `a file the IDE would hand to the system, opened from the Project view, opens in its custom editor`() =
        withWorkbenchProbe("split-mode-native-custom-editor", "", files = mapOf("example.probecounter.pdf" to "3\n")) {
            nativeFile()
        }

    @Test
    fun `a page as large as drawio's loads its resources quickly, and again when reopened`() =
        withWorkbenchProbe("split-mode-heavy-webview", "", files = heavyFiles() + ("example.probeheavy" to "heavy\n")) {
            heavyEditor()
        }

    @Test
    fun `the editor switcher beside the tabs reopens a file as text and back, and sets the default`() =
        withWorkbenchProbe("split-mode-custom-editor-switcher", "", files = mapOf("example.probetext" to "hello\n")) {
            val settings = driver.utility(UISettingsRef::class, RdTarget.FRONTEND).getInstance()
            val keepPopups = driver.withContext(OnDispatcher.EDT) {
                settings.getKeepPopupsForToggles().also { settings.setKeepPopupsForToggles(true) }
            }
            try {
                switcher()
            } finally {
                driver.withContext(OnDispatcher.EDT) { settings.setKeepPopupsForToggles(keepPopups) }
            }
        }

    @Test
    fun `Open With in the Project view opens a file in an editor it opens in only when asked for`() =
        withWorkbenchProbe("split-mode-custom-editor-open-with", "", files = mapOf("example.probetext" to "hello\n")) {
            openWith()
        }

    /**
     * *Open With* on the Project view's file, as the user opens the Hex Editor beside a file's own
     * custom editor: the file there is the Project view's, not the one an editor tab shows.
     */
    private fun WorkbenchProbe.openWith() {
        val frame = driver.ideFrame { }
        val name = "example.probetext"
        open(name)
        awaitProbeValue("the custom editor's page", { frame.jcef().present() }) { it }

        driver.invokeAction("ActivateProjectToolWindow")
        frame.projectView { projectViewTree.rightClickPath(project.fileName.toString(), name, fullMatch = false) }
        val menu = driver.ui.popupMenu()
        // At the top, under New, where a file is opened from.
        awaitProbeValue("'Open With' under 'New' in the Project view's menu", { if (menu.present()) menu.itemsList() else emptyList() }) {
            it.indexOf("Open With") == it.indexOf("New") + 1 && "New" in it
        }
        menu.select("Open With")
        // The submenu is a menu of its own, listing the text editor among the file's editors.
        val editor = "Probe Text Viewer"
        val submenu = awaitProbeValue("the editors in the 'Open With' submenu", {
            driver.ui.xx("//div[@class='MyMenu']", PopupMenuUiComponent::class.java).list()
                .firstOrNull { runCatching { editor in it.itemsList() }.getOrDefault(false) }
        }) { it != null && "Text Editor" in it.itemsList() }
        checkNotNull(submenu).select(editor)

        // The probe's own editor resolved it before; the viewer resolving it too is its page made, beside that one.
        awaitProbeValue("the file resolved in the viewer", { command("probe.customEditorState") }) {
            it?.contains(""""viewer":{"resolved":["$name"]""") == true
        }
    }

    /**
     * VS Code's editor picker above its editors, here beside the tabs: it names the editor the tab
     * shows, switches to another, and makes one the default, which a file opened later then opens in.
     */
    private fun WorkbenchProbe.switcher() {
        val frame = driver.ideFrame { }
        val name = "example.probetext"
        fun textShowing() = frame.xx("//div[@class='EditorComponentImpl' and contains(@accessiblename, '$name')]").list().any { it.component.isShowing() }
        fun pageShowing() = frame.jcef().present()
        fun switcher(editor: String) = frame.x("//div[@class='ActionButtonWithText' and @accessiblename='$editor']")
        /** Opens the switcher, now naming [editor], and chooses [path], a submenu at a time. */
        fun choose(editor: String, vararg path: String) {
            frame.dismissTrialNotification()
            // The menu, and each submenu opened from it, is a list of its own: the one at [level].
            fun menu(level: Int) = driver.ui.xx("//div[@class='MyList']", JListUiComponent::class.java).list().getOrNull(level)
            // Clicked again while no menu shows: the button ignores a click just after its last menu closed.
            awaitProbeValue("the switcher naming $editor opening its menu", {
                runCatching { switcher(editor).takeIf { it.present() }?.click() }
                (1..10).any { runCatching { menu(0) != null }.getOrDefault(false) || run { Thread.sleep(300); false } }
            }) { it }
            path.forEachIndexed { level, item ->
                fun menu() = menu(level)
                // By where it starts: "Text Editor" is within "Probe Text Editor" too.
                val items = awaitProbeValue("'$item' in the switcher's menu", { runCatching { menu()?.rawItems }.getOrNull().orEmpty() }) { items ->
                    items.any { it.startsWith(item) }
                }
                val popup = checkNotNull(menu())
                popup.selectItem(items.first { it.startsWith(item) })
                // Chosen as Enter chooses it, without input: on Windows the click fixture can highlight an
                // item without invoking it, and on Xvfb a reused popup window can stay under the frame,
                // focused for Swing but never sent the key.
                driver.withContext(OnDispatcher.EDT) {
                    checkNotNull(driver.utility(PopupUtilRef::class, RdTarget.FRONTEND).getPopupContainerFor(popup.component)) {
                        "No popup holds the switcher's menu"
                    }.handleSelect(true)
                }
            }
            awaitProbeValue("the editor switcher's menu closed after choosing ${path.last()}", {
                menu(0) == null
            }) { it }
        }

        open(name)
        awaitProbeValue("the custom editor's page", { pageShowing() }) { it }
        // The file selected in the Project view names the file, not its tab: still the custom editor.
        driver.invokeAction("ActivateProjectToolWindow")
        frame.projectView { projectViewTree.clickPath(project.fileName.toString(), name, fullMatch = false) }

        repeat(3) {
            choose("Probe Text Editor", "Text Editor")
            awaitProbeValue("the file shown as text", { textShowing() }) { it }

            choose("Text Editor", "Probe Text Editor")
            awaitProbeValue("the file back in its custom editor", { pageShowing() && !textShowing() }) { it }
            Unit
        }

        choose("Probe Text Editor", "Set Default for '*.probetext'", "Text Editor")
        awaitProbeValue("the default editor association saved", { userSettings.readText() }) {
            Regex(""""\*\.probetext"\s*:\s*"default"""").containsMatchIn(it)
        }
        // Kept by the open tab, as VS Code keeps it; a file opened afterwards opens as text.
        awaitProbeValue("the custom editor still shown", { pageShowing() }) { it }
        driver.invokeAction("CloseContent", component = frame.jcef().component)
        open(name)
        awaitProbeValue("the file opened as text by default", { textShowing() }) { it }
        awaitProbeValue("the switcher naming the text editor", { switcher("Text Editor").present() }) { it }
    }

    private fun WorkbenchProbe.heavyEditor() {
        val frame = driver.ideFrame { }
        fun loads() = Regex(""""ms":(\d+)""").findAll(command("probe.customEditorState").orEmpty())
            .map { it.groupValues[1].toInt() }.toList()
        // Reopened twice: every load must finish and run all scripts, including after the browser was disposed.
        repeat(3) { round ->
            open("example.probeheavy")
            val ms = awaitProbeValue("load ${round + 1} of the heavy page", ::loads) { it.size > round }[round]
            println("Heavy webview page, load ${round + 1}: $ms ms; ${command("probe.customEditorState")}")
            if (enforcePerformanceBudgets) {
                check(ms < HEAVY_PAGE_MILLIS) { "Load ${round + 1} of the heavy page took $ms ms" }
            }
            val browser = frame.jcef()
            awaitProbeValue("every script run", { html(browser) }) { "Loaded $SMALL_SCRIPTS small scripts" in it }
            driver.invokeAction("CloseContent", component = browser.component)
        }
    }

    /**
     * What draw.io's page loads, in size: [HEAVY_SCRIPT_MEGABYTES] MB of script, as minified code
     * is, which says how many lines it has, and [SMALL_SCRIPTS] small files that count themselves.
     */
    private fun heavyFiles(): Map<String, String> {
        val big = StringBuilder()
        var lines = 0
        while (big.length < HEAVY_SCRIPT_MEGABYTES * 1024 * 1024) {
            big.append("/*").append(lines.toString(36)).append("*/a").append(lines)
                .append("=function(b,c){return b+c*").append(lines).append("};\n")
            lines++
        }
        big.append("window.__heavy=").append(lines).append(";\n")
        val small = (0 until SMALL_SCRIPTS).associate { "heavy-small-$it.js" to "window.__small++; /* ${"x".repeat(2000 + it)} */\n" }
        return small + ("heavy-big.js" to big.toString())
    }

    /**
     * A `.pdf` is one of the IDE's native file types, which navigation hands to the system's viewer
     * without opening any editor -- on a split-mode backend, to nothing at all. Opened as the user
     * opens it, by double-clicking it in the Project view, which is navigation and not `vscode.open`.
     */
    private fun WorkbenchProbe.nativeFile() {
        val frame = driver.ideFrame { }
        val name = "example.probecounter.pdf"
        driver.invokeAction("ActivateProjectToolWindow")
        frame.projectView { projectViewTree.doubleClickPath(project.fileName.toString(), name, fullMatch = false) }
        val browser = frame.jcef()
        awaitProbeValue("the custom editor's page", { runCatching { browser.present() }.getOrDefault(false) }) { it }
        awaitProbeValue("the file's count in the page", { html(browser) }) { "Count: 3</p>" in it }
        awaitProbeValue("the document made from the file", { command("probe.customEditorState") }) {
            it?.contains(""""opened":["$name"]""") == true
        }
    }

    private fun WorkbenchProbe.textEditor() {
        val frame = driver.ideFrame { }
        fun state() = command("probe.customEditorState")
        open("example.probetext")
        // The tab's icon is the backend's for the file: the monogram of the probe's `probetext` language,
        // which the client, knowing no such language, would otherwise draw as plain text.
        awaitProbeValue("the tab drawn with the file's language icon", { tabIcon(frame, "example.probetext") }) { "monograms" in it.orEmpty() }
        val browser = frame.jcef()
        awaitProbeValue("the custom editor's page", { runCatching { browser.present() }.getOrDefault(false) }) { it }
        awaitProbeValue("the file's text in the page", { html(browser) }) { "Text: hello</p>" in it }
        awaitProbeValue("the editor resolved once", ::state) { it?.contains(""""resolved":["example.probetext"]""") == true }
        // In place of the text, not beside it.
        val text = frame.xx("//div[@class='EditorComponentImpl' and contains(@accessiblename, 'example.probetext')]").list()
        check(text.none { it.component.isShowing() }) { "The file's text showed beside its custom editor" }

        // The extension's workspace edit, then the page's undo and redo keys taking it back and making it again.
        browser.findElement("//button[@id='act']").click()
        awaitProbeValue("the extension's edit in the page", { html(browser) }) { "Text: hello!</p>" in it }
        focusValue(frame, browser)
        frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z) }
        awaitProbeValue("the edit undone", { html(browser) }) { "Text: hello</p>" in it }
        frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) }
        awaitProbeValue("the edit redone", { html(browser) }) { "Text: hello!</p>" in it }
        frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_S) }
        awaitProbeValue("the edited text saved", { project.resolve("example.probetext").readText() }) { it == "hello!\n" }

        driver.invokeAction("CloseContent", component = browser.component)
        awaitProbeValue("closing the tab disposing the extension's panel", ::state) { it?.contains(""""disposed":1""") == true }
    }

    private fun WorkbenchProbe.counterEditor() {
        val frame = driver.ideFrame { }
        val file = project.resolve("example.probecounter")
        fun state() = command("probe.customEditorState")
        open("example.probecounter")
        val browser = frame.jcef()
        fun count(value: Int) = awaitProbeValue("the count at $value", { html(browser) }) { "Count: $value</p>" in it }

        awaitProbeValue("the custom editor's page", { runCatching { browser.present() }.getOrDefault(false) }) { it }
        count(0)
        awaitProbeValue("the document made from the file", ::state) { it?.contains(""""opened":["example.probecounter"]""") == true }

        browser.findElement("//button[@id='act']").click()
        count(1)
        browser.findElement("//button[@id='act']").click()
        count(2)
        // The page shows a count before the workbench hears of its edit, which has to be there to be
        // undone: the editor is modified once the backend has it.
        awaitProbeValue("the workbench holding the extension's edits", { selectedEditorModified() }) { it == true }
        focusValue(frame, browser)
        pageUndoKey(frame, browser, redo = false)
        awaitProbeValue("the extension asked to undo", ::state) { it?.contains(""""undone":1""") == true }
        count(1)
        pageUndoKey(frame, browser, redo = true)
        count(2)
        pageUndoKey(frame, browser, redo = false)
        count(1)
        awaitProbeValue("the extension asked to undo twice and redo once", ::state) {
            it?.contains(""""undone":2""") == true && it.contains(""""redone":1""")
        }

        frame.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_S) }
        awaitProbeValue("the document saved", { file.readText() }) { it == "1\n" }

        // Changed again and closed unsaved: saved on the way, as a closed notebook is.
        browser.findElement("//button[@id='act']").click()
        count(2)
        driver.invokeAction("CloseContent", component = browser.component)
        awaitProbeValue("the changes saved as the editor closed", { file.readText() }) { it == "2\n" }
        awaitProbeValue("the document let go of", ::state) {
            it?.contains(""""saved":[1,2]""") == true && it.contains(""""disposed":1""")
        }
    }

    /**
     * Ctrl+Z, or Ctrl+Shift+Z to [redo], pressed in [browser]'s page.
     *
     * On Windows the page is handed the key itself: the driver posts key events rather than typing
     * them, and those carry no scan code, which is what the off-screen browser there makes a page's
     * key from. Each reaches the page as an unidentified key, so the page never sees an undo.
     */
    private fun pageUndoKey(frame: IdeaFrameUI, browser: JCefUI, redo: Boolean) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            frame.keyboard {
                if (redo) hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_Z) else hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_Z)
            }
            return
        }
        browser.jcefWorker.runJs(
            "(document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', " +
                "{key: '${if (redo) "Z" else "z"}', code: 'KeyZ', ctrlKey: true, shiftKey: $redo, bubbles: true, cancelable: true}))"
        )
    }

    /** Whether the client's selected editor has changes, which a custom editor's are the backend's; null while it cannot say. */
    private fun WorkbenchProbe.selectedEditorModified(): Boolean? = runCatching {
        driver.service(FileEditorManagerRef::class, driver.singleProject()).getSelectedEditor()?.isModified()
    }.getOrNull()

    @Remote("com.intellij.openapi.ui.popup.util.PopupUtil")
    interface PopupUtilRef {
        fun getPopupContainerFor(component: Component): ListPopupRef?
    }

    @Remote("com.intellij.ide.ui.UISettings")
    interface UISettingsRef {
        fun getInstance(): UISettingsRef
        fun getKeepPopupsForToggles(): Boolean
        fun setKeepPopupsForToggles(value: Boolean)
    }

    @Remote("com.intellij.openapi.ui.popup.ListPopup")
    interface ListPopupRef {
        fun handleSelect(handleFinalChoices: Boolean)
    }

    @Remote("com.intellij.openapi.fileEditor.FileEditorManager")
    interface FileEditorManagerRef {
        fun getSelectedEditor(): FileEditorRef?
    }

    @Remote("com.intellij.openapi.fileEditor.FileEditor")
    interface FileEditorRef {
        fun isModified(): Boolean
    }

    /** The command awaits opening; replaying it after a delayed response can open the file twice. */
    private fun WorkbenchProbe.open(name: String) {
        assertEquals("\"$name\"", command("probe.openCustomEditorFile"), "$name opened")
    }

    /** What the icon of editor tab [name] is, or null while there is no such tab. */
    private fun WorkbenchProbe.tabIcon(frame: IdeaFrameUI, name: String): String? = runCatching {
        frame.xx("//div[@class='EditorTabs']").list().firstNotNullOfOrNull { tabs ->
            driver.cast(tabs.component, EditorTabsRef::class).getTabs().firstOrNull { it.text == name }?.getIcon()?.toString()
        }
    }.getOrNull()

    private companion object {
        const val HEAVY_SCRIPT_MEGABYTES = 16
        const val SMALL_SCRIPTS = 40

        /**
         * How long the heavy page may take to load. Its files once took 15 seconds to arrive, each
         * byte a number in the RPC message, and as long again when the page was opened once more.
         */
        const val HEAVY_PAGE_MILLIS = 8_000
    }

    /**
     * Focuses the page's value, for the keys typed next to reach the page: the client's frame first,
     * which a startup window or the browser's own may have taken focus from. Asking again is
     * idempotent, where a replayed key would not be.
     */
    private fun WorkbenchProbe.focusValue(frame: IdeaFrameUI, browser: JCefUI) {
        awaitProbeValue("the client's frame focused", {
            if (!frame.isFocused()) driver.withContext(OnDispatcher.EDT) { frame.component.requestFocus() }
            frame.isFocused()
        }) { it }
        browser.findElement("//p[@id='value']").click()
        awaitProbeValue("the page's value focused", {
            browser.callJs("String(document.hasFocus() && document.activeElement.id === 'value')")
        }) { it == "true" }
    }

    /** Lookup timeouts are retried by [awaitProbeValue], retaining the failure on timeout. */
    private fun html(browser: JCefUI): String = browser.getHtml()
}
