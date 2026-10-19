package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** `window.createTerminal` and what an extension then does with the terminal, as the host sends it. */
class TerminalsTest {

    @Test
    fun `reads a shell terminal`() {
        val create = Terminals.create(
            """["ext-1",{"name":"Build","shellPath":"/bin/bash","shellArgs":["-l"],"cwd":"/w/sub",
            "env":{"A":"1","GONE":null},"strictEnv":false,"isExtensionOwnedTerminal":true}]"""
        )
        assertEquals("ext-1", create.extHostId)
        assertEquals("Build", create.name)
        assertFalse(create.pseudoterminal)
        assertEquals("/bin/bash", create.shellPath)
        assertEquals(listOf("-l"), create.shellArgs)
        assertNull(create.shellArgsLine)
        assertEquals("/w/sub", create.cwd)
        assertEquals(mapOf("A" to "1", "GONE" to null), create.env)
    }

    @Test
    fun `reads an extension terminal, and windows shell arguments given as one line`() {
        val pty =
            Terminals.create("""["ext-2",{"name":"Pty","isExtensionCustomPtyTerminal":true,"isTransient":true}]""")
        assertTrue(pty.pseudoterminal)
        assertNull(pty.shellPath)
        val line =
            Terminals.create("""["ext-3",{"shellPath":"cmd.exe","shellArgs":"/k echo hi","hideFromUser":true}]""")
        assertNull(line.shellArgs)
        assertEquals("/k echo hi", line.shellArgsLine)
        assertTrue(line.hideFromUser)
    }

    @Test
    fun `a terminal is named by the workbench's number or the host's own id`() {
        // sendText straight after createTerminal still carries the host's string id.
        assertEquals("ext-1", Terminals.sendText("""["ext-1","ls",true]""").terminal.text)
        assertEquals("7", Terminals.ref(Terminals.SHOW, """[7,true]""").text)
        assertTrue(Terminals.preserveFocus("""[7,true]"""))
    }

    @Test
    fun `typed text ends lines with carriage returns, and executing adds one`() {
        assertEquals("echo a\recho b\r", Terminals.typed("echo a\necho b", execute = true))
        assertEquals("partial", Terminals.typed("partial", execute = false))
        assertEquals("done\r", Terminals.typed("done\r\n", execute = true))
    }

    @Test
    fun `reads what a pseudoterminal sends`() {
        assertEquals("hello\r\n", Terminals.processData("""[3,"hello\r\n"]"""))
        assertEquals(2, Terminals.exitCode("""[3,2]"""))
        assertNull(Terminals.exitCode("""[3,null]"""))
        assertEquals("New title", Terminals.title("""[3,{"type":"title","value":"New title"}]"""))
        assertNull(Terminals.title("""[3,{"type":"cwd","value":"/w"}]"""))
    }

    @Test
    fun `writes what the host is told`() {
        val config = Terminals.launchConfig("bash", "/bin/bash", listOf("-l"), "/w")
        assertEquals(
            """[4,"ext-1","bash",{"name":"bash","executable":"/bin/bash","args":["-l"],"cwd":"/w"}]""",
            Terminals.opened(4, "ext-1", "bash", config)
        )
        assertEquals(
            """[5,null,"task",{"name":"task","args":[]}]""",
            Terminals.opened(5, null, "task", Terminals.launchConfig("task", null, emptyList(), null))
        )
        assertEquals("[4,0,2]", Terminals.closed(4, 0, Terminals.ExitReason.PROCESS))
        assertEquals("[4,null,4]", Terminals.closed(4, null, Terminals.ExitReason.EXTENSION))
        assertEquals("""[4,{"columns":120,"rows":30}]""", Terminals.startExtensionTerminal(4, 120, 30))
        assertEquals("""[4,"x\r"]""", Terminals.input(4, "x\r"))
        assertEquals("[4,true]", Terminals.shutdown(4, true))
    }

    @Test
    fun `a custom execution names the terminal its task runs in`() {
        val execution =
            Tasks.executionDto("t1", Json.parseToJsonElement("""{"name":"n","definition":{"type":"x"}}""").jsonObject)
        assertEquals(
            """[{"id":"t1","task":{"name":"n","definition":{"type":"x"}}},9,{"type":"x"}]""",
            Tasks.started(execution, 9)
        )
    }
}
