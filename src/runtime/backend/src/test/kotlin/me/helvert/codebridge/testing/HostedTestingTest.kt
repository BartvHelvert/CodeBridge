package me.helvert.codebridge.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Testing
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.started
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A controller's tree as its diffs build it, the discovery asked for, and a run from start to end. */
class HostedTestingTest {

    private class Host : HostedTesting.HostCalls {
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        val replies = mutableMapOf<String, CompletableFuture<String>>()
        /** Answers as the method's future completes, so a test can hold a reply back. */
        override suspend fun call(
            service: String,
            method: String,
            argsJson: String,
            cancellable: Boolean
        ): String {
            calls += Triple(method, argsJson, cancellable)
            return replies.getOrPut(method) { CompletableFuture.completedFuture("null") }.await()
        }

        fun args(method: String) = calls.filter { it.first == method }.map { it.second }
    }

    private val host = Host()
    private val testing = HostedTesting(CoroutineScope(SupervisorJob() + Dispatchers.Default)).apply { hostStarted(host) }

    private fun call(method: String, args: String, buffers: Map<Int, ByteArray> = emptyMap()) =
        started { testing.handle(MainThreadCall(Testing.SERVICE, method, args, buffers)) }.get(5, TimeUnit.SECONDS)

    /** The reply is followed off the thread that completes it, so the run ends a moment later. */
    private fun assertEnded(run: HostedTesting.Run) = runBlocking { withTimeout(5.seconds) { run.ended.await() } }

    private val file = "/w/a.test.ts"
    private val fileKey = UriComponents.key("file://$file")

    private fun item(extId: String, label: String, line: Int? = null, path: String? = file) =
        """{"extId":"$extId","label":"$label","tags":[],"busy":false,${path?.let { """"uri":{"scheme":"file","path":"$it"},""" } ?: ""}
           "range":${line?.let { """{"startLineNumber":$it,"startColumn":1,"endLineNumber":${it + 2},"endColumn":2}""" } ?: "null"}}"""

    private fun add(extId: String, label: String, expand: Int = 0, line: Int? = null, path: String? = file) =
        """{"op":0,"item":{"expand":$expand,"item":${item(extId, label, line, path)}}}"""

    private fun register() {
        call(Testing.REGISTER_CONTROLLER, """["ctrl","Mocha",2]""")
        call(
            Testing.PUBLISH_PROFILE,
            """[{"profileId":1,"controllerId":"ctrl","label":"Run","group":2,"isDefault":true}]"""
        )
        call(
            Testing.PUBLISH_DIFF,
            """["ctrl",[${add("ctrl", "Mocha", path = null)},{"op":1,"item":{"extId":"ctrl","expand":1}}]]"""
        )
    }

    @Test
    fun `discovers a controller's first level, then the files someone looks at`() {
        register()
        assertEquals(
            listOf("""["ctrl",1]"""),
            host.args(Testing.EXPAND),
            "The root's first level, as the test view asks"
        )

        call(
            Testing.PUBLISH_DIFF, """["ctrl",[${add("ctrl\u0000a.test.ts", "a.test.ts", expand = 1)},
            ${add("ctrl\u0000src", "src", expand = 1, path = "/w/src")}]]"""
        )
        assertEquals(1, host.args(Testing.EXPAND).size, "Nothing below the first level until a file is asked about")

        assertEquals(emptyList(), testing.itemsIn(fileKey).filter { it.range != null })
        assertEquals("""["ctrl\u0000a.test.ts",-1]""", host.args(Testing.EXPAND).last(), "The file, all the way down")

        call(
            Testing.PUBLISH_DIFF, """["ctrl",[{"op":1,"item":{"extId":"ctrl\u0000a.test.ts","expand":3}},
            ${add("ctrl\u0000a.test.ts\u0000adds", "adds", line = 4)}]]"""
        )
        assertEquals(listOf("adds"), testing.itemsIn(fileKey).filter { it.range != null }.map { it.label })

        // A file inside a folder item leads the folder to be discovered one level at a time.
        testing.itemsIn(UriComponents.key("file:///w/src/b.test.ts"))
        assertEquals("""["ctrl\u0000src",1]""", host.args(Testing.EXPAND).last())
        assertEquals(3, host.args(Testing.EXPAND).size)

        call(Testing.PUBLISH_DIFF, """["ctrl",[{"op":3,"itemId":"ctrl\u0000a.test.ts"}]]""")
        assertEquals(emptyList(), testing.itemsIn(fileKey))
    }

    @Test
    fun `tells listeners which files' tests changed`() {
        register()
        val heard = mutableListOf<Set<String>>()
        testing.addChangeListener({ heard += it }, com.intellij.openapi.util.Disposer.newDisposable())
        call(Testing.PUBLISH_DIFF, """["ctrl",[${add("ctrl\u0000a.test.ts", "a.test.ts")}]]""")
        assertEquals(listOf(setOf(fileKey)), heard)
    }

