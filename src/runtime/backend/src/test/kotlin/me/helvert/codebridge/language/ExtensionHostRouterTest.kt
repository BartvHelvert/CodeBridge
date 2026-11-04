package me.helvert.codebridge.language

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.helvert.codebridge.exthost.ExtensionHost
import me.helvert.codebridge.exthost.HostReply
import me.helvert.codebridge.exthost.MainThreadCall
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Where the workbench's calls go between the Node host and the web worker host, and how the worker's numbers come in. */
class ExtensionHostRouterTest {
    /** A host that records what it is asked, and answers with [answer]. */
    private class Host(val name: String, val answer: suspend (String, String) -> String = { _, _ -> "\"$name\"" }) : ExtensionHost {
        val calls = CopyOnWriteArrayList<String>()
        override val termination: Deferred<Throwable> = CompletableDeferred()

        override suspend fun call(
            service: String, method: String, argsJson: String, cancellable: Boolean,
            buffers: Map<Int, ByteArray>, objectBuffers: Map<Int, List<ByteArray>>,
        ): String {
            calls += "$method $argsJson"
            return answer(method, argsJson)
        }

        override suspend fun callForBytes(service: String, method: String, argsJson: String, cancellable: Boolean): ByteArray? =
            call(service, method, argsJson).encodeToByteArray()

        override suspend fun callWithBuffers(
            service: String, method: String, argsJson: String, cancellable: Boolean,
            buffers: Map<Int, ByteArray>, objectBuffers: Map<Int, List<ByteArray>>, timesOut: Boolean,
        ): HostReply = HostReply(call(service, method, argsJson))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val node = Host("node")
    private val worker = Host("worker")
    private val router = ExtensionHostRouter(node, worker, setOf("acme.web"), scope)
    private val offset = ExtensionHostRouter.WORKER_OFFSET

    @AfterTest
    fun cleanUp() = scope.cancel()

    private fun from(host: ExtensionHost, service: String, method: String, args: String) =
        router.incoming(MainThreadCall(service, method, args), host).argsJson

    @Test
    fun `the worker host's handles come in moved up, and calls on them go back to it moved down`() = runBlocking {
        assertEquals("[${offset + 3},[\"x\"],${offset + 4}]",
            from(worker, "MainThreadLanguageFeatures", $$"$registerCodeLensSupport", """[3,["x"],4]"""))
        // The Node host's are as they were.
        assertEquals("""[3,["x"],4]""", from(node, "MainThreadLanguageFeatures", $$"$registerCodeLensSupport", """[3,["x"],4]"""))
        // A handle the host left out stays out.
        assertEquals("[${offset + 5},[],\"acme.web\",null]",
            from(worker, "MainThreadLanguageFeatures", $$"$registerFoldingRangeProvider", """[5,[],"acme.web",null]"""))

        assertEquals("\"worker\"", router.call("ExtHostLanguageFeatures", $$"$provideHover", "[${offset + 3},{}]"))
        assertEquals(listOf($$"$provideHover [3,{}]"), worker.calls)
        assertEquals("\"node\"", router.call("ExtHostLanguageFeatures", $$"$provideHover", "[3,{}]"))
        assertEquals(listOf($$"$provideHover [3,{}]"), node.calls)
    }

    @Test
    fun `a quick pick the worker host shows is answered there`() = runBlocking {
        assertEquals("[{\"id\":${offset + 1},\"title\":\"t\"}]", from(worker, "MainThreadQuickOpen", $$"$createOrUpdate", """[{"id":1,"title":"t"}]"""))
        from(worker, "MainThreadQuickOpen", $$"$show", "[2,{},null]")
        router.call("ExtHostQuickOpen", $$"$onItemSelected", "[7]")
        router.call("ExtHostQuickOpen", $$"$onDidAccept", "[${offset + 1}]")
        assertEquals(listOf($$"$onItemSelected [7]", $$"$onDidAccept [1]"), worker.calls)
    }

    @Test
    fun `what a host registers by name is asked of that host`() = runBlocking {
        from(worker, "MainThreadCommands", $$"$registerCommand", """["web.draw"]""")
        from(node, "MainThreadCommands", $$"$registerCommand", """["node.build"]""")
        router.call("ExtHostCommands", $$"$executeContributedCommand", """["web.draw",1]""")
        router.call("ExtHostCommands", $$"$executeContributedCommand", """["node.build"]""")
        // Unknown names go where everything went before there was a second host.
        router.call("ExtHostCommands", $$"$executeContributedCommand", """["nobody.knows"]""")
        assertEquals(listOf($$"$executeContributedCommand [\"web.draw\",1]"), worker.calls)
        assertEquals(listOf($$"$executeContributedCommand [\"node.build\"]", $$"$executeContributedCommand [\"nobody.knows\"]"), node.calls)

        from(worker, "MainThreadCommands", $$"$unregisterCommand", """["web.draw"]""")
        router.call("ExtHostCommands", $$"$executeContributedCommand", """["web.draw"]""")
        assertEquals(3, node.calls.size)
    }

