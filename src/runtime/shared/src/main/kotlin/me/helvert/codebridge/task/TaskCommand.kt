package me.helvert.codebridge.task

import me.helvert.codebridge.workspace.VsCodeFiles
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.Tasks
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a task runs, wherever the task was defined: a program with its arguments, or a shell command.
 *
 * Ours, not VS Code's: a task run configuration stores this, so it must not change shape when a new
 * VS Code changes the task DTO it was first read from. `tasks.json` entries and the tasks extensions
 * build both come down to it.
 */
sealed interface TaskCommand {
    /** The working directory as written, before variables and the workspace resolve it. */
    val cwd: String?

    /** Variables to set; a null value removes one. */
    val env: Map<String, String?>

    /** A program run directly, with its arguments as separate arguments. */
    data class Process(
        val program: String,
        val args: List<String> = emptyList(),
        override val cwd: String? = null,
        override val env: Map<String, String?> = emptyMap(),
    ) : TaskCommand

    /**
     * A shell command: one [commandLine] the shell reads as it is, or a [command] and [args] quoted
     * for it. [executable] and [shellArgs] name a shell other than the platform's.
     */
    data class Shell(
        val commandLine: String? = null,
        val command: Argument? = null,
        val args: List<Argument> = emptyList(),
        val executable: String? = null,
        val shellArgs: List<String>? = null,
        override val cwd: String? = null,
        override val env: Map<String, String?> = emptyMap(),
    ) : TaskCommand

    /** A shell argument and how it asked to be quoted, if it did. */
    data class Argument(val value: String, val quoting: Quoting? = null)

    enum class Quoting { ESCAPE, STRONG, WEAK }

    /** Read aloud: the command as one line, for a settings page. */
    fun describe(): String = when (this) {
        is Process -> (listOf(program) + args).joinToString(" ")
        is Shell -> commandLine ?: (listOfNotNull(command) + args).joinToString(" ") { it.value }
    }

    /** As the execution of a task DTO, for telling the extension host about a run. */
    fun executionDto(): JsonObject = buildJsonObject {
        when (val command = this@TaskCommand) {
            is Process -> {
                put("process", command.program)
                put("args", JsonArray(command.args.map(::JsonPrimitive)))
            }

            is Shell -> {
                command.commandLine?.let { put("commandLine", it) }
                command.command?.let { put("command", it.value) }
                if (command.commandLine == null) put("args", JsonArray(command.args.map { JsonPrimitive(it.value) }))
            }
        }
        put("options", buildJsonObject {
            cwd?.let { put("cwd", it) }
            put("env", JsonObject(env.mapValues { (_, value) -> JsonPrimitive(value) }))
        })
    }

    companion object {
        /** A task's execution, as an extension described it; null for one that is not a command. */
        fun of(execution: Tasks.Execution?): TaskCommand? = when (execution) {
            is Tasks.Process -> Process(execution.process, execution.args, execution.options.cwd, execution.options.env)
            is Tasks.Shell -> Shell(
                commandLine = execution.commandLine,
                command = execution.command?.let(::argument),
                args = execution.args.map(::argument),
                executable = execution.executable,
                shellArgs = execution.shellArgs,
                cwd = execution.options.cwd,
                env = execution.options.env,
            )

            is Tasks.Custom, null -> null
        }

        private fun argument(argument: Tasks.ShellArgument) = Argument(
            argument.value, when (argument.quoting) {
                Tasks.Quoting.ESCAPE -> Quoting.ESCAPE
                Tasks.Quoting.STRONG -> Quoting.STRONG
                Tasks.Quoting.WEAK -> Quoting.WEAK
                null -> null
            }
        )
    }
}

/** Where a task comes from, which decides what it is each time it runs. */
sealed interface TaskSource {
    /** What the run is called. */
    val name: String

    /**
     * An entry of `tasks.json`, by label: read again on every run. [folder] is the workspace folder
     * whose `tasks.json` is looked in first, in a multi-root workspace; then, and with none, the
     * workspace's own -- the `.code-workspace` file's `tasks`, or the one `.vscode/tasks.json`.
     */
    data class TasksJson(val label: String, val folder: String? = null) : TaskSource {
        override val name: String get() = label
    }

    /**
     * A task an extension built and ran, kept as it was then. [definition] is the extension's own
     * task definition (`{"type": "cargo", ...}`), which it recognises its task by. [problemMatchers]
     * are the names it gave the task, `$rustc` and the like.
     */
    data class Extension(
        override val name: String,
        val extensionId: String?,
        val definition: JsonObject,
        val command: TaskCommand,
        val problemMatchers: List<String> = emptyList(),
    ) : TaskSource
}

