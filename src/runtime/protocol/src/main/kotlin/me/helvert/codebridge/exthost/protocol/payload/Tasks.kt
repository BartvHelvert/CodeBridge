package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * `MainThreadTask` / `ExtHostTask`: `vscode.tasks.executeTask`, and the events that follow it.
 *
 * Running a task is two calls from the host, both carrying the same task: `$getTaskExecution` asks
 * for the execution id the extension's `TaskExecution` will be known by, and `$executeTask` starts
 * it. The host then waits for the start and end events, by that id, to settle the promises an
 * extension awaits on -- `onDidEndTaskProcess` is how one learns the exit code.
 */
object Tasks {

    const val SERVICE: String = "MainThreadTask"
    const val EXT_HOST: String = "ExtHostTask"

    const val REGISTER_PROVIDER: String = $$"$registerTaskProvider"
    const val UNREGISTER_PROVIDER: String = $$"$unregisterTaskProvider"
    const val GET_EXECUTION: String = $$"$getTaskExecution"
    const val EXECUTE: String = $$"$executeTask"
    const val TERMINATE: String = $$"$terminateTask"
    const val FETCH: String = $$"$fetchTasks"
    const val CREATE_ID: String = $$"$createTaskId"

    const val RESOLVE: String = $$"$resolveTask"
    const val DID_START: String = $$"$onDidStartTask"
    const val DID_START_PROCESS: String = $$"$onDidStartTaskProcess"
    const val DID_END_PROCESS: String = $$"$onDidEndTaskProcess"

    /** Capitalised in VS Code itself, unlike the others. */
    const val DID_END: String = $$"$OnDidEndTask"

    /** `cwd` and `env` as the task gave them; a null value in [env] removes the variable. */
    data class Options(val cwd: String?, val env: Map<String, String?>)

    sealed interface Execution {
        val options: Options
    }

    /** `ProcessExecution`: a program and its arguments, run without a shell. */
    data class Process(val process: String, val args: List<String>, override val options: Options) : Execution

    /**
     * `ShellExecution`: either one [commandLine] handed to the shell as it is, or a [command] and
     * [args] the workbench quotes. [executable] and [shellArgs] replace the default shell.
     */
    data class Shell(
        val commandLine: String?,
        val command: ShellArgument?,
        val args: List<ShellArgument>,
        override val options: Options,
        val executable: String?,
        val shellArgs: List<String>?,
    ) : Execution

    /** `CustomExecution`: a pseudoterminal the extension drives itself. */
    data class Custom(override val options: Options = Options(null, emptyMap())) : Execution

    /** A shell argument and how it asked to be quoted, if it did. */
    data class ShellArgument(val value: String, val quoting: Quoting?)

    /** `vscode.ShellQuoting`: a number in the API, and kept as either. */
    enum class Quoting { ESCAPE, STRONG, WEAK }

    /** A task DTO, read as far as running it needs; [raw] is what goes back to the host. */
    data class Task(val name: String, val type: String?, val execution: Execution?, val raw: JsonObject)

    /** `$registerTaskProvider(handle, type)`. */
    data class ProviderRegistration(val handle: Int, val type: String)

    fun providerRegistration(argsJson: String): ProviderRegistration {
        val args = Args.of(REGISTER_PROVIDER, argsJson)
        return ProviderRegistration(args.int(0), args.str(1))
    }

    /**
     * The task argument of [GET_EXECUTION] or [EXECUTE].
     *
     * Null for a handle -- `{id, workspaceFolder}` -- which names a task the workbench listed
     * earlier through `fetchTasks`, rather than carrying the task.
     */
    fun task(method: String, argsJson: String): Task? {
        val dto = Args.of(method, argsJson).obj(0)
        if (dto["definition"] == null && dto["id"] != null) return null
        return task(dto)
    }

    fun task(dto: JsonObject): Task = Task(
        name = dto.string("name") ?: "Task",
        type = (dto["definition"] as? JsonObject)?.string("type"),
        execution = execution(dto["execution"]),
        raw = dto,
    )

    fun execution(element: JsonElement?): Execution? {
        val json = element as? JsonObject ?: return null
        if (json.string("customExecution") == "customExecution") return Custom()
        val options = json["options"] as? JsonObject
        val parsed = Options(
            cwd = options?.string("cwd"),
            env = (options?.get("env") as? JsonObject).orEmpty()
                .mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull },
        )
        json.string("process")?.let { process ->
            return Process(
                process,
                (json["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                parsed
            )
        }
        val commandLine = json.string("commandLine")
        val command = argument(json["command"])
        if (commandLine == null && command == null) return null
        return Shell(
            commandLine = commandLine,
            command = command,
            args = (json["args"] as? JsonArray).orEmpty().mapNotNull(::argument),
            options = parsed,
            executable = options?.string("executable"),
            shellArgs = (options?.get("shellArgs") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** `ITaskExecutionDTO`, the reply to [GET_EXECUTION] and [EXECUTE] and the subject of [DID_END]. */
    fun executionDto(id: String, task: JsonObject): JsonObject = buildJsonObject {
        put("id", id); put("task", task)
    }

    /**
     * `[execution, terminalId, resolvedDefinition]`. A process or shell task runs in no terminal, and
     * its id names none; a custom execution's pseudoterminal is attached to [terminalId].
     */
    fun started(execution: JsonObject, terminalId: Int = -1): String = buildJsonArray {
        add(execution); add(JsonPrimitive(terminalId))
        add((execution["task"] as? JsonObject)?.get("definition") ?: JsonNull)
    }.toString()

    fun processStarted(id: String, processId: Long): String =
        buildJsonArray { add(buildJsonObject { put("id", id); put("processId", processId) }) }.toString()

    /** A null [exitCode] is a process that could not be started or reported none. */
    fun processEnded(id: String, exitCode: Int?): String =
        buildJsonArray { add(buildJsonObject { put("id", id); put("exitCode", exitCode) }) }.toString()

    fun ended(execution: JsonObject): String = buildJsonArray { add(execution) }.toString()

    /** `$resolveTask(handle, task)`, asked of the provider of a task that came without an execution. */
    fun resolve(handle: Int, task: JsonObject): String =
        buildJsonArray { add(JsonPrimitive(handle)); add(task) }.toString()

    private fun argument(element: JsonElement?): ShellArgument? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.let { ShellArgument(it, null) }
        is JsonObject -> element.string("value")?.let { ShellArgument(it, quoting(element["quoting"])) }
        else -> null
    }

    private fun quoting(element: JsonElement?): Quoting? {
        val primitive = element as? JsonPrimitive ?: return null
        return when (primitive.intOrNull ?: primitive.contentOrNull?.lowercase()) {
            1, "escape" -> Quoting.ESCAPE
            2, "strong" -> Quoting.STRONG
            3, "weak" -> Quoting.WEAK
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
