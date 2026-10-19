package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Console
import me.helvert.codebridge.exthost.protocol.payload.LogLevel
import me.helvert.codebridge.exthost.protocol.payload.Loggers
import me.helvert.codebridge.exthost.protocol.payload.Telemetry
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * `MainThreadConsole`, `MainThreadLogger` and `MainThreadTelemetry`: what an extension says about
 * itself.
 *
 * None of these three draws anything, which is why they are one class: each is the extension host
 * telling us something about its own running rather than asking for a piece of editor. Left
 * unanswered they were not errors -- an unhandled service replies "ok, no value" and the host
 * carries on -- so the cost was silence, and silence in the one place that would have explained
 * why an extension is not working.
 *
 * What each is actually worth is uneven, and worth saying plainly:
 *
 * - **Console** is the real gap. The host forwards every `console.*` call from every extension
 *   here, and until now all of it was discarded. It does also write the same text natively into
 *   `exthost-stdout.log`, fenced between `START_NATIVE_LOG` markers in a file under a temporary
 *   host-storage directory -- true, and no help to anyone diagnosing an extension.
 * - **Logger** is mostly bookkeeping. The host's node logger service writes `file:` loggers itself,
 *   so an extension's log was never being lost; what was missing is the registration that says
 *   which extension owns which file, and the rare logger the host cannot write.
 * - **Telemetry** changes no behaviour at all. It is answered so that dropping it is a decision
 *   with a comment on it rather than a service nobody got to.
 *
 * Plain class rather than a project service: it holds no IntelliJ state, which lets the tests hand
 * it a recorder in place of [record] and assert on severities and text directly.
 */
internal class HostedLogging(
    private val files: Files = Files.NONE,
    private val record: (LogLevel, String) -> Unit = ::toIdeLog,
) {

    /**
     * Where an extension's own log files are announced, so they can be shown as well as recorded.
     *
     * Keyed by the logger's resource URI, which is what deregistration names it by.
     */
    interface Files {
        fun opened(resource: String, name: String, file: Path, extensionId: String?)
        fun closed(resource: String)

        companion object {
            val NONE = object : Files {
                override fun opened(resource: String, name: String, file: Path, extensionId: String?) = Unit
                override fun closed(resource: String) = Unit
            }
        }
    }

    /**
     * Registered loggers by file, so a `$log` can name the extension its messages came from.
     *
     * Bounded by the host that owns it: [HostedWorkbench] is built per host start and dropped when
     * one stops, so there is nothing here to clear on a restart.
     */
    private val loggers = ConcurrentHashMap<String, Loggers.Resource>()

    /**
     * Handles one call, reporting whether it belonged to this service.
     *
     * Every method here is a notification except `$createLogger`, which the host awaits -- and an
     * empty reply satisfies it, so nothing in here produces a result.
     */
    fun handle(call: MainThreadCall): Boolean {
        try {
            when (call.service) {
                Console.SERVICE -> console(call)
                Telemetry.SERVICE -> telemetry(call)
                Loggers.SERVICE -> logger(call)
                else -> return false
            }
        } catch (failure: Exception) {
            // Never propagated. A malformed log line must not fail the call that carried it: the
            // host would take the rejection as the workbench refusing the service.
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
        return true
    }

    private fun console(call: MainThreadCall) {
        if (call.method != Console.LOG_MESSAGE) return unhandled(call)
        val message = Console.message(call.argsJson)
        record(message.level, "console: ${message.text}")
    }

    private fun telemetry(call: MainThreadCall) {
        if (!call.method.startsWith(Telemetry.PUBLIC_LOG_PREFIX)) return unhandled(call)
        val event = Telemetry.eventName(call.method, call.argsJson) ?: return
        // Debug, and the name only. Extensions call this constantly, and the properties are the
        // part that carries paths and identifiers; see the note on [Telemetry].
        record(LogLevel.DEBUG, "telemetry event dropped: $event")
    }

    private fun logger(call: MainThreadCall) {
        when (call.method) {
            Loggers.REGISTER -> registered(Loggers.registration(call.argsJson))
            Loggers.CREATE -> registered(Loggers.created(call.argsJson))
            Loggers.DEREGISTER -> Loggers.file(call.method, call.argsJson)?.let { file ->
                loggers.remove(file.uri)?.let { record(LogLevel.DEBUG, "${it.label} stopped logging") }
                files.closed(file.uri)
            }

            Loggers.LOG -> {
                val (file, messages) = Loggers.messages(call.argsJson)
                val label = file?.uri?.let { loggers[it] }?.label ?: "extension"
                messages.forEach { record(it.level, "$label: ${it.text}") }
            }
            // The host has flushed a file it wrote itself, and visibility is its own bookkeeping
            // about which log the Output panel would have shown. Neither leaves anything to do.
            Loggers.FLUSH, Loggers.SET_VISIBILITY -> Unit
            else -> unhandled(call)
        }
    }

    /**
     * Records a logger, saying so out loud only when it is one a user would recognise.
     *
     * Every output channel registers a hidden logger of its own behind
     * [HostedOutputChannels.handle], which already reports the channel by name; repeating that here
     * would double every line of the one thing this service is useful for.
     */
    private fun registered(resource: Loggers.Resource) {
        val file = resource.file ?: return
        loggers[file.uri] = resource
        val level = if (resource.hidden) LogLevel.DEBUG else LogLevel.INFO
        record(level, "${resource.label} is logging to ${file.path}")
        // A hidden logger is the file behind an output channel, which is shown as that channel.
        if (!resource.hidden && file.scheme == "file") files.opened(
            file.uri,
            resource.label,
            file.toPath(),
            resource.extensionId
        )
    }

    /**
     * A method this build of VS Code has and the handler does not.
     *
     * Debug rather than a warning: the call was still answered, and the point of recording it is
     * that the next person to wonder why something is missing has a name to search for.
     */
    private fun unhandled(call: MainThreadCall) =
        record(LogLevel.DEBUG, "${call.service}.${call.method} is not handled")

    private companion object {
        private val LOG = Logger.getInstance(HostedLogging::class.java)

        /**
         * Extension messages in the IDE log, at the severity the extension chose -- with one floor.
         *
         * `LOG.error` does not mean "log an error" in IntelliJ: it files an IDE error report, which
         * raises in tests and puts a red badge and a "Report to JetBrains" dialog in front of the
         * user. An extension writing `console.error` is not the IDE failing, so an error from one
         * lands as a warning and keeps its own severity in the text.
         */
        private fun toIdeLog(level: LogLevel, text: String) = when (level) {
            LogLevel.ERROR -> LOG.warn("[error] $text")
            LogLevel.WARNING -> LOG.warn(text)
            LogLevel.INFO -> LOG.info(text)
            LogLevel.DEBUG, LogLevel.TRACE -> LOG.debug(text)
            LogLevel.OFF -> Unit
        }
    }
}
