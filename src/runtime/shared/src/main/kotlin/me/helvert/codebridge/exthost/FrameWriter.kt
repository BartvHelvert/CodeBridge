package me.helvert.codebridge.exthost

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts frames on the wire, and is the only thing allowed to block doing it.
 *
 * The pipe is in blocking mode once the host connects, so a write stalls for as long as the host
 * takes to drain it -- seconds at a time while an extension is activating, and a published
 * extension can register hundreds of things while it starts. That used to happen on whichever
 * thread called `send`: the reader, which then stopped reading, and the workbench dispatcher, which
 * then delivered no replies. Nothing the plugin did could be felt while either was stuck, so a
 * language feature waiting on a reply and an extension's progress waiting to be drawn both queued
 * behind writes they had nothing to do with -- measured at over two minutes behind.
 *
 * Moving the wait here does not make the host read any sooner. It makes everything else carry on
 * while it does not.
 */
internal class FrameWriter(
    private val channel: ExtensionHostChannel,
    private val onFailure: (Throwable) -> Unit,
    private val report: (String) -> Unit = {},
) : AutoCloseable {

    /** A frame and when it was handed over, so the wait before it reached the wire is visible. */
    private class Queued(val frame: ByteArray, val at: Long)

    private val outgoing = LinkedBlockingQueue<Queued>()
    private val closed = AtomicBoolean()
    private val thread = Thread(::run, "codebridge-exthost-writer").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    /**
     * Hands over one encoded frame.
     *
     * Never blocks on the host, which is the whole point. The queue is unbounded: a bound would
     * reintroduce the stall at the boundary, and a host that has stopped reading entirely is a
     * session that ends on the read side rather than a backlog worth capping.
     */
    fun enqueue(frame: ByteArray) {
        if (!closed.get()) outgoing.put(Queued(frame, System.nanoTime()))
    }

    /** Frames written but not yet on the wire, for tests and diagnosis. */
    fun queued(): Int = outgoing.size

    /**
     * A failure here is the connection failing.
     *
     * There is no frame to retry: the host counts them, and a gap makes it wait silently forever.
     * So the session ends, which is what [onFailure] does.
     */
    private fun run() {
        try {
            while (!closed.get()) {
                val queued = outgoing.take()
                // Split, because they have different causes and only one of them is ours: time
                // spent waiting behind other frames, and time the write itself took because the
                // host was not draining the pipe.
                val waited = millisSince(queued.at)
                val started = System.nanoTime()
                channel.write(queued.frame)
                val writing = millisSince(started)
                if (waited + writing >= SLOW_MILLIS) {
                    report("A ${queued.frame.size}-byte frame waited ${waited}ms to be written and ${writing}ms writing")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!closed.get()) onFailure(error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        thread.interrupt()
        outgoing.clear()
    }

    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private companion object {
        /** Below this nothing on the wire could be felt. */
        const val SLOW_MILLIS = 150L
    }
}
