package me.helvert.codebridge.rpc

import kotlinx.coroutines.flow.Flow
import me.helvert.codebridge.progress.withRawProgress

/** The marketplace's half of the backend, which the core [CodeBridgeApi] knows nothing of. */
suspend fun <T> marketplace(work: suspend MarketplaceApi.() -> T): T = work(MarketplaceApi.getInstance())

/** Waits out an install or uninstall, showing its progress, and returns what was installed. */
suspend fun Flow<OperationUpdate>.awaitOperation(): InstallResultDto? = withRawProgress { progress ->
    var completed = false
    var result: InstallResultDto? = null
    collect { update ->
        update.error?.let { throw IllegalStateException(it) }
        progress.text(update.text)
        progress.fraction(update.fraction)
        if (update.completed) {
            completed = true
            result = update.result
        }
    }
    check(completed) { "The backend operation ended before reporting completion" }
    result
}
