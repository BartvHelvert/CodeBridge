package me.helvert.codebridge.exthost

import me.helvert.codebridge.exthost.protocol.payload.Console
import me.helvert.codebridge.exthost.protocol.payload.LogLevel
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
 * Boots the real VS Code extension host and activates an extension in it.
 *
 * Skipped when no VS Code installation is present, since it depends on one being available rather
 * than on anything this project ships. Point [VsCodeInstallation.OVERRIDE_ENV] at an install to
 * run it elsewhere.
 */
class ExtensionHostSessionTest {

    private val probeDirectory: Path = Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", "probe-extension").toAbsolutePath().normalize()

    private fun installationOrSkip(): VsCodeInstallation? {
        val installation = TestRuntime.orSkip() ?: return null
        if (!probeDirectory.resolve("package.json").exists()) {
            println("skipping: probe extension missing at $probeDirectory")
            return null
        }
        return installation
    }

    @Test
    fun `locates an installation with a usable extension host`() {
        val installation = installationOrSkip() ?: return

        assertTrue(installation.executable.exists(), "executable should exist")
        assertTrue(installation.appDirectory.resolve("out/bootstrap-fork.js").exists())
        assertTrue(installation.version.isNotBlank())
        println("found VS Code ${installation.version} at ${installation.appDirectory}")
    }

    @Test
    fun `boots the extension host and activates an extension`() {
        val installation = installationOrSkip() ?: return

        val manifest = assertNotNull(
            VsixManifestParser.parse(probeDirectory.resolve("package.json").readText()).manifest,
            "probe manifest should parse",
        )
        val description = ExtensionHostInitData.describeExtension(manifest, probeDirectory)

        val storage = createTempDirectory("codebridge-session")
        val handler = MainThreadHandler { call ->
            // The only startup call whose result the host actually reads.
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

            // The probe calls vscode.window.showInformationMessage during activate().
            val message = assertNotNull(
                session.awaitCall(10, TimeUnit.SECONDS) { it.method == $$"$showMessage" },
                "probe should have shown a message",
            )
            assertEquals("MainThreadMessageService", message.service)
            assertTrue(message.argsJson.contains("Hello from inside the real extension host"))

            // console.log from inside the extension arrives over MainThreadConsole.
            val logged = assertNotNull(
                session.awaitCall(10, TimeUnit.SECONDS) {
                    it.method == $$"$logExtensionHostMessage" && it.argsJson.contains("activate() called")
                },
                "probe console output should arrive",
            )
            assertEquals("MainThreadConsole", logged.service)
            // And reads back as the line the probe wrote. `LoggingTest` covers the shapes against
            // captured traffic; this is the one place the parser meets a real host's spelling.
            assertTrue(Console.message(logged.argsJson).text.contains("activate() called"))
            assertEquals(LogLevel.INFO, Console.message(logged.argsJson).level)

            val services = session.observedCalls().map { it.service }.distinct().sorted()
            println("MainThread services exercised: $services")
            assertTrue(services.contains("MainThreadExtensionService"))
        }
    }
}
