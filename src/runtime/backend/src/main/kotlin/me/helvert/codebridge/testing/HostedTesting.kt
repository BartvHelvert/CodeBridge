package me.helvert.codebridge.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.Args
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.collections.ArrayDeque
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * `MainThreadTesting`: the tests extensions declare with `vscode.tests.createTestController`, and
 * their runs.
 *
 * The backend keeps the controllers' trees as their diffs describe them, which is all an extension
 * has discovered so far; [itemsIn] is what asks for more, for a file someone is looking at. Runs are
 * [Run]s, started by [run] or announced by the extension, and drawn by whoever [Run.attach]es to
 * them -- the `CodeBridge.testrunner.backend` module, which owns everything that needs the IDE's test runner.
 *
 * Outlives the host: the trees go with it, the last results stay, so a gutter keeps saying what
 * failed last time across a restart.
 */
@Service(Service.Level.PROJECT)
class HostedTesting(
    /** Where run requests, and what is sent without waiting, are made; it ends with the project. */
    private val scope: CoroutineScope,
) {

    /** Calls into the host: service, method, arguments and whether the call is cancellable. */
    fun interface HostCalls {
        suspend fun call(service: String, method: String, argsJson: String, cancellable: Boolean): String
    }

    /** Told which files' tests changed, by [UriComponents.key]; an empty set means any file may have. */
    fun interface ChangeListener {
        fun changed(files: Set<String>)
    }

    /** Shows a run nobody in the IDE started: one the extension began on its own. */
    fun interface RunOpener {
        fun open(run: Run)
    }

    class Controller(val id: String, var label: String) {
        internal val nodes = LinkedHashMap<String, Node>()
        internal val profiles = LinkedHashMap<Int, Testing.Profile>()
    }

    internal class Node(var item: Testing.Item, var expand: Testing.Expand) {
        val children = LinkedHashSet<String>()

        /** The deepest discovery asked of the host for this item, so it is asked once. */
        var expandedTo = 0
    }

    private val lock = Any()
    private val controllers = LinkedHashMap<String, Controller>()
    private val runs = HashMap<String, Run>()

    /** The last finished state of each test, by its id, for the gutter. */
    private val results = HashMap<String, Testing.State>()

    /** Files someone asked about: their tests are discovered as soon as the host can say where. */
    private val wanted = HashSet<String>()
    private val listeners = CopyOnWriteArrayList<ChangeListener>()
    @Volatile
    private var openers: List<RunOpener> = emptyList()
    @Volatile
    private var host: HostCalls? = null

    fun hostStarted(calls: HostCalls) {
        host = calls
    }

    /** The trees belonged to the host; a run it was reporting ends unfinished. */
    fun hostStopped() {
        host = null
        val ended = synchronized(lock) {
            controllers.clear()
            runs.values.toList().also { runs.clear() }
        }
        ended.forEach { it.end("The extension host stopped") }
        fire(emptySet())
    }

    fun addChangeListener(listener: ChangeListener, parent: Disposable) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    fun setRunOpener(opener: RunOpener, parent: Disposable) {
        openers = openers + opener
        Disposer.register(parent) { openers = openers - opener }
    }

    /** Handles one `MainThreadTesting` call; only `$runTests` answers later than at once. */
    suspend fun handle(call: MainThreadCall): MainThreadResult =
        when (call.method) {
            Testing.REGISTER_CONTROLLER -> {
                val controller = Testing.controller(call.argsJson)
                synchronized(lock) { controllers[controller.id] = Controller(controller.id, controller.label) }
                MainThreadResult()
            }

            Testing.UPDATE_CONTROLLER -> {
                val (id, label) = Testing.controllerLabel(call.argsJson)
                if (label != null) synchronized(lock) { controllers[id]?.label = label }
                MainThreadResult()
            }

            Testing.UNREGISTER_CONTROLLER -> {
                val id = Testing.TestId.controllerId(Args.of(call.method, call.argsJson).str(0))
                synchronized(lock) { controllers.remove(id) }
                fire(emptySet())
                MainThreadResult()
            }

            Testing.PUBLISH_DIFF -> {
                apply(Testing.diff(call.argsJson)); MainThreadResult()
            }

            Testing.PUBLISH_PROFILE -> {
                val profile = Testing.publishedProfile(call.argsJson)
                synchronized(lock) { controllers[profile.controllerId]?.profiles?.put(profile.profileId, profile) }
                MainThreadResult()
            }

            Testing.UPDATE_PROFILE -> {
                val update = Testing.profileUpdate(call.argsJson)
                synchronized(lock) {
                    val profiles = controllers[update.controllerId]?.profiles ?: return@synchronized
                    profiles[update.profileId]?.let { profiles[update.profileId] = it.updated(update.update) }
                }
                MainThreadResult()
            }

            Testing.REMOVE_PROFILE -> {
                val args = Args.of(call.method, call.argsJson)
                synchronized(lock) { controllers[args.str(0)]?.profiles?.remove(args.int(1)) }
                MainThreadResult()
            }

            Testing.STARTED_EXTENSION_RUN -> {
                extensionRun(Testing.extensionRun(call.argsJson)); MainThreadResult()
            }

            Testing.FINISHED_EXTENSION_RUN -> {
                val runId = Args.of(call.method, call.argsJson).str(0)
                run(runId)?.end(null)
                MainThreadResult()
            }

            Testing.STARTED_TASK -> {
                val task = Testing.startedTask(call.argsJson)
                run(task.runId)?.taskStarted(task.taskId, task.name)
                MainThreadResult()
            }

            Testing.FINISHED_TASK -> {
                val args = Args.of(call.method, call.argsJson)
                run(args.str(0))?.taskFinished(args.str(1))
                MainThreadResult()
            }

            Testing.ADD_TESTS -> {
                val added = Testing.addedTests(call.argsJson)
                run(added.runId)?.emit(Run.Event.Added(added.items))
                MainThreadResult()
            }

            Testing.UPDATE_STATE -> {
                stateChanged(Testing.stateUpdate(call.argsJson)); MainThreadResult()
            }

            Testing.APPEND_MESSAGES -> {
                val messages = Testing.messages(call.argsJson)
                run(messages.runId)?.messages(messages.testId, messages.messages)
                MainThreadResult()
            }

            Testing.APPEND_OUTPUT -> {
                val output = Testing.output(call.argsJson, call.buffers[Testing.OUTPUT_BUFFER])
                run(output.runId)?.emit(Run.Event.Output(output.text, output.testId))
                MainThreadResult()
            }

            Testing.RUN_TESTS -> runRequested(Testing.runRequest(call.argsJson))
            // `$markTestRetired`, `$subscribeToDiffs`, `$unsubscribeFromDiffs`, `$appendCoverage`.
            else -> MainThreadResult()
        }

    /** The tests in [file] (a [UriComponents.key]) that are known, and a request to discover the rest. */
    fun itemsIn(file: String): List<Testing.Item> {
        val expansions = synchronized(lock) {
            if (wanted.add(file)) expansionsLocked() else emptyList()
        }
        expansions.forEach(::expand)
        return synchronized(lock) {
            controllers.values.flatMap { controller ->
                controller.nodes.values.filter { node -> !Testing.TestId.isRoot(node.item.extId) && keyOf(node.item) == file }
                    .map { it.item }
            }
        }
    }

    fun item(extId: String): Testing.Item? = synchronized(lock) {
        controllers[Testing.TestId.controllerId(extId)]?.nodes?.get(extId)?.item
    }

    /**
     * Whether [extId] has items under it, or may once discovered: whether it is a suite rather than
     * a test.
     */
    fun hasChildren(extId: String): Boolean = synchronized(lock) {
        val node = controllers[Testing.TestId.controllerId(extId)]?.nodes?.get(extId) ?: return@synchronized false
        node.children.isNotEmpty() || node.expand == Testing.Expand.EXPANDABLE || node.expand == Testing.Expand.BUSY_EXPANDING
    }

    /** How [extId] ended the last time it ran, if it did. */
    fun lastResult(extId: String): Testing.State? = synchronized(lock) { results[extId] }

    /** The controllers' labels by id, in the order they registered. */
    fun controllers(): Map<String, String> = synchronized(lock) { controllers.values.associate { it.id to it.label } }

    /** Whether some controller of [extIds] -- or any, for none -- can run them in [group]. */
    fun canRun(extIds: Collection<String>, group: Testing.Group): Boolean = synchronized(lock) {
        val ids = extIds.map(Testing.TestId::controllerId).toSet().ifEmpty { controllers.keys }
        ids.any { id -> controllers[id]?.let { profileLocked(it, group) } != null }
    }

    /**
     * Runs [extIds] -- every controller's tests, for none -- with each controller's default profile
     * for [group], as one run however many controllers it takes. Events go to [Run.attach]; the run
     * ends once every controller's handler has returned and every task it opened has ended.
     */
    fun run(extIds: Collection<String>, group: Testing.Group): Run {
        val calls = host ?: return Run.failed("The extension host is not running")
        val runId = UUID.randomUUID().toString()
        val (requests, problems) = synchronized(lock) {
            val byController = extIds.groupBy(Testing.TestId::controllerId)
                .ifEmpty { controllers.keys.associateWith { listOf(it) } }
            val problems = mutableListOf<String>()
            val requests = byController.mapNotNull { (controllerId, ids) ->
                val controller = controllers[controllerId]
                val profile = controller?.let { profileLocked(it, group) }
                if (profile == null) {
                    problems += "${controller?.label ?: controllerId} cannot ${group.verb()} tests"
                    null
                } else Testing.ControllerRun(runId, controllerId, profile.profileId, ids)
            }
            requests to problems
        }
        if (requests.isEmpty()) return Run.failed(problems.ifEmpty { listOf("There are no tests to run") }
            .joinToString("\n"))
        val run = Run(this, runId, group, requests.map { it.controllerId })
        synchronized(lock) { runs[runId] = run }
        problems.forEach { run.emit(Run.Event.Output("$it\r\n", null)) }
        // Asked now; the run follows the reply, and stopping it withdraws the request.
        run.request = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val answer = try {
                calls.call(Testing.EXT_HOST, Testing.RUN_CONTROLLER_TESTS, Testing.runControllerTests(requests), true)
            } catch (cancelled: CancellationException) {
                run.replied()
                throw cancelled
            } catch (_: TimeoutException) {
                // Requests time out at the host session long before a test suite need finish: the
                // tasks say when it is over instead.
                run.replyTimedOut()
                return@launch
            } catch (error: Exception) {
                run.emit(Run.Event.Output("${error.message}\r\n", null))
                run.replied()
                return@launch
            }
            Testing.runErrors(answer).forEach { run.emit(Run.Event.Output("$it\r\n", null)) }
            run.replied()
        }
        return run
    }

    internal fun cancel(run: Run) {
        tell(Testing.CANCEL_RUN, Testing.cancelRun(run.id)) { "cancel test run ${run.id}" }
        run.request?.cancel()
    }

    /** Ends [run] after a while, if asking it to stop did not. */
    internal fun endLater(run: Run, problem: String) {
        scope.launch {
            delay(STOP_GRACE_SECONDS.seconds)
            run.end(problem)
        }
    }

    /** Sends [method] now, in order with what follows; a failure is only logged, as failing to [what]. */
    private fun tell(method: String, args: String, what: () -> String) {
        val calls = host ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                calls.call(Testing.EXT_HOST, method, args, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not ${what()}", failure)
            }
        }
    }

    internal fun ended(run: Run) {
        synchronized(lock) { runs.remove(run.id) }
    }

    private fun run(runId: String): Run? = synchronized(lock) { runs[runId] }

    /**
     * Whether a test run is under way. A debug session an extension starts meanwhile is taken to be
     * how the run runs its tests -- VS Code does not say -- and is the run's, not one to run again.
     */
    fun isRunning(): Boolean = synchronized(lock) { runs.isNotEmpty() }

    private fun extensionRun(started: Testing.ExtensionRun) {
        val run = Run(
            this,
            started.runId,
            started.group ?: Testing.Group.RUN,
            listOf(started.controllerId),
            extensionStarted = true
        )
        synchronized(lock) { runs[started.runId] = run }
        val shown = openers
        if (shown.isEmpty()) LOG.info("Test run ${started.runId} of ${started.controllerId} is not shown: nothing in the IDE draws test runs")
        shown.forEach { it.open(run) }
    }

    /** `vscode.tests.runTests`: answered once the run has ended, as it is in VS Code. */
    private suspend fun runRequested(request: Testing.RunRequest): MainThreadResult {
        val run = run(request.targets.flatMap { it.testIds }, request.group)
        val shown = openers
        shown.forEach { it.open(run) }
        run.ended.await()
        return MainThreadResult()
    }

    private fun stateChanged(update: Testing.StateUpdate) {
        val run = run(update.runId) ?: return
        if (update.state in FINAL) {
            synchronized(lock) { results[update.testId] = update.state }
            item(update.testId)?.let(::keyOf)?.let { fire(setOf(it)) }
        }
        run.stateChanged(update.testId, update.state, update.duration)
    }

    private fun apply(diff: Testing.Diff) {
        val changed = HashSet<String>()
        val expansions = synchronized(lock) {
            val controller = controllers[diff.controllerId] ?: return
            for (op in diff.ops) when (op) {
                is Testing.DiffOp.Add -> {
                    val previous = controller.nodes[op.item.extId]
                    controller.nodes[op.item.extId] =
                        Node(op.item, op.expand).also { node -> previous?.let { node.children += it.children } }
                    Testing.TestId.parent(op.item.extId)?.let { controller.nodes[it]?.children?.add(op.item.extId) }
                    keyOf(op.item)?.let(changed::add)
                }

                is Testing.DiffOp.Update -> {
                    val node = controller.nodes[op.extId] ?: continue
                    op.expand?.let {
                        // Replaced or reloaded: what was discovered under it may be asked for again.
                        if (it == Testing.Expand.EXPANDABLE) node.expandedTo = 0
                        node.expand = it
                    }
                    op.item?.let { node.item = node.item.updated(it); keyOf(node.item)?.let(changed::add) }
                }

                is Testing.DiffOp.Remove -> {
                    val node = controller.nodes[op.extId] ?: continue
                    Testing.TestId.parent(op.extId)?.let { controller.nodes[it]?.children?.remove(op.extId) }
                    val pending = ArrayDeque(listOf(node))
                    while (pending.isNotEmpty()) {
                        val removed = pending.removeFirst()
                        controller.nodes.remove(removed.item.extId)
                        keyOf(removed.item)?.let(changed::add)
                        removed.children.mapNotNullTo(pending) { controller.nodes[it] }
                    }
                }

                is Testing.DiffOp.Other -> Unit
            }
            expansionsLocked()
        }
        expansions.forEach(::expand)
        if (changed.isNotEmpty()) fire(changed)
    }

    /**
     * What to ask the host to discover: each controller's first level, as VS Code's test view does
     * when it is shown, then whatever leads to a file someone asked about -- a folder one level at a
     * time, the file itself all the way down.
     */
    private fun expansionsLocked(): List<Pair<String, Int>> {
        val asks = mutableListOf<Pair<String, Int>>()
        for (controller in controllers.values) for (node in controller.nodes.values) {
            if (node.expand != Testing.Expand.EXPANDABLE) continue
            val key = keyOf(node.item)
            val levels = when {
                Testing.TestId.isRoot(node.item.extId) -> 1
                key != null && key in wanted -> ALL
                key != null && wanted.any { it.startsWith("$key/") } -> 1
                else -> continue
            }
            val depth = if (levels == ALL) Int.MAX_VALUE else levels
            if (node.expandedTo >= depth) continue
            node.expandedTo = depth
            asks += node.item.extId to levels
        }
        return asks
    }

    private fun expand(ask: Pair<String, Int>) =
        tell(Testing.EXPAND, Testing.expand(ask.first, ask.second)) { "discover the tests under ${ask.first}" }

    private fun profileLocked(controller: Controller, group: Testing.Group): Testing.Profile? {
        val candidates = controller.profiles.values.filter { it.group == group }
        return candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull()
    }

    private fun fire(files: Set<String>) {
        listeners.forEach { listener ->
            runCatching { listener.changed(files) }.onFailure {
                LOG.warn(
                    "A test listener failed",
                    it
                )
            }
        }
    }

    /**
     * One test run, as the host reports it.
     *
     * Events are kept until someone [attach]es, and replayed to it: a run the extension starts on
     * its own is under way before any tab exists to show it.
     */
    class Run internal constructor(
        private val owner: HostedTesting?,
        val id: String,
        val group: Testing.Group,
        val controllerIds: List<String>,
        val extensionStarted: Boolean = false,
    ) {
        sealed interface Event {
            data class TaskStarted(val name: String) : Event

            /** Items the run touches, each after its parent; the controller's root first. */
            data class Added(val items: List<Testing.Item>) : Event
            data class State(
                val testId: String,
                val state: Testing.State,
                val duration: Double?,
                val messages: List<Testing.Message>
            ) : Event

            /** Terminal text; [testId] is the test it was written for, if any. */
            data class Output(val text: String, val testId: String?) : Event

            /** The run is over; [problem] says why it did not run, or why it stopped early. */
            data class Ended(val problem: String?) : Event
        }

        private val events = mutableListOf<Event>()
        private var listener: ((Event) -> Unit)? = null
        private val openTasks = HashSet<String>()
        private var anyTask = false
        private var repliedOrGaveUp = false
        private var timedOut = false
        private val pendingMessages = HashMap<String, MutableList<Testing.Message>>()
        internal var request: Job? = null

        /** Completes when the run is over, however it ended. */
        val ended = CompletableDeferred<Unit>()

        /** Delivers every event so far, then each as it comes, on the thread that reports it. */
        fun attach(listener: (Event) -> Unit) {
            val replay = synchronized(this) {
                this.listener = listener
                events.toList().also { events.clear() }
            }
            replay.forEach(listener)
        }

        /** Asks the extension to stop; the run ends when it has, or shortly after if it does not. */
        fun stop() {
            if (ended.isCompleted) return
            owner?.cancel(this)
            // The host ends a cancelled run's tasks itself after ten seconds; this is in case it is gone.
            owner?.endLater(this, "The tests did not stop when asked")
        }

        internal fun taskStarted(taskId: String, name: String) {
            synchronized(this) { openTasks += taskId; anyTask = true }
            emit(Event.TaskStarted(name))
        }

        internal fun taskFinished(taskId: String) {
            val over = synchronized(this) {
                openTasks -= taskId
                openTasks.isEmpty() && !extensionStarted && (repliedOrGaveUp || timedOut)
            }
            if (over) end(null)
        }

        internal fun replied() {
            val over = synchronized(this) { repliedOrGaveUp = true; openTasks.isEmpty() }
            if (over) end(null)
        }

        internal fun replyTimedOut() {
            val over = synchronized(this) { timedOut = true; anyTask && openTasks.isEmpty() }
            if (over) end(null)
        }

        internal fun messages(testId: String, messages: List<Testing.Message>) {
            synchronized(this) { pendingMessages.getOrPut(testId) { mutableListOf() } += messages }
        }

        internal fun stateChanged(testId: String, state: Testing.State, duration: Double?) {
            val messages = synchronized(this) {
                if (state in FINAL) pendingMessages.remove(testId).orEmpty() else emptyList()
            }
            emit(Event.State(testId, state, duration, messages))
        }

        internal fun emit(event: Event) {
            val deliver = synchronized(this) {
                if (ended.isCompleted) return
                listener ?: run { events += event; null }
            }
            deliver?.invoke(event)
        }

        internal fun end(problem: String?) {
            emit(Event.Ended(problem))
            if (ended.complete(Unit)) owner?.ended(this)
        }

        companion object {
            internal fun failed(problem: String): Run =
                Run(null, UUID.randomUUID().toString(), Testing.Group.RUN, emptyList()).also { it.end(problem) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(HostedTesting::class.java)
        private const val ALL = -1

        /** How long a run asked to stop has before it is ended anyway; the host itself takes ten seconds. */
        private const val STOP_GRACE_SECONDS = 15L
        private val FINAL =
            setOf(Testing.State.PASSED, Testing.State.FAILED, Testing.State.ERRORED, Testing.State.SKIPPED)

        fun getInstance(project: Project): HostedTesting = project.service()

        /** The key gutters and results look tests up by, for an item with a local file. */
        fun keyOf(item: Testing.Item): String? =
            item.uri?.takeIf { it.scheme == UriComponents.FILE }
                ?.let { runCatching { UriComponents.key(it.uri) }.getOrNull() }

        private fun Testing.Group.verb() = when (this) {
            Testing.Group.RUN -> "run"
            Testing.Group.DEBUG -> "debug"
            Testing.Group.COVERAGE -> "measure coverage of"
        }
    }
}
