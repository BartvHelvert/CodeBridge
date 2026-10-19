package me.helvert.codebridge.webview

import kotlinx.coroutines.*
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a page sends reaches its extension in the order it was sent, as in VS Code. */
class WebviewCallsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cancelScope() = scope.cancel()

    /** A backend whose calls take as long as each says: the time on the way, and in its queue. */
    private class Backend {
        val arrived: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        suspend fun message(number: Int, takes: Duration) {
            delay(takes)
            arrived += number
        }
    }

    @Test
    fun `a call is made only once the one before it is done`() = runBlocking {
        val backend = Backend()
        val calls = WebviewCalls(scope) { backend }
        calls.call { message(1, takes = 200.milliseconds) }
        calls.call { message(2, takes = Duration.ZERO) }
        calls.call { message(3, takes = Duration.ZERO) }

        withTimeout(5.seconds) { while (backend.arrived.size < 3) delay(10.milliseconds) }
        assertEquals(listOf(1, 2, 3), backend.arrived.toList())
    }

    @Test
    fun `a call made apart, such as a command that opens a dialog, holds up nothing`() = runBlocking {
        val backend = Backend()
        val calls = WebviewCalls(scope) { backend }
        calls.callApart { message(1, takes = 1.seconds) }
        calls.call { message(2, takes = Duration.ZERO) }

        withTimeout(5.seconds) { while (backend.arrived.isEmpty()) delay(10.milliseconds) }
        assertEquals(listOf(2), backend.arrived.toList())
    }

    @Test
    fun `a call that times out does not stop those after it`() = runBlocking {
        val backend = Backend()
        val calls = WebviewCalls(scope) { backend }
        calls.call { throw CancellationException("timed out") }
        calls.call { message(2, takes = Duration.ZERO) }

        withTimeout(5.seconds) { while (backend.arrived.isEmpty()) delay(10.milliseconds) }
        assertEquals(listOf(2), backend.arrived.toList())
    }

    @Test
    fun `a call that throws does not stop those after it`() = runBlocking {
        val backend = Backend()
        val calls = WebviewCalls(scope) { backend }
        calls.call { error("lost") }
        calls.call { message(2, takes = Duration.ZERO) }

        withTimeout(5.seconds) { while (backend.arrived.isEmpty()) delay(10.milliseconds) }
        assertEquals(listOf(2), backend.arrived.toList())
    }
}