    @Test
    fun `a run reports its tests until its tasks end and the handler returns`() {
        register()
        call(
            Testing.PUBLISH_DIFF,
            """["ctrl",[${add("ctrl\u0000a.test.ts", "a.test.ts")},${
                add(
                    "ctrl\u0000a.test.ts\u0000adds",
                    "adds",
                    line = 4
                )
            }]]"""
        )
        val reply = CompletableFuture<String>()
        host.replies[Testing.RUN_CONTROLLER_TESTS] = reply

        val run = testing.run(listOf("ctrl\u0000a.test.ts"), Testing.Group.RUN)
        val (_, args, cancellable) = host.calls.single { it.first == Testing.RUN_CONTROLLER_TESTS }
        assertTrue(cancellable)
        val request = Json.parseToJsonElement(args).jsonArray[0].jsonArray[0].jsonObject
        assertEquals(run.id, request["runId"]!!.jsonPrimitive.content)
        assertEquals(1, request["profileId"]!!.jsonPrimitive.content.toInt())
        assertEquals("ctrl\u0000a.test.ts", request["testIds"]!!.jsonArray.single().jsonPrimitive.content)

        // Reported before anyone attached: kept, and replayed in order.
        call(Testing.STARTED_TASK, """["${run.id}",{"id":"t1","ctrlId":"ctrl","name":"Mocha","running":true}]""")
        val events = mutableListOf<HostedTesting.Run.Event>()
        run.attach { events += it }
        call(
            Testing.ADD_TESTS,
            """["ctrl","${run.id}",[${item("ctrl", "Mocha", path = null)},${
                item(
                    "ctrl\u0000a.test.ts",
                    "a.test.ts"
                )
            }]]"""
        )
        call(Testing.UPDATE_STATE, """["${run.id}","t1","ctrl\u0000a.test.ts\u0000adds",2]""")
        call(
            Testing.APPEND_OUTPUT,
            """["${run.id}","t1",null,null,"ctrl\u0000a.test.ts\u0000adds"]""",
            mapOf(Testing.OUTPUT_BUFFER to "log\r\n".toByteArray())
        )
        call(
            Testing.APPEND_MESSAGES,
            """["${run.id}","t1","ctrl\u0000a.test.ts\u0000adds",[{"message":"no","type":0,"expected":"2","actual":"1"}]]"""
        )
        call(Testing.UPDATE_STATE, """["${run.id}","t1","ctrl\u0000a.test.ts\u0000adds",4,7]""")
        call(Testing.FINISHED_TASK, """["${run.id}","t1"]""")
        assertFalse(run.ended.isCompleted, "The handler has not returned")
        reply.complete("[{}]")
        assertEnded(run)

        assertEquals(HostedTesting.Run.Event.TaskStarted("Mocha"), events[0])
        assertEquals(
            listOf("ctrl", "ctrl\u0000a.test.ts"),
            assertIs<HostedTesting.Run.Event.Added>(events[1]).items.map { it.extId })
        assertEquals(Testing.State.RUNNING, assertIs<HostedTesting.Run.Event.State>(events[2]).state)
        assertEquals(HostedTesting.Run.Event.Output("log\r\n", "ctrl\u0000a.test.ts\u0000adds"), events[3])
        val failed = assertIs<HostedTesting.Run.Event.State>(events[4])
        assertEquals(Testing.State.FAILED, failed.state)
        assertEquals(7.0, failed.duration)
        assertEquals("2", failed.messages.single().expected, "Messages travel with the state they explain")
        assertEquals(HostedTesting.Run.Event.Ended(null), events.last())
        assertEquals(Testing.State.FAILED, testing.lastResult("ctrl\u0000a.test.ts\u0000adds"))
    }

    @Test
    fun `a long run ends when its tasks do, though the request timed out`() {
        register()
        val reply = CompletableFuture<String>()
        host.replies[Testing.RUN_CONTROLLER_TESTS] = reply
        val run = testing.run(emptyList(), Testing.Group.RUN)
        call(Testing.STARTED_TASK, """["${run.id}",{"id":"t1","ctrlId":"ctrl","name":"Mocha","running":true}]""")
        reply.completeExceptionally(TimeoutException())
        assertFalse(run.ended.isCompleted)
        call(Testing.FINISHED_TASK, """["${run.id}","t1"]""")
        assertEnded(run)
    }

    @Test
    fun `a controller without a debug profile cannot debug`() {
        register()
        assertTrue(testing.canRun(emptyList(), Testing.Group.RUN))
        assertFalse(testing.canRun(emptyList(), Testing.Group.DEBUG))
        val events = mutableListOf<HostedTesting.Run.Event>()
        testing.run(emptyList(), Testing.Group.DEBUG).attach { events += it }
        assertEquals(HostedTesting.Run.Event.Ended("Mocha cannot debug tests"), events.single())
    }

    @Test
    fun `a run the extension starts itself is opened, and ends when it says`() {
        register()
        val opened = mutableListOf<HostedTesting.Run>()
        testing.setRunOpener({ opened += it }, com.intellij.openapi.util.Disposer.newDisposable())
        call(
            Testing.STARTED_EXTENSION_RUN,
            """[{"controllerId":"ctrl","continuous":false,"exclude":[],"id":"ext","include":["ctrl"],"preserveFocus":true,"persist":true}]"""
        )
        val run = opened.single()
        assertEquals("ext", run.id)
        call(Testing.STARTED_TASK, """["ext",{"id":"t1","ctrlId":"ctrl","name":"Mocha","running":true}]""")
        call(Testing.FINISHED_TASK, """["ext","t1"]""")
        assertFalse(run.ended.isCompleted, "An extension's run is over when it says so")
        call(Testing.FINISHED_EXTENSION_RUN, """["ext"]""")
        assertTrue(run.ended.isCompleted)
    }

    @Test
    fun `stopping the host ends its runs`() {
        register()
        host.replies[Testing.RUN_CONTROLLER_TESTS] = CompletableFuture()
        val run = testing.run(emptyList(), Testing.Group.RUN)
        testing.hostStopped()
        val events = mutableListOf<HostedTesting.Run.Event>()
        run.attach { events += it }
        assertEquals(HostedTesting.Run.Event.Ended("The extension host stopped"), events.single())
    }
}
