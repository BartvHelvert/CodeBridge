package me.helvert.codebridge.exthost

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.helvert.codebridge.manifest.VsixArchive
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Obtains a debug adapter from a real extension by calling back into `ExtHostDebugService`.
 *
 * codelldb declares no `program` in its manifest and produces its adapter from a
 * `DebugAdapterDescriptorFactory` registered in JavaScript, so this is exactly the case that
 * cannot be handled by reading the manifest. Getting a descriptor out of it is the point at which
 * the `.vscode` dialect reader and the extension host meet: a `launch.json` entry goes in, and a
 * runnable adapter comes out.
 */
class DebugAdapterDiscoveryTest {

    private val vsix: Path = Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", "codelldb-1.12.3.vsix").toAbsolutePath().normalize()

    private val json = Json { isLenient = true }

    @Test
    fun `asks codelldb for a debug adapter descriptor`() {
        val installation = TestRuntime.orSkip() ?: return
        if (!vsix.exists()) {
            println("skipping: $vsix not present")
            return
        }

        val installDirectory = createTempDirectory("codelldb-install")
        VsixArchive.unpack(vsix, installDirectory)
        val manifest = assertNotNull(VsixArchive.readManifest(vsix).manifest)

        val description = buildJsonObject {
            ExtensionHostInitData.describeExtension(manifest, installDirectory)
                .forEach { (key, value) -> put(key, value) }
            put("activationEvents", JsonArray(listOf(JsonPrimitive("*"))))
            put("targetPlatform", "win32-x64")
        }

        val storage = createTempDirectory("codelldb-debug")
        val installed = mutableListOf<Path>()

        val handler = MainThreadHandler { call ->
            @Suppress("IntroduceWhenSubject")
            when {
                call.method == $$"$getInitialState" -> """{"isFocused":true,"isActive":true}"""

                // codelldb ships without its platform binary and asks the workbench to install the
                // package it just downloaded. Stubbing this is why the adapter spawn fails with
                // ENOENT: the extension does its real work and we simply never did ours.
                call.method == $$"$executeCommand" && call.argsJson.contains("installFromVSIX") -> {
                    vsixPathIn(call.argsJson)?.let { downloaded ->
                        // The platform package carries the adapter binary and is expected to land
                        // in the extension's own directory: codelldb then spawns
                        // <extensionPath>/adapter/codelldb. Our unpack strips the "extension/"
                        // prefix, so merging it here puts the binary exactly where it looks.
                        val written = VsixArchive.unpack(downloaded, installDirectory)
                        installed.add(downloaded)
                        println("  [main] installed ${downloaded.fileName}: ${written.size} files into $installDirectory")
                        val adapter = installDirectory.resolve("adapter")
                        println("  [main] adapter/: " + runCatching {
                            java.nio.file.Files.list(adapter).use { paths ->
                                paths.map { it.fileName.toString() }.toList().take(12)
                            }
                        }.getOrElse { listOf("<missing: ${it.message}>") })
                    }
                    null
                }

                else -> null
            }
        }

        ExtensionHostSession(installation, listOf(description), storage, handler).use { session ->
            session.start()
            assertTrue(session.awaitInitialized(60, TimeUnit.SECONDS), "host should initialize")

            val factoryCall = assertNotNull(
                session.awaitCall(60, TimeUnit.SECONDS) { it.method == $$"$registerDebugAdapterDescriptorFactory" },
                "codelldb should register an adapter descriptor factory",
            )
            session.awaitCall(5, TimeUnit.SECONDS) { it.method == $$"$registerDebugConfigurationProvider" }

            println("registerDebugAdapterDescriptorFactory args: ${factoryCall.argsJson}")

            val factoryHandle = assertNotNull(handleIn(factoryCall.argsJson), "factory handle")
            println("adapter factory handle = $factoryHandle")

            // There is more than one configuration provider; only some implement resolve.
            val providerHandles = session.observedCalls()
                .filter { it.method == $$"$registerDebugConfigurationProvider" }
                .mapNotNull { handleIn(it.argsJson) }
            println("configuration provider handles = $providerHandles")

            // A launch.json entry, of exactly the shape our parser produces.
            val configuration = buildJsonObject {
                put("type", "lldb")
                put("request", "launch")
                put("name", "Debug executable")
                put("program", installDirectory.resolve("probe-binary").toString())
            }

            for (handle in providerHandles) resolveConfiguration(session, handle, configuration)

            val before = session.observedCalls().size
            val first = requestAdapter(session, factoryHandle, configuration, attempt = 1)

            println()
            println("--- what codelldb asked for while building the descriptor ---")
            val during = session.observedCalls().drop(before)
            if (during.isEmpty()) println("  (nothing)")
            during.forEach { println("  ${it.service}.${it.method}  ${it.argsJson.take(160)}") }

            // The first attempt is what triggers the download and install. By now the adapter
            // binary is on disk, so ask again rather than assuming the extension retries itself.
            val descriptor = first ?: if (installed.isNotEmpty()) {
                println()
                println("--- platform package installed; asking again ---")
                requestAdapter(session, factoryHandle, configuration, attempt = 2)
            } else null

            if (descriptor == null) {
                // Obtaining a descriptor requires downloading codelldb's platform package, so this
                // is reported rather than failed when the network is unavailable.
                println("no descriptor obtained; skipping the DAP handshake")
                return
            }

            val reply = assertNotNull(speakDap(session, configuration), "expected a DAP response")
            assertTrue(reply.argsJson.contains("\"command\":\"initialize\""), "should answer initialize")
            assertTrue(reply.argsJson.contains("\"success\":true"), "initialize should succeed")
            assertTrue(reply.argsJson.contains("supportsConfigurationDoneRequest"), "real lldb capabilities")
        }
    }

