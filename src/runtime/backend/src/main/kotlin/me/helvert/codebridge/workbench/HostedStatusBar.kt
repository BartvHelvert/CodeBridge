package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.StatusBar
import me.helvert.codebridge.rpc.StatusTooltipBlockDto
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * `MainThreadStatusBar`: the items extensions put in the status bar, kept for the frontend to draw.
 *
 * This is where a language extension says whether its server is ready. rust-analyzer shows a
 * spinning icon while it loads the workspace and a warning or error background when that fails;
 * before this was answered, all of that went nowhere and the only sign of a slow or broken server
 * was an editor that quietly did nothing.
 *
 * The backend only keeps the entries as the extension described them. Drawing them happens in the
 * frontend, which is where the status bar is in split mode, and clicking one comes back here as
 * [run] because the command belongs to the extension host.
 */
@Service(Service.Level.PROJECT)
class HostedStatusBar(private val project: Project, private val scope: CoroutineScope) {

    private val entries = MutableStateFlow<Map<String, StatusBar.Entry>>(emptyMap())
    @Volatile
    private var session: ExtensionHostSession? = null

    /** The current items by entry id; the frontend collects this for as long as it shows them. */
    val items: StateFlow<Map<String, StatusBar.Entry>> = entries.asStateFlow()

    fun hostStarted(session: ExtensionHostSession) {
        this.session = session
    }

    /** Every item belonged to the host that has gone; left up, they would describe a dead server. */
    fun hostStopped() {
        session = null
        entries.value = emptyMap()
    }

    /** Handles one `MainThreadStatusBar` call. Nothing here returns a value to the host. */
    fun handle(call: MainThreadCall) {
        try {
            when (call.method) {
                StatusBar.SET_ENTRY -> {
                    val entry = StatusBar.entry(call.argsJson)
                    entries.update { it + (entry.entryId to entry) }
                }

                StatusBar.DISPOSE_ENTRY -> {
                    val entryId = StatusBar.entryId(call.argsJson)
                    entries.update { it - entryId }
                    tooltips.remove(entryId)
                }
            }
        } catch (failure: Exception) {
            // Arrives on the RPC reader, where an exception would end the session.
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
    }

    /** Runs the item's command in the extension host, as a click on it would in VS Code. */
    fun run(entryId: String) {
        val entry = entries.value[entryId] ?: return
        execute(entry, entry.command ?: return)
    }

    /** Each item's tooltip as rendered, by entry id, with the entry it was made from. */
    private val tooltips = ConcurrentHashMap<String, Pair<StatusBar.Entry, StatusTooltips.Rendered>>()

    /**
     * [entry]'s tooltip as the frontend's hover draws it; see [StatusTooltips]. Made again only when
     * the tooltip changes, not on every update of the item -- a spinner's text changes far more
     * often than what its hover says.
     */
    fun tooltipHtml(entry: StatusBar.Entry): String? = rendered(entry).html

    /** [entry]'s tooltip in the blocks the frontend's hover draws; see [StatusTooltips.blocks]. */
    fun tooltip(entry: StatusBar.Entry): List<StatusTooltipBlockDto>? = rendered(entry).blocks

    private fun rendered(entry: StatusBar.Entry): StatusTooltips.Rendered {
        tooltips[entry.entryId]?.let { (from, rendered) ->
            if (from.tooltip == entry.tooltip && from.markdown == entry.markdown &&
                from.themeIcons == entry.themeIcons && from.trustedCommands == entry.trustedCommands
            ) return rendered
        }
        return StatusTooltips.render(project, entry).also { tooltips[entry.entryId] = entry to it }
    }

    /**
     * Runs the [index]th command link of the item's tooltip, `codebridge-status:[index]`.
     *
     * Looked up here, against what the extension sent, so the frontend can only choose among the
     * links on offer; an index that no longer points at one -- the tooltip changed -- does nothing.
     */
    fun runAction(entryId: String, index: Int) {
        val entry = entries.value[entryId] ?: return
        execute(entry, rendered(entry).commands.getOrNull(index) ?: return)
    }

    /**
     * Runs [command] and, if it fails, says so where the user is looking.
     *
     * A failed click used to reach only the log, so a menu entry that could not work -- rust-analyzer's
     * check-on-save toggle, before settings could be written -- looked like one that did nothing.
     */
    private fun execute(entry: StatusBar.Entry, command: StatusBar.Command) {
        val host = session ?: return
        val args = buildJsonArray { add(JsonPrimitive(command.id)); command.arguments.forEach(::add) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                host.call("ExtHostCommands", $$"$executeContributedCommand", args.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed(entry, command, failure)
            }
        }
    }

    private fun failed(entry: StatusBar.Entry, command: StatusBar.Command, error: Throwable) {
        LOG.warn("Status bar command ${command.id} failed", error)
        if (project.isDisposed) return
        HostedMessages.getInstance(project).notify(
            Messages.Severity.ERROR, "'${command.id}' failed: ${reason(error)}",
            source = entry.name.ifBlank { entry.extensionId },
        )
    }

    companion object {
        private val LOG = Logger.getInstance(HostedStatusBar::class.java)

        /**
         * What went wrong, in the extension's words when it gave any.
         *
         * A failure from the host arrives as the error object it serialized, `{"message": ...}`
         * among its fields; that message is what belongs in front of the user, not the JSON.
         */
        internal fun reason(error: Throwable): String {
            val cause = generateSequence(error) { it.cause }.last()
            val text = cause.message ?: return cause.javaClass.simpleName
            return runCatching {
                (Json.parseToJsonElement(text) as JsonObject)["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: text
        }

        fun getInstance(project: Project): HostedStatusBar = project.service()
    }
}
