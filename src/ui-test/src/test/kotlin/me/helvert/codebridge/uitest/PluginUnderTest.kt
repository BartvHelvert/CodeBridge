package me.helvert.codebridge.uitest

import com.intellij.driver.sdk.ManualWaitForIndicators
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.community.PublicIdeDownloader
import com.intellij.ide.starter.driver.driver.remoteDev.RemoteDevBackgroundRun
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.installer.StandardInstaller
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.events.IdeAfterLaunchEvent
import com.intellij.ide.starter.runner.events.IdeLaunchEvent
import com.intellij.ide.starter.screenRecorder.IDEScreenRecorder
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.MarshallableCommand
import com.intellij.tools.ide.starter.bus.EventsBus
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimateProductInit
import me.helvert.codebridge.settings.BundledRuntime
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * What a test installs, named for the plugin a user chose: each brings what that plugin depends on.
 *
 * A test installs no more than the plugin whose behaviour it checks, so a runtime feature that only
 * works because CodeBridge happens to be installed beside it fails here rather than for a user who
 * has the runtime alone.
 */
internal enum class Installed {
    /** The runtime alone, as another plugin that depends on it brings it. */
    RUNTIME,

    /** The runtime and CodeBridge Bundled Extensions, as a plugin made from an extension brings them. */
    BUNDLED_EXTENSIONS,

    /** CodeBridge, and the runtime it depends on. */
    CODEBRIDGE,
}

/**
 * IntelliJ IDEA opening [project]: the build the plugins are built against, which the Gradle task
 * names in `codebridge.ide.build`. Starter would otherwise download the newest build, which changes under us.
 *
 * By its real path: Starter trusts the path it is given, and the IDE checks the one it resolves. On
 * the Windows CI runner the temporary directory is a short name, `C:\Users\RUNNER~1\...`, which the
 * IDE resolves to `runneradmin`; the project was then untrusted, and the backend waited on its Trust
 * and Open Project dialog for as long as the test ran.
 *
 * The installer is downloaded to `codebridge.ide.installers`, which every checkout on the machine
 * shares, instead of under this checkout's `out`. What Starter unpacks from it stays per checkout: it
 * deletes and rewrites an unpacked build, which another checkout's tests could be running.
 */
internal fun builtAgainst(project: Path): TestCase<LocalProjectInfo> {
    val pinned = TestCase(IdeaUltimateProductInit().ideInfo, LocalProjectInfo(project.toRealPath()))
        .withBuildNumber(checkNotNull(System.getProperty("codebridge.ide.build")) { "codebridge.ide.build is not set" })
    val installers = Path.of(checkNotNull(System.getProperty("codebridge.ide.installers")) { "codebridge.ide.installers is not set" })
    // After the build number: that sets an installer of its own, which downloads from the public feed.
    return pinned.onIDE(pinned.ideInfo.copy(getInstaller = {
        StandardInstaller(PublicIdeDownloader(), Files.createDirectories(installers))
    }))
}

/** The existing file or directory the Gradle task names in system [property]. */
internal fun requiredPath(property: String): Path =
    Path.of(checkNotNull(System.getProperty(property)) { "$property is not set" }).also {
        check(Files.exists(it)) { "$property does not exist: $it" }
    }

/**
 * Installs [installed] into both halves of a split IDE, and points the backend at its runtime.
 *
 * The plugins come from the build's test sandboxes. None carries the extension host: the build
 * unpacks the build machine's runtime once and hands its location to this test JVM, and it is passed
 * on to the IDEs here the same way `runIde` gets it.
 */
internal fun IDERemDevTestContext.install(installed: Installed) {
    val runtime = Path.of(checkNotNull(System.getProperty(BundledRuntime.DIRECTORY_PROPERTY)) {
        "${BundledRuntime.DIRECTORY_PROPERTY} is not set"
    })
    check(Files.isDirectory(runtime)) { "The extension host is not unpacked at $runtime; run :runtime:extractHostRuntime" }
    val bothHalves = listOf(sandboxPlugin("codebridge.runtime.plugin.dir")) +
            if (installed == Installed.CODEBRIDGE) listOf(sandboxPlugin("codebridge.plugin.dir")) else emptyList()
    for (plugin in bothHalves) {
        pluginConfigurator.installPluginFromDir(plugin)
        frontendIDEContext.pluginConfigurator.installPluginFromDir(plugin)
    }
    // Backend-only, so on the backend alone: installed in the client as well, the backend disabled
    // it a few seconds after start (DisabledPluginsState, through disablePluginsWithDependencies).
    if (installed == Installed.BUNDLED_EXTENSIONS) {
        pluginConfigurator.installPluginFromDir(sandboxPlugin("codebridge.bundled.plugin.dir"))
    }
    applyVMOptionsPatch { addSystemProperty(BundledRuntime.DIRECTORY_PROPERTY, runtime) }
    // The driver's robot posts mouse and key events into IdeEventQueue instead of moving the OS
    // cursor, so a run does not take over the developer's mouse and keyboard. The UI is driven in
    // the client; the backend gets it too in case a test drives backend UI.
    applyVMOptionsPatch { addSystemProperty(INPUT_EVENTS_ROBOT, true) }
    frontendIDEContext.applyVMOptionsPatch { addSystemProperty(INPUT_EVENTS_ROBOT, true) }
}

