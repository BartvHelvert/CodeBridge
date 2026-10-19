package me.helvert.codebridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

/**
 * [call], started on the calling thread and handed back as a future to wait on.
 *
 * As the workbench dispatcher starts a host's call: what runs before the first suspension has run
 * by the time this returns, so a test can look at what the call did at once. Waiting is left to the
 * test, which on the editor thread has to keep dispatching events while it waits.
 */
fun <T> started(call: suspend () -> T): CompletableFuture<T> =
    CoroutineScope(SupervisorJob() + Dispatchers.Default).future(start = CoroutineStart.UNDISPATCHED) { call() }
