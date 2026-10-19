package me.helvert.codebridge.exthost

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.Documents
import me.helvert.codebridge.exthost.protocol.payload.EditorTabs
import me.helvert.codebridge.exthost.protocol.payload.Indentation
import kotlin.test.*

/**
 * The payloads document synchronization sends, without a host to send them to.
 *
 * [LanguageFeaturesIntegrationTest] proves the same path against a real extension, but only where a
 * runtime is available and only for the happy sequence. These are the cheap half: the exact shapes
 * VS Code's editor model accepts, and the cases an editor produces that a hand-written sequence
 * never would -- the same keystroke reported twice, a file loaded with CRLF, a document reopened
 * after it was closed.
 */
class ExtensionHostDocumentsTest {

    private val sent = mutableListOf<Pair<String, JsonArray>>()

    /**
     * The last call to one service method.
     *
     * Opening and closing now send two things -- the documents-and-editors delta and the tab model
     * -- so positional access would pin the order of two independent messages rather than either
     * one's content.
     */
    private fun lastCall(name: String): Pair<String, JsonArray> =
        sent.last { it.first == name }

    private fun deltas(): List<JsonArray> =
        sent.filter { it.first == $$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta" }.map { it.second }

    private val documents = ExtensionHostDocuments { service, method, argsJson ->
        sent.add("$service.$method" to Json.parseToJsonElement(argsJson).jsonArray)
        ""
    }

    @Test
    fun `opening adds the document with its lines and the first version`() {
        documents.open(OWNER, URI, "codebridgebundled", "one\ntwo", dirty = false)
        val (call, args) = lastCall($$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta")
        assertEquals($$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta", call)
        val added = args.single().jsonObject.getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals(1, added.getValue("versionId").jsonPrimitive.int)
        assertEquals("codebridgebundled", added.getValue("languageId").jsonPrimitive.content)
        assertEquals(listOf("one", "two"), added.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, added.getValue("isDirty").jsonPrimitive.boolean)
        assertEquals(PATH, added.getValue("uri").jsonObject.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `an editor indents as its text does, and as it was told where the text does not say`() {
        documents.open(OWNER, URI, "codebridgebundled", "{\n  \"a\": [\n    1\n  ]\n}", dirty = false, indentation = Indentation(4, true))
        documents.open(OWNER, URI.replace("probe", "flat"), "codebridgebundled", "one\ntwo", dirty = false, indentation = Indentation(3, false))

        val (indented, flat) = deltas().map { it.single().jsonObject.getValue("addedEditors").jsonArray.single().jsonObject.getValue("options").jsonObject }
        // CodeLLDB formats the configuration it writes into launch.json with these: told 4 for a file
        // indented by 2, it unindented the line above and indented the new configuration by 4.
        assertEquals(2, indented.getValue("tabSize").jsonPrimitive.int)
        assertEquals(2, indented.getValue("indentSize").jsonPrimitive.int)
        assertEquals(true, indented.getValue("insertSpaces").jsonPrimitive.boolean)
        assertEquals(3, flat.getValue("tabSize").jsonPrimitive.int)
        assertEquals(false, flat.getValue("insertSpaces").jsonPrimitive.boolean)
    }

    @Test
    fun `opening also announces an editor showing the document, and makes it active`() {
        documents.open(OWNER, URI, "codebridgebundled", "one\ntwo", dirty = false)

        val delta = deltas().single().single().jsonObject
        val editor = delta.getValue("addedEditors").jsonArray.single().jsonObject
        val id = editor.getValue("id").jsonPrimitive.content

        // The point of the editor. Extensions ask what is *visible*, not what is open: a client that
        // pulls diagnostics reads `window.visibleTextEditors`, so a document with no editor is one
        // it will answer every refresh request about and never actually ask for.
        assertEquals(id, delta.getValue("newActiveEditor").jsonPrimitive.content)
        assertEquals(PATH, editor.getValue("documentUri").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(id, documents.editorOf(URI))
        assertEquals(id, documents.activeEditor())

        // Every one of these is read eagerly when the runtime builds its editor, so a missing field
        // is a failure in the host rather than a default.
        val caret = editor.getValue("selections").jsonArray.single().jsonObject
        assertEquals(1, caret.getValue("selectionStartLineNumber").jsonPrimitive.int)
        assertEquals(1, caret.getValue("positionColumn").jsonPrimitive.int)
        for (option in listOf("tabSize", "indentSize", "insertSpaces", "cursorStyle", "lineNumbers")) {
            assertTrue(option in editor.getValue("options").jsonObject, "options.$option is read on arrival")
        }
        // The whole document is visible: claiming less would have a puller skip what it cannot see.
        val visible = editor.getValue("visibleRanges").jsonArray.single().jsonObject
        assertEquals(2, visible.getValue("endLineNumber").jsonPrimitive.int)
        assertEquals(4, visible.getValue("endColumn").jsonPrimitive.int)
    }

    @Test
    fun `a second owner of an open document does not add a second editor`() {
        documents.open(OWNER, URI, "codebridgebundled", "one\ntwo", dirty = false)
        val first = documents.editorOf(URI)
        sent.clear()

        documents.open("other", URI, "codebridgebundled", "one\ntwo", dirty = false)

        // The runtime raises "editor '<id>' already exists!" for a repeated id, and a second editor
        // for one document would be a second thing to keep in step for no gain.
        assertEquals(first, documents.editorOf(URI))
        assertTrue(sent.none { it.second.toString().contains("addedEditors") }, "Sent: $sent")
    }

    @Test
    fun `closing withdraws the editor with the document and clears the active one`() {
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        val id = documents.editorOf(URI)
        sent.clear()

        documents.close(OWNER, URI)

        val delta = deltas().single().single().jsonObject
        assertEquals(listOf(id), delta.getValue("removedEditors").jsonArray.map { it.jsonPrimitive.content })
        // Null rather than omitted: the runtime asserts that `newActiveEditor` names an editor it
        // still holds, so leaving the closed one named would be a hard failure in the host.
        assertTrue(delta.getValue("newActiveEditor") is JsonNull)
        assertNull(documents.activeEditor())
    }

    @Test
    fun `selecting a file already open makes its editor the active one again`() {
        val other = URI.replace("probe", "other")
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        val first = documents.editorOf(URI)
        documents.open(OWNER, other, "codebridgebundled", "two", dirty = false)
        sent.clear()

        // What CodeLLDB's "Cargo ..." completion checks before it writes anything: that
        // `window.activeTextEditor` is the file being completed in, not the last one opened.
        assertTrue(documents.activate(OWNER, URI))

        val delta = deltas().single().single().jsonObject
        assertEquals(setOf("newActiveEditor"), delta.keys)
        assertEquals(first, delta.getValue("newActiveEditor").jsonPrimitive.content)
        assertEquals(first, documents.activeEditor())
        val active = lastCall($$"ExtHostEditorTabs.$acceptEditorTabModel").second.single().jsonArray.single()
            .jsonObject.getValue("tabs").jsonArray.single { it.jsonObject.getValue("isActive").jsonPrimitive.boolean }
        assertEquals(EditorTabs.idOf(first!!), active.jsonObject.getValue("id").jsonPrimitive.content)

        // Already active, or only held for an extension: nothing to tell the host.
        sent.clear()
        assertFalse(documents.activate(OWNER, URI))
        documents.open(HOLDER, URI.replace("probe", "held"), "codebridgebundled", "three", dirty = false, shown = false)
        sent.clear()
        assertFalse(documents.activate(HOLDER, URI.replace("probe", "held")))
        assertTrue(sent.isEmpty(), "Sent: $sent")
    }

    @Test
    fun `a file opened behind the selected one does not take its place`() {
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        val selected = documents.editorOf(URI)
        sent.clear()

        // A tab restored with the project, mirrored after the one the user is in.
        documents.open(OWNER, URI.replace("probe", "restored"), "codebridgebundled", "two", dirty = false, active = false)

        val delta = deltas().single().single().jsonObject
        assertTrue("addedEditors" in delta)
        assertFalse("newActiveEditor" in delta)
        assertEquals(selected, documents.activeEditor())
    }

    @Test
    fun `reopening a closed document gets an editor id of its own`() {
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        val first = documents.editorOf(URI)
        documents.close(OWNER, URI)
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)

        // Reusing an id is not a wrong value but a rejected delta: "editor already exists!".
        assertNotEquals(first, documents.editorOf(URI))
    }

    @Test
    fun `opening publishes a tab model, which is what a pull client reads`() {
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)

        val (call, args) = sent.last()
        assertEquals($$"ExtHostEditorTabs.$acceptEditorTabModel", call)
        // One argument, which is the array of groups: the host calls `.map` on it directly, and a
        // bare group object there fails with "t.map is not a function" and no tab model at all.
        val group = args.single().jsonArray.single().jsonObject
        assertEquals(true, group.getValue("isActive").jsonPrimitive.boolean)

        val tab = group.getValue("tabs").jsonArray.single().jsonObject
        // The whole reason this exists: the language client's pull-diagnostics feature builds its
        // set of open resources from `window.tabGroups` and asks whether a document is in it before
        // requesting `textDocument/diagnostic`. Reporting an editor is not enough -- it reads tabs.
        assertEquals(true, tab.getValue("isActive").jsonPrimitive.boolean)
        assertEquals(PATH, tab.getValue("input").jsonObject.getValue("uri").jsonObject
            .getValue("path").jsonPrimitive.content)
        // TabInputKind.TextInput, which the runtime revives as a TabInputText; another kind would
        // be revived as a diff or a custom editor and the uri never read.
        assertEquals(1, tab.getValue("input").jsonObject.getValue("kind").jsonPrimitive.int)
        assertTrue(tab.getValue("label").jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `closing the last document leaves no tabs behind`() {
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        documents.close(OWNER, URI)

        val (call, args) = sent.last()
        assertEquals($$"ExtHostEditorTabs.$acceptEditorTabModel", call)
        // An empty group rather than no message: the client reconciles against what it is sent, so
        // a closed file has to disappear from the model or it stays pullable forever.
        assertTrue(args.single().jsonArray.single().jsonObject.getValue("tabs").jsonArray.isEmpty())
    }

    @Test
    fun `a host that has just started is given an active tab group, with no tabs yet`() {
        documents.hostInitialized()

        val (_, args) = lastCall("${EditorTabs.TABS}.${EditorTabs.ACCEPT_MODEL}")
        val group = args.single().jsonArray.single().jsonObject
        assertTrue(group.getValue("isActive").jsonPrimitive.boolean)
        assertEquals(0, group.getValue("tabs").jsonArray.size)
    }

    @Test
    fun `a tab model the host rejects does not stop the document being mirrored`() {
        val failures = mutableListOf<Throwable>()
        val documents = ExtensionHostDocuments(failures::add) { service, method, argsJson ->
            sent.add("$service.$method" to Json.parseToJsonElement(argsJson).jsonArray)
            if (service == EditorTabs.TABS) throw IllegalStateException("t.map is not a function")
            else ""
        }

        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)

        // The real failure this guards. Publishing tabs is the last thing `open` does, so a host
        // that rejects the model used to abort the whole call -- leaving the caller believing the
        // document had never been mirrored, and every edit after it dropped on the floor.
        assertTrue(documents.isOpen(OWNER, URI), "The document is mirrored whatever tabs did")
        assertTrue(documents.change(OWNER, URI, "two", dirty = true), "Edits still reach the host")
        assertEquals(1, failures.size, "The tab failure is reported rather than swallowed")
    }

    @Test
    fun `an edit sends only the span that changed`() {
        documents.open(OWNER, URI, "codebridgebundled", "one\ntwo\nthree", dirty = false)
        assertTrue(documents.change(OWNER, URI, "one\ntWOOo\nthree", dirty = true))

        val (call, args) = sent.last()
        assertEquals($$"ExtHostDocuments.$acceptModelChanged", call)
        assertEquals(2, args[1].jsonObject.getValue("versionId").jsonPrimitive.int)
        assertEquals(true, args[2].jsonPrimitive.boolean, "The editor's buffer no longer matches disk")
        // Not the whole file: a server told the whole file was replaced reads it again from scratch.
        val change = args[1].jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertEquals("WOO", change.getValue("text").jsonPrimitive.content)
        // The range is in the *old* document's coordinates, which is what the host applies it to.
        assertEquals(5, change.getValue("rangeOffset").jsonPrimitive.int)
        assertEquals(1, change.getValue("rangeLength").jsonPrimitive.int)
        assertEquals(listOf(2, 2, 2, 3), range(change))
    }

    @Test
    fun `an edit across lines is placed in the old document's lines`() {
        documents.open(OWNER, URI, "codebridgebundled", "one\ntwo\nfour", dirty = false)
        documents.change(OWNER, URI, "one\nfour", dirty = true)

        val change = sent.last().second[1].jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertEquals("", change.getValue("text").jsonPrimitive.content)
        assertEquals(4, change.getValue("rangeLength").jsonPrimitive.int)
        assertEquals(listOf(2, 1, 3, 1), range(change), "`two` and the line break after it")
    }

    @Test
    fun `an edit never splits a surrogate pair`() {
        // Two emoji sharing their high surrogate: a plain character comparison stops between the
        // halves, which would ask the host to replace half a character.
        val before = "a😀b"
        val after = "a😁b"
        assertEquals(Documents.Change(1, 3, 3), Documents.changeBetween(before, after))
    }

    @Test
    fun `replacing everything is still a whole-document edit`() {
        documents.open(OWNER, URI, "codebridgebundled", "one\ntwo", dirty = false)
        documents.change(OWNER, URI, "three", dirty = true)

        val change = sent.last().second[1].jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertEquals("three", change.getValue("text").jsonPrimitive.content)
        assertEquals(7, change.getValue("rangeLength").jsonPrimitive.int)
        assertEquals(listOf(1, 1, 2, 4), range(change), "One past the last character")
    }

    private fun range(change: JsonObject): List<Int> = change.getValue("range").jsonObject.let { range ->
        listOf("startLineNumber", "startColumn", "endLineNumber", "endColumn")
            .map { range.getValue(it).jsonPrimitive.int }
    }

    @Test
    fun `the same text twice is not an edit`() {
        // Several IntelliJ endpoints report one keystroke, and edits are coalesced before they get
        // here, so a repeat is ordinary. A version that moved for each would outrun the server.
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        assertFalse(documents.change(OWNER, URI, "one", dirty = false))
        assertEquals(1, documents.versionOf(URI))
        assertEquals(1, deltas().size, "Re-sending identical text is not an edit")
    }

    @Test
    fun `line endings are compared the way they are sent`() {
        // IntelliJ hands out `\n` whatever the file holds; a document loaded from a CRLF file must
        // not read as an edit of itself the first time anything touches it.
        documents.open(OWNER, URI, "codebridgebundled", "one\r\ntwo", dirty = false)
        assertFalse(documents.change(OWNER, URI, "one\ntwo", dirty = false))
    }

    @Test
    fun `versions keep climbing across edits`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        documents.change(OWNER, URI, "ab", dirty = true)
        documents.change(OWNER, URI, "abc", dirty = true)
        assertEquals(3, documents.versionOf(URI))
    }

    @Test
    fun `a saved document is reported, and stays open`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = true)
        documents.save(OWNER, URI)
        assertEquals($$"ExtHostDocuments.$acceptModelSaved", sent.last().first)
        assertTrue(documents.isOpen(OWNER, URI))
    }

    @Test
    fun `closing removes the document`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        documents.close(OWNER, URI)
        val (call, args) = lastCall($$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta")
        assertEquals($$"ExtHostDocumentsAndEditors.$acceptDocumentsAndEditorsDelta", call)
        assertEquals(PATH, args.single().jsonObject.getValue("removedDocuments").jsonArray.single()
            .jsonObject.getValue("path").jsonPrimitive.content)
        assertFalse(documents.isOpen(OWNER, URI))
        assertNull(documents.versionOf(URI))
    }

    @Test
    fun `a document stays open until its last owner closes it`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        documents.open("debug", URI, "codebridgebundled", "a", dirty = false)
        documents.close(OWNER, URI)
        assertTrue(documents.isOpen("debug", URI), "Another owner still has it open")
        assertEquals(1, deltas().size, "Nothing to tell the host yet")
        documents.close("debug", URI)
        assertEquals(2, deltas().size, "The last owner leaving withdraws it")
    }

    @Test
    fun `a second owner brings the host's copy up to date`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        documents.open("debug", URI, "codebridgebundled", "ab", dirty = true)
        assertEquals($$"ExtHostDocuments.$acceptModelChanged", sent.last().first)
        assertEquals(2, documents.versionOf(URI))
    }

