package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `vscode.tests` as the pinned runtime's `ExtHostTesting` writes it. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class TestingTest {

    @Test
    fun `test ids are the controller and every ancestor, NUL-separated`() {
        val id = Testing.TestId.join("ctrl", "file.ts", "suite", "case")
        assertEquals("ctrl\u0000file.ts\u0000suite\u0000case", id)
        assertEquals("ctrl", Testing.TestId.controllerId(id))
        assertEquals(Testing.TestId.join("ctrl", "file.ts", "suite"), Testing.TestId.parent(id))
        assertEquals("case", Testing.TestId.localId(id))
        assertNull(Testing.TestId.parent("ctrl"))
        assertEquals(true, Testing.TestId.isRoot("ctrl"))
    }

    @Test
    fun `reads a diff as the collection publishes it`() {
        // The controller's root, made expandable once a resolveHandler is set, then a file and a
        // test in it: `uri.toJSON()` and `range.toJSON()`, a null range for the root.
        val diff = Testing.diff($$"""["ctrl",[
            {"op":0,"item":{"expand":0,"item":{"extId":"ctrl","label":"Mocha","tags":[],"busy":false,"range":null,"description":null,"error":null,"sortText":null}}},
            {"op":1,"item":{"extId":"ctrl","expand":1}},
            {"op":0,"item":{"expand":1,"item":{"extId":"ctrl\u0000a.test.ts","label":"a.test.ts","tags":["ctrl\u0000slow"],"busy":false,
               "uri":{"$mid":1,"fsPath":"c:\\w\\a.test.ts","external":"file:///c%3A/w/a.test.ts","path":"/c:/w/a.test.ts","scheme":"file"},
               "range":{"startLineNumber":3,"startColumn":1,"endLineNumber":9,"endColumn":4},"description":"unit","error":{"value":"**bad**"}}}},
            {"op":1,"item":{"extId":"ctrl\u0000a.test.ts","item":{"label":"renamed"}}},
            {"op":3,"itemId":"ctrl\u0000gone"},
            {"op":2,"uri":{"scheme":"file","path":"/w/a.test.ts"},"docv":3},
            {"op":6,"tag":{"id":"ctrl\u0000slow"}}
        ]]""")
        assertEquals("ctrl", diff.controllerId)
        val root = assertIs<Testing.DiffOp.Add>(diff.ops[0])
        assertEquals(Testing.Expand.NOT_EXPANDABLE, root.expand)
        assertNull(root.item.uri)
        assertNull(root.item.range)
        assertNull(root.item.error)
        assertEquals(Testing.DiffOp.Update("ctrl", Testing.Expand.EXPANDABLE, null), diff.ops[1])

        val file = assertIs<Testing.DiffOp.Add>(diff.ops[2]).item
        assertEquals("/c:/w/a.test.ts", file.uri?.path)
        assertEquals(Range(3, 1, 9, 4), file.range)
        assertEquals("unit", file.description)
        assertEquals("**bad**", file.error)
        assertEquals(listOf("ctrl\u0000slow"), file.tags)

        val update = assertIs<Testing.DiffOp.Update>(diff.ops[3])
        assertNull(update.expand)
        val renamed = file.updated(update.item!!)
        assertEquals("renamed", renamed.label)
        assertEquals(file.range, renamed.range)

        assertEquals(Testing.DiffOp.Remove("ctrl\u0000gone"), diff.ops[4])
        assertEquals(Testing.DiffOp.Other(2), diff.ops[5])
        assertEquals(Testing.DiffOp.Other(6), diff.ops[6])
    }

    @Test
    fun `reads run profiles and their updates`() {
        val profile = Testing.publishedProfile("""[{"profileId":1,"controllerId":"ctrl","tag":null,"label":"Run",
            "group":2,"isDefault":true,"hasConfigurationHandler":false,"supportsContinuousRun":false}]""")
        assertEquals(Testing.Profile("ctrl", 1, "Run", Testing.Group.RUN, true, null, profile.raw), profile)
        val update = Testing.profileUpdate("""["ctrl",1,{"isDefault":false,"label":"Run all"}]""")
        val updated = profile.updated(update.update)
        assertEquals("Run all", updated.label)
        assertEquals(false, updated.isDefault)
        assertEquals(Testing.Group.DEBUG, Testing.publishedProfile("""[{"profileId":2,"controllerId":"ctrl","label":"Debug","group":4,"isDefault":true}]""").group)
    }

    @Test
    fun `reads what a run reports`() {
        val task = Testing.startedTask("""["run1",{"id":"task1","ctrlId":"ctrl","name":"Mocha","running":true}]""")
        assertEquals(Testing.Task("run1", "task1", "Mocha"), task)

        // `TestItem.from`: a revived URI and a `Range` object, root first.
        val added = Testing.addedTests($$"""["ctrl","run1",[
            {"extId":"ctrl","label":"Mocha","busy":false,"tags":[],"range":null,"description":null,"sortText":null,"error":null},
            {"extId":"ctrl\u0000a.test.ts","label":"a.test.ts","uri":{"$mid":1,"path":"/w/a.test.ts","scheme":"file"},"busy":false,"tags":[],
             "range":{"startLineNumber":1,"startColumn":1,"endLineNumber":20,"endColumn":1},"description":null,"sortText":null,"error":null}]]""")
        assertEquals("run1", added.runId)
        assertEquals(listOf("ctrl", "ctrl\u0000a.test.ts"), added.items.map { it.extId })

        assertEquals(Testing.StateUpdate("run1", "task1", "ctrl\u0000a", Testing.State.PASSED, 12.5),
            Testing.stateUpdate("""["run1","task1","ctrl\u0000a",3,12.5]"""))
        assertEquals(Testing.StateUpdate("run1", "task1", "ctrl\u0000a", Testing.State.RUNNING, null),
            Testing.stateUpdate("""["run1","task1","ctrl\u0000a",2]"""))

        val messages = Testing.messages("""["run1","task1","ctrl\u0000a",[{"message":{"value":"expected 1 to equal 2"},"type":0,
            "expected":"2","actual":"1","location":{"range":{"startLineNumber":4,"startColumn":5,"endLineNumber":4,"endColumn":9},
            "uri":{"scheme":"file","path":"/w/a.test.ts"}},
            "stackTrace":[{"label":"Context.<anonymous>","uri":{"scheme":"file","path":"/w/a.test.ts"},"position":{"lineNumber":4,"column":5}},{"label":"native"}]}]]""")
        val message = messages.messages.single()
        assertEquals("expected 1 to equal 2", message.text)
        assertEquals("2", message.expected)
        assertEquals("1", message.actual)
        assertEquals(Range(4, 5, 4, 9), message.range)
        assertEquals(listOf(
            Testing.Frame("Context.<anonymous>", UriComponents("file", "", "/w/a.test.ts", "", ""), Position(4, 5)),
            Testing.Frame("native", null, null),
        ), message.stackTrace)
    }

    @Test
    fun `output arrives as a buffer beside the arguments`() {
        // A `VSBuffer` argument is sent out of band; its place in the JSON array is taken by null.
        val output = Testing.output("""["run1","task1",null,null,"ctrl\u0000a"]""", "ok\r\n".toByteArray())
        assertEquals(Testing.Output("run1", "task1", "ok\r\n", "ctrl\u0000a"), output)
        assertNull(Testing.output("""["run1","task1",null]""", "x".toByteArray()).testId)
    }

    @Test
    fun `reads a run the extension started itself`() {
        val run = Testing.extensionRun("""[{"controllerId":"ctrl","continuous":false,"profile":{"group":4,"id":2},"exclude":[],
            "id":"ext-run","include":["ctrl\u0000a.test.ts"],"preserveFocus":true,"persist":true}]""")
        assertEquals(Testing.ExtensionRun("ext-run", "ctrl", Testing.Group.DEBUG, listOf("ctrl\u0000a.test.ts")), run)
        assertNull(Testing.extensionRun("""[{"controllerId":"ctrl","id":"r","include":["ctrl"]}]""").group)
    }

    @Test
    fun `reads an extension's request to run tests`() {
        val request = Testing.runRequest("""[{"preserveFocus":true,"group":2,"targets":[{"testIds":["ctrl\u0000a"],"profileId":1,"controllerId":"ctrl"}],"exclude":[]}]""")
        assertEquals(Testing.RunRequest(Testing.Group.RUN, listOf(Testing.Target("ctrl", 1, listOf("ctrl\u0000a"))), emptyList()), request)
    }

    @Test
    fun `writes the calls into the host`() {
        assertEquals("""["ctrl\u0000a",-1]""", Testing.expand("ctrl\u0000a", -1))
        assertEquals(Json.parseToJsonElement("""[[{"runId":"r","controllerId":"ctrl","profileId":1,"testIds":["ctrl"],"excludeExtIds":[]}]]"""),
            Json.parseToJsonElement(Testing.runControllerTests(listOf(Testing.ControllerRun("r", "ctrl", 1, listOf("ctrl"))))))
        assertEquals("""["r"]""", Testing.cancelRun("r"))
        assertEquals(listOf("Error: no tests"), Testing.runErrors("""[{},{"error":"Error: no tests"}]"""))
        assertEquals(emptyList(), Testing.runErrors("null"))
    }
}
