package me.helvert.codebridge.workbench

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.OutputChannels
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadOutputService`: the Output panel, where language servers say what went wrong.
 *
 * It is the first place anyone looks when an extension misbehaves, and until now everything written
 * there was discarded. Answering it is also how the plugin stops being unable to explain itself:
 * the server's own account of a failure is usually the whole diagnosis.
 *
 * The protocol is unusual, and none of it is guessable -- it was read off the runtime bundle. The
 * extension host does not send the text. It writes to a log file of its own and tells us how far it
 * has got with `$update`, so this reads that file from the offset it last stopped at. And it only
 * sends `$update` for the channel it believes is *visible* -- one at a time, chosen by us with
 * `$setVisibleChannel`. A channel nobody is looking at costs nothing and produces nothing, which is
 * why selecting a tab has to reach back into the host rather than just switch a panel.
 */
@Service(Service.Level.PROJECT)
class HostedOutputChannels(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /**
     * One `window.createOutputChannel`, as the host reports it, or an extension's own log.
     *
     * [polled] marks a log: the host writes those files itself and never sends `$update` for them,
     * so the only way to follow one is to look at the file while its tab is showing.
     */
    class Channel(
        val id: String,
        val name: String,
        val file: Path,
        val polled: Boolean = false,
        /** The extension that created it, where the host said; how "Show Output" finds its tab. */
        val extensionId: String? = null,
    ) {
        /** How much of [file] has been drawn already. */
        @Volatile
        var offset: Long = 0
    }

    /** The tool window, as this service needs it. Null until someone opens it. */
    interface View {
        /** A channel was registered or disposed; rebuild the tabs. */
        fun refresh(channels: List<Channel>)
        fun select(channelId: String)

        /** [reset] means everything drawn before this point is no longer part of the channel. */
        fun append(channel: Channel, text: String, reset: Boolean)
    }

    private val channels = ConcurrentHashMap<String, Channel>()
    private val order = mutableListOf<String>()
    private val ids = AtomicInteger()
    @Volatile
    private var visible: String? = null
    @Volatile
    private var session: ExtensionHostSession? = null
    @Volatile
    private var disposed = false
    @Volatile
    private var view: View? = null

    /** Log channels by the logger resource that registered them, so deregistration can find them. */
    private val logs = ConcurrentHashMap<String, String>()

    /** File reads and the calls they answer stay in order, and off the RPC reader thread. */
    private val worker = Dispatchers.IO.limitedParallelism(1)

    init {
        // A log's tab follows its file only while it is the one showing, so a panel nobody looks
        // at, or one showing an ordinary channel, costs a size check at most.
        scope.launch(worker) {
            while (true) {
                delay(POLL_MILLIS.milliseconds)
                val id = visible ?: continue
                if (channels[id]?.polled == true) update(id, APPEND, null)
            }
        }
    }

    fun hostStarted(session: ExtensionHostSession) {
        this.session = session
    }

    fun hostStopped() {
        session = null
        channels.clear()
        logs.clear()
        synchronized(order) { order.clear() }
        visible = null
        onEdt { view?.refresh(emptyList()) }
    }

    /** The channels registered so far, in the order the extensions created them. */
    fun channels(): List<Channel> = synchronized(order) { order.mapNotNull(channels::get) }

    /** Called by the tool window once it is built, and again with null when it is disposed. */
    fun attach(view: View?) {
        this.view = view
        view?.refresh(channels())
    }

    /**
     * A panel going away; ignored if another has attached since.
     *
     * Now that a panel lives as long as its tool window rather than the project, a newer window
     * can attach before the old one is disposed, and must not be detached by it.
     */
    fun detach(view: View) {
        if (this.view === view) this.view = null
    }

    /**
     * Handles one `MainThreadOutputService` call, returning the reply the host waits for.
     *
     * `$register` is the only one with a result: the id it answers is what every later call names
     * the channel by, so it is minted here rather than taken from anything the extension controls.
     */
    fun handle(call: MainThreadCall): String? {
        try {
            when (call.method) {
                OutputChannels.REGISTER -> return register(OutputChannels.registration(call.argsJson))
                OutputChannels.UPDATE -> OutputChannels.update(call.argsJson)
                    .let { update(it.channelId, it.mode, it.till) }

                OutputChannels.REVEAL -> reveal(OutputChannels.channelId(call.method, call.argsJson))
                OutputChannels.CLOSE ->
                    if (visible == OutputChannels.channelId(call.method, call.argsJson)) show(null)

                OutputChannels.DISPOSE -> forget(OutputChannels.channelId(call.method, call.argsJson))
            }
        } catch (failure: Exception) {
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
        return null
    }

    /** Mints the id every later call names the channel by; the extension does not choose it. */
    private fun register(registration: OutputChannels.Registration): String {
        val name = registration.name
        val channel = Channel(
            "codebridge-output-${ids.incrementAndGet()}", name, registration.file.toPath(),
            extensionId = registration.extensionId
        )
        channels[channel.id] = channel
        synchronized(order) { order.add(channel.id) }
        LOG.info("An extension opened the output channel '$name'")
        onEdt { view?.refresh(channels()) }
        return OutputChannels.registered(channel.id)
    }

    /**
     * An extension's own log, as `MainThreadLogger` registers it: a tab like any channel's.
     *
     * VS Code lists these in the Output panel beside the ordinary channels, and they are usually
     * where an extension says the most -- rust-analyzer's "rust-analyzer Extension" log carries its
     * startup and every failure, while its "Language Server" channel is empty unless the server
     * itself errs. Without them, the panel an extension opens to explain itself looked empty.
     */
    fun logRegistered(resource: String, name: String, file: Path, extensionId: String? = null) {
        if (logs.containsKey(resource)) return
        val channel = Channel("codebridge-log-${ids.incrementAndGet()}", name, file, polled = true, extensionId = extensionId)
        logs[resource] = channel.id
        channels[channel.id] = channel
        synchronized(order) { order.add(channel.id) }
        onEdt { view?.refresh(channels()) }
    }

    fun logDeregistered(resource: String) {
        logs.remove(resource)?.let(::forget)
    }

    /**
     * Chooses which channel the host should keep flushing.
     *
     * Only one can be visible, which is VS Code's own rule rather than a simplification of it: the
     * host tracks a single `visibleChannelId` and stays silent about the rest. Whatever was written
     * while nothing was visible is already in the file, so becoming visible reads from the recorded
     * offset and the backlog appears rather than being lost.
     */
    fun show(id: String?) {
        if (visible == id) return
        visible = id
        session?.let { call ->
            // A log is not a channel the host knows by id; for the host, nothing is visible.
            val hostId = id?.takeUnless { channels[it]?.polled == true }
            val argument = if (hostId == null) "null" else JsonPrimitive(hostId).toString()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    call.call("ExtHostOutputService", $$"$setVisibleChannel", "[$argument]")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.warn("Could not make output channel $id visible", failure)
                }
            }
        }
        // Draw the backlog either way: whether the host was told or not, what it wrote while
        // nothing was visible is already in the file and is what the user came to read.
        id?.let { update(it, APPEND, null) }
    }

    /**
     * `$update(id, mode, till)`: read what the host has written since we last looked.
     *
     * `till` is the offset the channel stood at *before* the operation, so Replace and Clear both
     * mean "nothing before this point counts any more"; they differ only in whether what follows is
     * drawn.
     */
    private fun update(id: String, mode: Int, till: Long?) {
        val channel = channels[id] ?: return
        val reset = mode != APPEND
        scope.launch(worker) {
            if (disposed || project.isDisposed) return@launch
            // Here, in order with the reads, not on the caller's thread: an append still queued
            // would otherwise read past the point this rewinds to, draw the replacement as plain
            // output, and leave the replace itself with nothing to draw.
            if (reset) channel.offset = till ?: channel.offset
            val read = if (mode == CLEAR) Read.NOTHING else runCatching { read(channel) }
                .onFailure { LOG.warn("Could not read output channel '${channel.name}'", it) }
                .getOrDefault(Read.NOTHING)
            // A file read again from its start replaces what was drawn, as a Replace would.
            val replace = reset || read.restarted
            if (read.text.isEmpty() && !replace) return@launch
            onEdt { view?.append(channel, read.text, replace) }
        }
    }

    /** What one [read] found: [text] new since the last, or from the start when [restarted]. */
    private class Read(val text: String, val restarted: Boolean) {
        companion object {
            val NOTHING = Read("", false)
        }
    }

    /** Reads from the recorded offset to the end, and remembers where that was. */
    private fun read(channel: Channel): Read {
        if (!Files.exists(channel.file)) return Read.NOTHING
        val size = Files.size(channel.file)
        // A rotated or rewritten file is shorter than what we already drew; start again rather than
        // read from beyond its end, and say so, so the old text is not left above the new.
        val restarted = size < channel.offset
        if (restarted) channel.offset = 0
        if (size == channel.offset) return Read("", restarted)
        Files.newByteChannel(channel.file).use { source ->
            source.position(channel.offset)
            val buffer = ByteBuffer.allocate((size - channel.offset).coerceAtMost(MAX_READ).toInt())
            while (buffer.hasRemaining() && source.read(buffer) > 0) continue
            channel.offset += buffer.position()
            return Read(String(buffer.array(), 0, buffer.position(), Charsets.UTF_8), restarted)
        }
    }

    /** `channel.show()` from an extension: open the panel even if nobody ever has. */
    private fun reveal(id: String) {
        if (!channels.containsKey(id)) return
        onEdt {
            val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@onEdt
            // The content is built by the factory the first time it is shown, so selecting has to
            // wait for that: before it, there is no view to select in.
            window.show { view?.select(id) }
        }
    }

    private fun forget(id: String) {
        channels.remove(id) ?: return
        synchronized(order) { order.remove(id) }
        if (visible == id) visible = null
        onEdt { view?.refresh(channels()) }
    }

    override fun dispose() {
        disposed = true
        view = null
    }

    private fun onEdt(block: () -> Unit) {
        scope.launch(Dispatchers.EDT) { if (!project.isDisposed) block() }
    }

    companion object {
        private val LOG = Logger.getInstance(HostedOutputChannels::class.java)

        /** Must match the `toolWindow` id in `CodeBridge.backend.xml`. */
        const val TOOL_WINDOW_ID = "CodeBridge Output"

        /** VS Code's `OutputChannelUpdateMode`. */
        private const val APPEND = 1

        @Suppress("unused") // listed for completeness; anything but APPEND resets
        private const val REPLACE = 2
        private const val CLEAR = 3

        /** How often a showing log's file is looked at: prompt enough to read as live. */
        private const val POLL_MILLIS = 500L

        /** One read; a channel that outran us catches up on the next update rather than in one gulp. */
        private const val MAX_READ = 1L shl 20

        /**
         * The log file the host named, from the URI components it sent.
         *
         * `path` is VS Code's `/C:/...` spelling, which `java.net.URI` only reads correctly once it
         * carries a scheme -- the same trap the document keys fell into.
         */
        fun getInstance(project: Project): HostedOutputChannels = project.service()
    }
}