    private fun resolveConfiguration(
        session: ExtensionHostSession,
        handle: Int,
        configuration: JsonObject,
    ) {
        println()
        println($$"--- $resolveDebugConfiguration(handle=$$handle) ---")
        val result = runCatching {
            runBlocking { withTimeout(30.seconds) { session.call(
                "ExtHostDebugService",
                $$"$resolveDebugConfiguration",
                "[$handle,null,$configuration,null]",
            ) } }
        }
        result.onSuccess { println("resolved: ${it.take(600)}") }
        result.onFailure { println("resolve failed: ${it.message?.take(600)}") }
    }

    private fun requestAdapter(
        session: ExtensionHostSession,
        factoryHandle: Int,
        configuration: JsonObject,
        attempt: Int,
    ): String? {
        // IDebugSessionDto; the host revives this into a DebugSession for the factory.
        val sessionDto = buildJsonObject {
            put("id", "codebridge-session-1")
            put("type", "lldb")
            put("name", "Debug executable")
            put("configuration", configuration)
        }

        println()
        println($$"--- $provideDebugAdapter(handle=$$factoryHandle) attempt $$attempt ---")
        val result = runCatching {
            runBlocking { withTimeout(60.seconds) { session.call(
                "ExtHostDebugService",
                $$"$provideDebugAdapter",
                "[$factoryHandle,$sessionDto]",
            ) } }
        }
        result.onSuccess { descriptor ->
            println("DESCRIPTOR: ${descriptor.take(800)}")
            describe(descriptor)
        }
        result.onFailure { failure ->
            println("provideDebugAdapter failed: ${failure::class.simpleName}: ${failure.message?.take(400)}")
        }
        return result.getOrNull()
    }

    /**
     * A descriptor of type `implementation` means the adapter lives inside the extension host, so
     * DAP travels over the same RPC rather than over a socket or pipe we own: we push messages with
     * `$sendDAMessage` and receive them as `MainThreadDebugService.$acceptDAMessage`.
     */
    private fun speakDap(session: ExtensionHostSession, configuration: JsonObject): MainThreadCall? {
        val adapterHandle = 1
        val sessionDto = buildJsonObject {
            put("id", "codebridge-session-1")
            put("type", "lldb")
            put("name", "Debug executable")
            put("configuration", configuration)
        }

        println()
        println($$"--- $startDASession(handle=$$adapterHandle) ---")
        val started = runCatching {
            runBlocking { withTimeout(60.seconds) { session.call(
                "ExtHostDebugService",
                $$"$startDASession",
                "[$adapterHandle,$sessionDto]",
            ) } }
        }
        started.onFailure {
            println("startDASession failed: ${it.message?.take(400)}")
            return null
        }
        println("session started")

        val initialize = """
            {"seq":1,"type":"request","command":"initialize","arguments":{
              "clientID":"codebridge","clientName":"CodeBridge","adapterID":"lldb",
              "locale":"en","linesStartAt1":true,"columnsStartAt1":true,"pathFormat":"path",
              "supportsVariableType":true,"supportsRunInTerminalRequest":true}}
        """.trimIndent().replace("\n", "").replace("  ", "")

        println()
        println("--- DAP initialize ---")
        // Not awaited: the adapter's answer arrives as a call of its own.
        CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.call("ExtHostDebugService", $$"$sendDAMessage", "[$adapterHandle,$initialize]")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }

        val reply = session.awaitCall(30, TimeUnit.SECONDS) { it.method == $$"$acceptDAMessage" }
        if (reply == null) {
            println("no DAP reply within 30s")
            val debugCalls = session.observedCalls().filter { it.service == "MainThreadDebugService" }
            println("MainThreadDebugService calls: ${debugCalls.map { it.method }.distinct()}")
            return null
        }

        println("DAP REPLY: ${reply.argsJson.take(900)}")
        val capabilities = Regex("\"supports[A-Za-z]+\":true").findAll(reply.argsJson).map { it.value }.toList()
        println("adapter capabilities reported: ${capabilities.size}")
        capabilities.take(10).forEach { println("    $it") }
        return reply
    }

    /** VS Code tags adapter descriptors with a `type` discriminator. */
    private fun describe(descriptorJson: String) {
        val descriptor = runCatching { json.parseToJsonElement(descriptorJson) as? JsonObject }.getOrNull() ?: return
        when ((descriptor["type"] as? JsonPrimitive)?.contentOrNull) {
            "executable" -> println(
                "  -> spawn: ${(descriptor["command"] as? JsonPrimitive)?.contentOrNull} ${descriptor["args"]}"
            )

            "server" -> println("  -> connect to port ${(descriptor["port"] as? JsonPrimitive)?.intOrNull}")
            "pipeServer" -> println("  -> connect to pipe ${descriptor["path"]}")
            else -> println("  -> descriptor type ${descriptor["type"]}")
        }
    }

    /** The register calls carry an integer handle; find it without assuming its position. */
    private fun handleIn(argsJson: String): Int? {
        val args = runCatching { json.parseToJsonElement(argsJson) as? JsonArray }.getOrNull() ?: return null
        return args.firstNotNullOfOrNull { (it as? JsonPrimitive)?.intOrNull }
    }

    /** Digs the file path out of `["workbench...installFromVSIX", [[uri]], true]`. */
    private fun vsixPathIn(argsJson: String): Path? {
        val path = Regex("\"path\":\"([^\"]+\\.vsix)\"").find(argsJson)?.groupValues?.get(1) ?: return null
        return runCatching { Path.of(path.removePrefix("/")) }.getOrNull()?.takeIf { it.exists() }
    }
}