    @Test
    fun `a webview revived or resolved by a host is that host's from then on`() = runBlocking {
        from(worker, "MainThreadWebviewViews", $$"$registerWebviewViewProvider", """[{"id":"acme.web"},"web.view",{}]""")
        router.call("ExtHostWebviewViews", $$"$resolveWebviewView", """["handle-1","web.view","Title",null]""")
        router.call("ExtHostWebviews", $$"$onMessage", """["handle-1","{}",[]]""")
        assertEquals(2, worker.calls.size)
        assertEquals(0, node.calls.size)
    }

    @Test
    fun `a custom editor's documents and pages are its provider's host's`() = runBlocking {
        from(worker, "MainThreadCustomEditors", $$"$registerCustomEditorProvider", """[{"id":"acme.web"},"web.draw",{},{},false,false]""")
        val file = """{"scheme":"file","path":"/a.draw"}"""
        router.call("ExtHostCustomEditors", $$"$createCustomDocument", """[$file,"web.draw",null,null]""")
        router.call("ExtHostCustomEditors", $$"$resolveCustomEditor", """[$file,"handle-1","web.draw",{},0]""")
        router.call("ExtHostWebviews", $$"$onMessage", """["handle-1","{}",[]]""")
        router.call("ExtHostCustomEditors", $$"$onSave", """[$file,"web.draw"]""")
        assertEquals(4, worker.calls.size)
        assertEquals(0, node.calls.size)

        from(worker, "MainThreadCustomEditors", $$"$unregisterEditorProvider", """["web.draw"]""")
        router.call("ExtHostCustomEditors", $$"$createCustomDocument", """[$file,"web.draw",null,null]""")
        assertEquals(1, node.calls.size)
    }

    @Test
    fun `an activation goes to the extension's host`() = runBlocking {
        router.call("ExtHostExtensionService", $$"$activate", """[{"value":"Acme.Web","_lower":"acme.web"},{}]""")
        router.call("ExtHostExtensionService", $$"$activate", """[{"value":"acme.node","_lower":"acme.node"},{}]""")
        assertEquals(1, worker.calls.size)
        assertEquals(1, node.calls.size)
    }

    @Test
    fun `news every host keeps reaches both, and is answered without waiting for the worker`() = runBlocking {
        val slow = Host("slow") { _, _ -> awaitCancellation() }
        val router = ExtensionHostRouter(node, slow, emptySet(), scope)
        assertEquals("\"node\"", withTimeout(5.seconds) { router.call("ExtHostDocuments", $$"$acceptModelChanged", "[1]") })
        assertEquals(listOf($$"$acceptModelChanged [1]"), slow.calls)
    }

    @Test
    fun `test runs are split between the hosts their controllers are in, and answered in order`() = runBlocking {
        val node = Host("node") { _, _ -> """[{"error":"n"}]""" }
        val worker = Host("worker") { _, _ -> """[{"error":"w"}]""" }
        val router = ExtensionHostRouter(node, worker, emptySet(), scope)
        router.incoming(MainThreadCall("MainThreadTesting", $$"$registerTestController", """["web","Web",0]"""), worker)
        val reply = router.call("ExtHostTesting", $$"$runControllerTests", """[[{"controllerId":"web","runId":"1"},{"controllerId":"node","runId":"2"}],null]""")
        assertEquals("""[{"error":"w"},{"error":"n"}]""", reply)
        assertEquals(listOf($$"$runControllerTests [[{\"controllerId\":\"web\",\"runId\":\"1\"}],null]"), worker.calls)
    }

    @Test
    fun `a starting host takes calls in order once it is ready`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val starting = StartingHost(worker, ready)
        val first = async { starting.call("S", "first", "[]") }
        val second = async { starting.call("S", "second", "[]") }
        kotlinx.coroutines.yield()
        assertEquals(emptyList(), worker.calls)
        ready.complete(Unit)
        first.await(); second.await()
        assertEquals(listOf("first []", "second []"), worker.calls)
    }
}
