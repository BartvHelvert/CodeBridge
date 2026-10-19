package me.helvert.codebridge.rpc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.*
import java.awt.Component
import kotlin.time.Duration.Companion.seconds

/** A panel owns these requests and cancels all of them when it is disposed. */
class BackendRequests : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun <T> request(
        work: suspend CodeBridgeApi.() -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
    ): Job = scope.launch {
        try {
            val value = work(CodeBridgeApi.getInstance())
            onUi { success(value) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (error: Exception) {
            onUi { failure(error) }
        }
    }

    /** Reconnect after backend availability changes; every subscription starts with a revision. */
    fun watchExtensions(changed: () -> Unit, failed: (Throwable) -> Unit): Job = scope.launch {
        while (isActive) {
            try {
                CodeBridgeApi.getInstance().extensionChanges().collect { onUi(changed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onUi { failed(error) }
            }
            delay(2.seconds)
        }
    }

    private fun onUi(action: () -> Unit) {
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) { action() }
    }

    override fun dispose() {
        scope.cancel()
    }
}

/** Enters modality synchronously while executing RPC off the EDT, with cancellation propagation. */
fun <T> backendModal(parent: Component?, title: String, work: suspend CodeBridgeApi.() -> T): T =
    runWithModalProgressBlocking(parent?.let(ModalTaskOwner::component) ?: ModalTaskOwner.guess(), title) {
        work(CodeBridgeApi.getInstance())
    }
