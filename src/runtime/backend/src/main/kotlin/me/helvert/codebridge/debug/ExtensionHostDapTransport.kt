package me.helvert.codebridge.debug

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.DebugAdapters
import me.helvert.codebridge.exthost.protocol.payload.DebugService
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Forward MainThreadDebugService calls here from the host's MainThreadHandler. */
class ExtensionHostDebugAdapters(
    /** Told of every message to and from an adapter, to learn the breakpoints it reports. */
    private val breakpoints: DapBreakpoints? = null,
    private val callHost: suspend (String, String) -> String,
) {
    constructor(host: ExtensionHostSession, breakpoints: DapBreakpoints? = null) : this(breakpoints, { method, args ->
        host.call(DebugService.EXT_HOST, method, args)
    })

    /** Where the adapters' calls are made: each one's in the order it was asked, behind its start. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Debug sessions now share a project host. A late stop from an old session must never
    // address an adapter started by a later session, even if startup was still pending.
    private companion object { val nextHandle = AtomicInteger() }
    private val adapters = ConcurrentHashMap<Int, Adapter>()

    /** The extension host owns executable, server and inline adapter lifetimes in all cases. */
    fun transport(sessionDto: JsonObject): DapTransport = Adapter(nextHandle.incrementAndGet(), sessionDto)

    fun handle(call: MainThreadCall): Boolean {
        if (call.service != DebugService.SERVICE) return false
        val event = DebugAdapters.event(call.method, call.argsJson) ?: return false
        val adapter = adapters[event.handle] ?: return true
        when (event) {
            // Back to the paths the adapter wrote; the host relayed them as URIs.
            is DebugAdapters.Event.Message -> {
                val json = DebugAdapters.adapterPaths(event.json)
                breakpoints?.received(adapter.sessionId, json)
                adapter.receive(json)
            }
            is DebugAdapters.Event.Failed -> adapter.failed(IOException("Debug adapter error: ${event.detail}"))
            is DebugAdapters.Event.Exited -> adapter.failed(IOException("Debug adapter exited: ${event.detail}"))
        }
        return true
    }

    /** Call when the containing extension host exits, to fail in-flight DAP requests. */
    fun hostClosed(cause: Throwable = IOException("Extension host closed")) {
        adapters.values.toList().forEach { it.failed(cause) }
    }

    private inner class Adapter(val handle: Int, val dto: JsonObject) : DapTransport {
        /** The id the host knows this adapter's session by. */
        val sessionId: String = (dto["id"] as? JsonPrimitive)?.contentOrNull ?: "adapter-$handle"
        lateinit var receive: (String) -> Unit
        lateinit var failed: (Throwable) -> Unit
        private val started = CompletableDeferred<String>()
        /** What the client sent, waiting for the adapter to have started; then passed on as it comes. */
        private val outgoing = Channel<String>(Channel.UNLIMITED)

        override fun connect(receive: (String) -> Unit, failed: (Throwable) -> Unit) {
            this.receive = receive
            this.failed = failed
            check(adapters.putIfAbsent(handle, this) == null)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    started.complete(callHost(DebugAdapters.START_SESSION, DebugAdapters.startSession(handle, dto.toString())))
                } catch (cancelled: CancellationException) {
                    started.cancel(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    started.completeExceptionally(error)
                    failed(error)
                    return@launch
                }
                // Each written as it is taken, in order; none waits for the one before to be answered.
                for (json in outgoing) launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        callHost(DebugAdapters.SEND_MESSAGE, DebugAdapters.sendMessage(handle, json))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failed(error)
                    }
                }
            }
        }

        override fun send(json: String) {
            breakpoints?.sent(sessionId, json)
            if (adapters[handle] !== this || outgoing.trySend(json).isFailure) failed(IOException("Adapter closed"))
        }

        override fun close() {
            if (adapters.remove(handle, this)) {
                breakpoints?.ended(sessionId)
                outgoing.close()
                // Queue stop after start so closing during startup cannot leak an adapter.
                scope.launch {
                    try {
                        started.await()
                        callHost(DebugAdapters.STOP_SESSION, DebugAdapters.stopSession(handle))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Never started, or the host has gone with it: nothing left to stop.
                    }
                }
            }
        }
    }
}
