package me.helvert.codebridge.debug

import com.intellij.platform.dap.connection.DebugAdapterHandle
import java.io.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts extension-host RPC to IntelliJ's existing DAP client. Only Content-Length envelopes
 * change: request, response and event bodies (including reverse requests) pass through untouched.
 * No adapter process or socket is created here; the extension host owns the adapter.
 */
class ExtensionHostDebugAdapterHandle(private val transport: DapTransport) : DebugAdapterHandle, Closeable {
    private val closed = AtomicBoolean()
    override val input: InputStream field = MessageInput()
    override val output: OutputStream = object : OutputStream() {
        private val header = ByteArrayOutputStream()
        private val body = ByteArrayOutputStream()
        private var length: Int? = null

        @Synchronized
        override fun write(value: Int) {
            if (closed.get()) throw IOException("Debug adapter connection closed")
            try {
                if (length == null) {
                    header.write(value)
                    if (header.size() > MAX_HEADER) throw IOException("DAP header exceeds $MAX_HEADER bytes")
                    if (value == 10 && header.size() >= 4) {
                        val bytes = header.toByteArray()
                        if (bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) {
                            val lengths = bytes.toString(Charsets.US_ASCII).split("\r\n")
                                .filter { it.substringBefore(':').equals("Content-Length", ignoreCase = true) }
                            val size = lengths.singleOrNull()?.substringAfter(':')?.trim()?.toIntOrNull()
                                ?: throw IOException("Missing or invalid DAP Content-Length")
                            if (size !in 1..MAX_MESSAGE) throw IOException("Invalid DAP message size: $size")
                            length = size
                            header.reset()
                        }
                    }
                } else {
                    body.write(value)
                    if (body.size() == length) {
                        val json = body.toByteArray().toString(Charsets.UTF_8)
                        body.reset()
                        length = null
                        transport.send(json)
                    }
                }
            } catch (error: Exception) {
                fail(error)
                throw error
            }
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            Objects.checkFromIndexSize(offset, count, bytes.size)
            for (index in offset until offset + count) write(bytes[index].toInt() and 0xff)
        }

        override fun close() = this@ExtensionHostDebugAdapterHandle.close()
    }

    init {
        try {
            transport.connect({ json ->
                if (!closed.get()) {
                    try {
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        if (bytes.size > MAX_MESSAGE) throw IOException("DAP message too large")
                        input.offer("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + bytes)
                    } catch (error: Exception) {
                        fail(error)
                    }
                }
            }, ::fail)
        } catch (error: Exception) {
            fail(error)
            throw error
        }
    }

    private fun fail(error: Throwable) {
        if (closed.compareAndSet(false, true)) {
            input.finish(IOException("Extension-host debug adapter failed", error))
            runCatching { transport.close() }
        }
    }

    override suspend fun disconnect() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            input.finish(null)
            transport.close()
        }
    }

    /** Queue complete frames without blocking the extension host's shared RPC reader. */
    private inner class MessageInput : InputStream() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait/notifyAll need java.lang.Object
        private val lock = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var offset = 0
        private var queuedBytes = 0
        private var finished = false
        private var failure: IOException? = null

        fun offer(bytes: ByteArray) = synchronized(lock) {
            if (!finished) {
                if (queuedBytes + bytes.size > MAX_QUEUED) throw IOException("DAP input queue overflow")
                chunks.addLast(bytes)
                queuedBytes += bytes.size
                lock.notifyAll()
            }
        }

        fun finish(error: IOException?) = synchronized(lock) {
            finished = true
            failure = error
            lock.notifyAll()
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, start: Int, count: Int): Int = synchronized(lock) {
            Objects.checkFromIndexSize(start, count, bytes.size)
            if (count == 0) return@synchronized 0
            while (chunks.isEmpty() && !finished) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("DAP read interrupted")
                }
            }
            if (chunks.isEmpty()) {
                failure?.let { throw it }
                return@synchronized -1
            }
            val chunk = chunks.first
            val copied = minOf(count, chunk.size - offset)
            chunk.copyInto(bytes, start, offset, offset + copied)
            offset += copied
            queuedBytes -= copied
            if (offset == chunk.size) {
                chunks.removeFirst(); offset = 0
            }
            copied
        }

        override fun close() = this@ExtensionHostDebugAdapterHandle.close()
    }

    private companion object {
        const val MAX_HEADER = 8192
        const val MAX_MESSAGE = 16 * 1024 * 1024
        const val MAX_QUEUED = 32 * 1024 * 1024
    }
}
