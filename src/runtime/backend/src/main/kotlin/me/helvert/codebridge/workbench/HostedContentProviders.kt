package me.helvert.codebridge.workbench

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.DocumentUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.DocumentContentProviders
import me.helvert.codebridge.exthost.protocol.payload.Documents
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.CodeBridgeTextEditors
import org.jetbrains.annotations.TestOnly
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * `MainThreadDocumentContentProviders`: which extension makes up documents for which URI scheme,
 * and opening one of those documents for the user.
 *
 * Opened as VS Code opens one from a link: the provider is asked for the text, which is shown in a
 * read-only editor named after the last segment of the URI's path. Opening the same URI again asks
 * again and shows the same editor, so a stale page is refreshed rather than duplicated.
 */
@Service(Service.Level.PROJECT)
class HostedContentProviders(private val project: Project) {

    /** Asks the host, as `ExtensionHostSession.call` does; null while none runs. */
    @Volatile
    private var callHost: (suspend (String, String, String) -> String)? = null

    /** Scheme by provider handle; the host numbers handles across every extension. */
    private val schemes = HashMap<Int, String>()

    /**
     * The file systems extensions provide, by handle: the other way an extension serves documents of
     * its own scheme, and the one GitLens uses for its revisions, `gitlens:`. Read with
     * `ExtHostFileSystem.$readFile`, which answers in bytes.
     */
    private val fileSystems = HashMap<Int, String>()

    private var readFile: (suspend (Int, UriComponents) -> ByteArray?)? = null

    /**
     * The documents opened, by URI.
     *
     * Held strongly: nothing else refers to an in-memory file, and in split mode the platform keeps
     * the ones it has sent to a client only softly.
     */
    private val opened = HashMap<String, LightVirtualFile>()

    fun hostStarted(session: ExtensionHostSession) {
        callHost = { service, method, args -> session.call(service, method, args) }
        readFile = { handle, resource ->
            session.callForBytes(
                FILE_SYSTEM,
                $$"$readFile",
                buildJsonArray { add(handle); add(resource.toJson()) }.toString()
            )
        }
    }

    /** Answers with [read], as `callHostForTest` does. */
    @TestOnly
    fun readFileForTest(read: suspend (Int, UriComponents) -> ByteArray?) {
        readFile = read
    }

    /**
     * Handles `MainThreadFileSystem.$registerFileSystemProvider(handle, scheme, …)` and
     * `$unregisterProvider(handle)`, answering whether the call was one of them. Everything else the
     * service asks is about the files on disk, and answered by the workbench.
     */
    fun handleFileSystem(method: String, argsJson: String): Boolean {
        val args = Args.of(method, argsJson)
        when (method) {
            $$"$registerFileSystemProvider" -> synchronized(this) { fileSystems[args.int(0)] = args.str(1) }
            $$"$unregisterProvider" -> synchronized(this) { fileSystems.remove(args.int(0)) }
            else -> return false
        }
        return true
    }

    /** Whether an extension provides a file system for [scheme]: activating it for that is then done. */
    @Synchronized
    fun providesFileSystem(scheme: String): Boolean = scheme in fileSystems.values

    /** Answers with [call]: a test suspends in it to stand for a reply still on its way. */
    @TestOnly
    fun callHostForTest(call: suspend (String, String, String) -> String) {
        callHost = call
    }

    @Synchronized
    fun hostStopped() {
        callHost = null
        readFile = null
        schemes.clear()
        fileSystems.clear()
    }

