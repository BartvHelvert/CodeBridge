package me.helvert.codebridge.language

import com.intellij.openapi.extensions.ExtensionPointName

/**
 * What lets [HostedLanguageFeatures] restart highlighting without waiting the auto-reparse delay.
 *
 * A point rather than a call because the only way to is internal API, kept in `CodeBridge.internal.backend`
 * so a build without that module ships none of it. Without an implementation an answer from an
 * extension is drawn after the ordinary delay, 300ms by default.
 */
interface CodeBridgeDaemonRestarter {
    /** Runs [restart], which restarts the daemon, so that the pass it schedules starts at once. On the EDT. */
    fun restartNow(restart: () -> Unit)

    companion object {
        val EP_NAME: ExtensionPointName<CodeBridgeDaemonRestarter> =
            ExtensionPointName("me.helvert.codebridge.daemonRestarter")
    }
}
