// Internal API is this module's purpose; each use is listed in docs/platform-limitations.md.
@file:Suppress("UnstableApiUsage")

package me.helvert.codebridge.internal

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.util.ThrowableRunnable
import me.helvert.codebridge.language.CodeBridgeDaemonRestarter

/**
 * Restarts with the IDE's auto-reparse delay set aside, so the pass starts at once.
 *
 * `restart` does not run a pass: it schedules one for now plus the auto-reparse delay, 300ms by
 * default, read synchronously inside the call. That delay is for typing -- it keeps the IDE from
 * highlighting between keystrokes -- and a diagnostic push is the opposite case: it arrives
 * half a second after the user stopped, the pass it needs takes ~10ms, and the delay was the
 * whole of this plugin's share of how late errors appeared. Only this call is affected; every
 * keystroke keeps the user's delay.
 *
 * `forceUseZeroAutoReparseDelayIn` is internal API, so a platform that renames or removes it
 * gets the ordinary restart instead of a failure.
 */
internal class CodeBridgeInternalDaemonRestarter : CodeBridgeDaemonRestarter {
    override fun restartNow(restart: () -> Unit) {
        try {
            DaemonCodeAnalyzerSettings.getInstance()
                .forceUseZeroAutoReparseDelayIn(ThrowableRunnable<RuntimeException> { restart() })
        } catch (_: LinkageError) {
            restart()
        }
    }
}
