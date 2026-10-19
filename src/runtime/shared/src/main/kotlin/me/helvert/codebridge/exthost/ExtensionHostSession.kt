package me.helvert.codebridge.exthost

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.exthost.protocol.*
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A call from the extension host into the main thread. */
data class MainThreadCall(
    val service: String,
    val method: String,
    val argsJson: String,
    val buffers: Map<Int, ByteArray> = emptyMap(),
)

/** A workbench reply can carry JSON, raw bytes, or undefined. */
data class MainThreadResult(val json: String? = null, val bytes: ByteArray? = null) {
    override fun equals(other: Any?): Boolean =
        other is MainThreadResult && json == other.json && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * json.hashCode() + bytes.contentHashCode()
}

/**
 * What the extension host replied with, before a caller decides which half it wanted.
 *
 * The other direction of [MainThreadResult], and separate from it so neither can be handed to the
 * wrong side by accident. Most methods answer in JSON; a few -- semantic tokens above all -- answer
 * with a `VSBuffer`, because their result is an array of numbers large enough that encoding it as
 * text would cost more than the call.
 */
class HostReply(val json: String = "", val bytes: ByteArray? = null)

class MainThreadException(val remoteName: String, message: String) : IOException(message)

/**
 * Handles calls from the extension host.
 *
 * Returning `null` replies with "ok, no value", which is what almost every method during startup
 * needs. The notable exception is `MainThreadWindow.$getInitialState`, where `undefined` makes the
 * host throw while destructuring the result.
 */
fun interface MainThreadHandler {
    fun handle(call: MainThreadCall): String?

    /**
     * The reply to [call]. What runs before the first suspension runs on the workbench thread, in
     * the order the host sent its calls; see [WorkbenchDispatcher].
     */
    suspend fun answer(call: MainThreadCall): MainThreadResult = MainThreadResult(handle(call))
}

/**
 * Runs VS Code's extension host as a child process and speaks its protocol.
 *
 * The sequence, all of which was established by observing a real host:
 *
 * 1. create the pipe, then launch the process pointed at it
 * 2. the host sends `Ready`; reply with the initialization payload
 * 3. the host sends `Initialized`
 * 4. open the `ExtHostConfiguration` and `ExtHostWorkspace` barriers, or `activate()` never runs
 * 5. serve calls coming the other way
 */