    /** Handles one `MainThreadDocumentContentProviders` call. Nothing here returns a value. */
    fun handle(call: MainThreadCall) {
        try {
            when (call.method) {
                DocumentContentProviders.REGISTER -> DocumentContentProviders.registration(call.argsJson)
                    .let { synchronized(this) { schemes[it.handle] = it.scheme } }

                DocumentContentProviders.UNREGISTER -> DocumentContentProviders.unregistered(call.argsJson)
                    .let { synchronized(this) { schemes.remove(it) } }

                else -> LOG.debug("Not handled: ${call.service}.${call.method}")
            }
        } catch (failure: Exception) {
            // Arrives on the RPC reader, where an exception would end the session.
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
    }

    /**
     * The URI [file] was provided for, when it is one of these documents: what the host knows it by,
     * since it has no path of its own.
     */
    @Synchronized
    fun uriOf(file: VirtualFile): String? = opened.entries.firstOrNull { it.value == file }?.key

    /** Whether some extension makes up documents for [scheme]. */
    @Synchronized
    fun provides(scheme: String): Boolean = scheme in schemes.values || scheme in fileSystems.values

    /** Asks the provider for [uri]'s text and shows it, answering whether there was a provider that answered. */
    suspend fun open(uri: String): Boolean {
        val resource = DocumentContentProviders.resource(uri) ?: return false
        val text = content(resource) ?: return false
        show(resource.uri, nameOf(resource), text)
        return true
    }

    /**
     * The document for [resource], to navigate into: a definition in a library, which Java's
     * language server names under `jdt:` and serves the source or decompiled text of on request.
     *
     * Blocks for the provider, as asking for the definition itself does; the caller is already
     * waiting on the extension host. A document opened before is reused rather than asked for
     * again, so following a second link into the same class costs nothing. Null when no extension
     * provides documents for the scheme, or the provider had nothing.
     */
    fun document(resource: UriComponents): VirtualFile? {
        synchronized(this) { opened[resource.uri] }?.let { return it }
        if (!provides(resource.scheme)) return null
        val text = try {
            // A timeout answers null inside, rather than escaping as a cancellation of the caller.
            runBlockingMaybeCancellable { withTimeoutOrNull(PROVIDE_TIMEOUT_SECONDS.seconds) { content(resource) } }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("The provider for '${resource.scheme}' did not answer for ${resource.uri}", failure)
            null
        } ?: return null
        val file = LightVirtualFile(nameOf(resource), PlainTextFileType.INSTANCE, text).apply { isWritable = false }
        return synchronized(this) { opened.getOrPut(resource.uri) { file } }
    }

    /** Asks the provider for [resource]'s text, normalized; null when there is no provider or no text. */
    suspend fun content(resource: UriComponents): String? {
        val handle = synchronized(this) { schemes.entries.firstOrNull { it.value == resource.scheme }?.key }
        val host = callHost
        if (handle == null) {
            val fileSystem = synchronized(this) { fileSystems.entries.firstOrNull { it.value == resource.scheme }?.key }
            val read = readFile
            if (fileSystem != null && read != null) return fileContent(read, fileSystem, resource)
        }
        if (handle == null || host == null) {
            LOG.info("No extension provides documents for '${resource.scheme}', so ${resource.uri} was not opened")
            return null
        }
        val reply = host(
            DocumentContentProviders.EXT_HOST, DocumentContentProviders.PROVIDE,
            DocumentContentProviders.provide(handle, resource)
        )
        val text = DocumentContentProviders.content(reply)
        if (text == null) LOG.info("The provider for '${resource.scheme}' had nothing for ${resource.uri}")
        return text?.let(Documents::normalize)
    }

    /**
     * [resource]'s text from the file system [handle] an extension provides for its scheme, read as
     * UTF-8 as VS Code opens a document without an encoding of its own.
     */
    private suspend fun fileContent(read: suspend (Int, UriComponents) -> ByteArray?, handle: Int, resource: UriComponents): String? {
        val bytes = try {
            read(handle, resource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.info("The file system for '${resource.scheme}' could not read ${resource.uri}", failure)
            null
        }
        return bytes?.toString(Charsets.UTF_8)?.removePrefix("﻿")?.let(Documents::normalize)
    }

    private fun nameOf(resource: UriComponents): String =
        resource.path.substringAfterLast('/').ifEmpty { resource.scheme }

    private suspend fun show(uri: String, name: String, text: String) {
        val shown = withContext(Dispatchers.EDT) {
            check(!project.isDisposed) { "The project has been closed" }
            val file =
                synchronized(this) { opened.getOrPut(uri) { LightVirtualFile(name, PlainTextFileType.INSTANCE, "") } }
            FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                // Read-only to the user, as VS Code shows these; only the provider writes them.
                document.setReadOnly(false)
                @Suppress("UsePropertyAccessSyntax") // `text` has no setter: the getter is String, the setter CharSequence
                DocumentUtil.writeInRunUndoTransparentAction { document.setText(text) }
                document.setReadOnly(true)
            }
            file
        }
        CodeBridgeTextEditors.getInstance(project).reveal(shown, null, true)
    }

    companion object {
        private val LOG = Logger.getInstance(HostedContentProviders::class.java)

        private const val FILE_SYSTEM = "ExtHostFileSystem"

        /** As long as asking for a definition: the user is waiting on the jump. */
        private const val PROVIDE_TIMEOUT_SECONDS = 5L

        fun getInstance(project: Project): HostedContentProviders = project.service()
    }
}
