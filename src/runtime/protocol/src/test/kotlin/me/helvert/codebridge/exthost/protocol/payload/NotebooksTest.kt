package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Notebooks as the pinned runtime's `ExtHostNotebookController` and `ExtHostNotebookKernels` write them. */
class NotebooksTest {
    private val notebook = UriComponents("file", "", "/c:/w/a.ipynb", "", "")

    @Test
    fun `cell uris are vscode's own, so extensions can find a cell's notebook`() {
        // `CellUri.generate`: the handle in base 7 behind a letter for its length, then `s` and the
        // notebook's scheme in padded base64.
        assertEquals("W0sZmlsZQ==", Notebooks.cellUri(notebook, 0).fragment)
        assertEquals("X13sZmlsZQ==", Notebooks.cellUri(notebook, 10).fragment)
        assertEquals(Notebooks.CELL_SCHEME, Notebooks.cellUri(notebook, 0).scheme)
        assertEquals(notebook.path, Notebooks.cellUri(notebook, 0).path)
    }

    @Test
    fun `reads a serializer's notebook, outputs from the buffers beside it`() {
        // `$dataToNotebook`'s reply: `new SerializableObjectWithBuffers(NotebookData.from(data))`.
        val json = Json.parseToJsonElement($$$"""{"metadata":{"nbformat":4},"cells":[
            {"cellKind":2,"language":"python","source":"print(1)","metadata":{"id":"a"},
             "internalMetadata":{"executionOrder":3,"lastRunSuccess":true},
             "outputs":[{"outputId":"o1","items":[{"mime":"application/vnd.code.notebook.stdout","valueBytes":{"$$ref$$":0}}]}]},
            {"cellKind":1,"language":"markdown","source":"# Title","outputs":[]}
        ]}""")
        val data = Notebooks.data(json, Notebooks.Buffers(listOf("1\n".toByteArray())))
        assertEquals(JsonPrimitive(4), data.metadata["nbformat"])
        assertEquals(2, data.cells.size)
        val code = data.cells[0]
        assertEquals(Notebooks.CODE, code.kind)
        assertEquals("print(1)", code.source)
        assertEquals("1\n", code.outputs.single().items.single().data.decodeToString())
        assertEquals(JsonPrimitive(3), code.executionSummary?.get("executionOrder"))
        assertEquals(Notebooks.MARKUP, data.cells[1].kind)
    }

    @Test
    fun `writes a notebook back with each output's bytes as a buffer`() {
        val data = Notebooks.Data(
            JsonObject(emptyMap()),
            listOf(Notebooks.Cell(Notebooks.CODE, "x", "python", outputs = listOf(Notebooks.Output("o", listOf(Notebooks.Item("text/plain", byteArrayOf(1, 2))))))),
        )
        val buffers = Notebooks.Buffers()
        val json = Notebooks.dataJson(data, buffers)
        val item = json["cells"]!!.jsonArray[0].jsonObject["outputs"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals(buildJsonObject { put($$$"$$ref$$", 0) }, item["valueBytes"])
        assertEquals(listOf(1, 2), buffers.all.single().map { it.toInt() })
        // And reads back as it was written, the empty metadata it was given aside.
        val read = Notebooks.data(json, Notebooks.Buffers(buffers.all)).cells.single()
        assertEquals(data.cells.single(), read.copy(metadata = null, executionSummary = null))
    }

    @Test
    fun `a cell is first told to the host as its lines`() {
        val dto = Notebooks.cellDto(notebook, 4, Notebooks.Cell(Notebooks.CODE, "a\nb", "python"), Notebooks.Buffers())
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), dto["source"])
        assertEquals(JsonPrimitive(4), dto["handle"])
        assertEquals(Notebooks.cellUri(notebook, 4).toJson(), dto["uri"])
    }

    @Test
    fun `reads execution updates whether their buffers came beside them or not`() {
        // `$updateExecution(handle, new SerializableObjectWithBuffers(edits))`, with an output's bytes...
        val withBuffers = Notebooks.executionUpdate(
            $$$"""[7,[{"editType":1,"cellHandle":2,"append":true,"outputs":[{"outputId":"o","items":[{"mime":"text/plain","valueBytes":{"$$ref$$":0}}]}]},
                     {"editType":3,"runStartTime":100,"executionOrder":5}]]""",
            mapOf(1 to listOf("hi".toByteArray())),
        )
        assertEquals(7, withBuffers.first)
        val outputs = assertIs<Notebooks.ExecutionEdit.Outputs>(withBuffers.second[0])
        assertEquals(2, outputs.cell)
        assertEquals(true, outputs.append)
        assertEquals("hi", outputs.outputs.single().items.single().data.decodeToString())
        assertEquals(Notebooks.ExecutionEdit.State(100, 5), withBuffers.second[1])
        // ... and with none, as plain JSON wrapping the value.
        val plain = Notebooks.executionUpdate("""[7,{"value":[{"editType":3,"executionOrder":1}]}]""", emptyMap())
        assertEquals(Notebooks.ExecutionEdit.State(null, 1), plain.second.single())
    }

    @Test
    fun `reads a completion and its error`() {
        val completion = Notebooks.completion(
            """[3,{"value":{"runEndTime":250,"lastRunSuccess":false,"error":{"name":"KernelDied","message":"gone"}}}]""", emptyMap(),
        )
        assertEquals(Notebooks.Completion(3, 250, false, "KernelDied: gone"), completion)
        assertNull(Notebooks.completion("""[3,{"value":{"lastRunSuccess":true}}]""", emptyMap()).error)
    }

    @Test
    fun `reads a kernel as createNotebookController describes it`() {
        val kernel = Notebooks.kernel(
            Notebooks.ADD_KERNEL,
            """[0,{"id":"ms-toolsai.jupyter/py3","notebookType":"jupyter-notebook","extensionId":{"value":"ms-toolsai.jupyter","_lower":"ms-toolsai.jupyter"},
                "label":"Python 3","description":"~/venv","supportedLanguages":["python"],"supportsExecutionOrder":true,"preloads":[]}]""",
        )
        assertEquals("ms-toolsai.jupyter/py3", kernel.id)
        assertEquals("ms-toolsai.jupyter", kernel.extensionId)
        assertEquals(listOf("python"), kernel.supportedLanguages)
        assertEquals(true, kernel.supportsExecutionOrder)
        assertEquals(false, kernel.supportsInterrupt)
        assertNull(kernel.detail)
    }

    @Test
    fun `reads a serializer registration`() {
        val serializer = Notebooks.serializer(
            """[1,{"id":{"value":"vscode.ipynb","_lower":"vscode.ipynb"},"location":{"scheme":"file","path":"/x"}},"jupyter-notebook",
                {"transientOutputs":false,"transientCellMetadata":{"breakpointMargin":true},"transientDocumentMetadata":{},"cellContentMetadata":{}},null]""",
        )
        assertEquals(1, serializer.handle)
        assertEquals("vscode.ipynb", serializer.extensionId)
        assertEquals("jupyter-notebook", serializer.viewType)
        assertEquals(false, serializer.transientOutputs)
    }

    @Test
    fun `a delta names only what changed, and says when no editor is active`() {
        val delta = Notebooks.documentsDelta(removedEditors = listOf("e1"), visible = emptyList(), deactivated = true)
        assertEquals(setOf("removedEditors", "visibleEditors", "newActiveEditor"), delta.keys)
        assertEquals(JsonNull, delta["newActiveEditor"])
        assertEquals(setOf("visibleEditors"), Notebooks.documentsDelta(visible = listOf("e")).keys)
    }
}