/** A task ready to run: its command, and the tasks to run before it, in order, already resolved. */
data class ResolvedTask(
    val name: String,
    val command: TaskCommand,
    /** The task DTO the extension host is told about its runs with. */
    val dto: JsonObject,
    val dependencies: List<ResolvedTask> = emptyList(),
    /** The workspace folder it runs in, `${workspaceFolder}`; null for the workspace's own, see [TaskSource.TasksJson]. */
    val folder: Path? = null,
    val isBackground: Boolean = false,
    val background: List<TaskBackground> = emptyList(),
    /** What finds problems in its output. */
    val problems: TaskProblemMatchers.Diagnostics = TaskProblemMatchers.Diagnostics(),
) {
    /** Every step, dependencies first, each once, in the order they run. */
    fun steps(): List<ResolvedTask> {
        val steps = linkedMapOf<String, ResolvedTask>()
        fun add(task: ResolvedTask) {
            task.dependencies.forEach(::add)
            steps.putIfAbsent(task.name, task)
        }
        add(this)
        return steps.values.map { it.copy(dependencies = emptyList()) }
    }
}

object TaskSources {

    /** What [source] runs now, failing with a sentence for the user when it cannot run. */
    fun resolve(workspace: Path, source: TaskSource): ResolvedTask =
        resolve(workspace, source, TaskProblemMatchers.builtins())

    /** [problemPatterns] are the patterns [problemMatchers] can name, by bare name. */
    fun resolve(
        workspace: Path,
        source: TaskSource,
        problemMatchers: Map<String, JsonObject>,
        problemPatterns: Map<String, JsonObject> = TaskProblemMatchers.builtinPatterns(),
    ): ResolvedTask =
        when (source) {
            is TaskSource.Extension -> ResolvedTask(
                source.name, source.command,
                dto(source.name, source.definition, source.extensionId, source.command),
                problems = TaskProblemMatchers.diagnostics(
                    JsonArray(source.problemMatchers.map { JsonPrimitive(it) }), problemMatchers, problemPatterns
                ),
            )

            is TaskSource.TasksJson -> {
                val layout = CodeBridgeWorkspace.of(workspace)
                val origin = origin(layout, source)
                val folderPath = origin?.let { name -> layout.folders.first { it.name == name }.path }
                    ?: workspace.takeUnless { layout.isMultiRoot }
                resolve(
                    read(layout, origin),
                    source.label,
                    linkedSetOf(),
                    folderPath,
                    where(layout, origin),
                    problemMatchers,
                    problemPatterns,
                )
            }
        }

    /**
     * [source] as the file that serves it names it: a `preLaunchTask` of a folder's entry that only
     * the workspace's own `tasks.json` has is that one's. Anything else is as it was.
     */
    fun served(workspace: Path, source: TaskSource): TaskSource {
        if (source !is TaskSource.TasksJson) return source
        val origin = runCatching { origin(CodeBridgeWorkspace.of(workspace), source) }.getOrElse { return source }
        return source.copy(folder = origin)
    }

    /**
     * Every task the workspace lists, as VS Code offers them to run: those of the workspace's own
     * `tasks.json` -- the `.code-workspace` file's `tasks`, or the one `.vscode/tasks.json` -- and, in
     * a multi-root workspace, each folder's. A `"hide": true` task is left out, as VS Code leaves it
     * out of Run Task. Null when a file that has tasks is not JSON now, half-way through an edit.
     */
    fun listed(workspace: Path): List<TaskSource.TasksJson>? {
        val layout = CodeBridgeWorkspace.of(workspace)
        val origins = listOf<String?>(null) + if (layout.isMultiRoot) layout.folders.map { it.name } else emptyList()
        return origins.filter { exists(layout, it) }.flatMap { folder ->
            val result = TasksJsonParser.parse(text(layout, folder))
            if (!result.parsed) return null
            result.file.tasks.filterNot { (it.raw["hide"] as? JsonPrimitive)?.booleanOrNull == true }
                .map { it.label }.distinct().map { TaskSource.TasksJson(it, folder) }
        }
    }

    /** The folder's own first, then the workspace's; the first that has the label serves it. */
    private fun origin(layout: CodeBridgeWorkspace, source: TaskSource.TasksJson): String? {
        val origins = listOf(source.folder, null).distinct().filter { exists(layout, it) }
        if (origins.isEmpty()) throw IllegalArgumentException("No .vscode/tasks.json in ${layout.base}")
        // By index: null, the workspace's own, is an origin too.
        val labelled = origins.indexOfFirst { folder ->
            runCatching {
                read(
                    layout,
                    folder
                )
            }.getOrNull()?.tasks?.any { it.label == source.label } == true
        }
        return origins[labelled.coerceAtLeast(0)]
    }

    /** The workspace's own tasks, or [folder]'s; see [TaskSource.TasksJson]. */
    fun read(workspace: Path, folder: String? = null): TaskFile = read(CodeBridgeWorkspace.of(workspace), folder)

    private fun exists(layout: CodeBridgeWorkspace, folder: String?): Boolean = when {
        !VsCodeFiles.apply() -> false
        folder != null -> layout.folders.firstOrNull { it.name == folder }
            ?.let { Files.isRegularFile(layout.folderFile(it, TASKS)) } == true

        layout.isMultiRoot -> layout.section("tasks") != null
        else -> Files.isRegularFile(layout.base.resolve(".vscode/$TASKS"))
    }

