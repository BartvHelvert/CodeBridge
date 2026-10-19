package me.helvert.codebridge.exthost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.helvert.codebridge.manifest.VsixArchive
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Activates a real, published extension inside the host and records what it asks the main thread
 * for.
 *
 * This is the measurement that prices general extension support: the floor needed to activate the
 * trivial probe is known, and the question is how much further a genuine extension reaches.
 *
 * Skipped when VS Code is not installed. The VSIX is checked in, from
 * `https://open-vsx.org/api/vadimcn/vscode-lldb/1.12.3/file/vadimcn.vscode-lldb-1.12.3.vsix`.
 */
class RealExtensionSurveyTest {

    private val vsix: Path =
        Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", "codelldb-1.12.3.vsix").toAbsolutePath().normalize()

    /** Methods whose result the host or an extension actually reads. Grown by observing failures. */
    private val canned = mapOf(
        $$"$getInitialState" to """{"isFocused":true,"isActive":true}""",
    )

    @Test
    fun `activates codelldb and records the api surface it touches`() {
        val installation = TestRuntime.orSkip() ?: return
        if (!vsix.exists()) {
            println("skipping: $vsix not present")
            return
        }

        val installDirectory = createTempDirectory("codelldb-install")
        VsixArchive.unpack(vsix, installDirectory)
        val manifest = assertNotNull(
            VsixArchive.readManifest(vsix).manifest,
            "codelldb manifest should parse",
        )
        println("extension: ${manifest.id} ${manifest.version}, engine ${manifest.engineRange}")
        println("contributes: ${manifest.contributes.declaredKeys.sorted()}")

        // Force star activation so the extension starts without us driving a debug session first.
        val description = buildJsonObject {
            ExtensionHostInitData.describeExtension(manifest, installDirectory)
                .forEach { (key, value) -> put(key, value) }
            put("activationEvents", JsonArray(listOf(JsonPrimitive("*"))))
        }

        val storage = createTempDirectory("codelldb-session")
        val errors = mutableListOf<String>()
        val handler = MainThreadHandler { call ->
            if (call.method == $$"$onUnexpectedError") errors.add(call.argsJson.take(300))
            canned[call.method]
        }

        ExtensionHostSession(installation, listOf(description), storage, handler).use { session ->
            session.start()
            assertTrue(session.awaitInitialized(60, TimeUnit.SECONDS), "host should initialize")

            val activated = session.awaitCall(60, TimeUnit.SECONDS) {
                it.method == $$"$onDidActivateExtension" && it.argsJson.contains(manifest.id)
            }

            // Give it a moment to finish whatever activate() kicked off.
            session.awaitCall(3, TimeUnit.SECONDS) { false }

            report(session, manifest.id, activated != null, errors)

            assertNotNull(activated, "codelldb should activate")
        }
    }

    private fun report(
        session: ExtensionHostSession,
        extensionId: String,
        activated: Boolean,
        errors: List<String>,
    ) {
        val calls = session.observedCalls()
        val byService = calls.groupBy { it.service }.toSortedMap()

        println()
        println("=".repeat(78))
        println("$extensionId activated: $activated")
        println("${calls.size} calls across ${byService.size} MainThread services")
        println("=".repeat(78))
        for ((service, serviceCalls) in byService) {
            val methods = serviceCalls.groupingBy { it.method }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}${if (it.value > 1) " x${it.value}" else ""}" }
            println("  $service")
            println("      $methods")
        }

        val beyondFloor = byService.keys - PROBE_FLOOR
        println()
        println("services beyond the trivial-probe floor: ${if (beyondFloor.isEmpty()) "none" else beyondFloor.sorted()}")

        if (errors.isEmpty()) {
            println("no errors reported by the host")
        } else {
            println("${errors.size} error(s) reported by the host:")
            errors.forEach { println("    $it") }
        }
        println("=".repeat(78))
    }

    private companion object {
        /** Services the trivial probe alone caused the host to call. */
        val PROBE_FLOOR = setOf(
            "MainThreadChatSkills", "MainThreadCommands", "MainThreadConsole", "MainThreadDebugService",
            "MainThreadErrors", "MainThreadExtensionService", "MainThreadFileSystem", "MainThreadLogger",
            "MainThreadMessageService", "MainThreadSearch", "MainThreadStorage", "MainThreadTask",
            "MainThreadTelemetry", "MainThreadTerminalService", "MainThreadWindow",
        )
    }
}
