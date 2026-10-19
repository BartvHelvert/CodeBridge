package me.helvert.codebridge.rpc

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * How a long backend operation reaches the settings UI.
 *
 * Every install, uninstall and download the frontend shows progress for is one of these. The
 * contract the UI depends on is narrow and easy to break silently: a failure must arrive as a final
 * update rather than as an exception the RPC layer turns into a dropped connection, and the flow
 * must end exactly once either way.
 */
class BackendOperationTest {

    @Test
    fun `reports progress and then completion`() = runBlocking {
        // Updates are conflated, so a collector that lags sees only the latest; the work holds until
        // this one has seen the progress, as a long download does between its reports.
        val seen = CompletableDeferred<Unit>()
        val updates = mutableListOf<OperationUpdate>()
        operationFlow { progress ->
            progress.text = "Working"
            progress.fraction = 0.5
            withTimeout(10.seconds) { seen.await() }
            null
        }.collect { update ->
            updates += update
            if (update.fraction == 0.5) seen.complete(Unit)
        }

        assertTrue(updates.any { it.text == "Working" }, "Progress text must reach the UI: $updates")
        assertTrue(updates.any { it.fraction == 0.5 }, "Progress fraction must reach the UI: $updates")
        val last = updates.last()
        assertTrue(last.completed)
        assertNull(last.error)
    }

    @Test
    fun `turns a failure into a final update, not a thrown exception`() = runBlocking {
        // The UI shows `error` in place of progress. Letting this escape would surface as a broken
        // RPC call instead of a message the user can read.
        val updates = operationFlow { throw java.io.IOException("Checksum mismatch") }.toList()

        assertEquals("Checksum mismatch", updates.last().error)
        assertFalse(updates.last().completed)
    }

    @Test
    fun `names the exception when it carries no message`() = runBlocking {
        val updates = operationFlow { throw IllegalStateException() }.toList()
        assertEquals("IllegalStateException", updates.last().error)
    }

    @Test
    fun `lets cancellation through`() = runBlocking {
        // Cancellation is not a failure to report: the collector already knows, and reporting it as
        // an error would leave the UI showing a message for something the user did deliberately.
        assertFailsWith<CancellationException> {
            operationFlow { throw CancellationException("stopped") }.toList()
        }
        assertFailsWith<ProcessCanceledException> {
            operationFlow { throw ProcessCanceledException() }.toList()
        }
        Unit
    }

    @Test
    fun `is cold, so nothing runs until it is collected`() = runBlocking {
        var ran = false
        val flow = operationFlow { ran = true; null }
        assertFalse(ran, "Building the flow must not start the work")
        flow.first()
        assertTrue(ran)
    }

    @Test
    fun `deletes a transfer directory and everything under it`() {
        val root = Files.createTempDirectory("codebridge-transfer")
        root.resolve("nested/deeper").createDirectories().resolve("file.vsix").writeText("x")
        root.resolve("top.vsix").writeText("y")

        deleteTransferDirectory(root)

        assertFalse(root.exists(), "The directory itself goes too, not just its contents")
    }
}
