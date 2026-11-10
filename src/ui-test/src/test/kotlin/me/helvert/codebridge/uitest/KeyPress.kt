package me.helvert.codebridge.uitest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Presses a key until it arrives: [press] it, and if [landed] has not become true within
 * [between], press it again, for at most [timeout] in all.
 *
 * A keystroke is not delivered just because it was sent. While the IDE waits on a lock it draws a
 * freeze overlay, and for as long as that is up it takes every key press out of the event queue
 * and never puts it back -- by design, so a freeze does not end in a burst of input the user has
 * stopped meaning. The driver's robot posts into that same queue, so its keys go the same way.
 * Typing survives it, because a character carries a `KEY_TYPED` that is left alone and the editor
 * inserts from that one; a key with no character, Escape among them, is taken whole and leaves no
 * trace anywhere.
 *
 * So pressing once and waiting is not enough, and nor is waiting longer: the key is gone, not
 * late. Pressing again is what a user does when the IDE has eaten one. A key that arrives and does
 * nothing still fails, at [timeout], having pressed rather than waited -- this hides no bug in
 * what the key was meant to do.
 *
 * [between] has to outlast what the key sets off, or a slow success is pressed over: a second
 * press once the first has been acted on is a keystroke the test never meant to send.
 */
internal fun pressUntil(
    description: String,
    timeout: Duration = 90.seconds,
    between: Duration = 15.seconds,
    interval: Duration = 500.milliseconds,
    press: () -> Unit,
    landed: () -> Boolean,
) = runBlocking {
    require(timeout.isFinite() && timeout > Duration.ZERO)
    require(between.isFinite() && between > Duration.ZERO)
    val started = TimeSource.Monotonic.markNow()
    var presses = 0
    var lastFailure: Exception? = null
    while (true) {
        press()
        presses++
        val until = TimeSource.Monotonic.markNow()
        while (until.elapsedNow() < between) {
            val arrived = try {
                landed()
            } catch (error: Exception) {
                if (!isLookupFailure(error)) throw error
                lastFailure = error
                false
            }
            if (arrived) return@runBlocking
            if (started.elapsedNow() >= timeout) {
                throw AssertionError(
                    "Timed out waiting for $description after $timeout, pressed $presses time(s)",
                    lastFailure,
                )
            }
            delay(interval)
        }
    }
}
