package me.helvert.codebridge.task

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** A task group stays alive while its watchers do; launch readiness is independent of its exit. */
internal class TaskProcessSequence(
    private val steps: List<ResolvedTask>,
    private val create: (ResolvedTask) -> ProcessHandler,
    /** Where the steps are run, one after another, for as long as the group lives; it ends itself. */
    private val scope: CoroutineScope,
) : ProcessHandler() {
    /** The code once every step has started -- a background one, reported ready -- or the first to fail. */
    val ready = CompletableDeferred<Int>()
    private val children = CopyOnWriteArrayList<ProcessHandler>()
    private val stopped = CompletableDeferred<Int>()
    private val watcherExit = CompletableDeferred<Int>()
    @Volatile
    private var current: ProcessHandler? = null

    override fun startNotify() {
        super.startNotify()
        scope.launch { run() }
    }

    /** The code of whichever comes first: [first], when given, then a Stop, then a watcher's exit. */
    private suspend fun next(first: Deferred<Int>? = null): Int = select {
        first?.onAwait { it }
        stopped.onAwait { it }
        watcherExit.onAwait { it }
    }

    private suspend fun run() {
        var code = 1
        try {
            for (task in steps) {
                if (stopped.isCompleted || watcherExit.isCompleted) {
                    code = next()
                    return
                }
                notifyTextAvailable("> Task '${task.name}'\n", ProcessOutputTypes.SYSTEM)
                val child = create(task)
                children += child
                current = child
                val finished = CompletableDeferred<Int>()
                val monitor = TaskBackgroundMonitor(task.background)
                val background = task.isBackground && task.background.isNotEmpty()
                child.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        if (outputType != ProcessOutputTypes.SYSTEM) {
                            monitor.output(outputType, event.text)
                            notifyTextAvailable(event.text, outputType)
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        monitor.flush()
                        finished.complete(event.exitCode)
                        if (background) {
                            monitor.ready.completeExceptionally(
                                IllegalStateException(
                                    "Background task '${task.name}' exited with code ${event.exitCode} before reporting ready"
                                )
                            )
                            watcherExit.complete(if (event.exitCode == 0 && !ready.isCompleted) 1 else event.exitCode)
                        }
                    }
                })
                if (stopped.isCompleted) child.destroyProcess()
                child.startNotify()
                code = if (background) select {
                    monitor.ready.onAwait { 0 }
                    stopped.onAwait { it }
                    watcherExit.onAwait { it }
                } else next(finished)
                if (code != 0 && !task.isBackground && !stopped.isCompleted && !watcherExit.isCompleted) ready.complete(code)
                if (code != 0 || stopped.isCompleted || watcherExit.isCompleted) return
            }
            ready.complete(0)
            // With no background children, completion and readiness coincide. A watcher keeps its
            // Run tab and process alive until Stop or until it exits by itself.
            if (children.any { !it.isProcessTerminated }) code = next()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notifyTextAvailable("${error.message}\n", ProcessOutputTypes.STDERR)
            ready.completeExceptionally(error)
            code = 1
        } finally {
            children.filterNot { it.isProcessTerminated }.forEach { it.destroyProcess() }
            if (!ready.isCompleted) {
                if (stopped.isCompleted) ready.cancel()
                else ready.completeExceptionally(IllegalStateException("Task stopped before reporting ready (exit code $code)"))
            }
            notifyProcessTerminated(code)
        }
    }

    override fun destroyProcessImpl() {
        stopped.complete(-1)
        ready.cancel()
        children.filterNot { it.isProcessTerminated }.forEach { it.destroyProcess() }
    }

    override fun detachProcessImpl() = destroyProcessImpl()
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = current?.processInput
}
