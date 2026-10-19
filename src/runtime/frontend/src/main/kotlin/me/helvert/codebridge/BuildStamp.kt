package me.helvert.codebridge

/**
 * When this build was produced, written by the `generateBuildStamp` Gradle task.
 *
 * Logged when the extensions page opens, so a stale IDE is visible rather than baffling: a running
 * IDE holds its plugin classes in memory, so replacing the jar underneath it changes nothing, and
 * without a stamp that looks exactly like a change that did not work. It used to be a line at the
 * bottom of the page; the IDE's plugin page has no such line, so it went to the log instead.
 *
 * Read from a resource rather than from the jar's own timestamp, because the IntelliJ build
 * normalises jar entry times to a fixed 1980 date.
 */
object BuildStamp {

    /** e.g. `2026-09-11 11:23:04`, or `unknown` when the resource is missing. */
    val value: String by lazy {
        runCatching {
            BuildStamp::class.java.getResourceAsStream("/codebridge-build-stamp.txt")
                ?.bufferedReader()?.use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    fun describe(): String = "CodeBridge · build $value"
}
