package me.helvert.codebridge.testing.ui

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.testing.HostedTesting
import java.io.OutputStream

/**
 * A test run in the shape of a process, which is what the IDE's run tabs and test runner listen to.
 *
 * Nothing is started until the console is listening ([startNotify]): [start] makes or names the run
 * then, and its events are written as the service messages [CodeBridgeTestEvents] makes of them. Stop asks
 * the extension to cancel; the process ends when the run does, failed if any test did.
 */
class CodeBridgeTestProcessHandler(
    private val hasChildren: (String) -> Boolean,
    private val start: () -> HostedTesting.Run,
) : ProcessHandler() {
    private val ansi = AnsiEscapeDecoder()
    private val lock = Any()
    @Volatile
    private var run: HostedTesting.Run? = null
    private var atLineStart = true
    private var failed = false

    override fun startNotify() {
        super.startNotify()
        val events = CodeBridgeTestEvents(hasChildren, ::serviceMessage, ::text)
        synchronized(lock) { events.started() }
        val run = start()
        this.run = run
        run.attach { event ->
            synchronized(lock) {
                if (event is HostedTesting.Run.Event.State && (event.state == Testing.State.FAILED || event.state == Testing.State.ERRORED)) failed =
                    true
                events.accept(event)
            }
            if (event is HostedTesting.Run.Event.Ended) notifyProcessTerminated(if (failed || event.problem != null) 1 else 0)
        }
    }

    /** Each on a line of its own, which is the only place the test runner looks for one. */
    private fun serviceMessage(message: String) {
        if (!atLineStart) notifyTextAvailable("\n", ProcessOutputTypes.STDOUT)
        notifyTextAvailable(message + "\n", ProcessOutputTypes.STDOUT)
        atLineStart = true
    }

    /** Why the run ended, in its own colours. */
    private fun text(text: String) {
        if (text.isEmpty()) return
        ansi.escapeText(text, ProcessOutputTypes.STDOUT) { chunk, key -> notifyTextAvailable(chunk, key) }
        atLineStart = text.endsWith("\n")
    }

    override fun destroyProcessImpl() {
        val running = run
        if (running == null || running.ended.isCompleted) notifyProcessTerminated(1) else running.stop()
    }

    override fun detachProcessImpl() = notifyProcessDetached()
    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = null
}
