package me.helvert.codebridge.debug

import com.intellij.execution.process.KillableProcessHandler
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.util.io.BaseOutputReader
import com.jetbrains.dap.protocol.RunInTerminalRequestArguments
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `runInTerminal` gives the program a terminal. An adapter may attach the program to the terminal
 * itself rather than to standard output -- CodeLLDB does -- and that text must reach the console too.
 */
class RunInTerminalTest {
    private val fixture = IdeaTestFixtureFactory.getFixtureFactory().createBareFixture()

    @BeforeEach
    fun setUp() = fixture.setUp()
    @AfterEach
    fun tearDown() = fixture.tearDown()

    @Test
    fun `what a program writes to its terminal, not just its output, reaches the console`() {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val written = StringBuffer()
        RunInTerminal { text, _ -> written.append(text) }.use { terminal ->
            runBlocking { terminal.run(
                RunInTerminalRequestArguments(
                // The console device and the controlling tty: past standard output, as a debuggee on
                // the terminal's own console would write.
                    args = if (windows) listOf("cmd.exe", "/d", "/c", "echo to-the-console> CON & echo to-stdout")
                    else listOf("/bin/sh", "-c", "echo to-the-console > /dev/tty; echo to-stdout")
                )
            ) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline && !(written.contains("to-the-console") && written.contains("to-stdout"))) Thread.sleep(
                50
            )
        }
        assertTrue(written.contains("to-the-console"), "The terminal's own text arrives: '$written'")
        assertTrue(written.contains("to-stdout"), "Standard output arrives: '$written'")
    }

    @Test
    fun `the session's end waits for what the terminal prints, not for what lingers in it`() {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val written = StringBuffer()
        RunInTerminal { text, _ -> written.append(text) }.use { terminal ->
            // Prints at once, then lingers, as CodeLLDB's helper stays until the session is disconnected.
            runBlocking { terminal.run(
                RunInTerminalRequestArguments(
                    args = if (windows) listOf("cmd.exe", "/d", "/c", "echo last-words && ping -n 6 127.0.0.1 >nul")
                    else listOf("/bin/sh", "-c", "echo last-words && sleep 5")
                )
            ) }
            val started = System.nanoTime()
            runBlocking { withTimeout(10.seconds) { terminal.finished().await() } }
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(written.contains("last-words"), "Read before the session ends: '$written'")
            assertTrue(waited < 2_500, "Done once the terminal went quiet, not when the process ends: ${waited}ms")
        }
    }

    @Test
    fun `a terminal that is slow to say its first word is not taken as quiet`() {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val written = StringBuffer()
        RunInTerminal { text, _ -> written.append(text) }.use { terminal ->
            // Silent for a second, as `cmd` starting on a slow runner's pseudo-terminal was, then lingers.
            runBlocking { terminal.run(
                RunInTerminalRequestArguments(
                    args = if (windows) listOf("cmd.exe", "/d", "/c", "ping -n 2 127.0.0.1 >nul && echo late-words && ping -n 6 127.0.0.1 >nul")
                    else listOf("/bin/sh", "-c", "sleep 1 && echo late-words && sleep 5")
                )
            ) }
            runBlocking { withTimeout(10.seconds) { terminal.finished().await() } }
            assertTrue(written.contains("late-words"), "Read before the session ends: '$written'")
        }
    }

