package me.helvert.codebridge.uitest

import com.intellij.driver.model.DriverIllegalStateException
import com.intellij.driver.sdk.WaitForException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.IllegalComponentStateException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Whether strict timing budgets are enforced: only on a controlled runner, with `-PuiTestPerformance=true`. */
internal val enforcePerformanceBudgets: Boolean get() = java.lang.Boolean.getBoolean("codebridge.ui.test.performance")

/**
 * A lookup that may succeed later: the driver's lookup timed out, or a component, or the driver's
 * reference to it, went away while read.
 */
internal fun isLookupFailure(error: Exception): Boolean =
    error is WaitForException || error is IllegalComponentStateException || error is DriverIllegalStateException

/**
 * Any failure but cancellation: for reads of a service that is still starting, such as a command of
 * an extension host that is not yet running, or of an extension not yet activated.
 */
internal fun isStartupFailure(error: Exception): Boolean = error !is CancellationException

/**
 * Polls [read] until it [matches]: observations only; issue commands and clicks before calling it.
 * A read failing with an exception [retrying] accepts is tried again, and is the cause of the
 * timeout's failure if it was the last; any other failure escapes at once.
 */
internal fun <T> awaitProbeValue(
    description: String,
    read: () -> T,
    timeout: Duration = 90.seconds,
    interval: Duration = 500.milliseconds,
    retrying: (Exception) -> Boolean = ::isLookupFailure,
    matches: (T) -> Boolean,
): T = runBlocking {
    require(timeout.isFinite() && timeout > Duration.ZERO)
    require(interval.isFinite() && interval > Duration.ZERO)
    val started = TimeSource.Monotonic.markNow()
    var last: T? = null
    var lastFailure: Exception? = null
    while (true) {
        val observed = try {
            read().also { last = it }
            true
        } catch (error: Exception) {
            if (!retrying(error)) throw error
            lastFailure = error
            false
        }
        @Suppress("UNCHECKED_CAST") // Observed, so [last] holds what [read] returned.
        if (observed && matches(last as T)) return@runBlocking last as T
        val remaining = timeout - started.elapsedNow()
        if (remaining <= Duration.ZERO) {
            throw AssertionError("Timed out waiting for $description after $timeout; last observed: $last", lastFailure)
        }
        delay(minOf(interval, remaining))
    }
    @Suppress("UNREACHABLE_CODE")
    error("unreachable")
}
