package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadDebugService` / `ExtHostDebugService`: what a debugger extension registers and is asked.
 *
 * A debugger extension is not only an adapter binary. It contributes configuration providers that
 * fill in a half-written `launch.json` entry, and descriptor factories that decide -- at launch,
 * per session -- what to actually run. Both are ordinary extension code, which is why this route
 * exists at all instead of speaking DAP to something directly.
 */
object DebugService {

    const val SERVICE: String = "MainThreadDebugService"
    const val EXT_HOST: String = "ExtHostDebugService"

    const val REGISTER_FACTORY: String = $$"$registerDebugAdapterDescriptorFactory"

    @Suppress("unused") // part of the protocol, not called yet
    const val UNREGISTER_FACTORY: String = $$"$unregisterDebugAdapterDescriptorFactory"
    const val REGISTER_PROVIDER: String = $$"$registerDebugConfigurationProvider"
    const val UNREGISTER_PROVIDER: String = $$"$unregisterDebugConfigurationProvider"
    const val APPEND_CONSOLE: String = $$"$appendDebugConsole"
    const val START_DEBUGGING: String = $$"$startDebugging"
    const val STOP_DEBUGGING: String = $$"$stopDebugging"
    const val CUSTOM_REQUEST: String = $$"$customDebugAdapterRequest"

    /** `DebugSession.getDebugProtocolBreakpoint(breakpoint)`: `(sessionId, breakpointId)`. */
    const val GET_PROTOCOL_BREAKPOINT: String = $$"$getDebugProtocolBreakpoint"

    /** To the host: breakpoints `added`, `removed` (by id) and `changed`, as `vscode.debug.breakpoints`. */
    const val ACCEPT_BREAKPOINTS_DELTA: String = $$"$acceptBreakpointsDelta"
    const val ACCEPT_CUSTOM_EVENT: String = $$"$acceptDebugSessionCustomEvent"

    const val RESOLVE_CONFIGURATION: String = $$"$resolveDebugConfiguration"
    const val RESOLVE_SUBSTITUTED: String = $$"$resolveDebugConfigurationWithSubstitutedVariables"

    /** `$registerDebugAdapterDescriptorFactory(type, handle)`. */
    data class FactoryRegistration(val type: String, val handle: Int)

    /**
     * `$registerDebugConfigurationProvider(type, trigger, resolve, resolveWithSubstituted, handle)`.
     *
     * The handle is last, not first, which is the opposite of every other registration on this
     * protocol -- worth reading twice when a new VS Code shifts these.
     */
    data class ProviderRegistration(
        val type: String,
        val handle: Int,
        val resolve: Boolean,
        val afterSubstitution: Boolean,
    )

    fun factoryRegistration(argsJson: String): FactoryRegistration {
        val args = Args.of(REGISTER_FACTORY, argsJson)
        return FactoryRegistration(args.str(0), args.int(1))
    }

    fun providerRegistration(argsJson: String): ProviderRegistration {
        val args = Args.of(REGISTER_PROVIDER, argsJson)
        return ProviderRegistration(
            type = args.str(0),
            handle = args.int(5),
            resolve = args.bool(3),
            afterSubstitution = args.bool(4),
        )
    }

    /**
     * `$startDebugging(folder, nameOrConfiguration, options)`: `vscode.debug.startDebugging`.
     *
     * What a code lens's Run and Debug usually end in. Either [name] -- an entry of `launch.json` --
     * or [configuration], written by the extension on the spot, is set. [noDebug] is VS Code's Run
     * Without Debugging, which is still a debug adapter session.
     *
     * [parentSessionId] makes it a child session: an adapter such as js-debug starts one per target
     * it finds (a worker, a child process), each attaching to the parent's own adapter. A child
     * [lifecycleManagedByParent] is stopped and restarted through its parent.
     */
    data class StartDebugging(
        val folder: UriComponents?,
        val name: String?,
        val configuration: JsonObject?,
        val noDebug: Boolean,
        val parentSessionId: String? = null,
        val lifecycleManagedByParent: Boolean = false,
    )

    fun startDebugging(argsJson: String): StartDebugging {
        val args = Args.of(START_DEBUGGING, argsJson)
        val nameOrConfiguration = args.element(1)
        val name = (nameOrConfiguration as? JsonPrimitive)?.takeIf { it.isString }?.content
        val configuration = nameOrConfiguration as? JsonObject
        if (name == null && configuration == null) {
            throw PayloadException("$START_DEBUGGING argument 1 is neither a configuration name nor a configuration: $nameOrConfiguration")
        }
        val options = args.objOrNull(2)
        fun flag(key: String) = (options?.get(key) as? JsonPrimitive)?.booleanOrNull == true
        val parent = (options?.get("parentSessionID") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return StartDebugging(
            args.uriOrNull(0),
            name,
            configuration,
            flag("noDebug"),
            parent,
            flag("lifecycleManagedByParent")
        )
    }

    /**
     * `$customDebugAdapterRequest(sessionId, command, args)`: `DebugSession.customRequest`, a request
     * the extension sends its own adapter through the workbench's connection to it. The reply is the
     * response's `body`, or a failure carrying its `message`.
     */
    data class CustomRequest(val sessionId: String, val command: String, val arguments: JsonElement?)

    fun customRequest(argsJson: String): CustomRequest {
        val args = Args.of(CUSTOM_REQUEST, argsJson)
        return CustomRequest(args.str(0), args.str(1), args.element(2)?.takeUnless { it is JsonNull })
    }

    /** `$stopDebugging(sessionId)`: the session to stop, or null for every session. */
    fun stoppedSession(argsJson: String): String? =
        (Args.of(STOP_DEBUGGING, argsJson).element(0) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Both `$unregister*` calls name what they withdraw by handle. */
    fun unregisteredHandle(method: String, argsJson: String): Int = Args.of(method, argsJson).int(0)

    /** `$appendDebugConsole(sessionId, output, ...)`, whose text is the last argument. */
    fun consoleText(argsJson: String): String =
        Args.of(APPEND_CONSOLE, argsJson).let { it.strOrNull(it.size - 1).orEmpty() }

    /**
     * Finds the one `file:` resource anywhere inside a command's arguments.
     *
     * `$executeCommand` carries whatever the extension chose to pass, and the URI may be nested
     * inside an object or an array of them. Searching is what keeps this working across the several
     * shapes `workbench.extensions.command.installFromVSIX` has been called with.
     */
    fun findFileUri(value: JsonElement?): UriComponents? = when (value) {
        is JsonObject ->
            if (value["scheme"]?.jsonPrimitive?.content == UriComponents.FILE) UriComponents.from(value)
            else value.values.firstNotNullOfOrNull(::findFileUri)

        is JsonArray -> value.firstNotNullOfOrNull(::findFileUri)
        else -> null
    }

    /**
     * The activation reason for `onDebug:<type>`, which is how a debugger extension is woken.
     *
     * The host finds the extension by the identifier's `_lower`, not its `value`: without it,
     * `$activate` answers false for every extension.
     */
    fun debugActivation(extensionId: String, type: String): String {
        val identifier =
            """{"value":${JsonPrimitive(extensionId)},"_lower":${JsonPrimitive(extensionId.lowercase())}}"""
        val reason = """{"startup":false,"activationEvent":"onDebug:$type","extensionId":$identifier}"""
        return "[$identifier,$reason]"
    }
}

/**
 * The debug adapter's own traffic, tunnelled through the extension host.
 *
 * DAP messages are not spoken to the adapter directly: the extension host owns the adapter's
 * lifetime -- executable, server or inline, all three -- so every message is relayed through it on
 * a handle. That is the point of the route. An extension that starts its adapter by running a
 * script, downloading a payload, or answering in JavaScript works the same way as one that names a
 * binary, because none of that is visible from this side.
 */
object DebugAdapters {

    const val ACCEPT_MESSAGE: String = $$"$acceptDAMessage"
    const val ACCEPT_ERROR: String = $$"$acceptDAError"
    const val ACCEPT_EXIT: String = $$"$acceptDAExit"

    const val START_SESSION: String = $$"$startDASession"
    const val SEND_MESSAGE: String = $$"$sendDAMessage"
    const val STOP_SESSION: String = $$"$stopDASession"

    /** Everything the host reports about an adapter names it by handle first. */
    sealed interface Event {
        val handle: Int

        /** One DAP message, left as the raw JSON text the adapter produced. */
        data class Message(override val handle: Int, val json: String) : Event

        /** The adapter failed; [detail] is whatever the host said about it. */
        data class Failed(override val handle: Int, val detail: String) : Event

        /** The adapter exited; [detail] is the exit code, when one was reported. */
        data class Exited(override val handle: Int, val detail: String) : Event
    }

    /** Reads one of the three `$acceptDA*` calls, or `null` for any other method. */
    fun event(method: String, argsJson: String): Event? {
        if (method !in METHODS) return null
        val args = Args.of(method, argsJson)
        val handle = args.int(0)
        return when (method) {
            // Not decoded: a DAP message is the debugger's own protocol, and this only relays it.
            ACCEPT_MESSAGE -> Event.Message(handle, (args.element(1) ?: return null).toString())
            ACCEPT_ERROR -> Event.Failed(handle, (1 until args.size).map { args.element(it) }.toString())
            else -> Event.Exited(handle, args.element(1)?.toString() ?: "null")
        }
    }

    /**
     * A message from the adapter, with the file paths it wrote given back to it.
     *
     * The host does not relay a message quite as the adapter sent it: it turns `source.path` into a
     * URI wherever a source can come back -- a stack frame, a breakpoint, an output event -- and the
     * workbench turns it back before its own client reads it. A client that reads DAP, as IntelliJ's
     * does, refuses the URI: a `stackTrace` reply would not parse, and a stop at a breakpoint would
     * never be shown. The places are VS Code's own (`debugUtils.convertToVSCPaths`), so that a path
     * left as text elsewhere stays as the adapter wrote it.
     */
    fun adapterPaths(json: String): String {
        val message =
            runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return json
        val type = (message["type"] as? JsonPrimitive)?.content
        val body = message["body"] as? JsonObject ?: return json
        val converted = when (type) {
            "event" -> when ((message["event"] as? JsonPrimitive)?.content) {
                "output", "loadedSource" -> body.edit("source", ::source)
                "breakpoint" -> body.edit("breakpoint") { (it as? JsonObject)?.edit("source", ::source) ?: it }
                else -> body
            }

            "response" -> if ((message["success"] as? JsonPrimitive)?.booleanOrNull != true) body
            else when ((message["command"] as? JsonPrimitive)?.content) {
                "stackTrace" -> body.edit("stackFrames") { each(it) { frame -> frame.edit("source", ::source) } }
                "loadedSources" -> body.edit("sources") { each(it) { source -> source(source) as JsonObject } }
                "scopes" -> body.edit("scopes") { each(it) { scope -> scope.edit("source", ::source) } }
                "setBreakpoints", "setFunctionBreakpoints" -> body.edit("breakpoints") {
                    each(it) { bp ->
                        bp.edit(
                            "source",
                            ::source
                        )
                    }
                }

                "disassemble" -> body.edit("instructions") {
                    each(it) { instruction ->
                        instruction.edit(
                            "location",
                            ::source
                        )
                    }
                }

                "locations" -> body.edit("source", ::source)
                else -> body
            }

            else -> body
        }
        return if (converted === body) json else JsonObject(message + ("body" to converted)).toString()
    }

    /** One source's `path`, from the URI the host made of it back to what the adapter wrote. */
    private fun source(element: JsonElement): JsonElement {
        val source = element as? JsonObject ?: return element
        val uri = source["path"] as? JsonObject ?: return element
        val components = UriComponents.from(uri) ?: return element
        val path = if (components.scheme != UriComponents.FILE) components.uri
        else (uri["fsPath"] as? JsonPrimitive)?.content
            ?: runCatching { components.toPath().toString() }.getOrDefault(components.path)
        return JsonObject(source + ("path" to JsonPrimitive(path)))
    }

    private fun JsonObject.edit(key: String, change: (JsonElement) -> JsonElement): JsonObject {
        val value = this[key] ?: return this
        val changed = change(value)
        return if (changed == value) this else JsonObject(this + (key to changed))
    }

    private fun each(element: JsonElement, change: (JsonObject) -> JsonObject): JsonElement =
        (element as? JsonArray)?.let { array -> JsonArray(array.map { (it as? JsonObject)?.let(change) ?: it }) }
            ?: element

    /**
     * The event, when it is one VS Code's own client does not read and hands to the extensions as
     * `onDidReceiveDebugSessionCustomEvent` -- js-debug's own events are. Null for anything else.
     */
    fun customEvent(json: String): JsonObject? {
        val message =
            runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return null
        if ((message["type"] as? JsonPrimitive)?.content != "event") return null
        val event = (message["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return message.takeIf { event !in STANDARD_EVENTS }
    }

    /** A request of the workbench's own, numbered as [seq]. */
    fun request(seq: Int, command: String, arguments: JsonElement?): String = buildJsonObject {
        put("seq", seq); put("type", "request"); put("command", command)
        arguments?.let { put("arguments", it) }
    }.toString()

    /** The `request_seq` of a response; null for any other message. */
    fun responseSeq(json: String): Int? {
        val message =
            runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return null
        if ((message["type"] as? JsonPrimitive)?.content != "response") return null
        return (message["request_seq"] as? JsonPrimitive)?.intOrNull
    }

    /** What `customRequest` answers for a [response]: its body, or a failure with its message. */
    fun customResponse(response: String): Result<String> {
        val message = Json.parseToJsonElement(response) as JsonObject
        if ((message["success"] as? JsonPrimitive)?.booleanOrNull == true) return Result.success(
            (message["body"] ?: JsonNull).toString()
        )
        val text = (message["message"] as? JsonPrimitive)?.content
            ?: ((message["body"] as? JsonObject)?.get("error") as? JsonObject)?.get("format")
                ?.let { (it as? JsonPrimitive)?.content }
            ?: "The debug adapter refused '${(message["command"] as? JsonPrimitive)?.content}'"
        return Result.failure(PayloadException(text))
    }

    fun startSession(handle: Int, sessionDto: String): String = "[$handle,$sessionDto]"

    fun sendMessage(handle: Int, json: String): String = "[$handle,$json]"

    fun stopSession(handle: Int): String = "[$handle]"

    private val METHODS = setOf(ACCEPT_MESSAGE, ACCEPT_ERROR, ACCEPT_EXIT)

    /** The events VS Code's `RawDebugSession` handles itself; any other is the extension's. */
    private val STANDARD_EVENTS = setOf(
        "initialized", "loadedSource", "capabilities", "stopped", "continued", "thread",
        "output", "breakpoint", "terminated", "exited", "progressStart", "progressUpdate", "progressEnd", "invalidated",
        "memory", "process", "module"
    )
}
