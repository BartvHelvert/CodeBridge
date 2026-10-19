package me.helvert.codebridge.ide

import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.workbench.HostedProgress
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bookkeeping behind `window.withProgress`.
 *
 * Each handle the extension opens becomes an IntelliJ background task that runs until the extension
 * says it is done, so what matters here is that every handle is eventually let go: one left open
 * leaves a task turning in the background for the life of the project, which is a worse outcome
 * than never having shown it.
 */
class ProgressTest : EditorFixture() {

    @Test
    fun `a started progress is tracked until the extension ends it`() = onEdt {
        start(1, """{"location":15,"title":"Downloading","cancellable":true}""")
        assertEquals(setOf(1), progress.active())

        send($$"$progressEnd", "[1]")
        assertTrue(progress.active().isEmpty())
    }

    @Test
    fun `the same handle twice is one progress`() = onEdt {
        // The host reuses handles once they are ended; it must never open two tasks for one.
        start(2, """{"title":"Indexing"}""")
        start(2, """{"title":"Indexing"}""")
        assertEquals(setOf(2), progress.active())
        send($$"$progressEnd", "[2]")
    }

    @Test
    fun `progress with neither a title nor a source still starts`() = onEdt {
        // VS Code allows it; IntelliJ needs something to put on the task.
        start(3, "{}")
        assertEquals(setOf(3), progress.active())
        send($$"$progressEnd", "[3]")
    }

    @Test
    fun `a report for a handle nobody started is ignored`() = onEdt {
        send($$"$progressReport", """[99,{"message":"stray","increment":10}]""")
        assertTrue(progress.active().isEmpty())
    }

    @Test
    fun `a malformed call does not take the handler down with it`() = onEdt {
        // Every one of these arrives on the RPC reader, where an exception would end the session.
        send($$"$startProgress", "not json")
        send($$"$progressReport", "[]")
        assertTrue(progress.active().isEmpty())
    }

    @Test
    fun `losing the host ends everything it had started`() = onEdt {
        // Nothing is going to report these finished now.
        start(4, """{"title":"Downloading"}""")
        start(5, """{"title":"Indexing"}""")

        progress.hostStopped()

        assertTrue(progress.active().isEmpty())
    }

    @Test
    fun `the plugin's own progress is held until it ends, and ending twice is harmless`() = onEdt {
        // Startup has several ways to finish -- activation settling, failing, timing out -- and
        // they can overlap; none of them may leave the task turning or trip over another.
        val startup = progress.own("Starting rust-analyzer")
        startup.text("Launching the extension host")
        assertEquals(1, progress.ownActive())

        startup.end()
        startup.end()
        assertEquals(0, progress.ownActive())
    }

    @Test
    fun `losing the host also ends the plugin's own progress`() = onEdt {
        // A host that dies mid-start will never finish activating, so nothing else would end it.
        progress.own("Starting rust-analyzer")

        progress.hostStopped()

        assertEquals(0, progress.ownActive())
    }

    private fun start(handle: Int, options: String) = send($$"$startProgress", "[$handle,$options,\"fixture\"]")

    private fun send(method: String, args: String) =
        progress.handle(MainThreadCall("MainThreadProgress", method, args))

    private val progress get() = HostedProgress.getInstance(fixture.project)
}
