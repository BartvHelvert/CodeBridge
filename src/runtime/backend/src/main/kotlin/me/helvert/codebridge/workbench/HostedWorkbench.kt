package me.helvert.codebridge.workbench

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostInitData
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.*
import me.helvert.codebridge.settings.WorkbenchSettings
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/** Shared by language and debug sessions. All paths and processes belong to the backend. */
internal class HostedWorkbench(
    private val workspace: Path,
    stateRoot: Path,
    manifests: List<JsonObject>,
    private val callHost: suspend (String, String, String) -> String,
    private val report: (String, Throwable?) -> Unit,
    logFiles: HostedLogging.Files = HostedLogging.Files.NONE,
    /** A sentence about each change an extension made to `.vscode/settings.json`, for the user. */
    settingsChanged: (String) -> Unit = {},
    /** The user's own `settings.json`, shared by every project; see [HostedConfiguration]. */
    userSettings: Path? = null,
    /** Told after an extension writes [userSettings], so the other projects reload it too. */
    userSettingsWritten: () -> Unit = {},
    /** The renderer's own commands, which need the IDE; see [HostedCommands]. */
    builtInCommands: suspend (command: String, arguments: JsonArray) -> MainThreadResult? = { _, _ -> null },
    /** Told of each context key an extension sets; see [HostedCommands]. */
    contextChanged: (key: String, value: JsonElement?) -> Unit = { _, _ -> },
    /** How an extension's settings write reaches the file; see [HostedConfiguration.SettingsFiles]. */
    settingsFiles: HostedConfiguration.SettingsFiles = HostedConfiguration.SettingsFiles.DISK,
    /** The workspace's folders; [workspace] alone unless it has a `.code-workspace` file. */
    initialLayout: CodeBridgeWorkspace = CodeBridgeWorkspace.single(workspace),
    /** Told why a `.code-workspace` file found when the folders change cannot be used; see [CodeBridgeWorkspace.of]. */
    private val workspaceProblem: (String) -> Unit = { report(it, null) },
    /** Where `context.secrets` are kept; see [HostedSecrets]. */
    secretStore: HostedSecrets.Store = HostedSecrets.PasswordSafeStore,
) : AutoCloseable {
    /** The workspace's folders, as the extensions were last told them; see [relayout]. */
    @Volatile
    var layout: CodeBridgeWorkspace = initialLayout
        private set
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    /** What the workbench sends the host without waiting for, and starts for it; ends with [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var ready = false
    private val notifications = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, String>>()
    private val notify: (String, String, String) -> Unit = { service, method, args ->
        if (!closed.get()) {
            notifications.add(Triple(service, method, args))
            flush()
        }
    }

    /** The settings declared: the extensions', and those of this workbench; see [WorkbenchSettings]. */
    private val declaring = manifests + WorkbenchSettings.raw
    val configuration = HostedConfiguration(
        workspace,
        HostedConfiguration.defaults(declaring),
        notify,
        { if (it is InvalidSettingsException) report(it.message!!, null) else report("Could not reload settings", it) },
        HostedConfiguration.registered(declaring),
        settingsChanged,
        userSettings,
        userSettingsWritten,
        HostedConfiguration.userOnly(declaring),
        settingsFiles,
        initialLayout,
        HostedConfiguration.folderScoped(declaring)
    )
    val storage = HostedStorage(stateRoot, workspace, notify)
    private val secrets = HostedSecrets(secretStore, notify)
    private val filesystem = HostedFileSystem(::changed)
    private val files = HostedWorkspace({ layout }, configuration::effectiveFor)
    private val commands = HostedCommands(scope, manifests, callHost, { report(it, null) }, contextChanged, builtInCommands)
    private val logging = HostedLogging(files = logFiles)
    /** Two searches at a time; the host cancels the ones it no longer wants. */
    private val searching = Dispatchers.IO.limitedParallelism(2)
    /** One reload at a time, so a slow one is never overtaken by the next. */
    private val settingsReload = Dispatchers.IO.limitedParallelism(1)
    private var pendingReload: Job? = null
    @Volatile
    private var watchers: HostedFileWatchers? = null

    @Synchronized
    fun startWatching() {
        check(!closed.get()) { "Workbench is closed" }
        watchers = HostedFileWatchers(layout, configuration::effectiveFor, notify, ::changed) {
            report(
                "File watcher failed",
                it
            )
        }
    }

    fun initialized() {
        // Advertise file: so workspace.fs.isWritableFileSystem and URI case comparisons work.
        val capabilities = 2 or if (System.getProperty("os.name").startsWith("Linux")) 1024 else 0
        notify("ExtHostFileSystemInfo", $$"$acceptProviderInfos", buildJsonArray {
            add(buildJsonObject { put("scheme", "file"); put("path", "/dummy") }); add(capabilities)
        }.toString())
        ready = true; flush(); configuration.reload()
    }

    @Synchronized
    private fun flush() {
        if (!ready) return
        while (true) {
            val (service, method, args) = notifications.poll() ?: break
            // Sent now, in order; the answer is only looked at for a failure.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    callHost(service, method, args)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    report("$service.$method failed", error)
                }
            }
        }
    }

    /**
     * Runs [command] with [arguments] as `vscode.commands.executeCommand` would: activating the
     * extension that contributes it, or doing it here when it is one of the workbench's own.
     */
    suspend fun executeCommand(command: String, arguments: JsonArray): MainThreadResult =
        commands.handle(MainThreadCall("MainThreadCommands", $$"$executeCommand", buildJsonArray {
            add(command); add(arguments); add(true)
        }.toString()))

    suspend fun handle(call: MainThreadCall): MainThreadResult? {
        check(!closed.get()) { "Workbench is closed" }
        return when (call.service) {
            "MainThreadStorage" -> MainThreadResult(storage.handle(call))
            SecretState.SERVICE -> secrets.handle(call)
            "MainThreadFileSystem" -> filesystem.handle(call)
            "MainThreadWorkspace" -> {
                // Stopped, interrupted and all, when the host cancels the search.
                val search = currentCoroutineContext().job
                runInterruptible(searching) { MainThreadResult(files.handle(call) { search.isCancelled }) }
            }

            "MainThreadFileSystemEventService" -> {
                checkNotNull(watchers) { "Workspace watchers are not ready" }.handle(call)
                MainThreadResult()
            }

            "MainThreadCommands" -> {
                // The debugger's native-payload installation must retain its special validation.
                if (call.method == $$"$executeCommand" && Args.of(call.method, call.argsJson)
                        .str(0) == "workbench.extensions.command.installFromVSIX"
                ) null
                else commands.handle(call)
            }

            Configuration.SERVICE -> {
                if (call.method != Configuration.UPDATE && call.method != Configuration.REMOVE) {
                    throw MainThreadException("Unavailable", "Unsupported configuration method ${call.method}")
                }
                // Refused rather than swallowed: the extension's `update` promise rejects, which is
                // how it learns the setting did not change -- and a status bar click says so too.
                configuration.write(Configuration.write(call.method, call.argsJson))
                MainThreadResult()
            }
            // What the extension is saying about itself, rather than anything it wants drawn. The
            // empty reply is also what `$createLogger` -- the one awaited method there -- needs.
            Console.SERVICE, Telemetry.SERVICE, Loggers.SERVICE -> {
                logging.handle(call)
                MainThreadResult()
            }

            else -> null
        }
    }

    @Synchronized
    private fun changed(path: Path) {
        if (closed.get()) return
        // A `.code-workspace` file made, edited or deleted in the project directory may change the folders.
        if (path.parent == workspace && CodeBridgeWorkspace.isWorkspaceFile(path)) {
            foldersMayHaveChanged = true
            return reloadSettings()
        }
        val settingsFiles =
            layout.folders.flatMap { listOf(it.path.resolve(".vscode"), layout.folderFile(it, "settings.json")) } +
                    listOfNotNull(workspace.resolve(".vscode/settings.json"), layout.file)
        if (path in settingsFiles) reloadSettings()
    }

    @Volatile
    private var foldersMayHaveChanged = false


    /**
     * The workspace's folders are [next]'s now, as VS Code changes them without a restart: settings,
     * search and watching follow them, and the extensions hear of it as `onDidChangeWorkspaceFolders`.
     */
    @Synchronized
    fun relayout(next: CodeBridgeWorkspace) {
        if (closed.get()) return
        layout = next
        configuration.relayout(next)
        watchers?.relayout(next)
        notify(
            "ExtHostWorkspace",
            $$"$acceptWorkspaceData",
            buildJsonArray { add(ExtensionHostInitData.workspaceData(storage.workspaceId, next)) }.toString()
        )
    }

    /** The user's settings file changed; it lives outside the workspace, so its watcher is the caller's. */
    @Synchronized
    fun userSettingsChanged() {
        if (!closed.get()) reloadSettings()
    }

    private fun reloadSettings() {
        // An atomic save can appear as delete/create on Windows. Publish the settled file,
        // rather than briefly removing every setting between those two native events.
        pendingReload?.cancel()
        pendingReload = scope.launch(settingsReload) {
            delay(100.milliseconds)
            if (foldersMayHaveChanged) {
                foldersMayHaveChanged = false
                val next = CodeBridgeWorkspace.of(workspace, workspaceProblem)
                if (next != layout) return@launch relayout(next)
            }
            fun excludes() = layout.folders.map { configuration.effectiveFor(it)["files.watcherExclude"] }
            val previous = excludes()
            configuration.reload()
            if (excludes() != previous) watchers?.reloadExcludes()
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        watchers?.close(); watchers = null
        scope.cancel()
        storage.close()
        secrets.close()
        notifications.clear()
    }
}
