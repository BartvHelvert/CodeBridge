package me.helvert.codebridge.exthost

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one property the whole plugin's responsiveness rests on: writing must not block the caller.
 *
 * The pipe blocks whenever the extension host is not draining it, which a real one does for seconds
 * at a time while an extension activates. When that wait happened on the calling thread it took the
 * RPC reader and the workbench dispatcher with it, and every reply, diagnostic and progress update
 * queued behind writes they had nothing to do with -- two minutes of backlog against rust-analyzer,
 * with `0ms computing, 2026ms writing the reply` in the log to say where it went.
 *
 * None of that was visible as a write problem from the outside; it looked like a slow extension.
 */
class FrameWriterTest {

    /** Comfortably past the writer's own threshold, so the test is not racing it. */
    private val stallMillis = 400L

    /** A pipe that has stopped draining, and records what eventually got through. */
    private class StalledChannel(private val release: CountDownLatch) : ExtensionHostChannel {
        val written = ConcurrentLinkedQueue<String>()
        val reached = CountDownLatch(1)

        override fun read(buffer: ByteArray): Int = -1

        override fun write(bytes: ByteArray) {
            reached.countDown()
            // As a blocking-mode pipe does while the host is busy elsewhere.
            assertTrue(release.await(5, TimeUnit.SECONDS), "Test did not release the write")
            written.add(String(bytes))
        }

        override fun close() = Unit
    }

    @Test
    fun `queueing does not wait for a host that has stopped reading`() {
        val release = CountDownLatch(1)
        val channel = StalledChannel(release)
        FrameWriter(channel, onFailure = { }).use { writer ->
            writer.start()
            writer.enqueue("first".toByteArray())
            assertTrue(channel.reached.await(5, TimeUnit.SECONDS), "The writer should have started writing")

            // The wire is now stuck. Callers must still get on with their work -- this is the
            // reader acknowledging a request, and the dispatcher answering one.
            val queued = System.nanoTime()
            repeat(50) { writer.enqueue("frame-$it".toByteArray()) }
            val waited = (System.nanoTime() - queued) / 1_000_000
            assertTrue(waited < 1_000, "Queueing behind a stalled write took ${waited}ms")

            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (channel.written.size < 51 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(51, channel.written.size, "Everything queued has to reach the wire")
            // Order is the host's whole protocol: it counts frames and waits silently on a gap.
            assertEquals("first", channel.written.first())
            assertEquals(List(50) { "frame-$it" }, channel.written.drop(1))
        }
    }

    @Test
    fun `a host that has stopped draining is reported as such`() {
        // Without this the logs cannot tell a host that is not reading from an extension that is
        // simply slow, which is exactly the confusion that made the original stall take so long to
        // find: every symptom pointed at the extension.
        val release = CountDownLatch(1)
        val channel = StalledChannel(release)
        val reported = ConcurrentLinkedQueue<String>()
        FrameWriter(channel, onFailure = { }, report = reported::add).use { writer ->
            writer.start()
            writer.enqueue("first".toByteArray())
            assertTrue(channel.reached.await(5, TimeUnit.SECONDS))
            // Long enough to be worth reporting; the threshold is deliberately above anything a
            // healthy host would take.
            Thread.sleep(stallMillis)
            release.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reported.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            val line = reported.peek()
            assertNotNull(line, "A stalled write has to be reported")
            assertTrue(line.contains("writing"), "The report must say the write itself stalled: $line")
        }
    }

    @Test
    fun `a failed write ends the session rather than losing the frame silently`() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val channel = object : ExtensionHostChannel {
            override fun read(buffer: ByteArray): Int = -1
            override fun write(bytes: ByteArray) = throw java.io.IOException("pipe broke")
            override fun close() = Unit
        }
        FrameWriter(channel, onFailure = failures::add).use { writer ->
            writer.start()
            writer.enqueue("doomed".toByteArray())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (failures.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        }
        // There is no retry: the host counts frames, so a dropped one would hang it rather than
        // fail it. The session has to end instead.
        assertNotNull(failures.peek(), "A broken pipe must be reported")
    }

    @Test
    fun `closing stops the writer and drops what it was holding`() {
        val release = CountDownLatch(1)
        val channel = StalledChannel(release)
        val writer = FrameWriter(channel, onFailure = { })
        writer.start()
        writer.enqueue("first".toByteArray())
        assertTrue(channel.reached.await(5, TimeUnit.SECONDS))
        writer.enqueue("never".toByteArray())

        writer.close()
        release.countDown()

        // Nothing queued after a close reaches a host that is going away, and enqueueing on a
        // closed writer must not grow a queue nobody will drain.
        writer.enqueue("also never".toByteArray())
        assertEquals(0, writer.queued())
    }
}
