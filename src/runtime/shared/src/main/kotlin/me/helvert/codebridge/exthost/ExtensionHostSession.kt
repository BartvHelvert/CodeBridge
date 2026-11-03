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
    /**
     * Arguments that are a `SerializableObjectWithBuffers`, by position: their slot in [argsJson]
     * holds the value itself, with `{"$$ref$$": n}` where the n-th of these buffers goes.
     */
    val objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
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
class HostReply(
    val json: String = "",
    val bytes: ByteArray? = null,
    /** The buffers a `SerializableObjectWithBuffers` reply's `{"$$ref$$": n}` entries in [json] name. */
    val buffers: List<ByteArray> = emptyList(),
)

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
 * Runs one of VS Code's extension hosts and speaks its protocol, over whatever [connection] reaches
 * it: by default the Node host, as a child process.
 *
 * The sequence, all of which was established by observing a real host:
 *
 * 1. start the host and connect to it
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
    /** Those of [extensions] this host runs; it knows of the others, as VS Code's hosts know of each other's. */
    private val myExtensions: List<JsonObject> = extensions,
    private val connection: ExtensionHostConnection = ProcessHostConnection(installation, storageRoot, preloadScript, report),
) : Closeable {

    private val outgoingRequestId = AtomicInteger(0)
    private val closed = AtomicBoolean()
    /** Completes once the session has ended, with why. */
    val termination: Deferred<Throwable> field = CompletableDeferred()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HostReply>>()
    private val activations = ExtensionActivations()
    private val initialized = CompletableDeferred<Unit>()

    /** Where the session opens the host's barriers; ends with it. */
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

        connection.open(::handleMessage, ::finish)
        if (closed.get()) throw IOException("Extension host stopped during startup")
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

    /**
     * Calls into the extension host for a method whose result is a `SerializableObjectWithBuffers` --
     * a notebook's cells, whose outputs are bytes -- sent and cancelled as [call] is. The reply's
     * [HostReply.json] is the value, its [HostReply.buffers] what that value's references name.
     */
    suspend fun callWithBuffers(
        service: String,
        method: String,
        argsJson: String,
        cancellable: Boolean = false,
        buffers: Map<Int, ByteArray> = emptyMap(),
        objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
        /**
         * Whether to give up after [REPLY_SECONDS]; not for a method answered once the work it starts
         * is done, such as a kernel's `$executeCells`. A host that dies still fails it.
         */
        timesOut: Boolean = true,
    ): HostReply = request(service, method, argsJson, cancellable, buffers, objectBuffers, timesOut)

    private suspend fun request(
        service: String,
        method: String,
        argsJson: String,
        cancellable: Boolean,
        buffers: Map<Int, ByteArray>,
        objectBuffers: Map<Int, List<ByteArray>>,
        timesOut: Boolean = true,
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
            if (!timesOut || service == "ExtHostCommands" && method == $$"$executeContributedCommand") return result.await()
            return withTimeoutOrNull(REPLY_SECONDS.seconds) { result.await() }
                ?: throw TimeoutException("$service.$method had no reply within $REPLY_SECONDS seconds")
        } catch (cancelled: CancellationException) {
            if (cancellable && !closed.get()) runCatching { send(RpcCodec.encode(RpcMessage.Cancel(requestId))) }
            throw cancelled
        } finally {
            pending.remove(requestId)
        }
    }

    /** One message from the host, on the connection's reader. */
    private fun handleMessage(payload: ByteArray) {
        when (HandshakeMessage.of(payload)) {
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

        val message = RpcCodec.decode(payload) ?: return

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
                dispatcher.receivedReply {
                    pending.remove(message.requestId)
                        ?.complete(HostReply(message.json, message.buffers.firstOrNull(), message.buffers))
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
            objectBuffers = message.objectBuffers,
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
        myExtensions = myExtensions,
        parentPid = ProcessHandle.current().pid(),
        globalStorageHome = globalStorageHome,
        workspaceStorageHome = workspaceStorageHome,
    ).toString()

    private fun send(payload: ByteArray) {
        if (closed.get()) throw IOException("Extension host is closed")
        connection.send(payload)
    }

    private fun finish(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        initialized.completeExceptionally(error)
        pending.values.toList().forEach { it.completeExceptionally(error) }
        pending.clear()
        dispatcher.close()
        runCatching { connection.close() }
        termination.complete(error)
        scope.cancel()
    }

    override fun close() = finish(IOException("Extension host session closed"))

    /** Native-host fixtures wait after close so Windows releases the host's open log files. */
    internal fun awaitProcessExit(timeout: Long, unit: TimeUnit): Boolean = (connection as? ProcessHostConnection)?.awaitExit(timeout, unit) ?: true

    private companion object {
        /** The host waits on this answer at startup; below this it is not worth a line in the log. */
        const val SLOW_READY_MILLIS = 150L

        /** How long a call waits for the host's reply; commands, which may wait on the user, wait for as long as they take. */
        const val REPLY_SECONDS = 90L
    }
}
