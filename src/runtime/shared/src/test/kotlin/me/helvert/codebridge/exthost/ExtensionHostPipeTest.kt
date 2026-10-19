package me.helvert.codebridge.exthost

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.io.RandomAccessFile
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The pipe every extension host is launched over.
 *
 * This covered the one failure mode that hangs rather than fails: `accept()` blocks until the host
 * connects, so a host that never starts must still let the pipe be closed. It used to live in the
 * stdio proxy's test class and was deleted with it -- the pipe outlived the proxy, and the coverage
 * should have too.
 */
class ExtensionHostPipeTest {

    // A thread for every blocked call, not the common pool: each test parks one call in accept, read
    // or write and unblocks it with a close from another. On a two-core CI runner the common pool
    // has a single worker, so the close queued behind the call it was meant to release.
    private val threads = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @AfterTest
    fun stopThreads() {
        threads.shutdownNow()
    }

    // As short as the session's own `codebridge-<uuid>`: macOS keeps its temp directory deep under
    // /var/folders, and a Unix socket path has to fit in 104 bytes there.
    private fun unixSafeId() = "codebridge-${UUID.randomUUID()}"

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, threads)

    @Test
    fun `closing before accept releases the pipe and rejects acceptance`() {
        val pipe = ExtensionHostPipeServer.create(unixSafeId())
        pipe.close()
        pipe.close()
        assertFailsWith<IOException> { pipe.accept() }
    }

    @Test
    fun `failed duplicate creation leaves the original pipe usable`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val id = "codebridge-duplicate-${UUID.randomUUID()}"
        ExtensionHostPipeServer.create(id).use {
            assertFailsWith<IOException> { ExtensionHostPipeServer.create(id).close() }
        }
        ExtensionHostPipeServer.create(id).close()
    }

    @Test
    fun `concurrent close drains a pending accept repeatedly`() {
        repeat(20) {
            ExtensionHostPipeServer.create(unixSafeId()).use { pipe ->
                val started = CountDownLatch(1)
                val accept = async {
                    started.countDown()
                    runCatching { pipe.accept() }.exceptionOrNull()
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                Thread.sleep(10)
                val first = async { pipe.close() }
                val second = async { pipe.close() }
                CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS)
                assertTrue(accept.get(3, TimeUnit.SECONDS) is IOException)
            }
        }
    }

    @Test
    fun `large payloads survive repeated transfers in both directions`() = withWindowsPipe { _, channel, client ->
        val payload = ByteArray(256 * 1024 + 37) { (it * 31).toByte() }
        repeat(3) {
            val received = async {
                ByteArray(payload.size).also { client.readFully(it) }
            }
            async { channel.write(payload) }.get(5, TimeUnit.SECONDS)
            assertContentEquals(payload, received.get(5, TimeUnit.SECONDS))

            val reading = async {
                val result = ByteArray(payload.size)
                val chunk = ByteArray(8191)
                var offset = 0
                while (offset < result.size) {
                    val count = channel.read(chunk)
                    check(count > 0) { "Unexpected end of pipe at $offset" }
                    chunk.copyInto(result, offset, 0, count)
                    offset += count
                }
                result
            }
            async { client.write(payload) }.get(5, TimeUnit.SECONDS)
            assertContentEquals(payload, reading.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `peer disconnect completes a pending read as end of stream`() = withWindowsPipe { _, channel, client ->
        val read = async { channel.read(ByteArray(32)) }
        Thread.sleep(100)
        assertFalse(read.isDone)
        client.close()
        assertEquals(-1, read.get(3, TimeUnit.SECONDS))
        assertFailsWith<IOException> { channel.write(byteArrayOf(1)) }
    }

    @Test
    fun `closing cancels a pending read and is repeatable`() = withWindowsPipe { pipe, channel, _ ->
        val read = async { channel.read(ByteArray(32)) }
        Thread.sleep(100)
        assertFalse(read.isDone)
        async { pipe.close() }.get(3, TimeUnit.SECONDS)
        assertEquals(-1, read.get(3, TimeUnit.SECONDS))
        channel.close()
        pipe.close()
        assertEquals(-1, channel.read(ByteArray(32)))
        assertFailsWith<IOException> { channel.write(byteArrayOf(1)) }
        assertFailsWith<IOException> { pipe.accept() }
    }

    @Test
    fun `closing cancels a write when the peer is not draining the pipe`() = withWindowsPipe { _, channel, _ ->
        val write = async {
            runCatching { channel.write(ByteArray(1024 * 1024)) }.exceptionOrNull()
        }
        Thread.sleep(100)
        assertFalse(write.isDone, "The payload should exceed the unread pipe's capacity")
        async { channel.close() }.get(3, TimeUnit.SECONDS)
        assertTrue(write.get(3, TimeUnit.SECONDS) is IOException)
    }

    @Test
    fun `empty transfers do not submit native IO`() = withWindowsPipe { _, channel, _ ->
        assertEquals(0, channel.read(ByteArray(0)))
        channel.write(ByteArray(0))
    }

    private fun withWindowsPipe(
        connectBeforeAccept: Boolean = true,
        block: (ExtensionHostPipeServer, ExtensionHostChannel, RandomAccessFile) -> Unit,
    ) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        // The non-ASCII name exercises CreateNamedPipeW's UTF-16 mapping. Connect before accept
        // deliberately exercises ERROR_PIPE_CONNECTED as well as ordinary pending connections.
        ExtensionHostPipeServer.create("codebridge-ffm-\u00e9-${UUID.randomUUID()}").use { pipe ->
            val accepting = if (connectBeforeAccept) null else async { pipe.accept() }
            RandomAccessFile(pipe.endpoint, "rw").use { client ->
                val channel = (accepting ?: async { pipe.accept() }).get(3, TimeUnit.SECONDS)
                channel.use { block(pipe, it, client) }
            }
        }
    }

    @Test
    fun `closing an unconnected host pipe unblocks accept`() {
        val pipe = ExtensionHostPipeServer.create(unixSafeId())
        val started = CountDownLatch(1)
        val accepted = async {
            started.countDown()
            runCatching { pipe.accept() }.exceptionOrNull()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        async { pipe.close() }.get(3, TimeUnit.SECONDS)
        assertNotNull(accepted.get(3, TimeUnit.SECONDS), "accept() must fail once the pipe closes, not hang")
    }

    @Test
    fun `writing does not wait for the peer to send something first`() = withWindowsPipe(false) { _, channel, _ ->
        // Windows serialises I/O on a synchronous handle, so a write could not start while the
        // reader thread was parked in a read on the same one -- it completed only when the peer
        // next sent something, which for an idle extension host is its two-second acknowledgement
        // timer. Every reply, diagnostic and progress update the plugin produced queued behind
        // that, and it looked from the outside exactly like a slow extension.
        val reading = async { channel.read(ByteArray(64 * 1024)) }
        // Let the read actually park on the handle before writing.
        Thread.sleep(200)

        val started = System.nanoTime()
        async { channel.write(ByteArray(18) { it.toByte() }) }.get(3, TimeUnit.SECONDS)
        val waited = (System.nanoTime() - started) / 1_000_000

        assertTrue(waited < 1_000, "Writing behind a parked read took ${waited}ms")
        assertFalse(reading.isDone, "The read should still be waiting, which is the point")
        channel.close()
        assertEquals(-1, reading.get(3, TimeUnit.SECONDS))
    }
}
