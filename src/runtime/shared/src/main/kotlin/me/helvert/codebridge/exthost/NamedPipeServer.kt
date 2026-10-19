package me.helvert.codebridge.exthost

import java.io.Closeable
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * The listening end of the channel the extension host connects to.
 *
 * The host always dials us: `VSCODE_EXTHOST_IPC_HOOK` names an endpoint that must already exist
 * when the process starts, so the server has to be created before launching it.
 */
interface ExtensionHostPipeServer : Closeable {

    /** The value to pass as `VSCODE_EXTHOST_IPC_HOOK`. */
    val endpoint: String

    /** Blocks until the extension host connects. */
    fun accept(): ExtensionHostChannel

    companion object {
        private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        /**
         * Creates a server appropriate to the platform.
         *
         * Windows needs a named pipe, because Node maps `net.connect(path)` to named pipes there
         * and will not speak to an AF_UNIX socket. Elsewhere the hook is an ordinary Unix domain
         * socket path.
         */
        fun create(id: String): ExtensionHostPipeServer =
            if (isWindows) WindowsNamedPipeServer(id) else UnixSocketPipeServer(id)
    }
}

/** A connected extension host. Reads block; a closed channel reports end of stream as -1. */
interface ExtensionHostChannel : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
}

/**
 * Windows uses overlapped named pipes so reads and writes can progress independently.
 * Win32Pipe owns the FFM bindings and keeps native memory alive until cancelled I/O completes.
 */
internal class WindowsNamedPipeServer(id: String) : ExtensionHostPipeServer {
    override val endpoint: String = ExtensionHostLaunchSpec.windowsPipeName(id)
    private val pipe = Win32Pipe(endpoint)

    override fun accept(): ExtensionHostChannel {
        pipe.accept()
        return object : ExtensionHostChannel {
            override fun read(buffer: ByteArray): Int = pipe.read(buffer)
            override fun write(bytes: ByteArray) = pipe.write(bytes)
            override fun close() = pipe.close()
        }
    }

    override fun close() = pipe.close()
}

/** Unix domain socket server, for Linux and macOS where the hook is a filesystem path. */
internal class UnixSocketPipeServer(id: String) : ExtensionHostPipeServer {

    private val socketPath: Path =
        Path.of(System.getProperty("java.io.tmpdir")).resolve("$id.sock")

    override val endpoint: String = socketPath.toString()

    private val channel: ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            Files.deleteIfExists(socketPath)
            bind(UnixDomainSocketAddress.of(socketPath))
        }

    override fun accept(): ExtensionHostChannel = UnixSocketChannel(channel.accept())

    override fun close() {
        channel.close()
        Files.deleteIfExists(socketPath)
    }
}

internal class UnixSocketChannel(private val channel: SocketChannel) : ExtensionHostChannel {

    override fun read(buffer: ByteArray): Int {
        val target = ByteBuffer.wrap(buffer)
        val read = channel.read(target)
        return read
    }

    override fun write(bytes: ByteArray) {
        val source = ByteBuffer.wrap(bytes)
        while (source.hasRemaining()) channel.write(source)
    }

    override fun close() = channel.close()
}
