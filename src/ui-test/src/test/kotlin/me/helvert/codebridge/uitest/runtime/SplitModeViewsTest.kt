package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JCefUI
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.jcef
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.uitest.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real extension tree commands and browser messages, observed in the JetBrains Client. */
@ExtendWith(RemoteDevRun::class)
class SplitModeViewsTest {
    @Remote("me.helvert.codebridge.language.HostedLanguageServers", plugin = "me.helvert.codebridge.runtime/CodeBridge.backend")
    interface HostedLanguageServersRef {
        fun executeCommandForTest(command: String, argumentsJson: String): String?
    }

    @Test
    fun `a tree refreshes and opens an interactive webview whose state survives a page reload`() =
        withProbe("split-mode-views") { driver, servers ->
            val frame = driver.ideFrame { }
            val stripe = frame.x(stripeButton("probeUI"))
            awaitValue("the contributed tool window in the client", { stripe.present() }) { it }
            val tree = frame.tree("//div[@class='ItemTree']")
            open("the extension's tree in the client", stripe::click) { tree.present() }
            awaitValue("the lazy tree root", { tree.collectExpandedPathsAsStrings() }) { paths ->
                paths.any { it.contains("Probe group") }
            }
            tree.expandPath("Probe group", fullMatch = false)
            awaitValue("the provider's child item", { tree.collectExpandedPathsAsStrings() }) { paths ->
                paths.any { it.contains("Open probe panel 0") }
            }

            // Drive the contributed title action, then check the refreshed child's visible label.
            frame.actionButton { byAccessibleName("Refresh probe tree") }.click()
            awaitValue(
                "the refreshed tree, retaining its expanded group",
                { tree.collectExpandedPathsAsStrings() }) { paths ->
                paths.any { it.contains("Open probe panel 1") } && paths.none { it.contains("Open probe panel 0") }
            }
            tree.clickRow { it.contains("Open probe panel 1") }
            val browser = frame.jcef()
            awaitValue("the webview opened by the tree command", { browser.present() }) { it }
            awaitValue(
                "the page's extension resource script loaded",
                { browser.getHtml() }) { it.contains("Clicks: 0") }
            // Posted as the panel was made, before its page -- or its description -- reached the client.
            awaitValue(
                "the message the extension posted at once",
                { browser.getHtml() }) { it.contains("Hello received") }

            clickSend(browser, clicks = 0)
            awaitValue("the page-to-extension-to-page message round trip", { browser.getHtml() }) {
                it.contains("Extension acknowledged 1") && it.contains("Clicks: 1")
            }
            awaitValue(
                "the panel title updated by the extension",
                { frame.editorTabs().isTabOpened("Probe panel 1") }) { it }

            browser.findElement("//button[@id='burst']").click()
            awaitValue("a burst of messages reaching the extension in the order sent", { webviewState(servers) }) {
                it?.contains(""""burst":${(0 until 50).joinToString(",", "[", "]")}""") == true
            }

            // Bytes as a view into the middle of a buffer: they reach the extension as such, and back.
            browser.findElement("//button[@id='binary']").click()
            awaitValue("bytes reaching the extension", { webviewState(servers) }) {
                it?.contains(""""binary":[[1,2,3,250]]""") == true
            }
            awaitValue("bytes echoed back to the page", { browser.getHtml() }) {
                it.contains("Binary echoed 250,3,2,1 as Uint8Array")
            }

            browser.findElement("//a[@id='command']").click()
            awaitValue("the command the page's link names, run with its arguments", { webviewState(servers) }) {
                it?.contains(""""links":["from-page"]""") == true
            }

            val previousUrl = browser.getUrl()
            assertEquals("true", servers.executeCommandForTest("probe.reloadPage", "[]"))
            awaitValue("a new page loaded", { browser.getUrl() }) { it != previousUrl }
            awaitValue("saved webview state restored after reloading HTML", { browser.getHtml() }) {
                it.contains("Clicks: 1") && it.contains("Waiting for click")
            }
            clickSend(browser, clicks = 1)
            awaitValue("messages working after the page reload", { browser.getHtml() }) {
                it.contains("Extension acknowledged 2") && it.contains("Clicks: 2")
            }
            awaitValue("the second panel title update", { frame.editorTabs().isTabOpened("Probe panel 2") }) { it }

            driver.invokeAction("CloseContent", component = browser.component)
            awaitValue("closing the tab disposing the extension's panel", {
                servers.executeCommandForTest("probe.uiState", "[]")
            }) { it == """{"open":false,"clicks":2,"generation":1}""" }
            awaitValue("the closed panel tab removed", { frame.editorTabs().isTabOpened("Probe panel 2") }) { !it }

            // The same command must create a fresh page after the old browser and panel were disposed.
            tree.clickRow { it.contains("Open probe panel 1") }
            val freshBrowser = frame.jcef()
            awaitValue("a fresh panel after closing", { if (freshBrowser.present()) freshBrowser.getHtml() else "" }) {
                it.contains("Clicks: 0") && it.contains("Waiting for click") && it.contains("Hello received")
            }
        }

