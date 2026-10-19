package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `MainThreadTerminalService` / `ExtHostTerminalService`: `window.createTerminal` and what follows.
 *
 * A terminal is one of two things. A shell terminal names a program, its arguments, directory and
 * environment, and the workbench runs it. An extension terminal (`ExtensionTerminalOptions`,
 * `isExtensionCustomPtyTerminal`) has no process at all: the extension's `Pseudoterminal` is the
 * process, writing output with `$sendProcessData` and hearing input with `$acceptProcessInput`.
 *
 * Terminals are named in two ways. The host makes up a string id before the workbench has answered,
 * and an extension that calls `sendText` straight after `createTerminal` sends that one. Once the
 * workbench has opened the terminal and said so with `$acceptTerminalOpened`, the host uses the
 * number the workbench gave it instead. Both arrive in the same argument, so [Ref] keeps both
 * readings as text.
 */
object Terminals {

    const val SERVICE: String = "MainThreadTerminalService"
    const val EXT_HOST: String = "ExtHostTerminalService"

    const val CREATE: String = $$"$createTerminal"
    const val SEND_TEXT: String = $$"$sendText"
    const val SHOW: String = $$"$show"
    @Suppress("unused") // part of the protocol, not called yet
    const val HIDE: String = $$"$hide"
    const val DISPOSE: String = $$"$dispose"
    @Suppress("unused") // part of the protocol, not called yet
    const val PROCESS_READY: String = $$"$sendProcessReady"
    const val PROCESS_DATA: String = $$"$sendProcessData"
    const val PROCESS_PROPERTY: String = $$"$sendProcessProperty"
    const val PROCESS_EXIT: String = $$"$sendProcessExit"

    const val OPENED: String = $$"$acceptTerminalOpened"
    const val CLOSED: String = $$"$acceptTerminalClosed"
    const val PROCESS_ID: String = $$"$acceptTerminalProcessId"
    const val ACTIVE_CHANGED: String = $$"$acceptActiveTerminalChanged"
    const val START_EXTENSION_TERMINAL: String = $$"$startExtensionTerminal"
    const val PROCESS_INPUT: String = $$"$acceptProcessInput"
    const val PROCESS_SHUTDOWN: String = $$"$acceptProcessShutdown"

    /** `vscode.TerminalExitReason`. */
    @Suppress("unused") // mirrors VS Code's TerminalExitReason
    enum class ExitReason(val code: Int) { UNKNOWN(0), SHUTDOWN(1), PROCESS(2), USER(3), EXTENSION(4) }

    /** A terminal as an argument names it: the workbench's number, or the host's own string id. */
    @JvmInline value class Ref(val text: String)

    /**
     * `$createTerminal(extHostTerminalId, config)`.
     *
     * [shellArgs] is null when the extension gave none. On Windows an extension may give one
     * command-line string instead of a list, kept as [shellArgsLine].
     */
    data class Create(
        val extHostId: String,
        val name: String?,
        val pseudoterminal: Boolean,
        val shellPath: String?,
        val shellArgs: List<String>?,
        val shellArgsLine: String?,
        val cwd: String?,
        val env: Map<String, String?>,
        val strictEnv: Boolean,
        val hideFromUser: Boolean,
        val initialText: String?,
    )

    fun create(argsJson: String): Create {
        val args = Args.of(CREATE, argsJson)
        val config = args.obj(1)
        val shellArgs = config["shellArgs"]
        return Create(
            extHostId = args.str(0),
            name = config.string("name"),
            pseudoterminal = config.bool("isExtensionCustomPtyTerminal"),
            shellPath = config.string("shellPath"),
            shellArgs = (shellArgs as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            shellArgsLine = (shellArgs as? JsonPrimitive)?.takeIf { it.isString }?.content,
            // A string, or a resource when the extension passed a `Uri`.
            cwd = config.string("cwd") ?: UriComponents.from(config["cwd"])?.let { uri ->
                runCatching { uri.toPath().toString() }.getOrNull()
            },
            env = (config["env"] as? JsonObject).orEmpty().mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull },
            strictEnv = config.bool("strictEnv"),
            hideFromUser = config.bool("hideFromUser"),
            initialText = config.string("initialText"),
        )
    }

