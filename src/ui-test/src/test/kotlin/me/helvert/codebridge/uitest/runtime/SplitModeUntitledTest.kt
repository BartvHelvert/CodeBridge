package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.uitest.Installed
import me.helvert.codebridge.uitest.builtAgainst
import me.helvert.codebridge.uitest.install
import me.helvert.codebridge.uitest.runSplitIde
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Can an untitled buffer an extension created on the backend be shown in the client?
 *
 * An untitled buffer is an in-memory file, which the backend has to hand to the client across the
 * split before the client can open it -- a path no single-process fixture takes. `OpenTextDocumentTest`
 * covers the rest of its life. No extension host is involved, for the reason `SplitModeDrawingTest`
 * gives: a failure here should mean the split, not the host.
 */
@ExtendWith(RemoteDevRun::class)
class SplitModeUntitledTest {

    @Remote("me.helvert.codebridge.language.HostedLanguageServers", plugin = "$PLUGIN_ID/CodeBridge.backend")
    interface HostedLanguageServersRef {
        fun showUntitledForTest(content: String): String?
    }

    @Test
    fun `an untitled buffer made on the backend is shown in the client`() {
        val project = Files.createTempDirectory("codebridge-split-untitled").also { it.resolve("example.txt").writeText("x") }

        val context = Starter.newContext(
            testName = "split-mode-untitled",
            testCase = builtAgainst(project),
        )
        (context as IDERemDevTestContext).install(Installed.RUNTIME)

        val run = context.runSplitIde(commands = CommandChain().waitForSmartMode())
        try {
            val driver = run.driver
            val servers = driver.service(HostedLanguageServersRef::class, backendProject(driver), RdTarget.BACKEND)

            // An editor id comes back only once the client has opened the buffer and the backend has
            // heard about it: `showTextDocument` waits for exactly that.
            val editor = servers.showUntitledForTest(CONTENT)
            assertNotNull(editor, "The backend never heard of an editor showing the untitled buffer")

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var shown = ""
            while (System.nanoTime() < deadline && CONTENT !in shown) {
                shown = runCatching { driver.ideFrame { }.codeEditor().text }.getOrDefault("")
                Thread.sleep(500)
            }
            assertTrue(CONTENT in shown, "The client's editor shows '$shown', not the untitled buffer")
        } finally {
            run.closeIdeAndWait()
        }
    }

    /** The project, once the backend has opened it; see `SplitModeDrawingTest.backendProject`. */
    private fun backendProject(driver: Driver): Project {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            runCatching { driver.singleProject(RdTarget.BACKEND) }
                .getOrNull()?.takeIf { it.isInitialized() }?.let { return it }
            Thread.sleep(500)
        }
        error("The backend never opened a project")
    }

    private companion object {
        const val PLUGIN_ID = "me.helvert.codebridge.runtime"
        const val CONTENT = "Written by an extension, saved nowhere"
    }
}
