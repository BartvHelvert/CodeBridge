package me.helvert.codebridge.debug

import com.intellij.openapi.progress.runBlockingMaybeCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.exthost.*
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.DebugAdapters
import me.helvert.codebridge.exthost.protocol.payload.DebugService
import me.helvert.codebridge.manifest.VsixArchive
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** IntelliJ owns the debugger model; this supplies its adapter from a project or standalone host. */
class HostedDebugSession(
    installation: VsCodeInstallation,
    extensions: List<InstalledExtension>,
    private val workspace: Path,
    private val storage: Path,
    settings: JsonObject,
    private val install: (Path) -> InstalledExtension,
    private val report: (String) -> Unit = {},
    sharedHost: ExtensionHostSession? = null,
    sharedLocations: Map<String, Path>? = null,
    private val onClose: () -> Unit = {},
    /** The project host's adapter router, when sessions share it; adapter handles are global. */
    sharedAdapters: ExtensionHostDebugAdapters? = null,
) : Closeable {
    /**
     * Disabled extensions are dropped here rather than at each call site, so a host can never be
     * handed one: disabling is exactly the promise that it will not be loaded.
     */
    private val extensions: List<InstalledExtension> = extensions.filter { it.enabled }

    private val closed = AtomicBoolean()
    private val providers = CopyOnWriteArrayList<Provider>()
    private val sessionId = UUID.randomUUID().toString()
    private val ownsHost = sharedHost == null
    private val locations = sharedLocations ?: extensions.associate { it.id to storage.resolve("extensions").resolve(it.id) }
    private val descriptions = extensions.map { extension -> buildJsonObject {
        ExtensionHostInitData.describeExtension(extension.manifest, locations.getValue(extension.id)).forEach { (k, v) -> put(k, v) }
        put("targetPlatform", extension.targetPlatform ?: "undefined")
    } }
    private val host = sharedHost ?: ExtensionHostSession(installation, descriptions, storage, MainThreadHandler(::handle),
        workspace = workspaceData(), configuration = configurationData(settings))
    private val adapters = sharedAdapters ?: ExtensionHostDebugAdapters(host)
    var configuration: JsonObject = buildJsonObject { }
        private set
    private var dto: JsonObject? = null
    /** The adapter's connection, once [adapter] has made it. */
    @Volatile private var transport: DapTransport? = null

    /** The client's end of [transport], ended cleanly when this session is closed. */
    @Volatile
    private var handle: ExtensionHostDebugAdapterHandle? = null
    private val customRequests = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<String>>()

    /** What the session tells the host without waiting, and where its terminal's output is ordered; ends with [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // As the host ends, not after: in-flight adapter requests fail with the reason it ended.
        if (sharedAdapters == null) host.termination.invokeOnCompletion {
            @OptIn(ExperimentalCoroutinesApi::class)
            adapters.hostClosed(host.termination.getCompleted())
        }
    }

    /** [parent] is the id of the session this one is a child of, which the host hands the extension. */
    fun prepare(raw: JsonObject, substitute: (JsonObject, (String) -> String?) -> JsonObject,
                checkCanceled: () -> Unit = {}, parent: String? = null) {
        DebugLaunch.validate(raw)
        if (ownsHost) for (extension in extensions) {
            val destination = locations.getValue(extension.id)
            Files.walk(extension.directory).use { paths -> paths.forEach { source ->
                checkCanceled()
                require(!Files.isSymbolicLink(source)) { "Extension contains a symbolic link: $source" }
                val target = destination.resolve(extension.directory.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
            } }
        }
        checkCanceled()
        if (ownsHost) host.start()
        require(host.awaitInitialized(30, TimeUnit.SECONDS)) { "Extension host initialization timed out" }
        val type = raw.getValue("type").jsonPrimitive.content
        for (extension in extensions) {
            if (extension.manifest.main == null) continue
            val activated = await(checkCanceled) {
                host.call("ExtHostExtensionService", $$"$activate", DebugService.debugActivation(extension.id, type))
            }
            require(activated == "true") { "Extension ${extension.id} could not be activated" }
        }
        var config = raw
        for (provider in providers.filter { (it.type == type || it.type == "*") && it.resolve }) {
            config = resolve(provider, DebugService.RESOLVE_CONFIGURATION, config, checkCanceled)
        }
        // With the configuration as its argument, as VS Code runs it: CodeLLDB's process picker
        // reads its `filter` and `initCommands` from there.
        val beforeSubstitution = config
        config = substitute(config) { command ->
            executeCommand(extensions.first().manifest.contributes.debugger(type)?.variables?.get(command) ?: command,
                beforeSubstitution, checkCanceled) ?: throw DebugLaunch.InputCanceledException("command:$command")
        }
        for (provider in providers.filter { (it.type == type || it.type == "*") && it.afterSubstitution }) {
            config = resolve(provider, DebugService.RESOLVE_SUBSTITUTED, config, checkCanceled)
        }
        DebugLaunch.validate(config, resolved = true)
        require(config["type"] == raw["type"] && config["request"] == raw["request"]) { "A debug configuration provider changed the debugger type or request" }
        configuration = config
        dto = buildJsonObject {
            put("id", sessionId); put("type", type); put("name", config["name"] ?: JsonPrimitive(type))
            put("folderUri", ExtensionHostInitData.fileUri(workspace)); put("configuration", config)
            parent?.let { put("parent", it) }
        }
        // The adapter's descriptor is not asked for here: `$startDASession` asks the extension's
        // factory itself, and a factory may start its adapter when asked -- CodeLLDB does -- so
        // asking first started one that nothing ever stopped.
    }

    /**
     * [startFailed] hears why, when the adapter refuses to launch or attach; the session then ends.
     *
     * With a program running in a terminal ([terminalInUse]), what the adapter sends is ordered
     * behind the terminal's text, which lags a little: see [TerminalOutputOrder]. [beforeEnd]
     * completes once the terminal is done. Without blocking -- the host's reader serves every session.
     */
    fun adapter(
        startFailed: (String) -> Unit = {},
        terminalInUse: () -> Boolean = { false },
        beforeEnd: () -> Deferred<*>? = { null },
    ): ExtensionHostDebugAdapterHandle {
        val sessionDto = checkNotNull(dto) { "Debug session has not been prepared" }
        check(!closed.get()) { "Debug session is closed" }
        val resolved = configuration
        val transport = adapters.transport(sessionDto)
        val startSeq = java.util.concurrent.atomic.AtomicReference<Int?>()
        // Run Without Debugging: refusals of what only a debugger does must not end the run.
        val noDebug = if ((resolved["noDebug"] as? JsonPrimitive)?.booleanOrNull == true) NoDebugReplies() else null
        this.transport = transport
        return ExtensionHostDebugAdapterHandle(object : DapTransport by transport {
            override fun connect(receive: (String) -> Unit, failed: (Throwable) -> Unit) {
                val order = TerminalOutputOrder({ json ->
                    receive(DebugLaunch.withoutFalseRestart(noDebug?.received(json) ?: json))
                    DebugAdapters.customEvent(json)?.let { event -> dto?.let { call(DebugService.ACCEPT_CUSTOM_EVENT, "[$it,$event]") } }
                    // VS Code reads the answer to `launch` whether or not `initialized` came first;
                    // IntelliJ's client does not, so a refusal is turned into the session ending.
                    val reason = startSeq.get()?.let { DebugLaunch.startFailure(json, it) }
                    if (reason != null) {
                        DebugLaunch.startFailedEvents(reason).forEach(receive)
                        startFailed(reason)
                    }
                }, terminalInUse, beforeEnd, scope)
                transport.connect({ json ->
                    // The answer to the extension's own request is the extension's, not the client's.
                    val answered = DebugAdapters.responseSeq(json)?.let(customRequests::remove)
                    if (answered != null) { answered.complete(json); return@connect }
                    order.offer(json)
                }, { error ->
                    customRequests.values.toList().forEach { it.completeExceptionally(error) }
                    failed(error)
                })
            }

            // The client sends the arguments it was handed before any extension resolved them; the
            // adapter must see what the extension's providers made of them, as it does in VS Code.
            override fun send(json: String) {
                DebugLaunch.startRequestSeq(json)?.let(startSeq::set)
                noDebug?.sent(json)
                transport.send(DebugLaunch.withNativeSourcePath(DebugLaunch.launchRequest(json, resolved, sessionId)))
            }
        }).also { handle = it; if (closed.get()) it.close() }
    }

    /**
     * `DebugSession.customRequest`: sent on the adapter's connection beside the client's own
     * requests, numbered where the client's never reach, and its response kept from the client.
     * Completes with what the extension's promise resolves to, the response's body.
     */
    suspend fun customRequest(command: String, arguments: JsonElement?): String {
        val transport = transport ?: throw IllegalStateException("The debug adapter has not started")
        val seq = nextCustomSeq.incrementAndGet()
        val response = CompletableDeferred<String>()
        customRequests[seq] = response
        try {
            transport.send(DebugAdapters.request(seq, command, arguments))
            return DebugAdapters.customResponse(response.await()).getOrThrow()
        } finally {
            customRequests.remove(seq)
        }
    }

    /**
     * Runs an extension's command, as `${command:...}` and a `command` input do, and answers the
     * string it returned; null for anything else, which leaves the variable unresolved.
     */
    fun executeCommand(command: String, args: JsonElement? = null, checkCanceled: () -> Unit = {}): String? {
        // The host spreads what follows the id into the command's arguments: an empty array here
        // would be one argument, `[]`, which CodeLLDB reads `filter` from -- and finds Array's own.
        val response = await(checkCanceled) {
            host.call("ExtHostCommands", $$"$executeContributedCommand", JsonArray(listOfNotNull(JsonPrimitive(command), args)).toString())
        }
        return if (response.isEmpty()) null else (Json.parseToJsonElement(response) as? JsonPrimitive)?.contentOrNull
    }

    /** The id the host knows this session by. */
    val id: String get() = sessionId

    fun started() {
        dto?.let { call($$"$acceptDebugSessionStarted", "[$it]"); call($$"$acceptDebugSessionActiveChanged", "[$it]") }
    }

    private fun resolve(provider: Provider, method: String, config: JsonObject, check: () -> Unit): JsonObject {
        val result = await(check) {
            host.call(DebugService.EXT_HOST, method, "[${provider.handle},${ExtensionHostInitData.fileUri(workspace)},$config,null]")
        }
        require(result.isNotEmpty() && result != "null") { "Extension canceled debug configuration resolution" }
        return Json.parseToJsonElement(result).jsonObject
    }

    /** Sends [method] now, in order with what follows, without waiting for the answer. */
    private fun call(method: String, args: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                host.call(DebugService.EXT_HOST, method, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Could not send $method: ${failure.message}")
            }
        }
    }

    internal fun handle(call: MainThreadCall): String? {
        if (adapters.handle(call)) return null
        when (call.service) {
            "MainThreadWindow" -> if (call.method == $$"$getInitialState") return "{\"isFocused\":true,\"isActive\":true}"
            DebugService.SERVICE -> when (call.method) {
                DebugService.REGISTER_PROVIDER -> DebugService.providerRegistration(call.argsJson)
                    .let { providers.add(Provider(it.type, it.handle, it.resolve, it.afterSubstitution)) }
                DebugService.UNREGISTER_PROVIDER -> {
                    val handle = DebugService.unregisteredHandle(call.method, call.argsJson)
                    providers.removeIf { it.handle == handle }
                }
                DebugService.APPEND_CONSOLE -> report(DebugService.consoleText(call.argsJson))
            }
            "MainThreadCommands" -> if (call.method == $$"$executeCommand") {
                val args = Args.of(call.method, call.argsJson)
                val command = args.str(0)
                // Only this one is serviced. Anything else is reported and answered with no value,
                // which is what VS Code returns for a command that produces none: throwing here
                // would reject the extension's own promise and take down whatever it was doing.
                if (command != "workbench.extensions.command.installFromVSIX") {
                    report("CodeBridge does not implement the workbench command '$command'")
                    return null
                }
                installPayload(call.argsJson, extensions, locations, install)
            }
            "MainThreadMessageService" -> if (call.method == $$"$showMessage")
                report(Args.of(call.method, call.argsJson).strOrNull(1).orEmpty())
            "MainThreadErrors" -> report(call.argsJson)
            "MainThreadStorage" -> if (call.method == $$"$initializeExtensionStorage") return "{}"
        }
        return null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        customRequests.values.toList().forEach { it.completeExceptionally(IllegalStateException("The debug session has ended")) }
        customRequests.clear()
        dto?.let {
            call($$"$acceptDebugSessionTerminated", "[$it]")
            call($$"$acceptDebugSessionActiveChanged", "[null]")
        }
        // Ended here, before the host goes: a host closing under a live adapter fails its stream,
        // which the client reports as an error although the session was stopped on purpose.
        handle?.close()
        // Host termination also fails all stream readers and pending adapter requests.
        if (ownsHost) host.close()
        scope.cancel()
        onClose()
    }

    private fun workspaceData() = buildJsonObject {
        put("id", workspace.toAbsolutePath().normalize().toString()); put("name", workspace.fileName.toString())
        put("folders", buildJsonArray { add(buildJsonObject {
            put("uri", ExtensionHostInitData.fileUri(workspace)); put("name", workspace.fileName.toString()); put("index", 0)
        }) })
    }

    private fun configurationData(settings: JsonObject): JsonObject {
        fun tree(entries: Map<String, JsonElement>): JsonObject = buildJsonObject {
            entries.entries.groupBy { it.key.substringBefore('.') }.forEach { (key, values) ->
                val direct = values.firstOrNull { it.key == key }
                put(key, direct?.value ?: tree(values.associate { it.key.substringAfter('.') to it.value }))
            }
        }
        return buildJsonObject {
            ExtensionHostInitData.emptyConfiguration().forEach { (k, v) -> put(k, v) }
            put("workspace", buildJsonObject {
                put("contents", tree(settings)); put("keys", JsonArray(settings.keys.map(::JsonPrimitive)))
                put("overrides", JsonArray(emptyList()))
            })
        }
    }

    private data class Provider(val type: String, val handle: Int, val resolve: Boolean, val afterSubstitution: Boolean)

    companion object {
        /**
         * `workbench.extensions.command.installFromVSIX`, as a debugger extension calls it with the
         * platform package it downloaded for itself -- CodeLLDB does on first activation. Only a
         * package of an extension the host runs, at the same version, is accepted. It is installed,
         * and unpacked over the host's private copy, so the running host has it without a restart.
         */
        fun installPayload(argsJson: String, extensions: List<InstalledExtension>, locations: Map<String, Path>,
                           install: (Path) -> InstalledExtension) {
            val args = Args.of($$"$executeCommand", argsJson)
            val uri = DebugService.findFileUri(args.element(1)) ?: error("Missing VSIX file URI")
            val pathText = uri.path
            val path = Path.of(if (System.getProperty("os.name").startsWith("Windows")) pathText.removePrefix("/") else pathText)
            val manifest = VsixArchive.readManifest(path).manifest ?: error("Invalid downloaded VSIX")
            val extension = extensions.singleOrNull { it.id.equals(manifest.id, true) && it.manifest.version == manifest.version }
                ?: error("The downloaded package does not match an extension this host runs")
            install(path)
            VsixArchive.unpack(path, locations.getValue(extension.id))
        }

        /** Far above any `seq` IntelliJ's client numbers its own requests with, which count from 1. */
        private val nextCustomSeq = java.util.concurrent.atomic.AtomicInteger(1_000_000_000)

        /**
         * Waits for [request] where nothing can suspend: a session is prepared by the synchronous
         * variable substitution it calls back into. Given up on with the caller's job or progress,
         * and after 90 seconds -- a [TimeoutException] then.
         */
        fun <T> await(checkCanceled: () -> Unit, request: suspend () -> T): T {
            checkCanceled()
            return runBlockingMaybeCancellable {
                withTimeoutOrNull(HOST_SECONDS.seconds) { request() }
                    ?: throw TimeoutException("The extension host did not answer within $HOST_SECONDS seconds")
            }
        }

        /** How long a debug session waits on the host for an activation, a resolution or a command. */
        private const val HOST_SECONDS = 90L
    }
}
