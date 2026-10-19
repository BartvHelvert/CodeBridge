package me.helvert.codebridge.launch

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.exthost.protocol.payload.CodeActions
import me.helvert.codebridge.ide.EditorFixture
import me.helvert.codebridge.started
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What of an extension's `startDebugging` is kept to run again; see [CodeBridgeStartDebugging.kept]. */
class ExtensionLaunchesKeptTest : EditorFixture() {

    private val runMain = CodeActions.Command(
        "java.debug.runCodeLens",
        "Run",
        JsonArray(listOf(JsonPrimitive("app.Main"), JsonPrimitive("demo")))
    )

    // java-debug's: the progress handle is good for one run only.
    private val generated = DebugSource.Extension(buildJsonObject {
        put("name", "Main"); put("type", "java"); put("request", "launch"); put("__progressId", "4f1c")
    })

    @Test
    fun `an extension's configuration is kept as the command that started it, never as itself`() {
        assertEquals(
            DebugSource.Command("Main", runMain),
            CodeBridgeStartDebugging.kept(generated, parent = null, CodeBridgeDebugOrigins.Origin(runMain), testing = false)
        )
    }

    @Test
    fun `a test run's session, a child, or one of no known command is not kept`() {
        val origin = CodeBridgeDebugOrigins.Origin(runMain)
        assertNull(CodeBridgeStartDebugging.kept(generated, parent = null, origin, testing = true))
        assertNull(CodeBridgeStartDebugging.kept(generated, parent = "session-1", origin, testing = false))
        assertNull(CodeBridgeStartDebugging.kept(generated, parent = null, origin = null, testing = false))
    }

    @Test
    fun `a launch json entry is kept by name, test run or not`() {
        val entry = DebugSource.LaunchJson("Server")
        assertEquals(entry, CodeBridgeStartDebugging.kept(entry, parent = null, origin = null, testing = true))
    }

    @Test
    fun `a command is the origin of what starts while it runs, and only then`() {
        val reply = CompletableFuture<String>()
        val origin = CodeBridgeDebugOrigins.Origin(runMain, noDebug = true)
        val running = started { CodeBridgeDebugOrigins.during(fixture.project, origin) { reply.await() } }
        assertEquals(origin, CodeBridgeDebugOrigins.current(fixture.project))
        reply.complete("")
        running.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(CodeBridgeDebugOrigins.current(fixture.project))
    }

    @Test
    fun `a kept command is written out and read back`() {
        val factory = CodeBridgeDebugConfigurationType().configurationFactories.first()
        val configuration = CodeBridgeDebugRunConfiguration(fixture.project, factory)
        configuration.source = DebugSource.Command("Main", runMain)
        val element = org.jdom.Element("configuration")
        configuration.writeExternal(element)

        val read = CodeBridgeDebugRunConfiguration(fixture.project, factory)
        read.readExternal(element)

        assertEquals(DebugSource.Command("Main", runMain), read.source)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("app.Main"), JsonPrimitive("demo"))),
            (read.source as DebugSource.Command).command.arguments
        )
    }

    @Test
    fun `a kept command runs as its command, not through the debug adapter`() {
        val configuration =
            CodeBridgeDebugRunConfiguration(fixture.project, CodeBridgeDebugConfigurationType().configurationFactories.first())
        configuration.source = DebugSource.Command("Main", runMain)
        assertTrue(CodeBridgeCommandRunner().canRun("Run", configuration))
        assertTrue(CodeBridgeCommandRunner().canRun("Debug", configuration))
        assertFalse(LaunchJsonDapArgumentsProvider().isApplicable("Run", configuration))
        assertFalse(LaunchJsonDapArgumentsProvider().isApplicable("Debug", configuration))
    }
}
