package me.helvert.codebridge.exthost

import me.helvert.codebridge.settings.BundledRuntime
import me.helvert.codebridge.settings.VsCodeRuntime

/**
 * The extension host these tests run on, resolved the way the plugin resolves it.
 *
 * Tests used to find their own VS Code: `VsCodeInstallation` scanned `C:\Program Files` and the
 * other usual locations, so whatever the developer happened to have installed is what the extension
 * host tests ran against. The plugin itself refuses to do that -- rpcIds drift between builds and a
 * mismatch misroutes calls silently, which is why `VsCodeRuntime` runs the pinned VSCodium REH
 * package and nothing else. Testing against an unpinned desktop build tested a configuration no user
 * can have.
 *
 * So this goes through [VsCodeRuntime] exactly as the backend does. The only difference is where
 * [BundledRuntime] looks: a test JVM has no installed plugin, so the build points
 * [BundledRuntime.DIRECTORY_PROPERTY] at the build machine's runtime, unpacked by
 * `extractHostRuntime` when [FETCH_ENV] is set.
 */
object TestRuntime {

    /** Lets the build fetch and unpack the runtime. Also gates the tests that start a real host. */
    const val FETCH_ENV: String = "CODEBRIDGE_TEST_LSP_HOST"

    /**
     * The runtime, or `null` with the reason on stdout.
     *
     * A runtime unpacked by an earlier run is used whether or not [FETCH_ENV] is set, so only the
     * first fetch needs it: a plain `gradlew test` should not reach for 113 MB of network, and a
     * machine without a runtime skips rather than fails.
     */
    fun orSkip(): VsCodeInstallation? {
        VsCodeRuntime.getInstance().installationOrNull()?.let { return it }
        val reason = when {
            !BundledRuntime.supported() -> BundledRuntime.unsupportedPlatformMessage()
            else -> "no extension host runtime at ${BundledRuntime.getInstance().installDirectory()}. " +
                "Run with $FETCH_ENV=1 to unpack the pinned ${BundledRuntime.NAME}, " +
                "or point ${VsCodeInstallation.OVERRIDE_ENV} at one"
        }
        println("skipping: $reason")
        return null
    }
}