    @Test
    fun `reopening after a close starts over`() {
        // Closing drops the host's copy, so the version its client sends upstream has to start
        // again rather than continue where the old document left off.
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        documents.change(OWNER, URI, "ab", dirty = true)
        documents.close(OWNER, URI)
        documents.open(OWNER, URI, "codebridgebundled", "ab", dirty = false)
        assertEquals(1, documents.versionOf(URI))
    }

    @Test
    fun `equivalent spellings of one file are one document`() {
        // VS Code lowercases the Windows drive; IntelliJ does not. Two documents for one file would
        // leave the second open forever and file its edits against a copy nothing reads.
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        val lowercase = URI.replace("/C:/", "/c:/")
        assertTrue(documents.isOpen(OWNER, lowercase))
        assertTrue(documents.change(OWNER, lowercase, "ab", dirty = true))
        assertEquals(2, documents.versionOf(URI))
    }

    @Test
    fun `editing or saving a document nobody opened fails rather than inventing one`() {
        // The host throws `unknown document` for these, and takes the extension down with it.
        assertFails { documents.change(OWNER, URI, "a", dirty = true) }
        assertFails { documents.save(OWNER, URI) }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `closing a document nobody opened is not an error`() {
        // Every closed file reaches this, whether or not an extension ever claimed it.
        documents.close(OWNER, URI)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `reopening a file under a different language is refused`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        assertFails { documents.open("debug", URI, "codebridgesocket", "a", dirty = false) }
    }

    @Test
    fun `a document an extension opened has no editor and no tab`() {
        documents.open(HOLDER, URI, "codebridgebundled", "one", dirty = false, shown = false)

        // `openTextDocument` makes a document the extension can read, not one anybody can see: a
        // pull client that treated it as visible would pull diagnostics for a file nobody opened.
        val delta = deltas().single().single().jsonObject
        assertEquals(PATH, delta.getValue("addedDocuments").jsonArray.single().jsonObject
            .getValue("uri").jsonObject.getValue("path").jsonPrimitive.content)
        assertFalse("addedEditors" in delta)
        assertFalse("newActiveEditor" in delta)
        assertNull(documents.editorOf(URI))
        assertTrue(documents.isOpen(HOLDER, URI))
        assertFalse(documents.isShown(HOLDER, URI))
        assertTrue(sent.none { it.first == $$"ExtHostEditorTabs.$acceptEditorTabModel" }, "Sent: $sent")
        // Edited and saved like any other, since the host keeps it in step either way.
        assertTrue(documents.change(HOLDER, URI, "one two", dirty = true))
        documents.save(HOLDER, URI)
    }

    @Test
    fun `opening a held document in an editor adds only the editor`() {
        documents.open(HOLDER, URI, "codebridgebundled", "one", dirty = false, shown = false)
        sent.clear()

        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)

        // The document is already in the host, which refuses to be given it twice.
        val delta = deltas().single().single().jsonObject
        assertFalse("addedDocuments" in delta)
        val id = delta.getValue("addedEditors").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(id, delta.getValue("newActiveEditor").jsonPrimitive.content)
        assertEquals(id, documents.editorOf(URI))
        val tabs = lastCall($$"ExtHostEditorTabs.$acceptEditorTabModel").second.toString()
        assertTrue(EditorTabs.idOf(id) in tabs, tabs)
    }

