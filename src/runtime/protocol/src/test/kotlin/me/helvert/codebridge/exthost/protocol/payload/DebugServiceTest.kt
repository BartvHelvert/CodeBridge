package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a debugger extension registers, and how its arguments are ordered. */
class DebugServiceTest {

    @Test
    fun `reads an adapter factory registration`() {
        val registration = DebugService.factoryRegistration("""["lldb",2]""")
        assertEquals("lldb", registration.type)
        assertEquals(2, registration.handle)
    }

    @Test
    fun `a configuration provider carries its handle last, not first`() {
        // The opposite of every other registration on this protocol, and the reason this is pinned.
        val registration = DebugService.providerRegistration("""["lldb",1,true,true,false,9]""")
        assertEquals("lldb", registration.type)
        assertEquals(9, registration.handle)
        assertTrue(registration.resolve)
        assertEquals(false, registration.afterSubstitution)
    }

    @Test
    fun `console output is the last argument`() {
        assertEquals("linking...", DebugService.consoleText("""["session-1",0,"linking..."]"""))
    }

    @Test
    fun `finds a file uri nested inside command arguments`() {
        // `$executeCommand` carries whatever the extension chose to pass, at whatever depth.
        val nested = Json.parseToJsonElement(
            """[{"wrapper":{"uri":{"scheme":"file","path":"/C:/downloads/probe.vsix"}}}]"""
        )
        assertEquals("/C:/downloads/probe.vsix", DebugService.findFileUri(nested)?.path)
    }

    @Test
    fun `ignores a resource that is not a file`() {
        val nested = Json.parseToJsonElement("""[{"scheme":"https","path":"/x"}]""")
        assertNull(DebugService.findFileUri(nested))
    }

    @Test
    fun `reads a debug start by launch json name`() {
        val start = DebugService.startDebugging("""[{"scheme":"file","path":"/w"},"Launch",{"noDebug":true,"repl":"separate"}]""")
        assertEquals("Launch", start.name)
        assertNull(start.configuration)
        assertTrue(start.noDebug)
        assertEquals("/w", start.folder?.path)
    }

    @Test
    fun `reads a debug start with a configuration the extension wrote`() {
        // A code lens's Debug: no folder, the configuration inline, and no noDebug.
        val start = DebugService.startDebugging("""[null,{"type":"lldb","request":"launch","name":"Debug main"},{"repl":"separate"}]""")
        assertNull(start.name)
        assertNull(start.folder)
        assertEquals("lldb", start.configuration!!["type"]!!.toString().trim('"'))
        assertEquals(false, start.noDebug)
        assertNull(start.parentSessionId)
    }

    @Test
    fun `reads a child debug start`() {
        // js-debug's attach to a target it found, under the session that found it.
        val start = DebugService.startDebugging("""[null,{"type":"pwa-node","request":"attach","name":"worker"},""" +
            """{"parentSessionID":"p-1","lifecycleManagedByParent":true,"repl":"mergeWithParent"}]""")
        assertEquals("p-1", start.parentSessionId)
        assertTrue(start.lifecycleManagedByParent)
    }

    @Test
    fun `refuses a debug start that names nothing to start`() {
        assertFailsWith<PayloadException> { DebugService.startDebugging("""[null,3,{}]""") }
    }

