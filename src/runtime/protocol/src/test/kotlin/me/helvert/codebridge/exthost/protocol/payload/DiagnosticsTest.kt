package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `MainThreadDiagnostics.$changeMany`, against the payloads a real host pushed. */
class DiagnosticsTest {

    @Test
    fun `reads the markers vs code pushes`() {
        val change = Diagnostics.changes(changeMany(MARKER)).single()
        assertTrue(change.uri.uri.endsWith("/example.codebridgebundled"), change.uri.uri)
        val marker = change.markers!!.single()
        assertEquals(1, marker.range.startLineNumber)
        assertEquals(1, marker.range.startColumn)
        assertEquals(6, marker.range.endColumn)
        assertEquals(Marker.ERROR, marker.severity)
        assertEquals("Replace ERROR to clear this diagnostic", marker.message)
        assertEquals("CodeBridge prototype", marker.source)
        assertEquals(emptyList(), marker.tags)
    }

    @Test
    fun `preserves unnecessary and other diagnostic tags`() {
        val marker = Diagnostics.changes(changeMany("""[{"message":"unused","severity":1,"tags":[1,2,99]}]"""))
            .single().markers!!.single()
        assertEquals(listOf(Marker.UNNECESSARY, 2, 99), marker.tags)
        assertEquals(1, marker.severity)
    }

    @Test
    fun `reads a bare code and a code with a target`() {
        val bare =
            Diagnostics.changes(changeMany("""[{"message":"m","code":"dead_code"}]""")).single().markers!!.single()
        assertEquals(Marker.Code("dead_code"), bare.code)
        // rust-analyzer's link to the compiler's full output, as the host sends it.
        val linked = Diagnostics.changes(
            changeMany(
                """[{"message":"m","code":{"value":"Click for full compiler diagnostic","target":""" +
                        """{"scheme":"rust-analyzer-diagnostics-view","path":"/diagnostic message [0]","query":"0",""" +
                        """"fragment":"file:///c%3A/x/src/main.rs"}}}]"""
            )
        ).single().markers!!.single()
        assertEquals("Click for full compiler diagnostic", linked.code!!.value)
        assertEquals("rust-analyzer-diagnostics-view", linked.code.target!!.scheme)
    }

    @Test
    fun `no code is no code`() {
        val marker = Diagnostics.changes(changeMany("""[{"message":"m","code":""}]""")).single().markers!!.single()
        assertNull(marker.code)
    }

    @Test
    fun `null diagnostic tags are treated as absent`() {
        val marker = Diagnostics.changes(changeMany("""[{"message":"ordinary","tags":null}]"""))
            .single().markers!!.single()
        assertEquals(emptyList(), marker.tags)
    }

    @Test
    fun `null markers clear a file rather than reporting nothing`() {
        // The difference matters: "no problems" has to remove what was there, not be ignored.
        assertNull(Diagnostics.changes(changeMany(null)).single().markers)
    }

    @Test
    fun `an empty marker list is not the same as clearing`() {
        assertEquals(emptyList(), Diagnostics.changes(changeMany("[]")).single().markers)
    }

    @Test
    fun `an entry without a path is skipped rather than failing the batch`() {
        val json = """[1,[[{"scheme":"untitled"},[]],[{"scheme":"file","path":"/C:/x.codebridgebundled"},[]]]]"""
        assertEquals(1, Diagnostics.changes(json).size)
    }

    @Test
    fun `a marker that names no range is placed at the start rather than dropped`() {
        // Extensions do push these -- a whole-file problem with no position worth reporting.
        val marker = Diagnostics.changes(changeMany("""[{"message":"whole file"}]"""))
            .single().markers!!.single()
        assertEquals(1, marker.range.startLineNumber)
        assertEquals(Marker.ERROR, marker.severity)
    }

    @Test
    fun `problems for the host are written as its markers and read back unchanged`() {
        val marker = Marker(
            Range(3, 5, 3, 9), Marker.WARNING, "Variable 'x' is never used", "IntelliJ IDEA",
            tags = listOf(Marker.UNNECESSARY), code = Marker.Code("unused")
        )
        val uri = UriComponents.of("file:///C:/Projects/Example.java")
        val args = Diagnostics.acceptMarkersChange(listOf(uri to listOf(marker)))
        // One argument: the list of [resource, markers] pairs.
        val sent =
            kotlinx.serialization.json.Json.parseToJsonElement(args).jsonArray.single().jsonArray.single().jsonArray
        assertEquals("/C:/Projects/Example.java", sent[0].jsonObject["path"]?.jsonPrimitive?.content)
        val written = sent[1].jsonArray.single()
        assertEquals("unused", written.jsonObject["code"]?.jsonPrimitive?.content)
        assertEquals(marker, Marker.from(written))
    }

    @Test
    fun `no problems for a resource is sent as an empty list, which withdraws them`() {
        val args = Diagnostics.acceptMarkersChange(listOf(UriComponents.of("file:///C:/x.java") to emptyList()))
        assertTrue(args.endsWith(",[]]]]"), args)
    }

    private fun changeMany(markers: String?) =
        """[1,[[{"scheme":"file","authority":"","path":"/C:/Projects/example.codebridgebundled"},${markers ?: "null"}]]]"""

    private companion object {
        const val MARKER = """[{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":6,""" +
                """"severity":8,"message":"Replace ERROR to clear this diagnostic","source":"CodeBridge prototype"}]"""
    }
}
