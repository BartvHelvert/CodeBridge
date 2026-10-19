package me.helvert.codebridge.json

import kotlinx.serialization.json.*
import me.helvert.codebridge.manifest.VsixManifest
import me.helvert.codebridge.manifest.VsixManifestParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSchemasTest {

    @Test
    fun `each debugger request is a configuration shape of its own`() {
        val schema = WorkspaceSchemas.launch(listOf(manifest("one", PROBE_DEBUGGER)))
        val selections = configurationSelections(schema)

        assertEquals(listOf("probe/launch", "probe/attach"), selections.map { "${constOf(it, "type")}/${constOf(it, "request")}" })
        val launch = selections.first()["then"]!!.jsonObject
        assertEquals(JsonPrimitive(false), launch["additionalProperties"])
        assertEquals(listOf("name", "type", "request", "program"), launch["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val properties = launch["properties"]!!.jsonObject
        assertTrue("program" in properties && "preLaunchTask" in properties && "presentation" in properties)
    }

    @Test
    fun `a configuration's type offers every debugger, in every shape`() {
        val other = """{"type": "other", "configurationAttributes": {"attach": {}}}"""
        val schema = WorkspaceSchemas.launch(listOf(manifest("one", PROBE_DEBUGGER), manifest("two", other)))
        val items = schema["properties"]!!.jsonObject["configurations"]!!.jsonObject["items"]!!.jsonObject
        val types = (listOf(items) + configurationShapes(schema)).map { it["properties"]!!.jsonObject["type"]!!.jsonObject }

        types.forEach { assertEquals(listOf("probe", "other"), it["enum"]!!.jsonArray.map { value -> value.jsonPrimitive.content }) }
        assertEquals("Probe Debugger", types.first()["x-intellij-enum-metadata"]!!.jsonObject["probe"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a type several extensions contribute to is one shape per request`() {
        val more = """{"type": "probe", "configurationAttributes": {"launch": {"properties": {"extra": {"type": "string"}}}}}"""
        val shapes = configurationShapes(WorkspaceSchemas.launch(listOf(manifest("one", PROBE_DEBUGGER), manifest("two", more))))

        assertEquals(2, shapes.size)
        val launch = shapes.first()["properties"]!!.jsonObject
        assertTrue("program" in launch && "extra" in launch)
    }

    @Test
    fun `without a debugger a configuration still offers the common fields`() {
        val items = WorkspaceSchemas.launch(emptyList())["properties"]!!.jsonObject["configurations"]!!.jsonObject["items"]!!.jsonObject

        assertNull(items["if"])
        assertTrue("preLaunchTask" in items["properties"]!!.jsonObject)
    }

    @Test
    fun `enum descriptions become the enum metadata the IDE reads`() {
        val translated = WorkspaceSchemas.withEnumMetadata(
            JsonObject(mapOf(
                "enum" to JsonArray(listOf(JsonPrimitive("off"), JsonPrimitive("verbose"))),
                "enumDescriptions" to JsonArray(listOf(JsonPrimitive("Nothing"), JsonPrimitive("Everything"))),
            ))
        ).jsonObject

        assertEquals(
            mapOf("off" to "Nothing", "verbose" to "Everything"),
            translated["x-intellij-enum-metadata"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `workspace settings leave out what only user settings may set`() {
        val manifests = listOf(manifest("one", configuration = PROBE_CONFIGURATION))

        val user = WorkspaceSchemas.settings(manifests, workspace = false)["properties"]!!.jsonObject
        val workspace = WorkspaceSchemas.settings(manifests, workspace = true)["properties"]!!.jsonObject

        assertEquals(setOf("probe.trace", "probe.path", "probe.indent"), user.keys)
        assertEquals(setOf("probe.trace", "probe.indent"), workspace.keys)
    }

    @Test
    fun `language overrides offer the language-overridable settings`() {
        val schema = WorkspaceSchemas.settings(listOf(manifest("one", configuration = PROBE_CONFIGURATION)), workspace = true)
        val override = schema["patternProperties"]!!.jsonObject.values.single().jsonObject

        assertEquals(setOf("probe.indent"), override["properties"]!!.jsonObject.keys)
        assertTrue(Regex(schema["patternProperties"]!!.jsonObject.keys.single()).matches("[rust][toml]"))
    }

    private fun configurationSelections(schema: JsonObject): List<JsonObject> =
        generateSequence(schema["properties"]!!.jsonObject["configurations"]!!.jsonObject["items"]!!.jsonObject.takeIf { "if" in it }) {
            it["else"] as? JsonObject
        }.toList()

    private fun configurationShapes(schema: JsonObject): List<JsonObject> =
        configurationSelections(schema).map { it["then"]!!.jsonObject }

    private fun constOf(selection: JsonObject, property: String): String =
        selection["if"]!!.jsonObject["properties"]!!.jsonObject[property]!!.jsonObject["const"]!!.jsonPrimitive.content

    private fun manifest(name: String, debugger: String? = null, configuration: String? = null): VsixManifest {
        val contributes = listOfNotNull(debugger?.let { "\"debuggers\": [$it]" }, configuration?.let { "\"configuration\": $it" })
        return VsixManifestParser.parse(
            """{"publisher": "codebridge", "name": "$name", "version": "1.0.0", "contributes": {${contributes.joinToString()}}}"""
        ).manifest!!
    }

    private companion object {
        const val PROBE_DEBUGGER = """{
            "type": "probe",
            "label": "Probe Debugger",
            "configurationAttributes": {
                "launch": {"required": ["program"], "properties": {"program": {"type": "string"}}},
                "attach": {"properties": {"port": {"type": "number"}}}
            }
        }"""

        const val PROBE_CONFIGURATION = """[
            {"title": "Probe", "properties": {
                "probe.trace": {"type": "string", "enum": ["off", "verbose"]},
                "probe.path": {"type": "string", "scope": "machine"}
            }},
            {"properties": {"probe.indent": {"type": "number", "scope": "language-overridable"}}}
        ]"""
    }
}