    @Test
    fun `builds the activation the host wakes a debugger extension with`() {
        val activation = DebugService.debugActivation("vadimcn.vscode-lldb", "lldb")
        assertTrue(activation.contains(""""activationEvent":"onDebug:lldb""""), activation)
        // The identifier is sent twice, as the extension to activate and inside the reason.
        assertEquals(4, Regex("vadimcn.vscode-lldb").findAll(activation).count(), activation)
        // The host looks the extension up by `_lower`; without it nothing activates.
        val mixed = DebugService.debugActivation("Vadimcn.vscode-lldb", "lldb")
        assertTrue(mixed.startsWith("""[{"value":"Vadimcn.vscode-lldb","_lower":"vadimcn.vscode-lldb"}"""), mixed)
    }
}

/** The DAP relay: handles in, raw messages through. */
class DebugAdaptersTest {

    @Test
    fun `gives a stack frame back the path the adapter wrote`() {
        // CodeLLDB's reply as the host relays it: the frame's path made a URI, the disassembly frame's left alone.
        val relayed = """{"seq":34,"type":"response","request_seq":8,"success":true,"command":"stackTrace","body":{"stackFrames":[""" +
            """{"id":1001,"line":22,"column":0,"name":"main","source":{"name":"main.rs","path":""" +
            $$"""{"$mid":1,"fsPath":"c:\\Projects\\s\\main.rs","external":"file:///c%3A/Projects/s/main.rs","path":"/c:/Projects/s/main.rs","scheme":"file"}}},""" +
            """{"id":1002,"line":8,"column":0,"name":"Nt","source":{"name":"@Nt","origin":"disassembly","sourceReference":1000}}]}}"""
        val frames = Json.parseToJsonElement(DebugAdapters.adapterPaths(relayed)).jsonObject["body"]!!.jsonObject["stackFrames"]!!.jsonArray
        assertEquals("c:\\Projects\\s\\main.rs", frames[0].jsonObject["source"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertNull(frames[1].jsonObject["source"]!!.jsonObject["path"])
    }

    @Test
    fun `converts breakpoint events, and leaves what vs code leaves`() {
        val event = """{"type":"event","event":"breakpoint","body":{"reason":"changed","breakpoint":{"id":1,"verified":true,""" +
            """"source":{"path":{"scheme":"file","path":"/tmp/a.rs"}}}}}"""
        val path = Json.parseToJsonElement(DebugAdapters.adapterPaths(event)).jsonObject["body"]!!.jsonObject["breakpoint"]!!
            .jsonObject["source"]!!.jsonObject["path"]!!.jsonPrimitive.content
        assertTrue(path.endsWith("a.rs") && !path.startsWith("{"), path)
        // A message with nothing converted is passed on as the same text.
        val stopped = """{"type":"event","event":"stopped","body":{"reason":"breakpoint","threadId":1}}"""
        assertEquals(stopped, DebugAdapters.adapterPaths(stopped))
        val failed = """{"type":"response","command":"stackTrace","success":false,"body":{"error":{"id":1}}}"""
        assertEquals(failed, DebugAdapters.adapterPaths(failed))
    }

    @Test
    fun `relays a dap message without decoding it`() {
        // The body is the debugger's own protocol; this side only carries it.
        val event = DebugAdapters.event(DebugAdapters.ACCEPT_MESSAGE,
            """[1,{"type":"event","event":"stopped","body":{"reason":"breakpoint"}}]""")
        val message = event as DebugAdapters.Event.Message
        assertEquals(1, message.handle)
        assertTrue(message.json.contains(""""reason":"breakpoint""""), message.json)
    }

    @Test
    fun `reports an adapter failure and an exit apart`() {
        assertTrue(DebugAdapters.event(DebugAdapters.ACCEPT_ERROR, """[2,"spawn ENOENT"]""")
            is DebugAdapters.Event.Failed)
        assertTrue(DebugAdapters.event(DebugAdapters.ACCEPT_EXIT, """[2,127]""")
            is DebugAdapters.Event.Exited)
    }

    @Test
    fun `an exit with no code still names the adapter`() {
        val event = DebugAdapters.event(DebugAdapters.ACCEPT_EXIT, "[2]") as DebugAdapters.Event.Exited
        assertEquals(2, event.handle)
        assertEquals("null", event.detail)
    }

    @Test
    fun `ignores a method that is not adapter traffic`() {
        assertNull(DebugAdapters.event(DebugService.APPEND_CONSOLE, """["s",0,"text"]"""))
    }

    @Test
    fun `builds the calls that drive one adapter`() {
        assertEquals("""[3,{"id":"s"}]""", DebugAdapters.startSession(3, """{"id":"s"}"""))
        assertEquals("""[3,{"seq":1}]""", DebugAdapters.sendMessage(3, """{"seq":1}"""))
        assertEquals("[3]", DebugAdapters.stopSession(3))
    }

    @Test
    fun `reads an extension's custom request and the session it is for`() {
        val request = DebugService.customRequest("""["s1","toggleSkipFileStatus",{"resource":"a.js"}]""")
        assertEquals("s1", request.sessionId)
        assertEquals("toggleSkipFileStatus", request.command)
        assertEquals("a.js", request.arguments!!.jsonObject["resource"]!!.jsonPrimitive.content)
        assertNull(DebugService.customRequest("""["s1","x",null]""").arguments)
    }

    @Test
    fun `stopDebugging names one session or none, which is all of them`() {
        assertEquals("s1", DebugService.stoppedSession("""["s1"]"""))
        assertNull(DebugService.stoppedSession("[null]"))
        assertNull(DebugService.stoppedSession("[]"))
    }

    @Test
    fun `only events VS Code does not handle itself are the extension's`() {
        assertEquals("copyRequested", DebugAdapters.customEvent("""{"seq":4,"type":"event","event":"copyRequested","body":{"text":"x"}}""")!!["event"]!!.jsonPrimitive.content)
        assertNull(DebugAdapters.customEvent("""{"seq":4,"type":"event","event":"stopped","body":{}}"""))
        assertNull(DebugAdapters.customEvent("""{"seq":4,"type":"response","command":"copyRequested","request_seq":1,"success":true}"""))
    }

    @Test
    fun `a custom request is answered with its response's body, or fails with its message`() {
        val request = Json.parseToJsonElement(DebugAdapters.request(1_000_000_001, "pretty", null)).jsonObject
        assertEquals("pretty", request["command"]!!.jsonPrimitive.content)
        assertEquals(null, request["arguments"])
        val ok = """{"seq":9,"type":"response","request_seq":1000000001,"command":"pretty","success":true,"body":{"a":1}}"""
        assertEquals(1_000_000_001, DebugAdapters.responseSeq(ok))
        assertEquals("""{"a":1}""", DebugAdapters.customResponse(ok).getOrThrow())
        assertNull(DebugAdapters.responseSeq("""{"seq":9,"type":"event","event":"output"}"""))
        val refused = DebugAdapters.customResponse("""{"seq":9,"type":"response","request_seq":1,"command":"pretty","success":false,"message":"No source"}""")
        assertEquals("No source", refused.exceptionOrNull()!!.message)
    }
}
