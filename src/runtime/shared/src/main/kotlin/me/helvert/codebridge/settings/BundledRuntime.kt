package me.helvert.codebridge.settings

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.platform.eel.*
import com.intellij.platform.eel.provider.localEel
import me.helvert.codebridge.exthost.VsCodeInstallation
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The VS Code extension host that ships inside the plugin, so that using CodeBridge does not
 * require installing VS Code.
 *
 * It is VSCodium's **remote extension host** package rather than a desktop build. Three reasons, in
 * order of how much they matter:
 *
 * 1. **It is redistributable.** VSCodium is MIT licensed. Microsoft's own VS Code build is not: its
 *    terms grant use but no right to redistribute, so shipping that one was never an option.
 * 2. **It is the half we use.** The REH package is the server side of Remote Development -- the
 *    extension host without a renderer, because in this architecture we are the renderer. It ships
 *    its own `node` with `node_modules` unpacked, so none of the Electron-and-asar machinery a
 *    desktop install needs applies.
 * 3. **It is pinned.** Extension-host rpcIds drift between builds and a mismatch misroutes calls
 *    silently, so running a build we chose and test against is worth more than tracking the latest.
 *
 * The plugin is published as one distribution per OS and architecture, and each carries its own
 * platform's package under `runtime/` -- see `buildPluginVariants_*` in the root build script, which is
 * also where the version is pinned. It used to be downloaded on first start instead, from the days
 * when the Marketplace took a single archive for every platform and five runtimes in it would have
 * been half a gigabyte.
 */
@Service(Service.Level.APP)
class BundledRuntime {

    /**
     * Where the runtime is, or `null` when the plugin cannot find its own installation.
     *
     * [DIRECTORY_PROPERTY] first, because a test JVM has no installed plugin to look in, and the
     * sandboxes `runIde` and the UI tests install from carry no runtime; the build points it at the
     * runtime it unpacked for the build machine. Otherwise the plugin's own directory,
     * from the class loader that loaded this: a content module's loader answers with the plugin's.
     */
    fun installDirectory(): Path? =
        System.getProperty(DIRECTORY_PROPERTY)?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: (javaClass.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.pluginPath?.resolve("runtime")

    /** The bundled runtime, or `null` when there is none (or it is unusable). */
    fun installation(): VsCodeInstallation? {
        val directory = installDirectory()?.takeIf { it.exists() } ?: return null
        return VsCodeInstallation.inspect(directory).installation
    }

    companion object {
        /**
         * The service, or a plain instance when there is no application to hold one.
         *
         * The fallback is what lets the tests resolve the runtime the way the IDE does. This class
         * holds no state, so an instance constructed outside the container behaves identically to
         * the service.
         */
        fun getInstance(): BundledRuntime =
            if (ApplicationManager.getApplication() != null) service() else BundledRuntime()

        const val NAME: String = "VSCodium"

        /** Overrides where the runtime is looked for. Set by the build for test JVMs and `runIde`. */
        const val DIRECTORY_PROPERTY: String = "codebridge.runtime.dir"

        /** Whether VSCodium publishes an extension host for this machine, and so whether one shipped. */
        // The extension host runs on this machine, whatever the project lives on.
        fun supported(): Boolean = platform(localEel.platform) != null

        /**
         * VSCodium's platform token for an OS and architecture, or `null` when it publishes no
         * extension-host package for that pair.
         *
         * Windows on ARM is the one gap: there is a `reh-win32-x64` build but no
         * `reh-win32-arm64`. The Windows ARM distribution is still built, since the six variants
         * come as a set, and it ships without a runtime.
         */
        internal fun platform(platform: EelPlatform): String? = when {
            platform.isWindows -> if (platform.isX86_64) "win32-x64" else null
            platform.isMac -> when {
                platform.isArm64 -> "darwin-arm64"
                platform.isX86_64 -> "darwin-x64"
                else -> null
            }

            else -> when {
                platform.isArm64 -> "linux-arm64"
                platform.isX86_64 -> "linux-x64"
                else -> null
            }
        }

        // The extension host runs on this machine, whatever the project lives on.
        fun unsupportedPlatformMessage(): String =
            "$NAME publishes no extension-host package for this platform " +
                    "(${localEel.platform.osName}, ${localEel.platform.archName}), so the extension host cannot run here."
    }
}