    private fun where(layout: CodeBridgeWorkspace, folder: String?): String = when {
        folder != null -> "$folder/.vscode/tasks.json"
        layout.file != null -> "the tasks section of ${layout.file.fileName}"
        else -> ".vscode/tasks.json"
    }

    private fun read(layout: CodeBridgeWorkspace, folder: String?): TaskFile {
        require(VsCodeFiles.apply()) { "The project's tasks.json does not apply without CodeBridge" }
        val result = TasksJsonParser.parse(text(layout, folder))
        require(result.problems.isEmpty()) { result.problems.joinToString("\n") }
        return result.file
    }

    private fun text(layout: CodeBridgeWorkspace, folder: String?): String = when {
        folder != null -> {
            val found = layout.folders.firstOrNull { it.name == folder }
                ?: throw IllegalArgumentException("There is no workspace folder named '$folder'")
            val file = layout.folderFile(found, TASKS)
            require(Files.isRegularFile(file)) { "No .vscode/tasks.json in the workspace folder '$folder'" }
            Files.readString(file)
        }

        layout.file != null -> (layout.section("tasks")
            ?: throw IllegalArgumentException("${layout.file.fileName} has no tasks")).toString()

        else -> {
            val file = layout.base.resolve(".vscode/$TASKS")
            require(Files.isRegularFile(file)) { "No .vscode/tasks.json in ${layout.base}" }
            Files.readString(file)
        }
    }

    private const val TASKS = "tasks.json"

    private fun resolve(
        file: TaskFile,
        label: String,
        active: MutableSet<String>,
        folder: Path?,
        where: String,
        problemMatchers: Map<String, JsonObject>,
        problemPatterns: Map<String, JsonObject>,
    ): ResolvedTask {
        val matches = file.tasks.filter { it.label == label }
        require(matches.size == 1) { "Expected one task labelled '$label' in $where; found ${matches.size}" }
        require(active.add(label)) { "Circular task dependency: ${active.joinToString(" -> ")} -> $label" }
        val task = matches.single()
        val dependencies =
            task.dependsOn.map { resolve(file, it, active, folder, where, problemMatchers, problemPatterns) }
        active.remove(label)
        val command = command(task)
        val definition = buildJsonObject { put("type", task.type); put("label", task.label) }
        val background =
            if (task.isBackground) TaskProblemMatchers.background(task.problemMatcher, problemMatchers) else emptyList()
        val dto = JsonObject(
            dto(task.label, definition, null, command) + mapOf(
            "isBackground" to JsonPrimitive(task.isBackground),
                "problemMatchers" to (task.problemMatcher?.let { it as? JsonArray ?: JsonArray(listOf(it)) }
                ?: JsonArray(emptyList())),
            "hasDefinedMatchers" to JsonPrimitive(task.problemMatcher != null)))
        val problems = TaskProblemMatchers.diagnostics(task.problemMatcher, problemMatchers, problemPatterns)
        return ResolvedTask(task.label, command, dto, dependencies, folder, task.isBackground, background, problems)
    }

    /** A `tasks.json` entry's command. Only `process` and `shell` run here; other types need their extension. */
    fun command(task: TaskDefinition): TaskCommand = when (task.type) {
        "process" -> TaskCommand.Process(task.command, task.args, task.options.cwd, task.options.env)
        // A command with no arguments is a command line, handed to the shell as written, as VS Code
        // hands it: `cargo build && cargo test` runs both. Quoted as one word, it named a program
        // called that, and the shell found none.
        "shell" -> if (task.args.isEmpty()) TaskCommand.Shell(
            commandLine = task.command,
            cwd = task.options.cwd,
            env = task.options.env
        )
        else TaskCommand.Shell(
            command = TaskCommand.Argument(task.command), args = task.args.map { TaskCommand.Argument(it) },
            cwd = task.options.cwd, env = task.options.env
        )

        else -> throw IllegalArgumentException("The task '${task.label}' is of type '${task.type}'; only process and shell tasks run from tasks.json")
    }

    /**
     * The task DTO for a run, as the host reads one back: enough for it to make a `Task` of a run it
     * did not start itself -- one started from the run widget, or a `tasks.json` entry.
     */
    fun dto(name: String, definition: JsonObject, extensionId: String?, command: TaskCommand): JsonObject =
        buildJsonObject {
            put("name", name)
            put("definition", definition)
            put("source", buildJsonObject {
                put("label", extensionId ?: "Workspace")
                extensionId?.let { put("extensionId", it) }
                // `TaskScope.Workspace`.
                put("scope", 2)
            })
            put("execution", command.executionDto())
            put("isBackground", false)
            put("problemMatchers", JsonArray(emptyList()))
            put("hasDefinedMatchers", false)
            put("runOptions", buildJsonObject { })
        }
}
