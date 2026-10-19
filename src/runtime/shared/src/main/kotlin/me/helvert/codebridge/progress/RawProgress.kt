package me.helvert.codebridge.progress

import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.platform.util.progress.reportRawProgress

/**
 * Keeps the platform's inline progress implementation in one place. Verifier reports its
 * internal handle as a usage in our bytecode (MP-7133), even though this is the public API.
 * A non-inline wrapper avoids duplicating those references in every caller.
 */
suspend fun <T> withRawProgress(action: suspend (RawProgressReporter) -> T): T =
    reportRawProgress { reporter -> action(reporter) }