    @Test
    fun `closing the editor of a held document leaves the document`() {
        documents.open(HOLDER, URI, "codebridgebundled", "one", dirty = false, shown = false)
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        val id = documents.editorOf(URI)
        sent.clear()

        documents.close(OWNER, URI)

        // The extension's `TextDocument` stays open: `onDidCloseTextDocument` is for when it lets go.
        val delta = deltas().single().single().jsonObject
        assertFalse("removedDocuments" in delta)
        assertEquals(listOf(id), delta.getValue("removedEditors").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(delta.getValue("newActiveEditor") is JsonNull)
        assertNull(documents.editorOf(URI))
        assertNull(documents.activeEditor())

        sent.clear()
        documents.close(HOLDER, URI)
        val removed = deltas().single().single().jsonObject
        assertEquals(PATH, removed.getValue("removedDocuments").jsonArray.single().jsonObject
            .getValue("path").jsonPrimitive.content)
        assertFalse("removedEditors" in removed)
        assertFalse(documents.isOpen(HOLDER, URI))
    }

    @Test
    fun `letting go of a document an editor still shows keeps it as it is`() {
        documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
        documents.open(HOLDER, URI, "codebridgebundled", "one", dirty = false, shown = false)
        val id = documents.editorOf(URI)
        sent.clear()

        documents.close(HOLDER, URI)

        assertTrue(sent.isEmpty(), "Sent: $sent")
        assertEquals(id, documents.editorOf(URI))
    }

    @Test
    fun `an untitled buffer is held under its own resource`() {
        documents.open(HOLDER, UNTITLED, "markdown", "# notes", dirty = true, shown = false)

        val uri = deltas().single().single().jsonObject.getValue("addedDocuments").jsonArray.single().jsonObject
            .getValue("uri").jsonObject
        assertEquals("untitled", uri.getValue("scheme").jsonPrimitive.content)
        assertEquals("Untitled-1", uri.getValue("path").jsonPrimitive.content)
        assertEquals(UNTITLED, documents.spellingOf(UNTITLED))
    }

    @Test
    fun `a document is added in the encoding it was read in`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false, encoding = "windows1252")

