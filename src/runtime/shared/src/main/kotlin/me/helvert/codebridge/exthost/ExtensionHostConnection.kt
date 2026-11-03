package me.helvert.codebridge.exthost

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import me.helvert.codebridge.exthost.protocol.Frame
import me.helvert.codebridge.exthost.protocol.FrameCodec
import me.helvert.codebridge.exthost.protocol.FrameType
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * How an [ExtensionHostSession] reaches its host: whole protocol messages each way, whatever carries
 * them. VS Code's Node host is a child process on a pipe ([ProcessHostConnection]); its web worker
 * host runs in a browser, and is reached through whatever relays its `MessagePort`.
 */
interface ExtensionHostConnection : Closeable {
    /**
     * Starts the host and connects to it, returning once connected. [received] is handed each message
     * from the host, one at a time and in order; [failed] is told once that the connection has ended,
     * and why.
     */
    fun open(received: (ByteArray) -> Unit, failed: (Throwable) -> Unit)

    /** Hands over one message for the host. Never blocks on the host; throws once the connection has ended. */
    fun send(message: ByteArray)
}

/**
 * VS Code's Node extension host: a child process that dials the pipe it is pointed at, and frames
 * every message it exchanges on it.
 */
internal class ProcessHostConnection(
    private val installation: VsCodeInstallation,
    private val storageRoot: Path,
    /** Opt-in experiments on a patched runtime; cannot be combined with process interception. */
    private val preloadScript: Path? = null,
    private val report: (String) -> Unit = {},
) : ExtensionHostConnection {
    private val pipe: ExtensionHostPipeServer = ExtensionHostPipeServer.create("codebridge-" + UUID.randomUUID())
    private var channel: ExtensionHostChannel? = null
    @Volatile
    private var process: Process? = null
    private var reader: Thread? = null
    @Volatile
    private var writer: FrameWriter? = null
    private var failed: (Throwable) -> Unit = {}

    private val outgoingFrameId = AtomicInteger(0)
    @Volatile
    private var lastIncomingFrameId = 0
    private val closed = AtomicBoolean()

    /** Where the connection watches its process; ends with it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("codebridge-exthost-process"))

    override fun open(received: (ByteArray) -> Unit, failed: (Throwable) -> Unit) {
        this.failed = failed
        val spec = installation.launchSpec(pipe.endpoint, ProcessHandle.current().pid()).copy(preloadScript = preloadScript)
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
            finish(IOException("Extension host exited with code ${exited.exitValue()}; see ${storageRoot.resolve("logs/exthost-stdout.log")}"))
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
        reader = Thread({ readLoop(received) }, "codebridge-exthost-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(received: (ByteArray) -> Unit) {
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
                    if (frame.type == FrameType.KEEP_ALIVE || frame.type == FrameType.RESUME || frame.payload.isEmpty()) continue
                    lastIncomingFrameId = frame.id
                    received(frame.payload)
                }
            }
        } catch (error: Exception) {
            finish(error)
        }
    }

    /**
     * Queues a frame; [FrameWriter] puts it on the wire, and explains why it is not written here.
     *
     * The frame id is assigned under the same lock that enqueues, so ids reach the host in the
     * order they were taken. They must start at 1 and never skip: a gap makes the host wait
     * silently forever.
     */
    @Synchronized
    override fun send(message: ByteArray) {
        val writer = this.writer ?: throw IOException("Extension host is not connected")
        if (closed.get()) throw IOException("Extension host is closed")
        writer.enqueue(FrameCodec.encode(Frame(FrameType.REGULAR, outgoingFrameId.incrementAndGet(), lastIncomingFrameId, message)))
    }

    private fun finish(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process?.destroy() }
        runCatching { channel?.close() }
        runCatching { pipe.close() }
        reader?.interrupt()
        runCatching { writer?.close() }
        scope.cancel()
        failed(error)
    }

    override fun close() = finish(IOException("Extension host session closed"))

    /** Native-host fixtures wait after close so Windows releases the host's open log files. */
    fun awaitExit(timeout: Long, unit: TimeUnit): Boolean = process?.waitFor(timeout, unit) ?: true

    private companion object {
        /** How long a started host has to connect. */
        const val CONNECT_SECONDS = 30L
    }
}
