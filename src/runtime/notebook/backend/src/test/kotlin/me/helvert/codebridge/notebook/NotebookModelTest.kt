package me.helvert.codebridge.notebook

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.Documents
import me.helvert.codebridge.exthost.protocol.payload.Notebooks
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a notebook tells the extension host as it is edited, run and saved. */
class NotebookModelTest {
    private val uri = UriComponents("file", "", "/w/a.ipynb", "", "")

    private fun model(vararg cells: Notebooks.Cell) =
        NotebookModel(uri, "jupyter-notebook", Notebooks.Data(JsonObject(emptyMap()), cells.toList())) { if (it == "python") ".py" else null }

    private fun event(message: NotebookModel.HostMessage): JsonObject =
        Json.parseToJsonElement(message.argsJson).jsonArray[Notebooks.MODEL_EVENT].jsonObject["rawEvents"]!!.jsonArray.single().jsonObject

    @Test
    fun `typing changes the cell's document and tells the notebook its content changed`() {
        val model = model(Notebooks.Cell(Notebooks.CODE, "print(1)", "python"))
        val messages = model.setText(0, "print(12)")!!
        assertEquals(2, messages.size)
        // The cell's own document hears only the span that changed, as any text document does.
        val document = messages[0]
        assertEquals(Documents.DOCUMENTS to Documents.MODEL_CHANGED, document.service to document.method)
        val args = Json.parseToJsonElement(document.argsJson).jsonArray
        assertEquals(Notebooks.cellUri(uri, 0).toJson(), args[0])
        assertEquals("2", args[1].jsonObject["changes"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), args[1].jsonObject["versionId"])
        // The notebook, that cell's content.
        assertEquals(Notebooks.CONTENT, event(messages[1])["kind"]!!.jsonPrimitive.int)
        assertEquals(true, model.dirty)
        assertEquals(1, model.version)
        assertNull(model.setText(0, "print(12)"), "the same text again changes nothing")
    }

    @Test
    fun `a new cell gets the next handle and is spliced in`() {
        val model = model(Notebooks.Cell(Notebooks.CODE, "a", "python"), Notebooks.Cell(Notebooks.CODE, "b", "python"))
        val (cell, messages) = model.insert(1, Notebooks.MARKUP, "markdown")
        assertEquals(2, cell.handle)
        assertEquals(listOf(0, 2, 1), model.cells.map { it.handle })
        val splice = event(messages.single())
        assertEquals(Notebooks.MODEL_CHANGE, splice["kind"]!!.jsonPrimitive.int)
        val change = splice["changes"]!!.jsonArray.single().jsonArray
        assertEquals(1, change[0].jsonPrimitive.int)
        assertEquals(0, change[1].jsonPrimitive.int)
        assertEquals(JsonPrimitive(2), change[2].jsonArray.single().jsonObject["handle"])
    }

