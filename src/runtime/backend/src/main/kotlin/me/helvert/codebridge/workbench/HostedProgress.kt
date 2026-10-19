package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Progress
import me.helvert.codebridge.progress.withRawProgress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MainThreadProgress`, drawn as IntelliJ background tasks.
 *
 * Extensions wrap anything slow in `window.withProgress` -- indexing a workspace, downloading a
 * toolchain, waiting for a server to come up. Unanswered, those calls succeed silently and the work
 * happens with nothing on screen, which is the state in which a user decides the plugin is hung.
 * `RealExtensionSurveyTest` records eleven of these from CodeLLDB's activation alone.
 *
 * One background task per progress handle. The task blocks until the extension reports it finished,
 * which is what makes IntelliJ's own indicator the thing the extension is driving: cancelling it
 * cancels the extension's token rather than just hiding the bar.
 */
@Service(Service.Level.PROJECT)
class HostedProgress(private val project: Project, private val scope: CoroutineScope) {

    internal class Running(val latch: CountDownLatch) {
        @Volatile var text: String = ""
        /** Null until the extension reports an increment; VS Code progress is indeterminate by default. */
        @Volatile var fraction: Double? = null
    }

    private val running = ConcurrentHashMap<Int, Running>()
    /** Progress the plugin shows for itself; no extension handle names these. */
    private val own = ConcurrentHashMap.newKeySet<Running>()
    @Volatile private var session: ExtensionHostSession? = null

    /**
     * Progress this plugin reports for work of its own, such as starting the host.
     *
     * The same background task an extension's progress gets, so the user sees one continuous
     * account: this one covers the seconds before any extension is running to report anything,
     * and the extension's own progress takes over from there.
     */
    inner class Own internal constructor(private val task: Running) {
        fun text(text: String) {
            task.text = text
        }

        /** Idempotent: the paths that end startup overlap. */
        fun end() {
            if (own.remove(task)) task.latch.countDown()
        }
    }

    fun own(title: String): Own {
        val task = Running(CountDownLatch(1))
        own.add(task)
        show(title, cancellable = false, task) {}
        return Own(task)
    }

    fun hostStarted(session: ExtensionHostSession) {
        this.session = session
    }

    /** The progress an extension has started and not yet ended, for tests and diagnosis. */
    fun active(): Set<Int> = running.keys.toSet()

    /** How many of this plugin's own progress tasks are still showing, for the same reasons. */
    fun ownActive(): Int = own.size

    /** Nothing will report these finished now, so release the tasks rather than leave them turning. */
    fun hostStopped() {
        session = null
        running.keys.toList().forEach(::end)
        own.toList().forEach { Own(it).end() }
    }

    /**
     * Handles one `MainThreadProgress` call.
     *
     * The caller routes by service name, so everything here is consumed and nothing returns a value.
     */
    fun handle(call: MainThreadCall) {
        try {
            when (call.method) {
                Progress.START -> start(Progress.start(call.argsJson))
                Progress.REPORT -> report(Progress.step(call.argsJson))
                Progress.END -> end(Progress.handle(call.method, call.argsJson))
            }
        } catch (failure: Exception) {
            LOG.warn("Could not read ${call.service}.${call.method}", failure)
        }
    }

    /**
     * `$startProgress(handle, options, extensionId)`.
     *
     * `options.location` says where VS Code would have drawn it -- a notification, the window bar,
     * a view. IntelliJ has one background-task surface that suits all three, so the location is not
     * read; only the title and whether the extension offered to be cancelled are.
     */
    private fun start(started: Progress.Start) {
        val handle = started.handle
        if (running.containsKey(handle)) return
        val task = Running(CountDownLatch(1))
        running[handle] = task
        show(started.title ?: DEFAULT_TITLE, started.cancellable, task) { cancel(handle) }
    }

    /** One background task that lasts until [task] is counted down, or the user cancels it. */
    private fun show(title: String, cancellable: Boolean, task: Running, cancelled: () -> Unit) {
        // Launched rather than run by the caller: this arrives on the RPC reader, which must not
        // wait on a latch only a later call from the same reader can count down.
        scope.launch {
            try {
                withBackgroundProgress(project, title, cancellable) {
                    withRawProgress { reporter ->
                        // Polled rather than signalled: the same wait ends the task, notices the user
                        // cancelling, and keeps the progress text in step with what the extension says.
                        while (task.latch.count > 0) {
                            reporter.text(task.text)
                            reporter.fraction(task.fraction)
                            delay(POLL_MILLIS.milliseconds)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                cancelled()
                throw cancellation
            }
        }
    }

    /** `$progressReport(handle, { message?, increment?, total? })`, where increments accumulate. */
    private fun report(step: Progress.Step) {
        val task = running[step.handle] ?: return
        step.message?.let { task.text = it }
        val increment = step.increment ?: return
        // VS Code reports percentages of the whole, one slice at a time.
        task.fraction = ((task.fraction ?: 0.0) + increment / 100.0).coerceIn(0.0, 1.0)
    }

    private fun end(handle: Int) {
        running.remove(handle)?.latch?.countDown()
    }

    /**
     * Tells the extension its token was cancelled, so the work actually stops.
     *
     * Without this the indicator would go away and the extension would keep going, which is worse
     * than never offering to cancel it.
     */
    private fun cancel(handle: Int) {
        val call = session ?: return run { running.remove(handle) }
        if (running.remove(handle) == null) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                call.call("ExtHostProgress", $$"$acceptProgressCanceled", "[$handle]")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not cancel extension progress $handle", failure)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(HostedProgress::class.java)

        /** Short enough that cancelling feels immediate, long enough to cost nothing while idle. */
        private const val POLL_MILLIS = 100L

        /** VS Code allows progress with no title at all; IntelliJ needs something to show. */
        private const val DEFAULT_TITLE = "VS Code extension"

        fun getInstance(project: Project): HostedProgress = project.service()
    }
}
