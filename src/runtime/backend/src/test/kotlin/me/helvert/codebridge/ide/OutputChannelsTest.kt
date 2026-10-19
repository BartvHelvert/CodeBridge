package me.helvert.codebridge.ide

import com.intellij.testFramework.PlatformTestUtil
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.workbench.HostedOutputChannels
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Output channels, read the way the extension host actually publishes them.
 *
 * None of this works the way it looks. The host never sends the text: it writes a log file of its
 * own and says how far it has got, so everything drawn here comes from re-reading that file at an
 * offset. Get the offset wrong and the panel repeats itself or silently stops -- both of which read
 * as "the extension isn't logging anything", which is the opposite of what this exists to show.
 */
class OutputChannelsTest : EditorFixture() {

    private class Recorder : HostedOutputChannels.View {
        val drawn = StringBuilder()
        var channels: List<HostedOutputChannels.Channel> = emptyList()
        var selected: String? = null

        override fun refresh(channels: List<HostedOutputChannels.Channel>) {
            this.channels = channels
        }

        override fun select(channelId: String) {
            selected = channelId
        }

        override fun append(channel: HostedOutputChannels.Channel, text: String, reset: Boolean) {
            if (reset) drawn.setLength(0)
            drawn.append(text)
        }
    }

    private val recorder = Recorder()

    @BeforeEach
    fun attachRecorder() = onEdt {
        output.hostStopped()
        output.attach(recorder)
    }

    @Test
    fun `registering answers with an id the later calls name the channel by`() = onEdt {
        val id = register("Server log")

        // A bare JSON string, not an object holding one: the host assigns the reply to the id.
        assertEquals("\"$id\"", raw)
        assertTrue(id.isNotEmpty())
        assertEquals(listOf("Server log"), output.channels().map { it.name })
        drain()
        assertEquals(listOf("Server log"), recorder.channels.map { it.name })
    }

    @Test
    fun `an update draws only what was written since the last one`() = onEdt {
        log.writeText("first line\n")
        val id = register("Server log")

        update(id)
        assertEquals("first line\n", drawn())

        log.appendText("second line\n")
        update(id)
        assertEquals("first line\nsecond line\n", drawn(), "The first line must not be drawn twice")
    }

    @Test
    fun `becoming visible draws what was written while nobody was looking`() = onEdt {
        // The host only flushes the visible channel, so everything before that is backlog sitting
        // in the file. Losing it would hide exactly the startup logging a user opens this to read.
        log.writeText("started before anyone looked\n")
        val id = register("Server log")

        output.show(id)

        assertEquals("started before anyone looked\n", drawn())
    }

    @Test
    fun `clearing removes what was drawn and draws nothing in its place`() = onEdt {
        log.writeText("noise\n")
        val id = register("Server log")
        update(id)

        // `till` is where the channel stood before the clear: everything up to it is discarded.
        update(id, mode = 3, till = Files.size(log))

        assertEquals("", drawn())
    }

    @Test
    fun `replacing draws only what came after the point it replaced`() = onEdt {
        log.writeText("old\n")
        val id = register("Server log")
        update(id)
        val till = Files.size(log)
        log.appendText("new\n")

        update(id, mode = 2, till = till)

        assertEquals("new\n", drawn())
    }

    @Test
    fun `a file that shrank under us is read again rather than from beyond its end`() = onEdt {
        log.writeText("a long first run\n")
        val id = register("Server log")
        update(id)
        // Read before the rewrite, or there is nothing to have shrunk from: the read is not the
        // test's to order, and only a slow CI runner used to get there in time.
        assertEquals("a long first run\n", drawn())

        log.writeText("short\n")
        update(id)

        assertEquals("short\n", drawn())
    }

    @Test
    fun `disposing a channel takes it out of the panel`() = onEdt {
        val id = register("Server log")
        drain()

        output.handle(call($$"$dispose", "[\"$id\"]"))
        drain()

        assertTrue(output.channels().isEmpty())
        assertTrue(recorder.channels.isEmpty())
    }

    @Test
    fun `an extension's own log is a tab, and follows its file while it is showing`() = onEdt {
        // The host writes these itself and never sends `$update` for them, so nothing but looking
        // at the file will ever draw a line of one.
        log.writeText("activating\n")
        output.logRegistered("file:///logs/probe", "Probe Extension", log)
        drain()
        assertEquals(listOf("Probe Extension"), recorder.channels.map { it.name })
        val id = recorder.channels.single().id

        output.show(id)
        assertEquals("activating\n", drawn(), "Showing it draws what was written before")

        log.appendText("server started\n")
        // Longer than one poll, and no `$update` in between.
        Thread.sleep(LOG_POLL_WAIT_MILLIS)
        assertEquals("activating\nserver started\n", drawn())
    }

    @Test
    fun `a log whose logger goes away leaves the panel`() = onEdt {
        output.logRegistered("file:///logs/probe", "Probe Extension", log)
        output.logRegistered("file:///logs/probe", "Probe Extension", log)
        drain()
        assertEquals(1, recorder.channels.size, "Registered twice is still one tab")

        output.logDeregistered("file:///logs/probe")
        drain()

        assertTrue(recorder.channels.isEmpty())
    }

    @Test
    fun `a closing panel does not detach the one that replaced it`() = onEdt {
        // Panels now live as long as their tool window, so a new one can attach before the old one
        // is disposed; the old one's disposal must not leave the new one blank.
        val newer = Recorder()
        output.attach(newer)
        output.detach(recorder)
        register("Server log")
        drain()
        assertEquals(listOf("Server log"), newer.channels.map { it.name })

        output.detach(newer)
        register("Another log")
        drain()
        assertEquals(listOf("Server log"), newer.channels.map { it.name }, "A detached panel hears nothing")
    }

    private fun register(name: String): String {
        if (!Files.exists(log)) log.writeText("")
        val uri = """{"scheme":"file","authority":"","path":"/${
            log.toAbsolutePath().toString().replace('\\', '/').trimStart('/')
        }"}"""
        raw = output.handle(call($$"$register", """["$name",$uri,null,"fixture.extension"]""")) ?: ""
        return raw.trim('"')
    }

    private fun update(id: String, mode: Int = 1, till: Long? = null) {
        output.handle(call($$"$update", "[\"$id\",$mode${if (till == null) "" else ",$till"}]"))
    }

    private fun call(method: String, args: String) = MainThreadCall("MainThreadOutputService", method, args)

    /**
     * The text the panel holds, once the reader thread and the event queue have caught up.
     *
     * Reads happen off the editor thread and are handed back to it, and this test *is* the editor
     * thread, so the queue has to be pumped rather than waited on.
     */
    private fun drawn(): String {
        // A fixed settle rather than a wait for something to appear: half of these assert that
        // nothing was drawn, and a wait that gives up early would pass those for the wrong reason.
        repeat(SETTLE_ROUNDS) { drain() }
        return recorder.drawn.toString()
    }

    private fun drain() {
        Thread.sleep(SETTLE_MILLIS)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private val output get() = HostedOutputChannels.getInstance(fixture.project)

    private var raw = ""

    private val log: Path by lazy { Files.createTempDirectory("codebridge-output").resolve("1-server.log") }

    private companion object {
        /** One local file read, handed back through the event queue; 300ms is generous for that. */
        const val SETTLE_ROUNDS = 15
        const val SETTLE_MILLIS = 20L

        /** Past the half-second a showing log is polled at, with room to spare. */
        const val LOG_POLL_WAIT_MILLIS = 1_200L
    }
}
