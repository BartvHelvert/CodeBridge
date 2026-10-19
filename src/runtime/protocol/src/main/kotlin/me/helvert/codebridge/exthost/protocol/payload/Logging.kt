package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * VS Code's `LogLevel`, which is **not** the spdlog numbering that sits beside it in the bundle.
 *
 * The runtime defines both: spdlog's (`Trace=0 ... Critical=5, Off=6`) for the file loggers the
 * host writes itself, and this one for everything that crosses the protocol. Reading a level with
 * the wrong table shifts every message by one severity, which is the kind of mistake nothing
 * reports -- an error arrives as a warning, and a warning as information.
 */
enum class LogLevel {
    OFF, TRACE, DEBUG, INFO, WARNING, ERROR;

    companion object {

        /** The level an ordinal off the wire names, or null for one this build does not define. */
        fun of(value: Int?): LogLevel? = entries.getOrNull(value ?: return null)
    }
}

/**
 * `MainThreadConsole`: `console.log` from an extension.
 *
 * The host wraps every console method while its services are being built and forwards the call
 * here, then *also* writes it natively when `consoleForward.logNative` is set -- which is how these
 * messages have been reaching `exthost-stdout.log`, fenced between `START_NATIVE_LOG` markers, and
 * nowhere anyone would look. Reading them here is what puts an extension's own running commentary
 * where the rest of the plugin's diagnostics already are.
 */
object Console {

    const val SERVICE: String = "MainThreadConsole"

    const val LOG_MESSAGE: String = $$"$logExtensionHostMessage"

    /** One line an extension wrote, at the severity it chose. */
    data class Message(val level: LogLevel, val text: String)

