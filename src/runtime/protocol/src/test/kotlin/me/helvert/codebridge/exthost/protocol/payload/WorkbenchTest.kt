package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.*

/** Output channels and progress, as the two workbench services send them. */
class WorkbenchTest {

    @Test
    fun `reads an output channel registration`() {
        val registration = OutputChannels.registration(
            """["Probe Language Server",{"scheme":"file","path":"/C:/logs/probe.log"},"log","me.probe"]"""
        )
        assertEquals("Probe Language Server", registration.name)
        assertEquals("/C:/logs/probe.log", registration.file.path)
    }

    @Test
    fun `reads how far the host has written`() {
        val update = OutputChannels.update("""["codebridge-output-1",1,4096]""")
        assertEquals("codebridge-output-1", update.channelId)
        assertEquals(OutputChannels.APPEND, update.mode)
        assertEquals(4096L, update.till)
    }

    @Test
    fun `an update that names no mode is an append`() {
        val update = OutputChannels.update("""["codebridge-output-1"]""")
        assertEquals(OutputChannels.APPEND, update.mode)
        assertNull(update.till)
    }

    @Test
    fun `the register reply is the id itself, not an object holding one`() {
        assertEquals(""""codebridge-output-1"""", OutputChannels.registered("codebridge-output-1"))
    }

    @Test
    fun `reads a progress start`() {
        val start = Progress.start("""[3,{"location":15,"title":"Indexing","cancellable":true},"me.probe"]""")
        assertEquals(3, start.handle)
        assertEquals("Indexing", start.title)
        assertTrue(start.cancellable)
    }

    @Test
    fun `falls back to the source label when the extension titles nothing`() {
        val start = Progress.start("""[3,{"source":{"label":"Probe"}},"me.probe"]""")
        assertEquals("Probe", start.title)
        assertFalse(start.cancellable)
    }

    @Test
    fun `an untitled progress names nothing rather than inventing a title`() {
        // What to call it instead is the workbench's decision, not the protocol's.
        assertNull(Progress.start("""[3,{"location":15},"me.probe"]""").title)
        assertNull(Progress.start("""[3,{"title":"   "},"me.probe"]""").title)
    }

    @Test
    fun `reads a progress step`() {
        val step = Progress.step("""[3,{"message":"half way","increment":50}]""")
        assertEquals("half way", step.message)
        assertEquals(50.0, step.increment)
    }

    @Test
    fun `a step that reports only a message does not move the bar`() {
        val step = Progress.step("""[3,{"message":"still going"}]""")
        assertEquals("still going", step.message)
        assertNull(step.increment)
    }

    @Test
    fun `every progress call names its handle first`() {
        assertEquals(3, Progress.handle(Progress.END, "[3]"))
    }

    @Test
    fun `reads a status bar entry as rust-analyzer sends it while loading`() {
        // An item created without an id gets a counter for its entry id, sent as a string.
        val entry = StatusBar.entry(
            """["0","rust-lang.rust-analyzer","rust-lang.rust-analyzer","rust-analyzer (Extension)",""" +
                    """"$(loading~spin) rust-analyzer",{"value":"Loading workspace","isTrusted":true},false,""" +
                    """{"id":"rust-analyzer.openLogs","title":""},{"id":"statusBarItem.warningForeground"},""" +
                    """{"id":"statusBarItem.warningBackground"},true,null,null]"""
        )
        assertEquals("0", entry.entryId)
        assertEquals("rust-lang.rust-analyzer", entry.extensionId)
        assertEquals("rust-analyzer (Extension)", entry.name)
        assertEquals("$(loading~spin) rust-analyzer", entry.text, "Icons are the UI's to draw, so they stay in")
        assertEquals("Loading workspace", entry.tooltip)
        assertEquals("rust-analyzer.openLogs", entry.command?.id)
        assertEquals("statusBarItem.warningBackground", entry.background)
        assertTrue(entry.alignLeft)
        assertNull(entry.priority)
    }

    @Test
    fun `a status bar command with arguments keeps them to run it with`() {
        // How the host sends a command whose arguments cannot cross: its delegating command, keyed.
        val entry = StatusBar.entry(
            """["probe.item","item","me.probe","Probe","Ready","plain tooltip",false,""" +
                    $$"""{"$ident":"probe.run/1","id":"_internal_command_delegation","title":"","arguments":["probe.run/1"]},""" +
                    """null,null,false,100,null]"""
        )
        assertEquals("_internal_command_delegation", entry.command?.id)
        assertEquals("[\"probe.run/1\"]", entry.command?.arguments.toString())
        assertEquals("plain tooltip", entry.tooltip, "A tooltip may be plain text rather than Markdown")
        assertFalse(entry.alignLeft)
        assertEquals(100.0, entry.priority)
        assertNull(entry.background)
    }

    @Test
    fun `a status bar entry with nothing optional still reads`() {
        val entry = StatusBar.entry("""["7","item",null,null,"Ready",null,false,null,null,null,true,null,null]""")
        assertEquals("item", entry.name, "Falls back to the item's id when it has no name")
        assertNull(entry.tooltip)
        assertNull(entry.command)
    }

    @Test
    fun `a tooltip arrives as the extension wrote it, with what it may run`() {
        // GitLens's Launchpad tooltip, shortened, as the host sends a MarkdownString.
        val markdown = "[$(plug) Connect to GitHub](command:gitlens.connect) to see pull requests"
        val entry = StatusBar.entry(
            tooltipped("""{"value":"$markdown","isTrusted":{"enabledCommands":["gitlens.connect"]},"supportThemeIcons":true}""")
        )

        assertEquals("[$(plug) Connect to GitHub](command:gitlens.connect) to see pull requests", entry.tooltip)
        assertTrue(entry.markdown)
        assertTrue(entry.themeIcons)
        assertTrue(entry.trusts("gitlens.connect"))
        assertFalse(entry.trusts("probe.deleteEverything"), "Only the commands it named")

        val plain = StatusBar.entry(tooltipped("\"Just $(words)\""))
        assertFalse(plain.markdown)
        assertFalse(plain.themeIcons)
        assertEquals("Just $(words)", plain.tooltip)
    }

    @Test
    fun `a trusted tooltip may run anything, an untrusted one nothing`() {
        // VS Code will not run a command link from a string the extension did not trust.
        assertTrue(StatusBar.entry(tooltipped("""{"value":"x","isTrusted":true}""")).trusts("probe.restart"))
        assertFalse(StatusBar.entry(tooltipped("""{"value":"x"}""")).trusts("probe.restart"))
        assertFalse(StatusBar.entry(tooltipped("""{"value":"x","isTrusted":false}""")).trusts("probe.restart"))
        assertFalse(StatusBar.entry(tooltipped("\"x\"")).trusts("probe.restart"), "Plain text is never trusted")
    }

    @Test
    fun `a command link's arguments are decoded from its query`() {
        // `command:id?<uri-encoded JSON>`: an array is the argument list, anything else is one.
        assertEquals(
            "[1,\"two\"]",
            StatusBar.linkCommand("command:probe.many?%5B1%2C%22two%22%5D")!!.arguments.toString()
        )
        assertEquals("[{\"a\":1}]", StatusBar.linkCommand("command:probe.one?%7B%22a%22%3A1%7D")!!.arguments.toString())
        // GitLens names commands for where they run from.
        assertEquals(
            "gitlens.ai.explainCommit:editor",
            StatusBar.linkCommand("command:gitlens.ai.explainCommit%3Aeditor?%7B%7D")!!.id
        )
        assertNull(StatusBar.linkCommand("https://example.com"))
    }

    @Test
    fun `reads a message and the items it offers`() {
        val message = Messages.message(
            """[2,"Toolchain is too old",{"modal":true,"detail":"Needs 1.78","source":{"identifier":"rust-lang.rust-analyzer","label":"rust-analyzer"}},""" +
                    """[{"title":"Update","handle":0,"isCloseAffordance":false},{"title":"Later","handle":1,"isCloseAffordance":true}]]"""
        )

        assertEquals(Messages.Severity.WARNING, message.severity)
        assertEquals("Toolchain is too old", message.text)
        assertEquals("Needs 1.78", message.detail)
        assertTrue(message.modal)
        assertEquals("rust-analyzer", message.source)
        assertEquals(listOf("Update", "Later"), message.items.map { it.title })
        assertTrue(message.items[1].closeAffordance)
    }

    @Test
    fun `a plain message has no items and an ignore severity reads as information`() {
        val message = Messages.message("""[0,"rust-analyzer version: 0.3",{},[]]""")

        assertEquals(Messages.Severity.INFO, message.severity)
        assertFalse(message.modal)
        assertTrue(message.items.isEmpty())
        assertNull(Messages.chosen(null), "A dismissal replies with no value at all")
    }

    @Test
    fun `reads a settings write, a removal, and a language-scoped write`() {
        val update = Configuration.write(Configuration.UPDATE, """[null,"rust-analyzer.checkOnSave",false,{},null]""")
        assertNull(update.target, "No target named: the workspace, for this setting")
        assertEquals("rust-analyzer.checkOnSave", update.key)
        assertEquals("false", update.value.toString())
        assertNull(update.language)

        val remove = Configuration.write(Configuration.REMOVE, """[5,"rust-analyzer.checkOnSave",{},null]""")
        assertEquals(Configuration.Target.WORKSPACE, remove.target)
        assertNull(remove.value, "A removal has no value")

        val scoped =
            Configuration.write(Configuration.UPDATE, """[2,"editor.tabSize",4,{"overrideIdentifier":"rust"},true]""")
        assertEquals(Configuration.Target.USER, scoped.target)
        assertEquals("rust", scoped.language)
    }

    /** A `$setEntry` whose only interesting argument is the tooltip, given as raw JSON. */
    private fun tooltipped(tooltip: String) =
        """["0","item","me.probe","Probe","Probe",$tooltip,false,null,null,null,true,null,null]"""

    @Test
    fun `disposing an entry names only its id`() {
        assertEquals("0", StatusBar.entryId("[\"0\"]"))
    }
}
