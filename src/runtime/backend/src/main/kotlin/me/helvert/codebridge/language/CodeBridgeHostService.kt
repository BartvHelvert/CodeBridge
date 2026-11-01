package me.helvert.codebridge.language

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import me.helvert.codebridge.exthost.HostReply
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import java.nio.file.Path

/**
 * Main-thread services a module of its own answers for the extension host, by name.
 *
 * A point rather than another branch of [HostedLanguageServers]' routing, so a feature whose UI
 * needs a platform part the backend does without -- notebooks, `CodeBridge.notebook.backend` --
 * keeps the protocol beside the UI, and the backend loads without either. A call for a service
 * nothing answers is answered with no value, as before.
 */
interface CodeBridgeHostService {
    /** The `MainThread*` services answered, by the names the host's rpcId table gives them. */
    val services: Set<String>

    /**
     * Which of the runtime's own built-in extensions, the folders in [directory], the host is to run
     * for this service. The host runs only those the user installed otherwise: VS Code's built-ins are
     * the runtime's to ship, not to start, until a feature that needs one -- notebooks need `ipynb`,
     * which reads `.ipynb` files -- asks for it.
     */
    fun builtinExtensions(directory: Path): List<Path> = emptyList()

    /** A host started for [project]; [host] calls into it until [hostStopped]. */
    fun hostStarted(project: Project, host: Host)

    /** [project]'s host stopped, and everything it had told this service went with it. */
    fun hostStopped(project: Project)

    /**
     * The reply to [call], for one of [services]. What runs before the first suspension runs in the
     * order the host sent its calls, as [me.helvert.codebridge.exthost.MainThreadHandler.answer] says.
     */
    suspend fun answer(project: Project, call: MainThreadCall): MainThreadResult

    /** Calls into a running extension host. */
    interface Host {
        /**
         * Calls [method] of [service], whose reply may be a `SerializableObjectWithBuffers`; see
         * [me.helvert.codebridge.exthost.ExtensionHostSession.callWithBuffers].
         */
        suspend fun call(
            service: String,
            method: String,
            argsJson: String,
            cancellable: Boolean = false,
            /** Arguments that are a `VSBuffer`, by position; their slot in [argsJson] is null. */
            buffers: Map<Int, ByteArray> = emptyMap(),
            objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
            /** Whether to give up on a reply that is slow in coming; see [me.helvert.codebridge.exthost.ExtensionHostSession.callWithBuffers]. */
            timesOut: Boolean = true,
        ): HostReply
    }

    companion object {
        val EP_NAME: ExtensionPointName<CodeBridgeHostService> = ExtensionPointName("me.helvert.codebridge.hostService")

        /** Every one registered; none where the point is not, as in a fixture that loads no descriptor. */
        val all: List<CodeBridgeHostService> get() = EP_NAME.extensionsIfPointIsRegistered

        /** The service answering [name], if any. */
        fun forService(name: String): CodeBridgeHostService? = all.firstOrNull { name in it.services }
    }
}
