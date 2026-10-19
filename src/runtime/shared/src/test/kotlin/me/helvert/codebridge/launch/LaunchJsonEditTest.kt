package me.helvert.codebridge.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.launch.LaunchJsonEdit.Section
import me.helvert.codebridge.workspace.JsoncEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A run configuration's form, applied: only the keys it changed, in the entry it names. */
class LaunchJsonEditTest {

    private val launch = """
        {
            "version": "0.2.0",
            "configurations": [
                {
                    "name": "Server",
                    "type": "lldb",
                    "request": "launch",
                    // Built by the task below.
                    "program": "target/debug/server",
                    "preLaunchTask": "build"
                },
                { "name": "Client", "type": "lldb", "request": "launch", "program": "client" }
            ],
            "compounds": [
                { "name": "Both", "configurations": ["Server", { "name": "Client" }, { "name": "Server", "folder": "other" }] }
            ]
        }
    """.trimIndent()

    @Test
    fun `only the changed keys of the named entry are written`() {
        val after = LaunchJsonEdit.apply(launch, emptyList(), Section.CONFIGURATIONS, "Server",
            EntryChanges(set = mapOf("preLaunchTask" to JsonPrimitive("compile"))))

        assertEquals(launch.replace("\"preLaunchTask\": \"build\"", "\"preLaunchTask\": \"compile\""), after)
    }

    @Test
    fun `a removed key goes, and the comment above the one before it stays`() {
        val after = LaunchJsonEdit.apply(launch, emptyList(), Section.CONFIGURATIONS, "Server", EntryChanges(removed = setOf("preLaunchTask")))

        assertEquals(launch.replace("\"target/debug/server\",\n            \"preLaunchTask\": \"build\"\n", "\"target/debug/server\"\n"), after)
    }

    @Test
    fun `a renamed configuration takes along the compounds naming it in this file`() {
        val after = LaunchJsonEdit.apply(launch, emptyList(), Section.CONFIGURATIONS, "Server",
            EntryChanges(set = mapOf("name" to JsonPrimitive("Backend"))))

        val members = JsoncEdit.parse(after)["compounds"]!!.jsonArray[0].jsonObject["configurations"]!!.jsonArray
        assertEquals(JsonPrimitive("Backend"), members[0])
        // Another folder's "Server" is another configuration.
        assertEquals(JsonPrimitive("Server"), members[2].jsonObject["name"])
        assertEquals(JsonPrimitive("Backend"), JsoncEdit.parse(after)["configurations"]!!.jsonArray[0].jsonObject["name"])
    }

    @Test
    fun `a compound is changed in the compounds`() {
        val after = LaunchJsonEdit.apply(launch, emptyList(), Section.COMPOUNDS, "Both",
            EntryChanges(set = mapOf("stopAll" to JsonPrimitive(true), "configurations" to JsonArray(listOf(JsonPrimitive("Client"))))))

        assertEquals(buildJsonObject {
            put("name", "Both")
            put("configurations", JsonArray(listOf(JsonPrimitive("Client"))))
            put("stopAll", true)
        }, JsoncEdit.parse(after)["compounds"]!!.jsonArray[0])
    }

    @Test
    fun `an entry of a code-workspace file is under its launch`() {
        val workspace = """{ "folders": [{ "path": "." }], "launch": $launch }"""

        val after = LaunchJsonEdit.apply(workspace, listOf("launch"), Section.CONFIGURATIONS, "Client",
            EntryChanges(set = mapOf("args" to JsonArray(listOf(JsonPrimitive("--verbose"))))))

        assertEquals(workspace.replace("\"program\": \"client\" }", "\"program\": \"client\", \"args\": [\"--verbose\"] }"), after)
    }

    @Test
    fun `a name that is taken, empty or removed is refused`() {
        for (changes in listOf(
            EntryChanges(set = mapOf("name" to JsonPrimitive("Client"))),
            EntryChanges(set = mapOf("name" to JsonPrimitive(" "))),
            EntryChanges(removed = setOf("name")),
        )) {
            assertFailsWith<IllegalArgumentException> { LaunchJsonEdit.apply(launch, emptyList(), Section.CONFIGURATIONS, "Server", changes) }
        }
    }

    @Test
    fun `changes are what differs, key by key`() {
        val before = buildJsonObject { put("name", "A"); put("program", "a"); put("stopOnEntry", true) }
        val after = buildJsonObject { put("name", "A"); put("program", "b"); put("cwd", "src") }

        assertEquals(EntryChanges(mapOf("program" to JsonPrimitive("b"), "cwd" to JsonPrimitive("src")), setOf("stopOnEntry")),
            EntryChanges.between(before, after))
    }
}