/** A plugin's directory in a test sandbox, which the Gradle task names in [property]. */
private fun sandboxPlugin(property: String): Path =
    Path.of(checkNotNull(System.getProperty(property)) { "$property is not set" }).also {
        check(Files.isDirectory(it)) { "$property does not exist: $it; run :prepareTestSandbox" }
    }

/**
 * Writes the backend's user `settings.json`, which the runtime reads whatever else is installed.
 *
 * A project's `.vscode/settings.json` is not that: it applies only with CodeBridge, so a test of the
 * runtime alone sets what it needs here. The path is `CodeBridgeUserSettings.file`, in the backend's own
 * config directory, never the developer's.
 */
internal fun IDERemDevTestContext.writeUserSettings(json: String) {
    Files.createDirectories(paths.configDir.resolve("codebridge")).resolve("settings.json").writeText(json)
}

/** Polls [read] until it [matches], for up to 90 seconds. */
internal fun <T> awaitProbeValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
    var last: T? = null
    while (System.nanoTime() < deadline) {
        last = read()
        if (matches(last)) return last
        Thread.sleep(500)
    }
    error("Timed out waiting for $description; last observed: $last")
}

/**
 * Starts the backend and the client with the driver attached, out of the developer's way.
 *
 * On Windows every capture of the whole screen makes the developer's cursor flicker, and Starter
 * captures it in two ways that this turns off:
 * - It records the screen for as long as the client runs. The split-mode client launcher always
 *   enables it, with no setting, by subscribing the recorder to the launch events just before this
 *   block runs; dropping those subscriptions keeps the recorder from ever starting.
 * - The client takes a screenshot every few seconds (`ide.performance.screenshot`). Any value turns
 *   it on, so the property is dropped rather than overridden. Starter adds it after the context's
 *   own patches, so this has to be a patch of the run.
 *
 * Neither IDE shows a splash, which always opens on the primary screen: `nosplash` wins over the
 * `splash` the launcher sets. The project frame opens on a screen other than the primary one, see
 * [secondaryScreenFrame]. It is not minimized: the robot types into the focus owner, and a minimized
 * frame has none.
 *
 * The system directories of the IDEs earlier tests started, their indexes and caches, are deleted:
 * half a gigabyte or more per test, which filled the CI runner's disk. Their logs are kept, outside
 * them. Not when an IDE exits: Starter reads its system directory after that.
 *
 * [open], a path relative to the project, is opened from the client once both halves have started,
 * and this returns once its editor is the client's. Not as a startup command of the backend's:
 * those run before the client connects, and a connecting client has the backend close its editors to
 * reopen them on the client's side -- disposing the one a command was opening, whose failure then
 * stopped the backend on CI.
 *
 * The backend gives a project with no JDK one of the machine's only when [jdk] is set, for a test of
 * Java code. Otherwise it scans and indexes that JDK, close to fifty thousand files, before the
 * project is smart: about 13 seconds per test on two cores, more on a CI runner.
 */