    @Test
    fun `undoing a delete brings the cell back as it was, and redo deletes it again`() {
        val output = Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))
        val model = model(Notebooks.Cell(Notebooks.CODE, "a", "python", outputs = listOf(output)), Notebooks.Cell(Notebooks.CODE, "b", "python"))
        model.setText(0, "ab")
        model.delete(0)
        assertEquals(listOf(1), model.cells.map { it.handle })

        val undone = model.undo()!!
        assertEquals(listOf(0, 1), model.cells.map { it.handle })
        assertEquals("ab", model.cells[0].text)
        assertEquals(listOf("o"), model.cells[0].outputs.map { it.id })
        assertEquals(Documents.FIRST_VERSION, model.cells[0].textVersion, "the host makes its document anew")
        assertEquals(listOf(0), undone.selection)
        val splice = event(undone.messages.single())["changes"]!!.jsonArray.single().jsonArray
        assertEquals(listOf(0, 0), listOf(splice[0].jsonPrimitive.int, splice[1].jsonPrimitive.int))
        assertEquals(JsonPrimitive(0), splice[2].jsonArray.single().jsonObject["handle"])

        assertEquals(listOf(1), model.redo()!!.selection, "the cell that took its place")
        assertEquals(listOf(1), model.cells.map { it.handle })
        assertNull(model.redo())
    }

    @Test
    fun `joining cells keeps the first one's outputs, and undo brings both back`() {
        val output = Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))
        val model = model(
            Notebooks.Cell(Notebooks.CODE, "a", "python", outputs = listOf(output)),
            Notebooks.Cell(Notebooks.CODE, "b", "python"),
            Notebooks.Cell(Notebooks.MARKUP, "c", "markdown"),
        )
        assertNull(model.join(listOf(1, 2)), "cells of different kinds")
        assertNull(model.join(listOf(0, 2)), "cells apart")
        assertNull(model.join(listOf(0)), "a cell alone")

        val (joined, messages) = model.join(listOf(1, 0))!!
        assertEquals("a\nb", joined.text)
        assertEquals(listOf("o"), joined.outputs.map { it.id })
        assertEquals(listOf(joined.handle, 2), model.cells.map { it.handle })
        val splice = event(messages.single())["changes"]!!.jsonArray.single().jsonArray
        assertEquals(listOf(0, 2), listOf(splice[0].jsonPrimitive.int, splice[1].jsonPrimitive.int))

        model.undo()
        assertEquals(listOf(0, 1, 2), model.cells.map { it.handle })
        assertEquals(listOf("a", "b"), model.cells.take(2).map { it.text })
    }

    @Test
    fun `a cell changed to markdown keeps its text but not its outputs`() {
        val output = Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))
        val model = model(Notebooks.Cell(Notebooks.CODE, "# a", "python", outputs = listOf(output)))
        val (cell, _) = model.changeKind(0, Notebooks.MARKUP, "markdown")!!
        assertEquals(Notebooks.MARKUP to "markdown", cell.kind to cell.language)
        assertEquals("# a", cell.text)
        assertEquals(emptyList(), cell.outputs)
        assertNull(model.changeKind(cell.handle, Notebooks.MARKUP, "markdown"), "it already is one")
    }

    @Test
    fun `pasted copies get handles and output ids of their own`() {
        val output = Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))
        val model = model(Notebooks.Cell(Notebooks.CODE, "a", "python", outputs = listOf(output)), Notebooks.Cell(Notebooks.CODE, "b", "python"))
        val copied = model.cells.slice(model.range(listOf(0))!!).map(model::snapshot)
        val (made, _) = model.insertCopies(2, copied)
        assertEquals(listOf(0, 1, 2), model.cells.map { it.handle })
        assertEquals("a", made.single().text)
        assert(made.single().outputs.single().id != "o")
        assertNull(model.range(listOf(0, 2)), "cells apart")
    }

    @Test
    fun `undo takes back inserts, moves and cleared outputs, last first, and a new edit ends redo`() {
        val output = Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))
        val model = model(Notebooks.Cell(Notebooks.CODE, "a", "python", outputs = listOf(output)), Notebooks.Cell(Notebooks.CODE, "b", "python"))
        model.insert(2, Notebooks.CODE, "python")
        model.move(2, -2)
        model.clearOutputs(null)
        assertEquals(listOf(2, 0, 1), model.cells.map { it.handle })

        assertEquals(listOf(0), model.undo()!!.selection)
        assertEquals(listOf("o"), model.cell(0)!!.outputs.map { it.id })
        assertEquals(Notebooks.MOVE, event(model.undo()!!.messages.single())["kind"]!!.jsonPrimitive.int)
        assertEquals(listOf(0, 1, 2), model.cells.map { it.handle })
        model.undo()
        assertEquals(listOf(0, 1), model.cells.map { it.handle })
        assertNull(model.undo())

        model.redo()
        assertEquals(listOf(0, 1, 2), model.cells.map { it.handle })
        model.delete(0)
        assertNull(model.redo(), "a new edit drops what was undone")
    }

    @Test
    fun `cells dragged together move as one block, one step to undo, told to the host with their length`() {
        val model = model(*"abcde".map { Notebooks.Cell(Notebooks.CODE, it.toString(), "python") }.toTypedArray())

        // Down: b and c dropped before e.
        val (down, messages) = model.move(listOf(1, 2), 4)!!
        assertEquals(listOf(0, 3, 1, 2, 4), model.cells.map { it.handle })
        assertEquals(Triple(1, 2, 2), Triple(down.from, down.to, down.length))
        val event = event(messages.single())
        assertEquals(Notebooks.MOVE, event["kind"]!!.jsonPrimitive.int)
        assertEquals(listOf(1, 2, 2), listOf("index", "newIdx", "length").map { event[it]!!.jsonPrimitive.int })

        // Up: b and c, now third and fourth, dropped before the first.
        model.move(listOf(1, 2), 0)
        assertEquals(listOf(1, 2, 0, 3, 4), model.cells.map { it.handle })

        // Dropped next to themselves, or apart from each other, they stay put.
        assertNull(model.move(listOf(1, 2), 0))
        assertNull(model.move(listOf(1, 2), 2))
        assertNull(model.move(listOf(1, 3), 5))

        model.undo()
        assertEquals(listOf(0, 3, 1, 2, 4), model.cells.map { it.handle })
        assertEquals(listOf(1, 2), model.undo()!!.selection, "the cells moved together, selected together")
        assertEquals(listOf(0, 1, 2, 3, 4), model.cells.map { it.handle })
    }

    @Test
    fun `outputs reach the host with their bytes as buffers, streams joined`() {
        val model = model(Notebooks.Cell(Notebooks.CODE, "x", "python"))
        val stdout = "application/vnd.code.notebook.stdout"
        model.setOutputs(0, listOf(Notebooks.Output("o", listOf(Notebooks.Item(stdout, "a".toByteArray())))))
        val (cell, messages) = model.setOutputItems("o", listOf(Notebooks.Item(stdout, "b".toByteArray())), append = true)!!
        assertEquals("ab", cell.outputs.single().items.single().data.decodeToString())
        val message = messages.single()
        assertEquals(listOf("ab"), message.objectBuffers.getValue(Notebooks.MODEL_EVENT).map { it.decodeToString() })
        assertEquals(Notebooks.OUTPUT, event(message)["kind"]!!.jsonPrimitive.int)
    }

    @Test
    fun `saving leaves out what the serializer does not keep`() {
        val model = model(
            Notebooks.Cell(
                Notebooks.CODE, "x", "python",
                outputs = listOf(Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))),
                metadata = buildJsonObject { put("id", "a"); put("breakpointMargin", true) },
            )
        )
        val serializer = Notebooks.Serializer(
            0, "vscode.ipynb", "jupyter-notebook",
            buildJsonObject { put("transientOutputs", true); putJsonObject("transientCellMetadata") { put("breakpointMargin", true) } },
        )
        val cell = model.data(serializer).cells.single()
        assertEquals(emptyList(), cell.outputs)
        assertEquals(setOf("id"), cell.metadata?.keys)
    }

    @Test
    fun `a cell's last run is read from what its file kept`() {
        val model = model(
            Notebooks.Cell(
                Notebooks.CODE, "x", "python",
                executionSummary = buildJsonObject { put("executionOrder", 4); put("lastRunSuccess", true); put("runStartTime", 10); put("runEndTime", 35) },
            )
        )
        assertEquals(RunDto(RunState.IDLE, 4, true, 25), model.dto(model.cells.single()).run)
        assertEquals(".py", model.dto(model.cells.single()).fileExtension)
    }

    @Test
    fun `reading the file again keeps the cells it still has at either end and replaces the rest`() {
        val output = Notebooks.Output("o", listOf(Notebooks.Item("text/plain", "1".toByteArray())))
        val model = model(
            Notebooks.Cell(Notebooks.CODE, "a", "python", outputs = listOf(output)),
            Notebooks.Cell(Notebooks.CODE, "b", "python"),
            Notebooks.Cell(Notebooks.MARKUP, "# c", "markdown"),
        )
        model.setText(1, "bb")
        model.delete(2)
        // Read again: its outputs under new ids, as a serializer makes them; the middle cell changed,
        // and the deleted one back.
        val metadata = buildJsonObject { put("nbformat", 4) }
        val reloaded = model.reload(Notebooks.Data(metadata, listOf(
            Notebooks.Cell(Notebooks.CODE, "a", "python", outputs = listOf(output.copy(id = "new"))),
            Notebooks.Cell(Notebooks.CODE, "b2", "python"),
            Notebooks.Cell(Notebooks.MARKUP, "# c", "markdown"),
        )))
        assertEquals(1, reloaded.index)
        assertEquals(1, reloaded.removed)
        assertEquals(listOf("b2", "# c"), reloaded.added.map { it.text })
        assertEquals(0, model.cells[0].handle, "the first cell is kept, with its handle")
        assertEquals(listOf("a", "b2", "# c"), model.cells.map { it.text })
        assertEquals(false, model.dirty)
        assertNull(model.undo(), "nothing is left to undo")

        val (changed, clean) = reloaded.messages
        val args = Json.parseToJsonElement(changed.argsJson).jsonArray
        assertEquals(JsonPrimitive(false), args[2], "the host hears it is not dirty")
        assertEquals(metadata, args[3], "and of its new metadata")
        val splice = event(changed)["changes"]!!.jsonArray.single().jsonArray
        assertEquals(listOf(1, 1), listOf(splice[0].jsonPrimitive.int, splice[1].jsonPrimitive.int))
        assertEquals(Notebooks.DIRTY_CHANGED, clean.method)

        assertEquals(emptyList(), model.reload(Notebooks.Data(metadata, model.cells.map(model::snapshot))).messages, "the same file changes nothing")
    }
}