    @Test
    fun `a shown terminal takes what the user types, and its text stays out of the console`() {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val console = StringBuffer()
        val shown = StringBuffer()
        var tab: String? = null
        var program: com.intellij.execution.process.KillableProcessHandler? = null
        val show: suspend (String, com.intellij.execution.process.KillableProcessHandler) -> Unit = { name, process ->
            tab = name
            program = process
            process.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(
                    event: com.intellij.execution.process.ProcessEvent,
                    outputType: com.intellij.openapi.util.Key<*>
                ) {
                    shown.append(event.text)
                }
            })
            process.startNotify()
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }
        RunInTerminal("Session", show) { text, _ -> console.append(text) }.use { terminal ->
            val response = runBlocking { terminal.run(
                RunInTerminalRequestArguments(
                    title = "Program",
                // Waits for a line, as an interactive program would.
                    args = if (windows) listOf("cmd.exe", "/d", "/c", "set /p answer= & call echo got-%answer%")
                    else listOf("/bin/sh", "-c", $$"read answer; echo got-$answer")
                )
            ) }
            assertTrue(response.processId != null, "The program's process id is reported")
            assertEquals("Program", tab)
            assertTrue(!terminal.inUse, "Nothing in the console waits on a shown terminal")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            // As the terminal console sends a typed line: to the program's input, Enter as CR.
            program!!.processInput.apply { write("typed\r".toByteArray()); flush() }
            while (System.nanoTime() < deadline && !shown.contains("got-typed")) Thread.sleep(50)
        }
        assertTrue(shown.contains("got-typed"), "What was typed reached the program: '$shown'")
        assertEquals("", console.toString(), "The terminal's text is not repeated in the console")
    }

    @Test
    fun `a program that has just finished is read to the end when the session closes`() {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val written = StringBuffer()
        // Closed the moment the program exits, as the session ends when the adapter sees a short
        // program exit -- and the terminal's text can come after that. Not closed before: a program
        // still running after the grace is stopped, and on a slow runner starting `cmd` and its
        // second of `ping` outlasted the grace, leaving only `^C`.
        RunInTerminal { text, _ -> written.append(text) }.use { terminal ->
            val response = runBlocking { terminal.run(
                RunInTerminalRequestArguments(
                    args = if (windows) listOf("cmd.exe", "/d", "/c", "ping -n 2 127.0.0.1 >nul && echo last-words")
                    else listOf("/bin/sh", "-c", "sleep 0.5 && echo last-words")
                )
            ) }
            val program = ProcessHandle.of(requireNotNull(response.processId).toLong())
            program.ifPresent { it.onExit().get(20, TimeUnit.SECONDS) }
        }
        assertTrue(written.contains("last-words"), "What it printed arrives: '$written'")
    }

    /**
     * On a slow Windows runner the pseudo-terminal delivered a finished program's last line half a
     * second after the grace, and closing lost it. A program that has exited is read to the end.
     */
    @Test
    fun `closing reads a program that has exited to the end, however late its text arrives`() {
        val written = StringBuffer()
        RunInTerminal { text, _ -> written.append(text) }.use { terminal ->
            terminal.watch(exitedWithLateOutput("last-words", lateMillis = 3_000), "late")
        }
        assertTrue(written.contains("last-words"), "What it printed arrives: '$written'")
    }

    @Test
    fun `the session's end waits for what a program that has exited is still printing`() {
        val written = StringBuffer()
        RunInTerminal { text, _ -> written.append(text) }.use { terminal ->
            terminal.watch(exitedWithLateOutput("last-words", lateMillis = 1_000), "late")
            runBlocking { withTimeout(20.seconds) { terminal.finished().await() } }
            assertTrue(written.contains("last-words"), "The program's text comes before the session's end: '$written'")
        }
    }

    /**
     * A program that has already exited, whose terminal delivers [text] [lateMillis] later, and only
     * then reports the end of its output. Read blocking, as a pseudo-terminal is.
     */
    private fun exitedWithLateOutput(text: String, lateMillis: Long): KillableProcessHandler {
        val stdout = PipedInputStream()
        val terminal = PipedOutputStream(stdout)
        thread(isDaemon = true) {
            Thread.sleep(lateMillis)
            terminal.use { it.write(text.toByteArray()) }
        }
        val process = object : Process() {
            override fun getInputStream(): InputStream = stdout
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun isAlive() = false
            override fun destroy() = Unit
        }
        return object : KillableProcessHandler(process, "late") {
            override fun readerOptions(): BaseOutputReader.Options = BaseOutputReader.Options.forTerminalPtyProcess()

            // There is no process to stop; stopping it is what loses the text, which the test asserts.
            override fun destroyProcessImpl() = Unit
        }
    }
}