internal fun IDETestContext.runSplitIde(
    commands: Iterable<MarshallableCommand> = CommandChain(),
    jdk: Boolean = false,
    open: String? = null,
): BackgroundRun {
    if (!jdk) applyVMOptionsPatch { addSystemProperty(JDK_AUTO_SETUP, false) }
    earlierContexts.forEach { it.wipeSystemDir() }
    earlierContexts.clear()
    earlierContexts += listOfNotNull(this, (this as? IDERemDevTestContext)?.frontendIDEContext)
    earlierClientSystems.forEach(::deleteQuietly)
    earlierClientSystems.clear()
    pendingClientSystem?.let(earlierClientSystems::add)
    pendingClientSystem = null
    val frame = secondaryScreenFrame()
    val placementAgent = if (frame == null) null else Path.of(checkNotNull(System.getProperty("codebridge.window.agent")) {
        "codebridge.window.agent is not set; build :ui-test:windowAgentJar before launching UI tests"
    }).also { check(Files.isRegularFile(it)) { "Window placement agent does not exist: $it" } }
    val run = runIdeWithDriver(commands = commands) {
        EventsBus.unsubscribe<IdeLaunchEvent>(IDEScreenRecorder::class.java)
        EventsBus.unsubscribe<IdeAfterLaunchEvent>(IDEScreenRecorder::class.java)
        addVMOptionsPatch {
            clearSystemProperty("ide.performance.screenshot")
            addSystemProperty("nosplash", true)
            if (frame != null) {
                // Saved project bounds are applied too late in client startup. The test-only agent
                // places each IDE frame synchronously before its first call into AWT's show().
                addLine("-javaagent:$placementAgent=${frame.x},${frame.y},${frame.width},${frame.height}")
            }
        }
    }
    // The driver answers as soon as the client's JVM does. On a two-core CI runner that is half a
    // minute before the client has opened the project, and two more before the backend has indexed
    // it -- and a JDK, when [jdk] is set -- and only then is the file every test starts from
    // opened. Each test's own waits are far shorter than that, so it waits here rather than in
    // every test. Not `pauseOnIndicators`: that holds back UI lookups, and the tests mostly ask the
    // backend's services.
    //
    // In both halves: the client's own project is smart, and its status bar idle, while the backend
    // is still indexing. Waiting on the client alone let every test on CI start a minute early.
    try {
        @OptIn(ManualWaitForIndicators::class)
        run.driver.waitForIndicators(STARTUP)
        if (run is RemoteDevBackgroundRun) {
            @OptIn(ManualWaitForIndicators::class)
            run.backendRun.driver.waitForIndicators(STARTUP)
        }
        if (open != null) run.driver.openFile(open, waitForCodeAnalysis = false)
    } catch (e: Throwable) {
        run.closeIdeAndWait()
        throw e
    }
    return run
}

private val STARTUP = 6.minutes

/** The registry key for `JdkAuto`, which sets up a JDK for a project that names none or an unknown one. */
private const val JDK_AUTO_SETUP = "jdk.auto.setup"

/** The IDEs the previous [runSplitIde] started, closed by the time the next one starts. */
private val earlierContexts = mutableListOf<IDETestContext>()

/**
 * Gives the client a system directory of its own, with a short path.
 *
 * Windows' DirectoryLock moves a socket whose path is too long into `Windows\Temp`, where a
 * restricted test account cannot bind it, and Starter's own system directory for the client is deep
 * under `out/ide-tests`.
 *
 * Deleted as the backend's is, once the next [runSplitIde] starts; what an earlier test JVM left
 * behind, once the first of this one asks for a directory.
 */
internal fun IDERemDevTestContext.useShortClientSystemDir() {
    val directory = Files.createTempDirectory(clientSystemRoot, "c")
    frontendIDEContext.applyVMOptionsPatch { addSystemProperty("idea.system.path", directory) }
    pendingClientSystem?.let(::deleteQuietly)
    pendingClientSystem = directory
}

/** Where [useShortClientSystemDir] makes its directories, emptied of an earlier test JVM's. */
private val clientSystemRoot: Path by lazy {
    val root = Path.of(checkNotNull(System.getProperty("codebridge.test.work")) { "codebridge.test.work is not set" }, "c")
    deleteQuietly(root)
    Files.createDirectories(root)
}

/** The client system directory of a context whose IDEs have not started yet. */
private var pendingClientSystem: Path? = null

/** The client system directories of the IDEs the previous [runSplitIde] started. */
private val earlierClientSystems = mutableListOf<Path>()

/**
 * Deletes [path] and what is in it, as far as it can: a file the closed IDE's processes still
 * hold on Windows is left for a later run, rather than failing this test.
 */
@OptIn(ExperimentalPathApi::class)
private fun deleteQuietly(path: Path) {
    runCatching { path.deleteRecursively() }
}

/**
 * Makes the project frame open centred on the first screen that is not the primary one, if there is
 * one.
 */
private fun secondaryScreenFrame(): Rectangle? {
    if (GraphicsEnvironment.isHeadless()) return null
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val configuration = environment.screenDevices.firstOrNull { it != environment.defaultScreenDevice }
        ?.defaultConfiguration ?: return null
    val screen = configuration.bounds
    val frame = Rectangle(FRAME_WIDTH.coerceAtMost(screen.width), FRAME_HEIGHT.coerceAtMost(screen.height))
    frame.setLocation(screen.x + (screen.width - frame.width) / 2, screen.y + (screen.height - frame.height) / 2)
    return frame
}

/** The size the IDE gave a frame with no saved bounds. */
private const val FRAME_WIDTH = 1400
private const val FRAME_HEIGHT = 1000

private const val INPUT_EVENTS_ROBOT = "driver.robot.use.input.events"
