package me.helvert.codebridge.uitest.runtime

import com.intellij.driver.client.Driver
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.BackgroundRun
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

internal class WorkbenchProbe(
    val driver: Driver,
    val project: Path,
    private val home: Path,
    /** The user settings file, see [writeUserSettings]. */
    val userSettings: Path,
    private val logs: WorkbenchLogs = WorkbenchLogs(home),
) {
    val backend = awaitProbeValue("the backend project", {
        driver.getOpenProjects(RdTarget.BACKEND).singleOrNull()?.takeIf { it.isInitialized() }
    }) { it != null }!!
    private val servers = driver.service(SplitModeViewsTest.HostedLanguageServersRef::class, backend, RdTarget.BACKEND)
    fun command(name: String): String? = servers.executeCommandForTest(name, "[]")
    fun command(name: String, argsJson: String): String? = servers.executeCommandForTest(name, argsJson)

    /** What the backend has logged so far: its `idea.log`, not the client's, which is under `frontend`. */
    fun backendLog(): String = logs.read()

    /** Client startup can lose debugger RPC subscriptions before the adapter starts (#147). */
    fun frontendLog(): String = Files.walk(home).use { paths ->
        paths.filter { file ->
            file.fileName.toString() == "idea.log" && home.relativize(file).any { it.toString() == "frontend" }
        }.toList().joinToString("\n") { it.readText() }
    }

    /** A scenario's files and log messages, while retaining the running IDEs. */
    fun scenario(directory: String) = WorkbenchProbe(driver, project.resolve(directory), home, userSettings, logs.snapshot())

    /** Fails with what the backend logged around [text], when it logged it. */
    fun assertNotLogged(text: String, failure: String) {
        val log = backendLog()
        val at = log.indexOf(text)
        check(at < 0) { "$failure:\n${log.substring((at - 3_000).coerceAtLeast(0), (at + 3_000).coerceAtMost(log.length))}" }
    }
}

/** A balloon: a message an extension shows, or one of the IDE's own notifications. */
internal const val BALLOON = $$"//div[@javaclass='com.intellij.ui.BalloonImpl$MyComponent']"

/** Fresh trial clients can put this balloon over a toolbar the test needs to click. */
internal fun IdeaFrameUI.dismissTrialNotification() {
    fun trialBalloons() = xx(BALLOON).list().filter {
        it.hasSubtext("Your free 30-day trial has started!")
    }
    trialBalloons().forEach { it.x { byVisibleText("Close") }.click() }
    awaitProbeValue("the trial notification clear of the toolbar", { trialBalloons().isEmpty() }) { it }
}

/** Keeps each workflow's project, installation and logs isolated, with one backend/client pair. */
internal fun withWorkbenchProbe(
    name: String,
    content: String,
    /** The user settings, see [writeUserSettings]. */
    settings: String = """{"files.autoSave":"off"}""",
    /** Log categories traced in both halves, for a workflow whose failures the default log leaves unexplained. */
    traced: String? = null,
    /** More files for the project, by name. */
    files: Map<String, String> = emptyMap(),
    /** The file the client opens first. */
    open: String = "example.codebridgeactions",
    /** VSIX files installed beside the probe. */
    extensions: List<Path> = emptyList(),
    test: WorkbenchProbe.() -> Unit,
) {
    startWorkbenchProbe(name, content, settings, traced, files, open, extensions).use { it.test(test) }
}

/** Offsets belong to individual files: appending to one must not hide messages in another. */
internal class WorkbenchLogs(private val home: Path, private val starts: Map<Path, Int> = emptyMap()) {
    private fun files(): List<Path> = Files.walk(home).use { paths ->
        paths.filter { file ->
            file.fileName.toString() == "idea.log" && home.relativize(file).none { it.toString() == "frontend" }
        }.toList()
    }

    fun snapshot() = WorkbenchLogs(home, files().associateWith { it.readText().length })

    fun read(): String = files().joinToString("\n") { file ->
        val text = file.readText()
        text.drop(starts[file]?.takeIf { it <= text.length } ?: 0)
    }
}

/** Owns a running pair, also usable by a class whose scenarios explicitly reset their state. */
internal class WorkbenchSession(
    private val name: String,
    val probe: WorkbenchProbe,
    private val context: IDERemDevTestContext,
    private val run: BackgroundRun,
) : AutoCloseable {
    fun test(test: WorkbenchProbe.() -> Unit) {
        try {
            // Browser and list fixtures are weak remote references outside a driver context: kept
            // alive for the workflow, across the polling helper's delays too.
            run.driver.withContext { probe.test() }
        } catch (error: Throwable) {
            // The -probe directory is in CI's IDE log artifacts, outside the disposable system caches.
            runCatching {
                val diagnostics = Files.createDirectories(context.paths.testHome.resolve("$name-probe"))
                run.driver.ui.robotProvider.saveHierarchy(diagnostics.toString(), "ui.html")
            }.onFailure(error::addSuppressed)
            context.reportHostFailure(error)
            throw error
        }
    }

    override fun close() = run.closeSplitIdeAndWait()
}

internal fun startWorkbenchProbe(
    name: String,
    content: String,
    settings: String = """{"files.autoSave":"off"}""",
    traced: String? = null,
    files: Map<String, String> = emptyMap(),
    open: String = "example.codebridgeactions",
    extensions: List<Path> = emptyList(),
): WorkbenchSession {
    val project = Files.createTempDirectory("codebridge-$name")
    project.resolve("example.codebridgeactions").writeText(content)
    files.forEach { (file, text) -> project.resolve(file).writeText(text) }
    val context = Starter.newContext(
        testName = name,
        testCase = builtAgainst(project),
    ) as IDERemDevTestContext

    context.install(Installed.RUNTIME)
    context.writeUserSettings(settings)
    val store = ExtensionStore(context.paths.configDir.resolve("codebridge/extensions"))
    (listOf(requiredPath("codebridge.workbench.vsix")) + extensions).forEach { store.install(it) }
    context.disableAutoCompletion()
    context.frontendIDEContext.disableAutoCompletion()
    context.useShortClientSystemDir()
    if (traced != null) {
        context.applyVMOptionsPatch { addSystemProperty("idea.log.trace.categories", traced) }
        context.frontendIDEContext.applyVMOptionsPatch { addSystemProperty("idea.log.trace.categories", traced) }
    }
    val run = context.runSplitIde(commands = CommandChain().waitForSmartMode(), open = open)
    try {
        val probe = WorkbenchProbe(
            run.driver, project, context.paths.testHome, context.paths.configDir.resolve("codebridge/settings.json"),
        )
        check(run.driver.isRemDevMode)
        awaitProbeValue(
            "the workbench probe activated",
            { probe.command("probe.uiState") }, retrying = ::isStartupFailure) {
            it != null
        }
        return WorkbenchSession(name, probe, context, run)
    } catch (error: Throwable) {
        context.reportHostFailure(error)
        runCatching { run.closeSplitIdeAndWait() }.onFailure(error::addSuppressed)
        throw error
    }
}

private fun IDERemDevTestContext.reportHostFailure(error: Throwable) {
    runCatching {
        val hosts = paths.systemDir.resolve("codebridge/hosts")
        if (Files.isDirectory(hosts)) Files.walk(hosts).use { paths ->
            paths.filter { it.fileName.toString() == "exthost-stdout.log" }.forEach {
                println("Extension host output ($it):\n${it.readText().takeLast(8_000)}")
            }
        }
    }.onFailure(error::addSuppressed)
}
