package me.helvert.codebridge.task

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.Messages
import me.helvert.codebridge.exthost.protocol.payload.Tasks
import me.helvert.codebridge.workbench.HostedMessages
import me.helvert.codebridge.workbench.HostedTerminals
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * `MainThreadTask`: the tasks an extension runs with `vscode.tasks.executeTask`.
 *
 * What a code lens's Run ends in when the extension builds a task rather than a debug
 * configuration -- rust-analyzer's `cargo run` is one. A process or shell task becomes a temporary
 * run configuration ([CodeBridgeTaskRunConfiguration], [TaskSource.Extension]) and runs as one. Every run
 * of a task configuration, the extension's or a `tasks.json` entry's, is reported here ([started]),
 * and the host hears it start and end as VS Code tells it, so an extension awaiting the exit code
 * gets it.
 *
 * A `CustomExecution` runs the extension's own pseudoterminal, in a terminal tab of its own.
 *
 * The problem matchers the extension names find problems in the output, as a `tasks.json` task's
 * do ([TaskProblems]).
 *
 * Not here: tasks listed by `fetchTasks` (the list is empty).
 */
class HostedTasks(
    private val project: Project,
    /** Where a custom execution's pseudoterminal is shown. */
    private val terminals: HostedTerminals,
    private val callHost: suspend (service: String, method: String, args: String) -> String,
) : Closeable {
    /** What is sent without waiting, and a custom execution's end awaited; ends with [close]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Provider handles by the task type each resolves. */
    private val providers = ConcurrentHashMap<Int, String>()
    /** Execution ids handed out by `$createTaskId` and `$getTaskExecution`, by the task they were handed out for. */
    private val pending = ConcurrentHashMap<String, String>()
    private val running = ConcurrentHashMap<String, ProcessHandler>()
    private val nextId = AtomicInteger()

    suspend fun handle(call: MainThreadCall): MainThreadResult =
        when (call.method) {
            Tasks.REGISTER_PROVIDER -> {
                Tasks.providerRegistration(call.argsJson).let { providers[it.handle] = it.type }
                MainThreadResult()
            }
            Tasks.UNREGISTER_PROVIDER -> { providers.remove(Args.of(call.method, call.argsJson).int(0)); MainThreadResult() }
            // The same id for the same task, as `$getTaskExecution` and `$executeTask` give it: the host
            // files a `CustomExecution` under this id and finds it again by the id the task is started
            // under. A fresh id here left the custom execution unfound -- its pseudoterminal never
            // opened, and CodeLLDB's `cargo` launch waited forever for a build that never ran.
            Tasks.CREATE_ID -> {
                val task = Tasks.task(call.method, call.argsJson) ?: unlisted()
                MainThreadResult(JsonPrimitive(pending.computeIfAbsent(task.raw.toString()) { newId() }).toString())
            }
            Tasks.GET_EXECUTION -> {
                val task = Tasks.task(call.method, call.argsJson) ?: unlisted()
                val id = pending.computeIfAbsent(task.raw.toString()) { newId() }
                MainThreadResult(Tasks.executionDto(id, task.raw).toString())
            }
            Tasks.EXECUTE -> {
                val task = Tasks.task(call.method, call.argsJson) ?: unlisted()
                val id = pending.remove(task.raw.toString()) ?: newId()
                execute(id, task)
                MainThreadResult(Tasks.executionDto(id, task.raw).toString())
            }
            Tasks.TERMINATE -> { running[Args.of(call.method, call.argsJson).str(0)]?.destroyProcess(); MainThreadResult() }
            Tasks.FETCH -> MainThreadResult("[]")
            // `$registerSupportedExecutions`, `$registerTaskSystem`, `$customExecutionComplete`.
            else -> MainThreadResult()
        }

    /**
     * A run of a task configuration has started: the one the extension asked for, under the
     * execution id it was given, or any other -- from the run widget, or a `tasks.json` entry -- as
     * a new execution the host hears of as it would of one the workbench started.
     */
    fun started(dto: JsonObject, handler: ProcessHandler, id: String?) {
        val executionId = id ?: newId()
        val execution = Tasks.executionDto(executionId, dto)
        running[executionId] = handler
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                running.remove(executionId)
                tell(Tasks.DID_END_PROCESS, Tasks.processEnded(executionId, event.exitCode))
                tell(Tasks.DID_END, Tasks.ended(execution))
            }
        })
        tell(Tasks.DID_START, Tasks.started(execution))
        (handler as? BaseProcessHandler<*>)?.process?.pid()?.let { pid ->
            tell(Tasks.DID_START_PROCESS, Tasks.processStarted(executionId, pid))
        }
    }

    /** Sent now, in order with what follows; the answer is only looked at for a failure. */
    private fun tell(method: String, args: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                callHost(Tasks.EXT_HOST, method, args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not send $method", failure)
            }
        }
    }

    /**
     * Forgets the runs. Their processes are IntelliJ's runs, stopped from their tab, and outlive the
     * host they were reported to.
     */
    override fun close() {
        running.clear()
        scope.cancel()
    }

    private suspend fun execute(id: String, task: Tasks.Task) {
        val resolved = resolved(task)
        val execution = Tasks.executionDto(id, task.raw)
        val command = TaskCommand.of(resolved.execution)
        when {
            command != null -> {
                val source = TaskSource.Extension(resolved.name, extensionId(resolved.raw),
                    resolved.raw["definition"] as? JsonObject ?: JsonObject(emptyMap()), command,
                    problemMatchers(resolved.raw)
                )
                val started = CompletableDeferred<Unit>()
                CodeBridgeTaskRuns.start(project, source, CodeBridgeTaskRuns.Pending(id, resolved.raw, started))
                withTimeoutOrNull(START_MINUTES.minutes) { started.await() }
                    ?: throw TimeoutException("The task '${task.name}' did not start within $START_MINUTES minutes")
            }
            resolved.execution is Tasks.Custom -> custom(resolved, execution)
            else -> {
                HostedMessages.getInstance(project).notify(Messages.Severity.WARNING,
                    "The task '${task.name}' cannot run: it has no execution.", source = "VS Code tasks")
                // Ends what the host started waiting on with `$getTaskExecution`.
                tell(Tasks.DID_END, Tasks.ended(execution))
                throw IllegalStateException("The task '${task.name}' cannot run: it has no execution")
            }
        }
    }

    /** A task with no execution is completed by the provider of its type, as the workbench asks it to. */
    private suspend fun resolved(task: Tasks.Task): Tasks.Task {
        if (task.execution != null) return task
        val handle = providers.entries.firstOrNull { it.value == task.type }?.key ?: return task
        val reply = callHost(Tasks.EXT_HOST, Tasks.RESOLVE, Tasks.resolve(handle, task.raw))
        return (runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject)?.let(Tasks::task) ?: task
    }

    /**
     * A custom execution: a terminal is opened for it first, the host is told the task started in
     * that terminal -- which is when the extension's callback makes the pseudoterminal and attaches
     * it -- and only then is the pseudoterminal started. The task ends when it closes.
     */
    private suspend fun custom(task: Tasks.Task, execution: JsonObject) {
        val terminal = terminals.openForTask(task.name)
        scope.launch {
            terminal.ended.await()
            tell(Tasks.DID_END, Tasks.ended(execution))
        }
        callHost(Tasks.EXT_HOST, Tasks.DID_START, Tasks.started(execution, terminal.id))
        terminal.startPseudoterminal()
    }

    /** The names in `TaskDTO.problemMatchers`, as the extension gave them. */
    private fun problemMatchers(dto: JsonObject): List<String> =
        (dto["problemMatchers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun extensionId(dto: JsonObject): String? =
        ((dto["source"] as? JsonObject)?.get("extensionId") as? JsonPrimitive)?.contentOrNull

    private fun newId() = "codebridge-task-${nextId.incrementAndGet()}"

    private fun unlisted(): Nothing =
        throw UnsupportedOperationException("Only tasks an extension builds itself can run; tasks listed by fetchTasks cannot")

    private companion object {
        val LOG = Logger.getInstance(HostedTasks::class.java)

        /** How long a task the extension asked for has to start: its configuration may build first. */
        const val START_MINUTES = 2L
    }
}