    @Test
    fun `a webview view is made when its tool window is first shown and keeps its page while hidden`() =
        withProbe("split-mode-webview-view") { driver, servers ->
            val frame = driver.ideFrame { }
            assertEquals("""{"resolved":0,"clicks":0,"visibility":[]}""", viewState(servers))
            val stripe = frame.x(stripeButton("probeWebview"))
            awaitValue("the webview view's tool window in the client", { stripe.present() }) { it }
            val browser = frame.jcef()
            open("the view's page", stripe::click) { browser.present() }
            awaitValue("the view's resource script loaded", { browser.getHtml() }) { it.contains("Clicks: 0") }
            assertEquals("""{"resolved":1,"clicks":0,"visibility":[]}""", viewState(servers))

            clickSend(browser, clicks = 0)
            awaitValue("the view's message round trip", { browser.getHtml() }) {
                it.contains("Extension acknowledged 1") && it.contains("Clicks: 1")
            }

            stripe.click()
            awaitValue("the extension hearing the view was hidden", { viewState(servers) }) {
                it == """{"resolved":1,"clicks":1,"visibility":[false]}"""
            }
            stripe.click()
            awaitValue("the extension hearing the view was shown again", { viewState(servers) }) {
                it == """{"resolved":1,"clicks":1,"visibility":[false,true]}"""
            }
            // The same page, not made again: what it showed is still there.
            awaitValue("the view's page kept while hidden", { browser.getHtml() }) {
                it.contains("Extension acknowledged 1") && it.contains("Clicks: 1")
            }
        }

    /**
     * The stripe button of the tool window for view container [containerId]. Found by the window's id,
     * which is what the button gives as its name: it keeps the title the window was registered with,
     * see "A stripe button keeps the title its tool window was registered with" in
     * docs/platform-limitations.md.
     */
    private fun stripeButton(containerId: String) =
        "//div[@class='SquareStripeButton' and @accessiblename='CodeBridge.$containerId']"

    /**
     * Opens a tool window by [click]ing its stripe button until [shown] -- its content is there. A click
     * while the client is still starting is sometimes lost, so one that showed nothing in a while is
     * made again; never sooner, as a second click on a window that did open closes it.
     */
    private fun open(description: String, click: () -> Unit, shown: () -> Boolean) {
        repeat(3) {
            click()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                if (shown()) return
                Thread.sleep(500)
            }
        }
        error("Timed out waiting for $description after three clicks")
    }

    private fun webviewState(servers: HostedLanguageServersRef): String? =
        servers.executeCommandForTest("probe.webviewState", "[]")

    private fun viewState(servers: HostedLanguageServersRef): String? =
        servers.executeCommandForTest("probe.viewState", "[]")

    /**
     * Runs [test] in a split IDE with the workbench probe installed and activated, given the client's
     * driver and the backend's language servers; the extension host's output is printed should it fail.
     */
    private fun withProbe(testName: String, test: (Driver, HostedLanguageServersRef) -> Any?) {
        val project = Files.createTempDirectory("codebridge-split-views")
        project.resolve("example.json").writeText("{}\n")
        val context = Starter.newContext(
            testName = testName,
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.RUNTIME)
        ExtensionStore(context.paths.configDir.resolve("codebridge/extensions"))
            .install(requiredPath("codebridge.workbench.vsix"))
        context.useShortClientSystemDir()
        context.frontendIDEContext.applyVMOptionsPatch { addSystemProperty("ide.browser.jcef.jsQueryPoolSize", 100) }
        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode())
        var failure: Throwable? = null
        try {
            val driver = run.driver
            assertTrue(driver.isRemDevMode, "This must run through a JetBrains Client")
            val servers = driver.service(HostedLanguageServersRef::class, backendProject(driver), RdTarget.BACKEND)
            awaitValue("the UI probe activated", {
                runCatching { servers.executeCommandForTest("probe.uiState", "[]") }.getOrNull()
            }) { it == """{"open":false,"clicks":0,"generation":0}""" }
            // Hard references for as long as the test runs: the browser it polls is otherwise a weak one,
            // and the client may collect it between two looks.
            driver.withContext { test(driver, servers) }
        } catch (error: Throwable) {
            failure = error
            // What the client had on its tool window stripes, for a failure to find one of them.
            runCatching {
                val stripes = run.driver.ideFrame { }.xx("//div[contains(@class,'StripeButton')]").list()
                println("Stripe buttons in the client (${stripes.size}):\n" + stripes.joinToString("\n") {
                    "  ${it.component.getClass().getName()}: ${
                        it.component.getAccessibleContext()?.getAccessibleName()
                    }"
                })
            }.onFailure(error::addSuppressed)
            runCatching {
                val hosts = context.paths.systemDir.resolve("codebridge/hosts")
                if (Files.isDirectory(hosts)) Files.walk(hosts).use { paths ->
                    paths.filter { it.fileName.toString() == "exthost-stdout.log" }.forEach {
                        println("Extension host output ($it):\n${it.readText().takeLast(8_000)}")
                    }
                }
            }.onFailure(error::addSuppressed)
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

    private fun backendProject(driver: Driver): Project = awaitValue("the backend project", {
        runCatching { driver.singleProject(RdTarget.BACKEND) }.getOrNull()?.takeIf { it.isInitialized() }
    }) { it != null }!!

    /**
     * Clicks the page's Send button until the page has counted the click, [clicks] being its count
     * before. The driver clicks a point of the browser rather than the element, and on CI one such
     * click never reached the page; its count says whether one landed, so another is sent only then.
     */
    private fun clickSend(browser: JCefUI, clicks: Int) {
        repeat(3) {
            browser.findElement("//button[@id='send']").click()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if ("Clicks: ${clicks + 1}" in browser.getHtml()) return
                Thread.sleep(500)
            }
        }
    }

    private fun <T> awaitValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var last: T? = null
        while (System.nanoTime() < deadline) {
            last = read()
            if (matches(last)) return last
            Thread.sleep(500)
        }
        error("Timed out waiting for $description; last observed: $last")
    }
}
