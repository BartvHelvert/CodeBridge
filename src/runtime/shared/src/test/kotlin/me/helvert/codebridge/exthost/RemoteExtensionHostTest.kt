package me.helvert.codebridge.exthost

import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boots the extension host out of a **remote-extension-host package** rather than an installed
 * editor.
 *
 * This is the runtime we can actually ship. VSCodium's `reh-*` packages are MIT licensed, are
 * roughly a seventh the size of a desktop install, and -- the part that matters most here -- carry
 * their own `node` with `node_modules` unpacked, so none of the Electron-and-asar machinery the
 * desktop path needs applies. If this passes, a managed runtime is a packaging exercise rather than
 * a second implementation.
 *
 * That packaging exercise is done, so this runs against the managed runtime itself rather than
 * against a package pointed at by hand -- see [TestRuntime].
 */
class RemoteExtensionHostTest {

    private val probeDirectory: Path =
        Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", "probe-extension").toAbsolutePath().normalize()

    private fun serverOrSkip(): VsCodeInstallation? {
        val installation = TestRuntime.orSkip() ?: return null
        if (installation.kind != VsCodeInstallation.Kind.SERVER) {
            // Only reachable through an override aimed at a desktop build. The managed runtime is
            // always a server package, and what this test has to say about one does not apply here.
            println("skipping: ${VsCodeInstallation.OVERRIDE_ENV} names a desktop install, not a server package")
            return null
        }
        if (!probeDirectory.resolve("package.json").exists()) {
            println("skipping: probe extension missing at $probeDirectory")
            return null
        }
        return installation
    }

    @Test
    fun `recognises a server package and launches it on its own node`() {
        val installation = serverOrSkip() ?: return

        assertEquals(VsCodeInstallation.Kind.SERVER, installation.kind)
        assertTrue(
            installation.executable.fileName.toString().startsWith("node"),
            "should run on the bundled node, not on Electron: ${installation.executable}",
        )

        val environment = installation.launchSpec("pipe", 1).environment()
        // The whole point of the server package: no asar, so nothing to talk Electron out of.
        assertTrue(
            "ELECTRON_RUN_AS_NODE" !in environment,
            "a plain node needs no Electron coaxing, but got $environment",
        )
        println("server runtime: ${installation.nameLong} ${installation.version} on ${installation.executable}")
    }

    @Test
    fun `boots the extension host and activates an extension`() {
        val installation = serverOrSkip() ?: return

        val manifest = assertNotNull(
            VsixManifestParser.parse(probeDirectory.resolve("package.json").readText()).manifest,
            "probe manifest should parse",
        )
        val description = ExtensionHostInitData.describeExtension(manifest, probeDirectory)

        val storage = createTempDirectory("codebridge-reh")
        val handler = MainThreadHandler { call ->
            if (call.method == $$"$getInitialState") """{"isFocused":true,"isActive":true}""" else null
        }

        ExtensionHostSession(installation, listOf(description), storage, handler).use { session ->
            session.start()

            assertTrue(
                session.awaitInitialized(60, TimeUnit.SECONDS),
                "extension host should report Initialized",
            )

            val activated = session.awaitCall(60, TimeUnit.SECONDS) { it.method == $$"$onDidActivateExtension" }
            assertNotNull(activated, "extension should activate; saw ${session.observedCalls().map { it.method }}")
            assertTrue(activated.argsJson.contains("codebridge.probe"))

            val message = assertNotNull(
                session.awaitCall(10, TimeUnit.SECONDS) { it.method == $$"$showMessage" },
                "probe should have shown a message",
            )
            assertTrue(message.argsJson.contains("Hello from inside the real extension host"))

            val services = session.observedCalls().map { it.service }.distinct().sorted()
            println("MainThread services exercised: $services")
            assertTrue(services.contains("MainThreadExtensionService"))
        }
    }

}
