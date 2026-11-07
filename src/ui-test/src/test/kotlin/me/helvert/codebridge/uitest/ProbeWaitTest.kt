package me.helvert.codebridge.uitest

import com.intellij.driver.model.DriverIllegalStateException
import com.intellij.driver.sdk.WaitForException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.awt.IllegalComponentStateException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProbeWaitTest {
    @Test
    fun `a disappearing component and a lookup timeout can recover`() {
        var reads = 0
        val value = awaitProbeValue("ready component", {
            when (++reads) {
                1 -> throw IllegalComponentStateException("component disposed")
                2 -> throw WaitForException(1.seconds, "not found yet")
                else -> "ready"
            }
        }, interval = 1.milliseconds) { it == "ready" }
        assertEquals("ready", value)
        assertEquals(3, reads)
    }

    @Test
    fun `a timeout retains the last observation and lookup failure`() {
        val failure = WaitForException(1.seconds, "popup not found")
        var reads = 0
        val timeout = assertFailsWith<AssertionError> {
            awaitProbeValue("popup contents", {
                if (++reads == 1) "still loading" else throw failure
            }, timeout = 1.seconds, interval = 1.milliseconds) { it == "ready" }
        }
        assertContains(timeout.message.orEmpty(), "popup contents")
        assertContains(timeout.message.orEmpty(), "still loading")
        assertSame(failure, timeout.cause)
    }

    @Test
    fun `unexpected read failures are not retried`() {
        val failure = IllegalStateException("remote service is broken")
        var reads = 0
        assertSame(failure, assertFailsWith<IllegalStateException> {
            awaitProbeValue<String>("service", { reads++; throw failure }) { true }
        })
        assertEquals(1, reads)
    }

    @Test
    fun `assertions and cancellation escape immediately`() {
        val assertion = AssertionError("wrong result")
        assertSame(assertion, assertFailsWith<AssertionError> {
            awaitProbeValue<String>("service", { throw assertion }) { true }
        })
        val cancellation = CancellationException("test canceled")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            awaitProbeValue<String>("service", { throw cancellation }) { true }
        })
    }

    @Test
    fun `matcher failures are not treated as lookup failures`() {
        val failure = WaitForException(1.seconds, "broken matcher")
        assertSame(failure, assertFailsWith<WaitForException> {
            awaitProbeValue("value", { "value" }) { throw failure }
        })
    }

    @Test
    fun `an expired driver reference can recover`() {
        var reads = 0
        val value = awaitProbeValue("fresh reference", {
            if (++reads == 1) throw DriverIllegalStateException("reference expired") else "fresh"
        }, interval = 1.milliseconds) { it == "fresh" }
        assertEquals("fresh", value)
    }

    @Test
    fun `a service still starting is retried when asked, but not its cancellation`() {
        var reads = 0
        val value = awaitProbeValue("started service", {
            if (++reads == 1) throw IllegalStateException("The extension host is not running") else "started"
        }, interval = 1.milliseconds, retrying = ::isStartupFailure) { it == "started" }
        assertEquals("started", value)
        val cancellation = CancellationException("test canceled")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            awaitProbeValue<String>("service", { throw cancellation }, retrying = ::isStartupFailure) { true }
        })
    }

    @Test
    fun `a null observation after a failed read still matches`() {
        var reads = 0
        assertEquals(null, awaitProbeValue<String?>("removed value", {
            if (++reads == 1) throw WaitForException(1.seconds, "not yet") else null
        }, interval = 1.milliseconds) { it == null })
    }

    @Test
    fun `null is a valid matching observation`() {
        assertEquals(null, awaitProbeValue<String?>("removed value", { null }) { it == null })
    }
}
