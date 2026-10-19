package me.helvert.codebridge.debug

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an adapter made of each breakpoint, read off the DAP messages as they pass. */
class DapBreakpointsTest {

    private val file = Path.of("C:/w/src/main.rs").toAbsolutePath()
    private val path = file.toString().replace("\\", "\\\\")

    private fun DapBreakpoints.idAt(line: Int) = (find("s1", file, line)?.get("id") as? JsonPrimitive)?.intOrNull

    @Test
    fun `a setBreakpoints reply names the adapter's breakpoints, line for line`() {
        val breakpoints = DapBreakpoints()
        breakpoints.sent("s1", """{"seq":1,"type":"request","command":"initialize","arguments":{"linesStartAt1":true}}""")
        breakpoints.sent("s1", """{"seq":7,"type":"request","command":"setBreakpoints",
            "arguments":{"source":{"path":"$path"},"breakpoints":[{"line":28},{"line":40}]}}""")
        breakpoints.received("s1", """{"seq":3,"type":"response","request_seq":7,"success":true,"command":"setBreakpoints",
            "body":{"breakpoints":[{"id":1,"verified":true,"line":28},{"id":2,"verified":false}]}}""")

        // The IDE counts from 0, this adapter from 1.
        assertEquals(1, breakpoints.idAt(27))
        assertEquals(2, breakpoints.idAt(39))
        assertNull(breakpoints.find("other", file, 27), "Another session's adapter reported nothing")
    }

    @Test
    fun `setting a file's breakpoints again replaces them, and events change them`() {
        val breakpoints = DapBreakpoints()
        breakpoints.sent("s1", """{"seq":7,"type":"request","command":"setBreakpoints",
            "arguments":{"source":{"path":"$path"},"breakpoints":[{"line":28},{"line":40}]}}""")
        breakpoints.received("s1", """{"type":"response","request_seq":7,"success":true,
            "body":{"breakpoints":[{"id":1},{"id":2}]}}""")
        breakpoints.sent("s1", """{"seq":8,"type":"request","command":"setBreakpoints",
            "arguments":{"source":{"path":"$path"},"breakpoints":[{"line":40}]}}""")
        breakpoints.received("s1", """{"type":"response","request_seq":8,"success":true,
            "body":{"breakpoints":[{"id":3}]}}""")
        assertNull(breakpoints.idAt(27))
        assertEquals(3, breakpoints.idAt(39))

        breakpoints.received("s1", """{"type":"event","event":"breakpoint","body":{"reason":"changed","breakpoint":{"id":3,"verified":true}}}""")
        assertEquals(true, (breakpoints.find("s1", file, 39)?.get("verified") as? JsonPrimitive)?.content?.toBoolean())
        breakpoints.received("s1", """{"type":"event","event":"breakpoint","body":{"reason":"removed","breakpoint":{"id":3}}}""")
        assertNull(breakpoints.idAt(39))
    }

    @Test
    fun `an ended session's breakpoints are forgotten`() {
        val breakpoints = DapBreakpoints()
        breakpoints.sent("s1", """{"seq":7,"type":"request","command":"setBreakpoints",
            "arguments":{"source":{"path":"$path"},"breakpoints":[{"line":28}]}}""")
        breakpoints.received("s1", """{"type":"response","request_seq":7,"success":true,"body":{"breakpoints":[{"id":1}]}}""")
        breakpoints.ended("s1")
        assertNull(breakpoints.idAt(27))
    }
}
