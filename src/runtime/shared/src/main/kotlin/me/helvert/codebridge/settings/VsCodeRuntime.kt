package me.helvert.codebridge.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import me.helvert.codebridge.exthost.VsCodeInstallation
import java.nio.file.Path

/**
 * Supplies the VS Code installation the extension host runs on.
 *
 * There is one, and it ships inside the plugin. Letting people point at a VS Code they already have
 * looks generous and is not: extension-host rpcIds drift between builds, a mismatch misroutes calls
 * silently, and the resulting behaviour -- extensions running against an API version nobody chose --
 * is close to undiagnosable. The version we pin is the version we test, so it is the only one the
 * plugin will run. See [BundledRuntime] for why that runtime is VSCodium's REH package.
 *
 * Deliberately not cached: resolving is a handful of `exists()` calls.
 */
@Service(Service.Level.APP)
class VsCodeRuntime {

    /** Which runtime won. */
    enum class Source {
        /** The [VsCodeInstallation.OVERRIDE_ENV] environment variable, for tests and development. */
        ENVIRONMENT,

        /** The VSCodium extension host shipped inside the plugin. */
        BUNDLED,
    }

    data class Resolution(val source: Source, val diagnosis: VsCodeInstallation.Diagnosis) {
        val installation: VsCodeInstallation? get() = diagnosis.installation
    }

    /**
     * Decides what to run on.
     *
     * The environment override comes first and is never silently replaced: it exists so that a
     * developer can run the extension host they are working on, and quietly substituting the
     * bundled one would defeat the point. Everyone else gets the bundled runtime.
     */
    fun resolve(): Resolution {
        System.getenv(VsCodeInstallation.OVERRIDE_ENV)?.takeIf { it.isNotBlank() }?.let {
            return Resolution(Source.ENVIRONMENT, VsCodeInstallation.inspect(Path.of(it)))
        }
        return Resolution(
            Source.BUNDLED,
            BundledRuntime.getInstance().installation()?.let { VsCodeInstallation.Diagnosis.Usable(it) }
                ?: VsCodeInstallation.Diagnosis.NotFound,
        )
    }

    /** The installation to run the extension host on, or `null` when there is not a usable one. */
    fun installationOrNull(): VsCodeInstallation? = resolve().installation

    /** The installation to run the extension host on, failing with a message fit to show the user. */
    fun installation(): VsCodeInstallation {
        val resolution = resolve()
        return resolution.installation ?: throw VsCodeUnavailableException(resolution.diagnosis)
    }

    companion object {
        /**
         * The service, or a plain instance when there is no application to hold one.
         *
         * Resolution is stateless and deliberately uncached, so the tests can run exactly this
         * against the runtime the IDE ships with. See [BundledRuntime.getInstance].
         */
        fun getInstance(): VsCodeRuntime =
            if (ApplicationManager.getApplication() != null) service() else VsCodeRuntime()
    }
}

/**
 * No usable VS Code. Carries the diagnosis so a caller can show the reason and offer the settings
 * page, rather than reporting a generic failure.
 */
class VsCodeUnavailableException(
    val diagnosis: VsCodeInstallation.Diagnosis,
) : RuntimeException("CodeBridge needs a VS Code extension host. " + diagnosis.message)