class ExtensionHostSession(
    private val installation: VsCodeInstallation,
    private val extensions: List<JsonObject>,
    private val storageRoot: Path,
    handler: MainThreadHandler,
    // Read from the build we are launching, so the ids cannot be from a different version.
    private val table: RpcIdTable = installation.rpcIdTable(),
    private val workspace: JsonObject? = null,
    private val configuration: JsonObject = ExtensionHostInitData.emptyConfiguration(),
    /** Opt-in experiments on a patched runtime; cannot be combined with process interception. */
    private val preloadScript: Path? = null,
    private val globalStorageHome: Path = storageRoot.resolve("globalStorage"),
    private val workspaceStorageHome: Path = storageRoot.resolve("workspaceStorage"),
    /** Where to say that the protocol itself cost time; no-op outside an IDE. */
    private val report: (String) -> Unit = {},
) : Closeable {

    private val pipe: ExtensionHostPipeServer = ExtensionHostPipeServer.create("codebridge-" + UUID.randomUUID())
    private var channel: ExtensionHostChannel? = null
    @Volatile
    private var process: Process? = null
    private var reader: Thread? = null
    @Volatile
    private var writer: FrameWriter? = null

    private val outgoingFrameId = AtomicInteger(0)
    private val outgoingRequestId = AtomicInteger(0)
    @Volatile
    private var lastIncomingFrameId = 0
    private val closed = AtomicBoolean()
    /** Completes once the session has ended, with why. */
    val termination: Deferred<Throwable> field = CompletableDeferred()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HostReply>>()
    private val activations = ExtensionActivations()
    private val initialized = CompletableDeferred<Unit>()

    /** Where the session watches its process and opens the host's barriers; ends with it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("codebridge-exthost"))
    private val calls = CopyOnWriteArrayList<MainThreadCall>()
    private val dispatcher = WorkbenchDispatcher(handler, { reply ->
        runCatching { send(RpcCodec.encode(reply)) }.onFailure { finish(it) }
    }, report)

    /** Every call the host has made, in order. */
    fun observedCalls(): List<MainThreadCall> = calls.toList()

    fun start() {
        check(!closed.get()) { "Extension host is closed" }
        for (directory in listOf("globalStorage", "workspaceStorage", "logs")) {
            Files.createDirectories(storageRoot.resolve(directory))
        }
        Files.createDirectories(globalStorageHome)
        Files.createDirectories(workspaceStorageHome)

        val spec =
            installation.launchSpec(pipe.endpoint, ProcessHandle.current().pid()).copy(preloadScript = preloadScript)
        process = ProcessBuilder(spec.command())
            .apply {
                environment().putAll(spec.environment())
                environment().remove("VSX_LSP_OWNER_PID")
                environment().remove("VSX_LSP_CONFIG")
            }
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(storageRoot.resolve("logs/exthost-stdout.log").toFile()))
            .start()

        // Project disposal can race ProcessBuilder.start(), before finish() sees the child.
        if (closed.get()) {
            process!!.destroy()
            throw IOException("Extension host stopped during process launch")
        }

        val started = process!!
        scope.launch {
            val exited = started.onExit().await()
            finish(
                IOException(
                    "Extension host exited with code ${exited.exitValue()}; see ${
                        storageRoot.resolve("logs/exthost-stdout.log")
                    }"
                )
            )
        }
        scope.launch {
            delay(CONNECT_SECONDS.seconds)
            if (channel == null && !closed.get()) finish(IOException("Extension host did not connect within $CONNECT_SECONDS seconds"))
        }
        channel = pipe.accept()
        if (closed.get()) {
            channel?.close(); throw IOException("Extension host stopped during startup")
        }
        // Before the reader: the first thing the host sends is `Ready`, and the reply to it is a
        // write that must not happen on the reader thread.
        writer = FrameWriter(channel!!, ::finish, report).also { it.start() }
        reader = Thread({ readLoop() }, "codebridge-exthost-reader").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Waits for the host to report `Initialized` and for the barriers to be opened.
     *
     * Blocking, and not tied to a caller's progress: a session also runs where no IDE does -- the
     * protocol's own tests -- and the platform's blocking wait needs one.
     */
    @Suppress("RAW_RUN_BLOCKING")
    fun awaitInitialized(timeout: Long, unit: TimeUnit): Boolean =
        runBlocking { withTimeoutOrNull(unit.toMillis(timeout).milliseconds) { initialized.await() } } != null

    /** Waits until [predicate] holds over the calls observed so far, or the timeout elapses. */
    fun awaitCall(timeout: Long, unit: TimeUnit, predicate: (MainThreadCall) -> Boolean): MainThreadCall? {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            calls.firstOrNull(predicate)?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    /**
     * Calls into the extension host, and returns the raw JSON it replied with; throws when it replied
     * with an error. Activation also checks the host's separate failure reports, since its
     * `$activate` reply only says whether the extension was found.
     *
     * The call is sent before this first suspends, so calls made one after another reach the host in
     * that order. Cancelling the caller cancels the call, which a [cancellable] one also tells the
     * host about.
     */
    suspend fun call(
        service: String,
        method: String,
        argsJson: String,
        cancellable: Boolean = false,
        buffers: Map<Int, ByteArray> = emptyMap(),
        /**
         * Arguments that are a `SerializableObjectWithBuffers`: their JSON slot holds the value, with
         * `{"$$ref$$": n}` where the n-th of these buffers goes.
         */
        objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
    ): String {
        val reply = request(service, method, argsJson, cancellable, buffers, objectBuffers)
        // A method that answers in bytes, asked for through the JSON accessor: a mistake about the
        // method rather than about the host, and one that would otherwise read as an empty answer.
        if (reply.bytes != null) throw IOException("$service.$method replied with bytes, not JSON")
        return if (service == "ExtHostExtensionService" && method == $$"$activate")
            activations.checkResult(argsJson, reply.json)
        else reply.json
    }

    /**
     * Calls into the extension host for a method whose result is a `VSBuffer`, sent and cancelled as
     * [call] is.
     *
     * Null when the host answered `null` instead -- which a provider with nothing to say does, and
     * which arrives as JSON rather than as an empty buffer.
     */
    suspend fun callForBytes(service: String, method: String, argsJson: String, cancellable: Boolean = false): ByteArray? =
        request(service, method, argsJson, cancellable, emptyMap(), emptyMap()).bytes

    private suspend fun request(
        service: String,
        method: String,
        argsJson: String,
        cancellable: Boolean,
        buffers: Map<Int, ByteArray>,
        objectBuffers: Map<Int, List<ByteArray>>,
    ): HostReply {
        if (closed.get()) throw IOException("Extension host is closed")
        val requestId = outgoingRequestId.incrementAndGet()
        val result = CompletableDeferred<HostReply>()
        pending[requestId] = result
        try {
            send(
                RpcCodec.encode(
                    RpcMessage.Request(
                        requestId = requestId,
                        rpcId = table.require(service),
                        method = method,
                        argsJson = argsJson,
                        // Methods taking a CancellationToken are sent as the cancellable request type:
                        // the host appends the token itself, so it must not appear in [argsJson].
                        cancellable = cancellable,
                        buffers = buffers,
                        objectBuffers = objectBuffers,
                    )
                )
            )
            if (closed.get()) throw IOException("Extension host is closed")
            // A command runs for as long as it takes, as in VS Code: many wait on the user -- a quick
            // pick, a message's buttons -- or on work like a project import, and a timeout here reported
            // those as failures. A host that dies still fails them, in [finish].
            if (service == "ExtHostCommands" && method == $$"$executeContributedCommand") return result.await()
            return withTimeoutOrNull(REPLY_SECONDS.seconds) { result.await() }
                ?: throw TimeoutException("$service.$method had no reply within $REPLY_SECONDS seconds")
        } catch (cancelled: CancellationException) {
            if (cancellable && !closed.get()) runCatching { send(RpcCodec.encode(RpcMessage.Cancel(requestId))) }
            throw cancelled
        } finally {
            pending.remove(requestId)
        }
    }

    private fun readLoop() {
        val channel = this.channel ?: return
        val buffer = ByteArray(64 * 1024)
        var buffered = ByteArray(0)

        try {
            while (!closed.get()) {
                val read = channel.read(buffer)
                if (read <= 0) throw IOException("Extension host connection closed")

                buffered += buffer.copyOfRange(0, read)
                while (true) {
                    val size = FrameCodec.frameSize(buffered) ?: break
                    val frame = FrameCodec.decode(buffered) ?: break
                    buffered = buffered.copyOfRange(size, buffered.size)
                    handleFrame(frame)
                }
            }
        } catch (error: Exception) {
            finish(error)
        }
    }

    private fun handleFrame(frame: Frame) {
        if (frame.type == FrameType.KEEP_ALIVE || frame.type == FrameType.RESUME) return
        if (frame.payload.isEmpty()) return
        lastIncomingFrameId = frame.id

        when (HandshakeMessage.of(frame.payload)) {
            HandshakeMessage.READY -> {
                // The host blocks here until this arrives, and its own marks say how long it felt
                // it waited. Timing both halves says whether the difference is ours to explain.
                val ready = System.nanoTime()
                val payload = initDataJson().toByteArray(Charsets.UTF_8)
                val built = (System.nanoTime() - ready) / 1_000_000
                send(payload)
                val queued = (System.nanoTime() - ready) / 1_000_000 - built
                if (built + queued >= SLOW_READY_MILLIS) report(
                    "Answered the host's Ready with " +
                            "${payload.size} bytes of init data: ${built}ms building it, ${queued}ms queueing it"
                )
                return
            }

            HandshakeMessage.INITIALIZED -> {
                openBarriers()
                return
            }

            else -> Unit
        }

        val message = RpcCodec.decode(frame.payload) ?: return

        when (message) {
            is RpcMessage.Acknowledged -> return
            is RpcMessage.Cancel -> {
                dispatcher.cancel(message.requestId); return
            }

            is RpcMessage.ReplyOkBuffer -> {
                dispatcher.receivedReply {
                    pending.remove(message.requestId)?.complete(HostReply(bytes = message.bytes))
                }
                return
            }

            is RpcMessage.ReplyOkJsonWithBuffers -> {
                // One buffer is all any method this side asks for; the shape allows several.
                dispatcher.receivedReply {
                    pending.remove(message.requestId)?.complete(HostReply(message.json, message.buffers.firstOrNull()))
                }
                return
            }

            is RpcMessage.ReplyOkEmpty -> {
                dispatcher.receivedReply { pending.remove(message.requestId)?.complete(HostReply()) }
                return
            }

            is RpcMessage.ReplyOkJson -> {
                dispatcher.receivedReply { pending.remove(message.requestId)?.complete(HostReply(message.json)) }
                return
            }

            is RpcMessage.ReplyError -> {
                dispatcher.receivedReply {
                    pending.remove(message.requestId)?.completeExceptionally(ExtensionHostException(message.json))
                }
                return
            }

            is RpcMessage.Request -> Unit
        }

        val call = MainThreadCall(
            service = table.name(message.rpcId) ?: "rpcId:${message.rpcId}",
            method = message.method,
            argsJson = message.argsJson,
            buffers = message.buffers,
        )
        calls.add(call)
        // Read on the wire thread before the activation reply can complete on another thread.
        // The host owns dependency ordering; we must retain its separately reported failures.
        runCatching { activations.observe(call) }.onFailure { report("Could not read ${call.method}: ${it.message}") }

        send(RpcCodec.encode(RpcMessage.Acknowledged(message.requestId)))
        dispatcher.request(message, call)
    }

    /** An error returned by the extension host for one of our calls. */
    class ExtensionHostException(val errorJson: String) : RuntimeException(errorJson)

    /** Sends both barriers' calls now, on the reader, and is initialized once both are answered. */
    private fun openBarriers() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineScope {
                    val config = async(start = CoroutineStart.UNDISPATCHED) {
                        call("ExtHostConfiguration", $$"$initializeConfiguration", "[$configuration]")
                    }
                    val folders = async(start = CoroutineStart.UNDISPATCHED) {
                        call("ExtHostWorkspace", $$"$initializeWorkspace", "[${workspace ?: "null"},true]")
                    }
                    awaitAll(config, folders)
                }
                initialized.complete(Unit)
            } catch (error: Throwable) {
                initialized.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
    }

    private fun initDataJson(): String = ExtensionHostInitData.build(
        installation = installation,
        storageRoot = storageRoot,
        extensions = extensions,
        parentPid = ProcessHandle.current().pid(),
        globalStorageHome = globalStorageHome,
        workspaceStorageHome = workspaceStorageHome,
    ).toString()

    /**
     * Queues a frame; [FrameWriter] puts it on the wire, and explains why it is not written here.
     *
     * The frame id is assigned under the same lock that enqueues, so ids reach the host in the
     * order they were taken. They must start at 1 and never skip: a gap makes the host wait
     * silently forever.
     */
    @Synchronized
    private fun send(payload: ByteArray) {
        val writer = this.writer ?: throw IOException("Extension host is not connected")
        if (closed.get()) throw IOException("Extension host is closed")
        writer.enqueue(
            FrameCodec.encode(
                Frame(
                    FrameType.REGULAR,
                    outgoingFrameId.incrementAndGet(),
                    lastIncomingFrameId,
                    payload
                )
            )
        )
    }

    private fun finish(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        initialized.completeExceptionally(error)
        pending.values.toList().forEach { it.completeExceptionally(error) }
        pending.clear()
        dispatcher.close()
        runCatching { process?.destroy() }
        runCatching { channel?.close() }
        runCatching { pipe.close() }
        reader?.interrupt()
        runCatching { writer?.close() }
        termination.complete(error)
        scope.cancel()
    }

    override fun close() = finish(IOException("Extension host session closed"))

    /** Native-host fixtures wait after close so Windows releases the host's open log files. */
    internal fun awaitProcessExit(timeout: Long, unit: TimeUnit): Boolean = process?.waitFor(timeout, unit) ?: true

    private companion object {
        /** The host waits on this answer at startup; below this it is not worth a line in the log. */
        const val SLOW_READY_MILLIS = 150L

        /** How long a started host has to connect. */
        const val CONNECT_SECONDS = 30L

        /** How long a call waits for the host's reply; commands, which may wait on the user, wait for as long as they take. */
        const val REPLY_SECONDS = 90L
    }
}
