package me.helvert.codebridge.exthost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.exthost.protocol.RpcMessage
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orders requests and host replies while allowing an async handler to wait for nested calls.
 *
 * One queue serves both directions: calls arriving from the host, and the completion of replies to
 * calls we made. That ordering is deliberate -- a registration sent before an activation reply must
 * be handled before that reply completes -- but it also means a reply we are waiting on cannot be
 * delivered until everything queued ahead of it has been handled. [report] says when that has cost
 * something, which is otherwise invisible: the waiting side only sees a slow extension.
 */
internal class WorkbenchDispatcher(
    private val handler: MainThreadHandler,
    private val send: (RpcMessage) -> Unit,
    private val report: (String) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean()
    /**
     * Where calls and replies are taken, one at a time. On the IO pool, because a synchronous
     * handler's reply is written from here, and a write to a full pipe blocks.
     */
    private val queue = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineName("codebridge-workbench")
    )
    private val incoming = ConcurrentHashMap<Int, CompletableDeferred<MainThreadResult>>()
    /**
     * Where handlers run. Each starts in [queue] and keeps it until it first suspends, which is
     * what keeps calls in order; after that it is off the queue, so a handler waiting on
     * the editor thread or the host holds up nothing behind it.
     */
    private val handlers = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("codebridge-workbench"))
    private val handled = java.util.concurrent.atomic.AtomicLong()
    private val first = java.util.concurrent.atomic.AtomicLong()

    // A registration sent before an activation reply must be handled before that reply completes
    // a command's activation future. Completing futures directly on the reader would race it.
    fun receivedReply(complete: () -> Unit) {
        val queued = System.nanoTime()
        execute {
            val waited = millisSince(queued)
            // The reply was already on the wire this long ago; whoever is blocked on it paid for
            // main-thread work it has nothing to do with.
            if (waited >= SLOW_MILLIS) report("A host reply waited ${waited}ms behind main-thread work")
            complete()
        }
    }

    fun request(message: RpcMessage.Request, call: MainThreadCall) {
        if (closed.get()) return
        countRequest()
        val response = CompletableDeferred<MainThreadResult>()
        incoming[message.requestId] = response
        response.invokeOnCompletion { error ->
            incoming.remove(message.requestId)
            if (closed.get()) return@invokeOnCompletion
            send(if (error != null) failed(message.requestId, error) else {
                @OptIn(ExperimentalCoroutinesApi::class)
                val result = response.getCompleted()
                when {
                    result.bytes != null -> RpcMessage.ReplyOkBuffer(message.requestId, result.bytes)
                    result.json != null -> RpcMessage.ReplyOkJson(message.requestId, result.json)
                    else -> RpcMessage.ReplyOkEmpty(message.requestId)
                }
            })
        }
        val queued = System.nanoTime()
        execute {
            if (response.isCompleted) return@execute
            val waited = millisSince(queued)
            // Split, because the two halves have different causes. A synchronous handler completes
            // its future before returning, so the reply is encoded and written from the queue too --
            // and a write to a full pipe blocks. Lumping them together reads as a slow handler.
            var computing = 0L
            try {
                if (message.objectBuffers.values.any { it.isNotEmpty() })
                    throw MainThreadException(
                        "Unavailable",
                        "Nested binary arguments are not supported by ${call.service}.${call.method}"
                    )
                val started = System.nanoTime()
                val handling = handlers.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        response.complete(handler.answer(call))
                    } catch (error: Throwable) {
                        response.completeExceptionally(error)
                    }
                }
                computing = millisSince(started)
                response.invokeOnCompletion { if (response.isCancelled) handling.cancel() }
            } catch (error: Exception) {
                response.completeExceptionally(error)
            }
            // Time spent here is time no reply can be delivered in, whichever half it went to.
            val held = millisSince(queued) - waited
            if (held >= SLOW_MILLIS || waited >= SLOW_MILLIS) report(
                "${call.service}.${call.method} held the workbench thread for ${held}ms" +
                        " (${computing}ms computing, ${held - computing}ms writing the reply)" +
                        " after queueing for ${waited}ms"
            )
        }
        if (closed.get()) response.cancel(CancellationException())
    }

    /** The reply telling the host its request [requestId] failed with [error]. */
    private fun failed(requestId: Int, error: Throwable) = RpcMessage.ReplyError(
        requestId,
        (error as? ExtensionHostSession.ExtensionHostException)?.errorJson ?: buildJsonObject {
            put($$"$isError", true)
            put("name", (error as? MainThreadException)?.remoteName ?: error.javaClass.simpleName)
            put("message", error.message ?: "Main-thread request failed")
        }.toString()
    )

    private fun execute(action: () -> Unit) {
        if (closed.get()) return
        queue.launch { if (!closed.get()) action() }
    }

    /** Withdraws a call the host no longer wants answered; the host hears it was cancelled. */
    fun cancel(requestId: Int) {
        incoming.remove(requestId)?.cancel(CancellationException())
    }

    /**
     * Reports the rate calls are arriving at, which no per-call line can show.
     *
     * An extension activating makes hundreds of these -- rust-analyzer registers over a hundred
     * commands alone -- and each is individually far too fast to report. What matters is whether
     * they are arriving as fast as the host can make them or trickling in at some per-call cost of
     * ours, and only the span across many of them says which.
     */
    private fun countRequest() {
        val count = handled.incrementAndGet()
        first.compareAndSet(0L, System.nanoTime())
        if (count % REPORT_EVERY == 0L) {
            val span = millisSince(first.get())
            // A rate, not a problem: logged rather than reported, so it does not read as one.
            LOG.info("Served $count main-thread call(s) in ${span}ms (${span / count}ms each)")
        }
    }

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        incoming.values.toList().forEach { it.cancel(CancellationException()) }; incoming.clear()
        handlers.cancel()
        queue.cancel()
    }

    private companion object {
        val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(WorkbenchDispatcher::class.java)

        /** Below this the ordering costs nothing anyone could feel. */
        const val SLOW_MILLIS = 150L

        /** Often enough to cover one extension activating, rarely enough to stay readable. */
        const val REPORT_EVERY = 100L
    }
}
