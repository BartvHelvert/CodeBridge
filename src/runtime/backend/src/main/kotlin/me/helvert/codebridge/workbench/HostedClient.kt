package me.helvert.codebridge.workbench

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Clipboard
import me.helvert.codebridge.rpc.ClientRequestDto
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What extensions do on the user's machine rather than the IDE's: open a web or mail link
 * (`env.openExternal`), and read or write the clipboard (`env.clipboard`). The client does each and
 * answers, since in split mode its browser and clipboard are the user's, and the backend's are not.
 *
 * Not `BrowserUtil.browse` here: the platform hands that to the client, which asks whether the
 * remote host may open the page, and the call returns before the user answers. The extension heard
 * "opened" at once -- GitLens put its paste-the-code box over the question -- and heard it even when
 * the user said no. Nor the backend's clipboard: what the client copied is not on it, and GitLens,
 * finding its login link missing after the user chose Copy, gave up.
 *
 * A request stays in [pending] until a client answers it, as [HostedMessages]' messages do.
 */
@Service(Service.Level.PROJECT)
class HostedClient {

    private val ids = AtomicLong()
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<ClientAnswer>>()
    private val requests = MutableStateFlow<List<ClientRequestDto>>(emptyList())

    /** A client's answer: whether it did what was asked, and the text it read, for a clipboard read. */
    data class ClientAnswer(val done: Boolean, val text: String?)

    /** Requests not yet answered, oldest first. */
    val pending: StateFlow<List<ClientRequestDto>> = requests.asStateFlow()

    /**
     * Opens [url] in the client's browser, answering whether it was opened.
     *
     * Except a page on a port of the backend's own machine -- a sign-in's loopback server, waiting
     * for the provider to send the browser back. The platform opens that itself, forwarding the port
     * to the client so the browser can reach it, and answers before the user has; it is "opened" then.
     */
    suspend fun browse(url: String): Boolean {
        if (onBackendLoopback(url)) {
            BrowserUtil.browse(url)
            return true
        }
        return ask(ClientRequestDto.BROWSE, url).done
    }

    /** Handles one `MainThreadClipboard` call, on the client's clipboard. */
    suspend fun clipboard(call: MainThreadCall): MainThreadResult = when (call.method) {
        Clipboard.READ -> MainThreadResult(Clipboard.text(ask(ClientRequestDto.READ_CLIPBOARD, null).text))
        Clipboard.WRITE -> {
            ask(ClientRequestDto.WRITE_CLIPBOARD, Clipboard.written(call.argsJson))
            MainThreadResult()
        }
        else -> throw IllegalArgumentException("Unknown ${Clipboard.SERVICE} method ${call.method}")
    }

    private suspend fun ask(kind: String, value: String?): ClientAnswer {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<ClientAnswer>()
        waiting[id] = reply
        requests.update { it + ClientRequestDto(id, kind, value) }
        return reply.await()
    }

    /** The client's answer to request [id]; the first answer wins. */
    fun answer(id: Long, done: Boolean, text: String?) {
        requests.update { pending -> pending.filterNot { it.id == id } }
        waiting.remove(id)?.complete(ClientAnswer(done, text))
    }

    /** Nobody is left to hear it; whatever still waits was not done. */
    fun hostStopped() {
        waiting.keys.toList().forEach { id -> waiting.remove(id)?.complete(ClientAnswer(false, null)) }
        requests.value = emptyList()
    }

    companion object {
        fun getInstance(project: Project): HostedClient = project.service()

        /** Not after a host-name character, so `notlocalhost:80` and `10.127.0.0.1:80` are not this machine. */
        private val LOOPBACK = Regex("""(?i)(?<![\w.-])(?:localhost|127\.0\.0\.1|\[::1]):(\d+)""")

        /**
         * Whether [url] names a port on this machine anywhere in it -- a sign-in names its loopback
         * server in the query, as the provider's `redirect_uri` -- other than a client's built-in
         * server, which the links into the editor are on and which is the client's own.
         */
        internal fun onBackendLoopback(url: String): Boolean {
            val text = runCatching { URLDecoder.decode(url, Charsets.UTF_8) }.getOrDefault(url)
            return LOOPBACK.findAll(text).any { match -> !HostedUrls.isClientPort(match.groupValues[1].toInt()) }
        }
    }
}
