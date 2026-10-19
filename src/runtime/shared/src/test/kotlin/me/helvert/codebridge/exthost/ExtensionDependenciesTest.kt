package me.helvert.codebridge.exthost

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.helvert.codebridge.manifest.VsixManifestParser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Checks dependency ordering and the failure side channel against the bundled, unmodified host. */
class ExtensionDependenciesTest {
    @TempDir lateinit var temp: Path

    private fun extension(name: String, script: String, dependencies: List<String> = emptyList()): JsonObject {
        val folder = temp.resolve(name).createDirectories()
        val manifest = buildJsonObject {
            put("publisher", "codebridge"); put("name", name); put("version", "0.0.1")
            put("engines", buildJsonObject { put("vscode", "*") })
            put("main", "./extension.js")
            put("activationEvents", buildJsonArray { add("onCommand:codebridge.dependency-test") })
            put("extensionDependencies", JsonArray(dependencies.map(::JsonPrimitive)))
        }
        folder.resolve("package.json").writeText(manifest.toString())
        folder.resolve("extension.js").writeText(script)
        return ExtensionHostInitData.describeExtension(assertNotNull(VsixManifestParser.parse(manifest.toString()).manifest), folder)
    }

    private fun run(extensions: List<JsonObject>, test: (ExtensionHostSession) -> Unit) {
        val installation = TestRuntime.orSkip() ?: return
        val handler = MainThreadHandler { call ->
            if (call.method == $$"$getInitialState") """{"isFocused":true,"isActive":true}""" else null
        }
        ExtensionHostSession(installation, extensions, temp.resolve("storage"), handler).use { session ->
            try {
                session.start()
                assertTrue(session.awaitInitialized(60, TimeUnit.SECONDS))
                test(session)
            } finally {
                session.close()
                assertTrue(session.awaitProcessExit(10, TimeUnit.SECONDS), "extension host should exit before fixture cleanup")
            }
        }
    }

    private fun activate(session: ExtensionHostSession) = runBlocking { withTimeout(30.seconds) { session.call(
        "ExtHostExtensionService", $$"$activate",
        """[{"value":"codebridge.root","_lower":"codebridge.root"},{"startup":false,"activationEvent":"onCommand:codebridge.dependency-test","extensionId":{"value":"codebridge.root","_lower":"codebridge.root"}}]""",
    ) } }

    @Test fun `dependency activation completes before the dependent reads its exports`() {
        val dependency = extension("dependency", """
            exports.activate = async () => {
                await new Promise(resolve => setTimeout(resolve, 100));
                return { ready: true };
            };
        """.trimIndent())
        val root = extension("root", """
            const vscode = require('vscode');
            exports.activate = () => {
                const dependency = vscode.extensions.getExtension('codebridge.dependency');
                if (!dependency.isActive || !dependency.exports.ready) throw new Error('dependency was not ready');
                console.log('dependent read ready exports');
            };
        """.trimIndent(), listOf("codebridge.dependency"))
        run(listOf(root, dependency)) { session ->
            assertEquals("true", activate(session))
            assertEquals("true", activate(session))
            val activated = session.observedCalls().filter { it.method == $$"$onDidActivateExtension" }
                .map { Json.parseToJsonElement(it.argsJson).jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content }
            assertEquals(listOf("codebridge.dependency", "codebridge.root"), activated)
            assertNotNull(session.awaitCall(10, TimeUnit.SECONDS) { it.argsJson.contains("dependent read ready exports") })
        }
    }

    @Test fun `missing or disabled dependency fails activation with its identifier`() {
        val root = extension("root", "exports.activate = () => { throw new Error('must not run'); };", listOf("codebridge.absent"))
        run(listOf(root)) { session ->
            val error = assertFailsWith<IllegalStateException> { activate(session) }
            assertContains(error.message!!, "codebridge.absent")
            val call = assertNotNull(session.awaitCall(10, TimeUnit.SECONDS) { it.method == $$"$onExtensionActivationError" })
            assertEquals("codebridge.absent", ExtensionActivations.failure(call)?.missingDependency)
            assertFalse(session.observedCalls().any { it.method == $$"$onDidActivateExtension" })
        }
    }

    @Test fun `failed dependency prevents the dependent from running and repeated requests still fail`() {
        val dependency = extension("dependency", "exports.activate = () => { throw new Error('dependency exploded'); };")
        val root = extension("root", "exports.activate = () => { console.log('dependent must not run'); };", listOf("codebridge.dependency"))
        run(listOf(root, dependency)) { session ->
            repeat(2) {
                val error = assertFailsWith<IllegalStateException> { activate(session) }
                assertContains(error.message!!, "dependency")
                assertContains(error.message!!, "failed to activate")
            }
            assertFalse(session.observedCalls().any { it.argsJson.contains("dependent must not run") })
            assertFalse(session.observedCalls().any { it.method == $$"$onDidActivateExtension" })
        }
    }
}
