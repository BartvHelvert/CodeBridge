package me.helvert.codebridge.exthost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import me.helvert.codebridge.exthost.protocol.RpcMessage
import java.util.concurrent.*
import kotlin.test.*

class WorkbenchDispatcherTest {
    @Test fun `pending command permits nested requests and observes registrations before activation reply`() {
        val started = CountDownLatch(1)
        val activation = CompletableDeferred<String>()
        val replies = LinkedBlockingQueue<RpcMessage>()
        var registered = false
        val handler = object : MainThreadHandler {
            override fun handle(call: MainThreadCall): String? {
                if (call.method == "register") registered = true
                return null
            }
            override suspend fun answer(call: MainThreadCall): MainThreadResult {
                if (call.method != "execute") return MainThreadResult(handle(call))
                started.countDown()
                activation.await()
                check(registered) { "Activation completed before its command registration was handled" }
                return MainThreadResult("42")
            }
        }
        WorkbenchDispatcher(handler, replies::add).use { dispatcher ->
            fun request(id: Int, method: String) = dispatcher.request(RpcMessage.Request(id, 1, method, "[]"), MainThreadCall("Commands", method, "[]"))
            request(1, "execute")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            request(2, "register")
            dispatcher.receivedReply { activation.complete("true") }
            assertEquals(RpcMessage.ReplyOkEmpty(2), replies.poll(2, TimeUnit.SECONDS))
            assertEquals(RpcMessage.ReplyOkJson(1, "42"), replies.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `cancellation reaches pending work without stopping later requests`() {
        val started = CountDownLatch(1)
        val cancelled = CompletableFuture<Boolean>()
        val replies = LinkedBlockingQueue<RpcMessage>()
        WorkbenchDispatcher(object : MainThreadHandler {
            override fun handle(call: MainThreadCall): String? = null
            override suspend fun answer(call: MainThreadCall): MainThreadResult {
                started.countDown()
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(true)
                }
            }
        }, replies::add).use { dispatcher ->
            dispatcher.request(RpcMessage.Request(1, 1, "search", "[]", true), MainThreadCall("Workspace", "search", "[]"))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            dispatcher.cancel(1)
            assertIs<RpcMessage.ReplyError>(replies.poll(2, TimeUnit.SECONDS))
            assertTrue(cancelled.get(2, TimeUnit.SECONDS))
        }
    }

    @Test fun `a call with nested buffers reaches its handler with them`() {
        val replies = LinkedBlockingQueue<RpcMessage>()
        val seen = CompletableFuture<List<ByteArray>>()
        val handler = MainThreadHandler { call -> seen.complete(call.objectBuffers[0].orEmpty()); "true" }
        WorkbenchDispatcher(handler, replies::add).use { dispatcher ->
            // A notebook kernel's `$updateExecution`: its outputs' bytes beside the JSON.
            val buffers = mapOf(0 to listOf(byteArrayOf(1)))
            dispatcher.request(RpcMessage.Request(1, 1, "update", "[null]", objectBuffers = buffers),
                MainThreadCall("Kernels", "update", "[null]", objectBuffers = buffers))
            assertEquals(RpcMessage.ReplyOkJson(1, "true"), replies.poll(2, TimeUnit.SECONDS))
            assertEquals(listOf(1), seen.get(2, TimeUnit.SECONDS).single().map { it.toInt() })
        }
    }
}
