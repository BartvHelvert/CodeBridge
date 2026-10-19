package me.helvert.codebridge.task

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import java.io.OutputStream
import java.util.concurrent.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Process lifecycle tests without an IDE or an external toolchain. */
class TaskProcessSequenceTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val sequences = mutableListOf<TaskProcessSequence>()
    private val watcher = ResolvedTask(
        "watch", TaskCommand.Process("watch"), buildJsonObject {}, isBackground = true,
        background = listOf(TaskBackground(true, Regex("^BEGIN$"), Regex("^READY$")))
    )
    private val build = ResolvedTask("build", TaskCommand.Process("build"), buildJsonObject {})

    private class FakeProcess : ProcessHandler() {
        val started = CountDownLatch(1)
        override fun startNotify() {
            super.startNotify(); started.countDown()
        }

        fun output(text: String) = notifyTextAvailable(text, ProcessOutputTypes.STDERR)
        fun end(code: Int) = notifyProcessTerminated(code)
        override fun destroyProcessImpl() = end(-1)
        override fun detachProcessImpl() = destroyProcessImpl()
        override fun detachIsDefault() = false
        override fun getProcessInput(): OutputStream? = null
    }

    private fun sequence(steps: List<ResolvedTask>, factory: (ResolvedTask) -> ProcessHandler): TaskProcessSequence =
        TaskProcessSequence(steps, factory, CoroutineScope(executor.asCoroutineDispatcher())).also { sequences += it }

    /** Waits for [this] off any coroutine, as the test thread is. */
    private fun <T> Deferred<T>.get(seconds: Long): T = runBlocking { withTimeout(seconds.seconds) { await() } }

    /** What [this] failed with, as opposed to being cancelled; null for neither. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<*>.failure(): Throwable? = getCompletionExceptionOrNull()?.takeIf { it !is CancellationException }

    @AfterEach
    fun cleanup() {
        sequences.filterNot { it.isProcessTerminated }.forEach { it.destroyProcess() }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `watcher readiness releases launch while the process and console stay alive`() {
        val process = FakeProcess()
        val sequence = sequence(listOf(watcher)) { process }
        val launch = CompletableDeferred<Int>()
        TaskProcesses.waitUntilReady(sequence, launch)
        sequence.startNotify()
        assertTrue(process.started.await(5, TimeUnit.SECONDS))
        assertFalse(launch.isCompleted)
        process.output("RE")
        process.output("ADY\n")
        assertEquals(0, launch.get(5))
        assertFalse(process.isProcessTerminated)
        assertFalse(sequence.isProcessTerminated)
        sequence.destroyProcess()
        assertTrue(sequence.waitFor(5000))
        assertTrue(process.isProcessTerminated)
    }

    @Test
    fun `a background dependency becomes ready before the foreground task runs`() {
        val watch = FakeProcess()
        val foreground = FakeProcess()
        val sequence = sequence(listOf(watcher, build)) { if (it.isBackground) watch else foreground }
        sequence.startNotify()
        assertTrue(watch.started.await(5, TimeUnit.SECONDS))
        assertEquals(1, foreground.started.count)
        watch.output("READY\n")
        assertTrue(foreground.started.await(5, TimeUnit.SECONDS))
        assertFalse(sequence.ready.isCompleted)
        foreground.end(0)
        assertEquals(0, sequence.ready.get(5))
        assertFalse(watch.isProcessTerminated)
    }

    @Test
    fun `a background process exiting even successfully before readiness prevents launch`() {
        val watch = FakeProcess()
        val sequence = sequence(listOf(watcher)) { watch }
        sequence.startNotify()
        assertTrue(watch.started.await(5, TimeUnit.SECONDS))
        watch.end(0)
        assertFailsWith<IllegalStateException> { sequence.ready.get(5) }
        assertTrue(sequence.waitFor(5000))
        assertNotEquals(0, sequence.exitCode)
    }

    @Test
    fun `a failed foreground task prevents the next step from starting`() {
        val first = FakeProcess()
        val next = FakeProcess()
        val sequence = sequence(listOf(build, build.copy(name = "next"))) { if (it.name == "build") first else next }
        sequence.startNotify()
        assertTrue(first.started.await(5, TimeUnit.SECONDS))
        first.end(7)
        assertTrue(sequence.waitFor(5000))
        assertEquals(7, sequence.exitCode)
        assertEquals(7, sequence.ready.get(5))
        assertEquals(1, next.started.count)
    }

    @Test
    fun `cancelling the readiness wait stops the watcher and cancels startup`() {
        val watch = FakeProcess()
        val sequence = sequence(listOf(watcher)) { watch }
        val launch = CompletableDeferred<Int>()
        TaskProcesses.waitUntilReady(sequence, launch)
        sequence.startNotify()
        assertTrue(watch.started.await(5, TimeUnit.SECONDS))
        launch.cancel()
        assertTrue(sequence.waitFor(5000))
        assertTrue(watch.isProcessTerminated)
        assertTrue(launch.isCancelled)
        assertTrue(sequence.ready.isCancelled)
    }

    @Test
    fun `watcher failure while a dependent build is running stops the whole group`() {
        val watch = FakeProcess()
        val foreground = FakeProcess()
        val sequence = sequence(listOf(watcher, build)) { if (it.isBackground) watch else foreground }
        sequence.startNotify()
        assertTrue(watch.started.await(5, TimeUnit.SECONDS))
        watch.output("READY\n")
        assertTrue(foreground.started.await(5, TimeUnit.SECONDS))
        watch.end(9)
        assertTrue(sequence.waitFor(5000))
        assertEquals(9, sequence.exitCode)
        assertNotNull(sequence.ready.failure())
        assertTrue(foreground.isProcessTerminated)
    }

    @Test
    fun `process startup errors retain their cause`() {
        val sequence = sequence(listOf(watcher)) { throw IllegalStateException("executable missing") }
        sequence.startNotify()
        val error = assertFailsWith<IllegalStateException> { sequence.ready.get(5) }
        assertEquals("executable missing", error.message)
        assertTrue(sequence.waitFor(5000))
    }

    @Test
    fun `background prelaunch without readiness patterns fails before any process starts`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TaskProcesses.start(java.nio.file.Path.of("."), watcher.copy(background = emptyList()), CompletableDeferred())
        }
        assertContains(error.message!!, "beginsPattern and endsPattern")
    }

    @Test
    fun `foreground readiness waits for exit and preserves its exit code`() {
        val process = FakeProcess()
        val launch = CompletableDeferred<Int>()
        TaskProcesses.waitUntilReady(process, launch)
        process.startNotify()
        process.output("READY\n")
        assertFalse(launch.isCompleted)
        process.end(3)
        assertEquals(3, launch.get(5))
    }

    @Test
    fun `cancelling before process notification prevents any child from starting`() {
        val process = FakeProcess()
        val sequence = sequence(listOf(watcher)) { process }
        val launch = CompletableDeferred<Int>()
        TaskProcesses.waitUntilReady(sequence, launch)
        launch.cancel()
        sequence.startNotify()
        assertTrue(sequence.waitFor(5000))
        assertEquals(1, process.started.count)
    }
}
