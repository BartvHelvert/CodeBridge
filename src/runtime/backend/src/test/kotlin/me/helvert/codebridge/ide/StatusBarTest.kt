package me.helvert.codebridge.ide

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.StatusBar
import me.helvert.codebridge.rpc.StatusItemDto
import me.helvert.codebridge.rpc.StatusTooltipActionDto
import me.helvert.codebridge.rpc.toDto
import me.helvert.codebridge.workbench.HostedStatusBar
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * The status bar items extensions show, as the backend keeps them for the frontend to draw.
 *
 * What matters is that the set is always the extension's current one: an item left behind after
 * the extension removed it, or after its host went away, would tell the user a server is loading or
 * broken when it is neither.
 */
class StatusBarTest : EditorFixture() {

    /** A light fixture can share its project between tests, and with it this service's items. */
    @AfterEach
    fun clear() = statusBar.hostStopped()

    @Test
    fun `an entry is shown, replaced by its next version, and removed when disposed`() = onEdt {
        set("0", "$(loading~spin) rust-analyzer")
        assertEquals("$(loading~spin) rust-analyzer", statusBar.items.value.getValue("0").text)

        // The host re-sends the whole item on every change.
        set("0", "rust-analyzer")
        assertEquals(listOf("rust-analyzer"), statusBar.items.value.values.map { it.text })

        send($$"$disposeEntry", "[\"0\"]")
        assertTrue(statusBar.items.value.isEmpty())
    }

    @Test
    fun `losing the host clears every item`() = onEdt {
        set("0", "rust-analyzer")
        set("1", "Other")

        statusBar.hostStopped()

        assertTrue(statusBar.items.value.isEmpty())
    }

    @Test
    fun `a malformed call does not take the handler down with it`() = onEdt {
        // Arrives on the RPC reader, where an exception would end the session.
        send($$"$setEntry", "not json")
        send($$"$setEntry", "[]")
        send($$"$disposeEntry", "[]")
        assertTrue(statusBar.items.value.isEmpty())
    }

    @Test
    fun `the frontend is told the severity rather than VS Code's colour id`() = onEdt {
        set("0", "$(warning) rust-analyzer", background = """{"id":"statusBarItem.warningBackground"}""")
        set("1", "$(error) rust-analyzer", background = """{"id":"statusBarItem.errorBackground"}""")
        set("2", "rust-analyzer")

        val items = statusBar.items.value
        assertEquals("warning", items.getValue("0").toDto().severity)
        assertEquals("error", items.getValue("1").toDto().severity)
        assertNull(items.getValue("2").toDto().severity)
        assertTrue(items.getValue("0").toDto().clickable)
    }