    /**
     * Reads `$logExtensionHostMessage({type, severity, arguments})`.
     *
     * `severity` is the name of the console method rather than a level, and only four are wrapped.
     */
    fun message(argsJson: String): Message {
        val entry = Args.of(LOG_MESSAGE, argsJson).obj(0)
        return Message(
            level = when (entry["severity"]?.jsonPrimitive?.contentOrNull) {
                "debug" -> LogLevel.DEBUG
                "warn" -> LogLevel.WARNING
                "error" -> LogLevel.ERROR
                // "log", and whatever a later build adds: an extension saying something ordinary.
                else -> LogLevel.INFO
            },
            text = text(entry["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()),
        )
    }

    /**
     * The console arguments, rendered as they were written.
     *
     * They arrive as a JSON *string* holding the argument array, with `Error`s already replaced by
     * their stacks -- except in the two cases where the host gives up and sends a plain sentence
     * instead ("Output omitted for a large object that exceeds the limits"), which is not JSON and
     * is passed through unchanged. A string argument is emitted bare; anything else keeps the JSON
     * spelling the host had already reduced it to.
     */
    private fun text(arguments: String): String {
        val parsed = runCatching { Json.parseToJsonElement(arguments) }.getOrNull() as? JsonArray
            ?: return arguments
        // `consoleForward.includeStack` appends the caller's stack as a trailing object. It is off,
        // so this normally finds nothing, and gives the stack its own lines when it is turned on.
        val stacks = parsed.mapNotNull { (it as? JsonObject)?.get(STACK)?.jsonPrimitive?.contentOrNull }
        val rendered = parsed
            .filter { (it as? JsonObject)?.containsKey(STACK) != true }
            .joinToString(separator = " ", transform = ::render)
        return (listOf(rendered) + stacks).filter { it.isNotEmpty() }.joinToString(separator = "\n")
    }

    private fun render(element: JsonElement): String =
        (element as? JsonPrimitive)?.contentOrNull ?: element.toString()

    private const val STACK = $$"__$stack"
}

/**
 * `MainThreadTelemetry`: what an extension reports about its own usage.
 *
 * Answered so that it is dropped deliberately rather than by accident -- and dropped is all it is.
 * Nothing here reaches a network, and the event name is kept without its properties, because the
 * properties are where an extension puts paths, identifiers and error text.
 *
 * This build only ever calls `$publicLog2`. The other spellings of the same thing are matched by
 * prefix, so a runtime that uses one is not mistaken for a service the plugin does not answer.
 */
object Telemetry {

    const val SERVICE: String = "MainThreadTelemetry"

    /** `$publicLog`, `$publicLog2`, `$publicLogError`, `$publicLogError2` -- all (event, data). */
    const val PUBLIC_LOG_PREFIX: String = $$"$publicLog"

    /** The event name, or null for a call that does not carry one. */
    fun eventName(method: String, argsJson: String): String? =
        Args.ofOrNull(method, argsJson)?.strOrNull(0)
}

/**
 * `MainThreadLogger`: the log files extensions and output channels write through.
 *
 * Most of what this service exists for never arrives, and that is worth knowing before reading the
 * handler. The host's *node* logger service overrides logger creation and writes every `file:`
 * logger itself with spdlog, under the `logsLocation` it was handed -- so those files are already
 * on disk beside the host's own log, and `$log` carries only the loggers it cannot write that way.
 *
 * What does arrive for every logger, file-backed or not, is the registration: the one place that
 * says which extension owns which file.
 */
object Loggers {

    const val SERVICE: String = "MainThreadLogger"

    const val CREATE: String = $$"$createLogger"
    const val REGISTER: String = $$"$registerLogger"
    const val DEREGISTER: String = $$"$deregisterLogger"
    const val LOG: String = $$"$log"
    const val FLUSH: String = $$"$flush"
    const val SET_VISIBILITY: String = $$"$setVisibility"

    /**
     * A registered logger: the file behind it, and who it belongs to.
     *
     * [hidden] is how an ordinary output channel registers the logger behind it -- the channel is
     * the surface a user chose, and the logger is an implementation detail of it. A logger that is
     * not hidden is an extension's own log, which is the kind worth naming out loud.
     */
    data class Resource(
        val file: UriComponents?,
        val id: String?,
        val name: String?,
        val extensionId: String?,
        val hidden: Boolean,
    ) {

        /**
         * Something to call it, falling back through the shapes a logger can be registered with.
         *
         * The id comes last despite always being present: a logger registered without one is given
         * `hash(resource).toString(16)`, so it is usually a few hex digits and says nothing. The
         * file name is the better answer for the unnamed loggers that output channels create.
         */
        val label: String
            get() = name ?: extensionId ?: file?.path?.substringAfterLast('/') ?: id ?: "log"
    }

    /** `$registerLogger(ILoggerResource)`, whose `resource` is the file it writes. */
    fun registration(argsJson: String): Resource {
        val info = Args.of(REGISTER, argsJson).obj(0)
        return resource(info, UriComponents.from(info["resource"]))
    }

    /**
     * `$createLogger(file, options)`: a logger the host did not write itself.
     *
     * The file is the first argument here and a field of the first argument in [registration],
     * which is the only difference between the two shapes.
     */
    fun created(argsJson: String): Resource {
        val args = Args.of(CREATE, argsJson)
        return resource(args.objOrNull(1), args.uriOrNull(0))
    }

    private fun resource(info: JsonObject?, file: UriComponents?): Resource = Resource(
        file = file,
        id = info?.get("id")?.jsonPrimitive?.contentOrNull,
        name = info?.get("name")?.jsonPrimitive?.contentOrNull,
        extensionId = info?.get("extensionId")?.jsonPrimitive?.contentOrNull,
        hidden = info?.get("hidden")?.jsonPrimitive?.booleanOrNull ?: false,
    )

    /** `$deregisterLogger(resource)` and `$flush(file)` each name a file and nothing else. */
    fun file(method: String, argsJson: String): UriComponents? =
        Args.ofOrNull(method, argsJson)?.uriOrNull(0)

    /**
     * `$setVisibility(resourceOrId, visible)`, whose first argument is either spelling.
     *
     * `AbstractLoggerService.setVisibility` accepts a URI or a logger id and passes on whichever it
     * was given, so a handler that assumed one of them would quietly ignore half the calls.
     */
    fun visibility(argsJson: String): Pair<String?, Boolean> {
        val args = Args.of(SET_VISIBILITY, argsJson)
        return (args.uriOrNull(0)?.uri ?: args.strOrNull(0)) to (args.boolOrNull(1) ?: false)
    }

    /**
     * `$log(file, [[level, message], ...])`: a batch, because a logger buffers until it is created.
     *
     * An entry whose level this build does not define is dropped rather than guessed at. The pair
     * is positional and undeclared, so a level that does not map means the shape has changed, and
     * carrying it at some invented severity would state something about the message that is false.
     */
    fun messages(argsJson: String): Pair<UriComponents?, List<Console.Message>> {
        val args = Args.of(LOG, argsJson)
        val entries = args.array(1).mapNotNull { entry ->
            val pair = entry as? JsonArray ?: return@mapNotNull null
            val level = LogLevel.of((pair.getOrNull(0) as? JsonPrimitive)?.intOrNull) ?: return@mapNotNull null
            val text = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Console.Message(level, text)
        }
        return args.uriOrNull(0) to entries
    }
}
