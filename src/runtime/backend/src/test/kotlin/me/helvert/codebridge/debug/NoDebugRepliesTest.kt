package me.helvert.codebridge.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** A run without debugging survives the adapter refusing what only a debugger does. */
class NoDebugRepliesTest {
    private val refusal = "Not supported in noDebug mode."

    private fun refused(seq: Int, command: String = "") =
        """{"seq":9,"type":"response","request_seq":$seq,"success":false,"command":"$command","message":"$refusal"}"""

    @Test
    fun `a refused debugger request becomes an empty success`() {
        val replies = NoDebugReplies()
        replies.sent("""{"seq":3,"type":"request","command":"threads"}""")
        replies.sent("""{"seq":4,"type":"request","command":"setBreakpoints","arguments":{"breakpoints":[{"line":1},{"line":5}]}}""")

        val threads = Json.parseToJsonElement(replies.received(refused(3))).jsonObject
        assertEquals(JsonPrimitive(true), threads["success"])
        assertEquals("threads", (threads["command"] as JsonPrimitive).content)
        assertEquals(0, threads["body"]!!.jsonObject["threads"]!!.jsonArray.size)

        val breakpoints = Json.parseToJsonElement(replies.received(refused(4, "setBreakpoints"))).jsonObject["body"]!!.jsonObject["breakpoints"]!!.jsonArray
        assertEquals(2, breakpoints.size)
        assertEquals(JsonPrimitive(false), breakpoints[0].jsonObject["verified"])
        assertEquals(JsonPrimitive(refusal), breakpoints[0].jsonObject["message"])
    }

    @Test
    fun `starting, stopping and successes keep their own answers`() {
        val replies = NoDebugReplies()
        replies.sent("""{"seq":1,"type":"request","command":"launch","arguments":{}}""")
        replies.sent("""{"seq":2,"type":"request","command":"disconnect"}""")
        replies.sent("""{"seq":3,"type":"request","command":"threads"}""")
        val ok = """{"seq":5,"type":"response","request_seq":3,"success":true,"command":"threads","body":{"threads":[{"id":1,"name":"main"}]}}"""

        assertEquals(refused(1), replies.received(refused(1)))
        assertEquals(refused(2, "disconnect"), replies.received(refused(2, "disconnect")))
        assertEquals(ok, replies.received(ok))
        // An answer to a request this did not see -- the extension's own -- is left alone too.
        assertEquals(refused(77), replies.received(refused(77)))
        val event = """{"seq":6,"type":"event","event":"output","body":{"output":"hi"}}"""
        assertEquals(event, replies.received(event))
        assertEquals(true, Json.parseToJsonElement(event) is JsonObject)
    }
}