    @Test
    fun `the tooltip reaches the frontend as the hover VS Code shows`() = onEdt {
        // GitLens's blame hover, shortened: an avatar sized after `|`, a commit badge in inline code,
        // command ids with a colon and arguments with parentheses, a link it did not trust, a rule.
        val markdown = listOf(
            """![You](data:image/png;base64,iVBORw0KGgo=|width=16,height=16 "You") &nbsp;**You**, $(history) 13 hours ago""",
            "Init",
            """[`$(git-commit) 25a20ef`](command:gitlens.showQuickCommitDetails?%7B%22sha%22%3A%2225a20ef%22%7D "Show Commit")""" +
                    """ &nbsp;[$(versions)](command:gitlens.openBlame?%5B%22file%3A%2F%2F%2Fa%20(b).rs%22%5D "Open Blame Prior to this Change")""" +
                    """ | [$(sparkle) Explain](command:gitlens.ai.explainCommit%3Aeditor?%7B%7D "Explain")""" +
                    """ | [Delete](command:probe.delete)""",
            "---",
            "```\n$(not-an-icon)\n```",
        ).joinToString("\n\n")
        send($$"$setEntry", buildJsonArray {
            add("0"); add("gitlens"); add("eamodio.gitlens"); add("Blame"); add("You, 13 hours ago")
            addJsonObject {
                put("value", markdown)
                putJsonObject("isTrusted") {
                    putJsonArray("enabledCommands") {
                        add("gitlens.showQuickCommitDetails"); add("gitlens.openBlame"); add("gitlens.ai.explainCommit:editor")
                    }
                }
                put("supportThemeIcons", true)
            }
            add(false); add(JsonNull); add(JsonNull); add(JsonNull); add(true); add(JsonNull); add(JsonNull)
        }.toString())

        val entry = statusBar.items.value.getValue("0")
        val html = assertNotNull(statusBar.tooltipHtml(entry))

        assertTrue("""src="data:image/png;base64,iVBORw0KGgo=" width="16" height="16"""" in html, html)
        assertTrue(
            """<code><icon src="codicon:git-commit"/> 25a20ef</code>""" in html,
            "Icons in inline code too: $html"
        )
        assertTrue("""<icon src="codicon:history"/>""" in html, html)
        for (index in 0..2) assertTrue("""href="${StatusItemDto.LINK_SCHEME}$index"""" in html, "Link $index: $html")
        assertFalse("command:" in html, "No link is left to the browser: $html")
        assertFalse("probe.delete" in html, "A link it did not trust is just its label: $html")
        assertTrue("$(not-an-icon)" in html, "A code block is shown as written: $html")
        assertSame(html, statusBar.tooltipHtml(entry), "Made once per tooltip")

        // The row of links shares a paragraph, so it stays HTML, where the extension put it.
        val blocks = assertNotNull(entry.toDto(statusBar.tooltip(entry)).tooltip)
        assertEquals(listOf(false, true, false), blocks.map { it.separator }, "Text, the rule, the code: $blocks")
        assertTrue(blocks.all { it.actions.isEmpty() }, "No list of actions: $blocks")
        assertTrue("""href="${StatusItemDto.LINK_SCHEME}2"""" in blocks[0].html.orEmpty(), blocks[0].html)
    }

    @Test
    fun `a column of command links is a list of actions`() = onEdt {
        // rust-analyzer's server hover, shortened.
        val markdown = listOf(
            """[Extension Info](command:rust-analyzer.serverVersion "Show version"): Version 0.4""",
            "---",
            """[$(terminal) Open Logs](command:rust-analyzer.openLogs "Open the server logs")""",
            """[$(refresh) Reload &amp; Workspace](command:rust-analyzer.reloadWorkspace)""",
            """[Stop server](command:rust-analyzer.stopServer "Stop the server")""",
            "Just text",
            """[$(debug-restart) Restart server](command:rust-analyzer.restartServer)""",
        ).joinToString("\n\n")
        send($$"$setEntry", buildJsonArray {
            add("0"); add("rust-lang.rust-analyzer"); add("rust-lang.rust-analyzer"); add("rust-analyzer"); add("rust-analyzer")
            addJsonObject {
                put("value", markdown)
                put("isTrusted", true)
                put("supportThemeIcons", true)
            }
            add(false); add(JsonNull); add(JsonNull); add(JsonNull); add(true); add(JsonNull); add(JsonNull)
        }.toString())

        val blocks = assertNotNull(statusBar.tooltip(statusBar.items.value.getValue("0")))

        assertEquals(4, blocks.size, "$blocks")
        assertTrue("Extension Info" in blocks[0].html.orEmpty(), "A link among text stays text: $blocks")
        assertTrue(blocks[1].separator)
        assertEquals(
            listOf(
                StatusTooltipActionDto(1, "Open Logs", "codicon:terminal", "Open the server logs"),
                StatusTooltipActionDto(2, "Reload & Workspace", "codicon:refresh"),
                StatusTooltipActionDto(3, "Stop server", title = "Stop the server"),
            ),
            blocks[2].actions,
        )
        // A link alone after the text is not a column: it stays in the text with it.
        val rest = blocks[3].html.orEmpty()
        assertTrue("Just text" in rest && """href="${StatusItemDto.LINK_SCHEME}4"""" in rest, rest)
    }

    @Test
    fun `a plain tooltip is text, icons and all`() = onEdt {
        send($$"$setEntry", """["0","x","x","X","X","Line <1>\n$(gear) Line 2",false,null,null,null,true,null,null]""")

        val entry = statusBar.items.value.getValue("0")
        assertEquals("Line &lt;1&gt;<br>$(gear) Line 2", statusBar.tooltipHtml(entry))
    }

    @Test
    fun `both sides name the tooltip's links alike`() {
        assertEquals(StatusBar.LINK_SCHEME, StatusItemDto.LINK_SCHEME)
    }

    @Test
    fun `an action that is not there does nothing`() = onEdt {
        // The tooltip can change between the menu opening and the click; nothing may throw.
        set("0", "rust-analyzer")
        statusBar.runAction("0", 0)
        statusBar.runAction("0", -1)
        statusBar.runAction("missing", 0)
    }

    @Test
    fun `clicking an item with no host does nothing`() = onEdt {
        // A click can race the host going away; it must not throw into the RPC call that sent it.
        set("0", "rust-analyzer")
        statusBar.run("0")
        statusBar.run("missing")
    }

    private fun set(entryId: String, text: String, background: String = "null") = send(
        $$"$setEntry",
        """["$entryId","rust-lang.rust-analyzer","rust-lang.rust-analyzer","rust-analyzer","$text",null,false,""" +
                """{"id":"rust-analyzer.openLogs","title":""},null,$background,true,null,null]""",
    )

    private fun send(method: String, args: String) =
        statusBar.handle(MainThreadCall("MainThreadStatusBar", method, args))

    private val statusBar get() = HostedStatusBar.getInstance(fixture.project)
}