    /** The terminal every call but [CREATE] names first. */
    fun ref(method: String, argsJson: String): Ref {
        val first = Args.of(method, argsJson).element(0) as? JsonPrimitive
            ?: throw PayloadException("$method does not name a terminal: $argsJson")
        return Ref(first.content)
    }

    /** `$sendText(terminal, text, shouldExecute)`. */
    data class SendText(val terminal: Ref, val text: String, val execute: Boolean)

    fun sendText(argsJson: String): SendText {
        val args = Args.of(SEND_TEXT, argsJson)
        return SendText(ref(SEND_TEXT, argsJson), args.str(1), args.boolOrNull(2) ?: true)
    }

    /** `$show(terminal, preserveFocus)`. */
    fun preserveFocus(argsJson: String): Boolean = Args.of(SHOW, argsJson).boolOrNull(1) ?: false

    /** `$sendProcessData(terminal, data)`: what an extension's pseudoterminal wrote. */
    fun processData(argsJson: String): String = Args.of(PROCESS_DATA, argsJson).str(1)

    /** `$sendProcessExit(terminal, exitCode)`; no code when the pseudoterminal closed without one. */
    fun exitCode(argsJson: String): Int? = Args.of(PROCESS_EXIT, argsJson).intOrNull(1)

    /** `$sendProcessProperty(terminal, {type, value})`, when it is a new title. */
    fun title(argsJson: String): String? {
        val property = Args.of(PROCESS_PROPERTY, argsJson).objOrNull(1) ?: return null
        return if (property.string("type") == "title") property.string("value") else null
    }

    /**
     * `[id, extHostTerminalId, name, shellLaunchConfig]`: the workbench has the terminal.
     *
     * The host waits for this before its `createTerminal` is done. A terminal the workbench made
     * itself -- a task's -- has no [extHostId], and the host makes an object for it from [config].
     */
    fun opened(id: Int, extHostId: String?, name: String, config: JsonObject): String = buildJsonArray {
        add(JsonPrimitive(id)); add(extHostId?.let(::JsonPrimitive) ?: JsonNull); add(JsonPrimitive(name)); add(config)
    }.toString()

    /** The shell launch configuration `$acceptTerminalOpened` carries, as the host reads it back. */
    fun launchConfig(name: String, executable: String?, args: List<String>, cwd: String?): JsonObject = buildJsonObject {
        put("name", name)
        executable?.let { put("executable", it) }
        put("args", JsonArray(args.map(::JsonPrimitive)))
        cwd?.let { put("cwd", it) }
    }

    fun closed(id: Int, exitCode: Int?, reason: ExitReason): String = buildJsonArray {
        add(JsonPrimitive(id)); add(JsonPrimitive(exitCode)); add(JsonPrimitive(reason.code))
    }.toString()

    fun processId(id: Int, pid: Long): String = "[$id,$pid]"

    fun active(id: Int?): String = "[${id ?: "null"}]"

    /** `$startExtensionTerminal(id, initialDimensions)`: the pseudoterminal may open now. */
    fun startExtensionTerminal(id: Int, columns: Int, rows: Int): String =
        buildJsonArray { add(JsonPrimitive(id)); add(buildJsonObject { put("columns", columns); put("rows", rows) }) }.toString()

    fun input(id: Int, data: String): String = buildJsonArray { add(JsonPrimitive(id)); add(JsonPrimitive(data)) }.toString()

    fun shutdown(id: Int, immediate: Boolean): String = "[$id,$immediate]"

    /**
     * What `sendText` types: line breaks become the carriage returns a terminal reads as Enter, and
     * executing adds one more, as the workbench does.
     */
    fun typed(text: String, execute: Boolean): String {
        val normalized = text.replace(Regex("\r?\n"), "\r")
        return if (execute && !normalized.endsWith("\r")) "$normalized\r" else normalized
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
}