        val added = deltas().single().single().jsonObject.getValue("addedDocuments").jsonArray.single().jsonObject
        assertEquals("windows1252", added.getValue("encoding").jsonPrimitive.content)
        assertEquals("windows1252", documents.encodingOf(URI))
    }

    @Test
    fun `a change of encoding is sent once`() {
        documents.open(OWNER, URI, "codebridgebundled", "a", dirty = false)
        sent.clear()

        assertTrue(documents.encodingChanged(OWNER, URI, "shiftjis"))
        assertFalse(documents.encodingChanged(OWNER, URI, "shiftjis"))

        val (call, args) = sent.single()
        assertEquals($$"ExtHostDocuments.$acceptEncodingChanged", call)
        assertEquals(PATH, args[0].jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("shiftjis", args[1].jsonPrimitive.content)
    }

    @Test
    fun `a reply that waits on a question to the model does not wait out the timeout`() {
        // The host's replies are delivered on the workbench thread, behind the calls it made first.
        // When one of those -- a code lens provider registering -- asks which documents are shown,
        // the reply cannot arrive until it has its answer. Waiting for the reply holding the model
        // used to stall both until the reply timed out: rust-analyzer's activation took a minute.
        val workbench = java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var documents: ExtensionHostDocuments
        documents = ExtensionHostDocuments { _, _, _ ->
            withContext(workbench.asCoroutineDispatcher()) { documents.isShown(OWNER, URI); "" }
        }
        try {
            val started = System.nanoTime()
            documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false)
            assertTrue(documents.change(OWNER, URI, "two", dirty = true))

            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 5_000, "Opening and editing took ${millis}ms")
            assertTrue(documents.isShown(OWNER, URI))
        } finally {
            workbench.shutdownNow()
        }
    }

    @Test
    fun `a host that fails a call still fails the caller, with the model as it was sent`() {
        val documents = ExtensionHostDocuments { service, _, _ ->
            if (service == EditorTabs.TABS) ""
            else throw IllegalStateException("rejected")
        }

        assertFails { documents.open(OWNER, URI, "codebridgebundled", "one", dirty = false) }
        // The delta was sent, so the host may well hold it; opening again adds an owner rather than
        // a second document the host would refuse.
        assertTrue(documents.isOpen(OWNER, URI))
    }

    private companion object {
        const val OWNER = "intellij"
        const val HOLDER = "extensions"
        const val UNTITLED = "untitled:Untitled-1"
        const val PATH = "/C:/Projects/probe.codebridgebundled"
        const val URI = "file://$PATH"
    }
}
