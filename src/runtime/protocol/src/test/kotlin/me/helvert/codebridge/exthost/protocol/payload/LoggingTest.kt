package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.*

/**
 * Console, telemetry and logger payloads, in the spellings the pinned runtime actually sends.
 *
 * The console shape is the awkward one: the arguments are a JSON string *inside* the argument
 * object, and the host sometimes replaces that string with a plain sentence when it cannot
 * serialize what the extension passed. Both have to read as the line the extension wrote.
 */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class LoggingTest {

    @Test
    fun `reads a console call and its severity`() {
        val message = Console.message(
            $$"""[{"type":"__$console","severity":"warn","arguments":"[\"starting\",\"probe\"]"}]"""
        )
        assertEquals(LogLevel.WARNING, message.level)
        assertEquals("starting probe", message.text)
    }

    @Test
    fun `console severities are method names, and an unknown one is ordinary output`() {
        fun level(severity: String) =
            Console.message("""[{"severity":"$severity","arguments":"[]"}]""").level
        assertEquals(LogLevel.DEBUG, level("debug"))
        assertEquals(LogLevel.INFO, level("log"))
        assertEquals(LogLevel.ERROR, level("error"))
        // Whatever a later build wraps: recorded rather than dropped, at the ordinary severity.
        assertEquals(LogLevel.INFO, level("info"))
    }

    @Test
    fun `a string argument is emitted bare and anything else keeps its JSON`() {
        val message = Console.message(
            """[{"severity":"log","arguments":"[\"handles\",{\"open\":2},null,7]"}]"""
        )
        assertEquals("""handles {"open":2} null 7""", message.text)
    }

    @Test
    fun `the sentence the host sends instead of arguments it cannot serialize is the line`() {
        // Not JSON, and deliberately so: the host gives up on a large or cyclic object and says so.
        val excuse = "Output omitted for a large object that exceeds the limits"
        val message = Console.message("""[{"severity":"log","arguments":"$excuse"}]""")
        assertEquals(excuse, message.text)
    }

    @Test
    fun `an appended stack gets its own lines`() {
        val message = Console.message(
            $$"""[{"severity":"error","arguments":"[\"failed\",{\"__$stack\":\"at one\\nat two\"}]"}]"""
        )
        assertEquals("failed\nat one\nat two", message.text)
    }

    @Test
    fun `telemetry is read for its event name alone`() {
        assertEquals(
            "extHostFindFiles", Telemetry.eventName(
                $$"$publicLog2", """["extHostFindFiles",{"extensionId":"me.probe","count":3}]"""
            )
        )
        // Every spelling of the same call starts this way, which is how they are matched.
        assertTrue($$"$publicLogError2".startsWith(Telemetry.PUBLIC_LOG_PREFIX))
        assertNull(Telemetry.eventName($$"$publicLog2", "[]"))
    }

    @Test
    fun `reads a logger registration and the file behind it`() {
        val resource = Loggers.registration(
            """[{"resource":{"scheme":"file","path":"/C:/logs/me.probe/Probe.log"},"id":"1a2b",
                "name":"Probe","extensionId":"me.probe","hidden":false}]"""
        )
        assertEquals("/C:/logs/me.probe/Probe.log", resource.file?.path)
        assertEquals("Probe", resource.label)
        assertFalse(resource.hidden)
    }

    @Test
    fun `an output channel registers the logger behind it as hidden`() {
        val resource = Loggers.registration(
            """[{"resource":{"scheme":"file","path":"/C:/logs/output/1-Server.log"},"id":"9f",
                "hidden":true}]"""
        )
        assertTrue(resource.hidden)
        // No name and no extension: the file is all there is to call it.
        assertEquals("1-Server.log", resource.label)
    }

    @Test
    fun `createLogger carries the file first and its options second`() {
        val resource = Loggers.created(
            """[{"scheme":"file","path":"/C:/logs/Probe.log"},{"name":"Probe","logLevel":"always"}]"""
        )
        assertEquals("/C:/logs/Probe.log", resource.file?.path)
        assertEquals("Probe", resource.label)
    }

    @Test
    fun `reads a batch of messages at their own levels`() {
        val (file, messages) = Loggers.messages(
            """[{"scheme":"file","path":"/C:/logs/Probe.log"},[[3,"ready"],[5,"it failed"]]]"""
        )
        assertEquals("/C:/logs/Probe.log", file?.path)
        assertEquals(listOf(LogLevel.INFO, LogLevel.ERROR), messages.map { it.level })
        assertEquals(listOf("ready", "it failed"), messages.map { it.text })
    }

    @Test
    fun `a level this build does not define is dropped rather than guessed at`() {
        // 6 is past Error in VS Code's LogLevel, and is spdlog's Off -- the table this must not use.
        val (_, messages) = Loggers.messages("""[null,[[6,"???"],[2,"kept"]]]""")
        assertEquals(listOf("kept"), messages.map { it.text })
    }

    @Test
    fun `visibility arrives against a file or against a logger id`() {
        assertEquals(
            "file:///C:/logs/Probe.log" to true,
            Loggers.visibility("""[{"scheme":"file","path":"/C:/logs/Probe.log"},true]""")
        )
        assertEquals("1a2b" to false, Loggers.visibility("""["1a2b",false]"""))
    }
}
