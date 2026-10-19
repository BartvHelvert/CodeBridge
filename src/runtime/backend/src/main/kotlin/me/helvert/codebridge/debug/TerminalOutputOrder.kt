package me.helvert.codebridge.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps what a program prints in a terminal ahead of the adapter's word that the program ended.
 *
 * Two streams reach the console: the adapter's messages, and the text of a program it had run in a
 * terminal (`runInTerminal`). The terminal's text lags a little -- some milliseconds through a
 * pseudo-terminal -- so the program's last lines came after the adapter's "Process exited", or
 * after `exited` itself, at which IntelliJ's client stops showing output at all.
 *
 * So while a terminal is in use, the adapter's `output` messages are passed on [OUTPUT_DELAY_MILLIS]
 * late, more than the terminal lags; and from the adapter's `exited` or `terminated` on, nothing more
 * is passed on until the terminal is done ([terminalDone]). What was still waiting -- the "Process
 * exited" before `exited` among it -- then follows the terminal's last lines. The adapter's messages
 * keep their order throughout; with no terminal in use they pass straight through.
 */
class TerminalOutputOrder(
    private val deliver: (String) -> Unit,
    /** Whether a program is running in a terminal for this session. */
    private val terminalInUse: () -> Boolean,
    /** Completes once the terminal's program has been read to the end; null with no terminal. */
    private val terminalDone: () -> Deferred<*>?,
    /** Where held messages are let go of, when they are due or the terminal is done. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val waiting = ArrayDeque<Pair<Long, String>>()

    /** Set from the program's end until the terminal is done. */
    private var holding: Deferred<*>? = null
    private var endSeen = false

    @Synchronized
    fun offer(json: String) {
        if (!endSeen && DebugLaunch.isEnding(json)) {
            endSeen = true
            holding = terminalDone()?.takeUnless { it.isCompleted }?.also { done ->
                scope.launch {
                    done.join()
                    release()
                }
            }
        }
        val delayed = isOutput(json) && terminalInUse()
        if (holding == null && waiting.isEmpty() && !delayed) return deliver(json)
        waiting.add((clock() + if (delayed) OUTPUT_DELAY_MILLIS else 0) to json)
        drain()
    }

    @Synchronized
    private fun release() {
        holding = null
        drain()
    }

    /** Passes on what is due, in order; comes back for the rest when it is. */
    @Synchronized
    private fun drain() {
        while (holding == null) {
            val (due, json) = waiting.peekFirst() ?: return
            val wait = due - clock()
            if (wait > 0) {
                scope.launch {
                    delay(wait.milliseconds)
                    drain()
                }
                return
            }
            waiting.removeFirst()
            deliver(json)
        }
    }

    private fun isOutput(json: String): Boolean {
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
        return (message["type"] as? JsonPrimitive)?.contentOrNull == "event" && (message["event"] as? JsonPrimitive)?.contentOrNull == "output"
    }

    companion object {
        /** More than a pseudo-terminal lags behind the adapter, which is some milliseconds. */
        const val OUTPUT_DELAY_MILLIS = 100L
    }
}
