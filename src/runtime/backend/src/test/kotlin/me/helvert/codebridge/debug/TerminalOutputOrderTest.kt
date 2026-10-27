package me.helvert.codebridge.debug

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A program's terminal text reaches the console ahead of the adapter's word that it ended. */
class TerminalOutputOrderTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val scope = CoroutineScope(scheduler.asCoroutineDispatcher())
    @AfterTest fun stop() { scheduler.shutdownNow() }

    private fun output(text: String) = """{"seq":1,"type":"event","event":"output","body":{"category":"console","output":"$text"}}"""
    private fun event(name: String) = """{"seq":1,"type":"event","event":"$name","body":{}}"""
    private fun label(json: String) = Regex("\"output\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)
        ?: Regex("\"event\":\"([^\"]*)\"").find(json)!!.groupValues[1]

    @Test
    fun `the program's last lines come before the adapter's 'Process exited', exited and terminated`() {
        val console = CopyOnWriteArrayList<String>()
        val terminalDone = CompletableDeferred<Unit>()
        val order = TerminalOutputOrder({ console += label(it) }, { true }, { terminalDone }, scope)

        order.offer(output("Launching"))
        awaitSize(console, 1)
        assertEquals(listOf("Launching"), console, "The adapter's output is shown, a moment late")

        // As CodeLLDB ends a run, with the terminal's text a few milliseconds behind it.
        order.offer(output("Process exited"))
        order.offer(event("exited"))
        order.offer(event("terminated"))
        Thread.sleep(15)
        console += "Greetings"
        terminalDone.complete(Unit)

        awaitSize(console, 5)
        assertEquals(listOf("Launching", "Greetings", "Process exited", "exited", "terminated"), console)
    }

    /** Waits for [list] to reach [size]: on a busy CI runner the delayed output can come well after it is due. */
    private fun awaitSize(list: List<String>, size: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (list.size < size && System.nanoTime() < deadline) Thread.sleep(20)
    }

    @Test
    fun `without a terminal everything passes straight through`() {
        val console = CopyOnWriteArrayList<String>()
        val order = TerminalOutputOrder({ console += label(it) }, { false }, { null }, scope)
        order.offer(output("Process exited"))
        order.offer(event("exited"))
        order.offer(event("terminated"))
        assertEquals(listOf("Process exited", "exited", "terminated"), console)
    }
}
