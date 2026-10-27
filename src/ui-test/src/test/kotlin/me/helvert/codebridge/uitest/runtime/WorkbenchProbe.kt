package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.singleProject
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.uitest.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class WorkbenchProbe(val driver: Driver, val project: Path) {
    private val backend = awaitProbeValue("the backend project", {
        runCatching { driver.singleProject(RdTarget.BACKEND) }.getOrNull()?.takeIf { it.isInitialized() }
    }) { it != null }!!
    private val servers = driver.service(SplitModeViewsTest.HostedLanguageServersRef::class, backend, RdTarget.BACKEND)
    fun command(name: String): String? = servers.executeCommandForTest(name, "[]")
}

/** A balloon: a message an extension shows, or one of the IDE's own notifications. */
internal const val BALLOON = $$"//div[@javaclass='com.intellij.ui.BalloonImpl$MyComponent']"

/** Keeps each workflow's project, installation and logs isolated, with one backend/client pair. */
internal fun withWorkbenchProbe(
    name: String,
    content: String,
    /** The user settings, see [writeUserSettings]. */
    settings: String = """{"files.autoSave":"off"}""",
    /** Log categories traced in both halves, for a workflow whose failures the default log leaves unexplained. */
    traced: String? = null,
    test: WorkbenchProbe.() -> Unit,
) {
    val project = Files.createTempDirectory("codebridge-$name")
    project.resolve("example.codebridgeactions").writeText(content)
    val context = Starter.newContext(
        testName = name,
        testCase = builtAgainst(project),
    ) as IDERemDevTestContext

    context.install(Installed.RUNTIME)
    context.writeUserSettings(settings)
    ExtensionStore(context.paths.configDir.resolve("codebridge/extensions")).install(requiredPath("codebridge.workbench.vsix"))
    context.disableAutoCompletion()
    context.frontendIDEContext.disableAutoCompletion()
    context.useShortClientSystemDir()
    if (traced != null) {
        context.applyVMOptionsPatch { addSystemProperty("idea.log.trace.categories", traced) }
        context.frontendIDEContext.applyVMOptionsPatch { addSystemProperty("idea.log.trace.categories", traced) }
    }
    val run = context.runSplitIde(commands = CommandChain().waitForSmartMode(), open = "example.codebridgeactions")
    var failure: Throwable? = null
    try {
        val probe = WorkbenchProbe(run.driver, project)
        check(run.driver.isRemDevMode)
        awaitProbeValue(
            "the workbench probe activated",
            { runCatching { probe.command("probe.uiState") }.getOrNull() }) {
            it != null
        }
        probe.test()
    } catch (error: Throwable) {
        failure = error
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
