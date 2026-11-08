import com.sun.net.httpserver.HttpServer
import org.gradle.api.GradleException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class DownloadVerifiedTest {
    @TempDir lateinit var directory: Path
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val requests = AtomicInteger()
    private val payload = "verified archive".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val target get() = directory.resolve("runtime.tar.gz").toFile()
    private val policy = DownloadPolicy(backoffMillis = 0)
    private val url get() = "http://127.0.0.1:${server.address.port}/archive"

    @AfterEach fun stop() { server.stop(0) }

    private fun respond(status: (Int) -> Int) {
        server.createContext("/archive") { exchange ->
            exchange.use {
                val code = status(requests.incrementAndGet())
                it.sendResponseHeaders(code, payload.size.toLong())
                it.responseBody.write(payload)
            }
        }
    }

    @Test fun `transient gateway errors recover with a verified archive`() {
        respond { if (it < 3) 504 else 200 }
        downloadVerified(url, hash, target, policy)
        assertEquals(3, requests.get())
        assertArrayEquals(payload, target.readBytes())
        assertEquals(listOf(target.name), directory.toFile().list()!!.toList())
    }

    @Test fun `request timeout and rate limiting are retried`() {
        respond { when (it) { 1 -> 408; 2 -> 429; else -> 200 } }
        downloadVerified(url, hash, target, policy)
        assertEquals(3, requests.get())
        assertArrayEquals(payload, target.readBytes())
    }

    @Test fun `permanent HTTP errors fail on the first request`() {
        respond { 404 }
        val failure = assertThrows(IOException::class.java) { downloadVerified(url, hash, target, policy) }
        assertTrue(failure.message!!.contains("404"))
        assertEquals(1, requests.get())
        assertFalse(target.exists())
        assertTrue(directory.toFile().list()!!.isEmpty())
    }

    @Test fun `exhausted retries preserve an existing archive and clean partial files`() {
        target.writeText("previous archive")
        respond { 503 }
        assertThrows(IOException::class.java) { downloadVerified(url, hash, target, policy) }
        assertEquals(4, requests.get())
        assertEquals("previous archive", target.readText())
        assertEquals(listOf(target.name), directory.toFile().list()!!.toList())
    }

    @Test fun `checksum failures are never retried or installed`() {
        respond { 200 }
        assertThrows(GradleException::class.java) { downloadVerified(url, "incorrect hash", target, policy) }
        assertEquals(1, requests.get())
        assertFalse(target.exists())
        assertTrue(directory.toFile().list()!!.isEmpty())
    }

    @Test fun `verified cached archives need no network`() {
        target.writeBytes(payload)
        respond { 500 }
        downloadVerified(url, hash, target, policy)
        assertEquals(0, requests.get())
    }

    @Test fun `a read timeout is retried without leaving a partial archive`() {
        server.createContext("/archive") { exchange ->
            exchange.use {
                if (requests.incrementAndGet() == 1) {
                    // No response until the client closes its timed-out connection.
                    Thread.sleep(150)
                } else {
                    it.sendResponseHeaders(200, payload.size.toLong())
                    it.responseBody.write(payload)
                }
            }
        }
        downloadVerified(url, hash, target, policy.copy(attempts = 10, readTimeoutMillis = 100))
        assertTrue(requests.get() >= 2)
        assertArrayEquals(payload, target.readBytes())
        assertEquals(listOf(target.name), directory.toFile().list()!!.toList())
    }
}
