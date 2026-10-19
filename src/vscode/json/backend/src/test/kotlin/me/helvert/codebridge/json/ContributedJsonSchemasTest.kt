package me.helvert.codebridge.json

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.json.ContributedJsonSchemas.FileMatch
import me.helvert.codebridge.json.ContributedJsonSchemas.Schema
import me.helvert.codebridge.manifest.VsixManifest
import me.helvert.codebridge.manifest.VsixManifestParser
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContributedJsonSchemasTest {

    @Test
    fun `a file name matches that file in any folder, and only that file`() {
        val match = FileMatch(listOf("rust-project.json"))
        assertTrue(match.matches("/work/app/rust-project.json"))
        assertTrue(match.matches("C:\\work\\rust-project.json"))
        assertFalse(match.matches("/work/my-rust-project.json"))
    }

    @Test
    fun `stars stay in a folder, double stars cross them, and a bang excludes`() {
        val match = FileMatch(listOf("/.vscode/*.json", "**/config/**/app.json", "!/.vscode/settings.json"))
        assertTrue(match.matches("/p/.vscode/cspell.json"))
        assertFalse(match.matches("/p/.vscode/sub/cspell.json"))
        assertFalse(match.matches("/p/.vscode/settings.json"))
        assertTrue(match.matches("/p/config/app.json"))
        assertTrue(match.matches("/p/config/a/b/app.json"))
    }

    @Test
    fun `VS Code's own locations match nothing`() {
        assertFalse(FileMatch(listOf("%APP_SETTINGS_HOME%/settings.json")).matches("/settings.json"))
    }

    @Test
    fun `schemas are on the web or inside the extension, never VS Code's own or outside it`() {
        val directory = Path.of("/ext/one").toAbsolutePath()
        val contributions = ContributedJsonSchemas.of("one", manifest(
            """{"fileMatch": "a.json", "url": "https://example.com/a.schema.json"},
               {"fileMatch": ["b.json"], "url": "./schemas/b.json"},
               {"fileMatch": "c.json", "url": "vscode://schemas/settings/user"},
               {"fileMatch": "d.json", "url": "../other/d.json"},
               {"fileMatch": [], "url": "./e.json"}"""
        ), directory)

        assertEquals(
            listOf(Schema.Remote("https://example.com/a.schema.json"), Schema.Local(directory.resolve("schemas/b.json"))),
            contributions.map { it.schema },
        )
        assertTrue(contributions[1].files.matches("/p/b.json"))
    }

    @Test
    fun `tasks offer each declared task type and problem matcher`() {
        val schema = TaskSchemas.tasks(listOf(VsixManifestParser.parse(
            """{"publisher": "codebridge", "name": "cargo", "version": "1.0.0", "contributes": {
                 "taskDefinitions": [{"type": "cargo", "required": ["command"], "properties": {"command": {"type": "string"}}}],
                 "problemMatchers": [{"name": "rustc", "label": "Rust compiler"}]}}"""
        ).manifest!!))

        val properties = schema["properties"]!!.jsonObject
        val items = properties["tasks"]!!.jsonObject["items"]!!.jsonObject
        val types = generateSequence(items) { it["else"] as? JsonObject }
            .map { it["if"]!!.jsonObject["properties"]!!.jsonObject["type"]!!.jsonObject["const"]!!.jsonPrimitive.content }
            .toList()
        assertEquals(listOf("shell", "process", "cargo"), types)

        val matchers = properties["problemMatcher"]!!.jsonObject["anyOf"]!!.jsonArray.first().jsonObject
        assertTrue($$"$rustc" in matchers["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Rust compiler", matchers["x-intellij-enum-metadata"]!!.jsonObject[$$"$rustc"]!!.jsonPrimitive.content)
    }

    @Suppress("SameParameterValue")
    private fun manifest(jsonValidation: String): VsixManifest = VsixManifestParser.parse(
        """{"publisher": "codebridge", "name": "one", "version": "1.0.0", "contributes": {"jsonValidation": [$jsonValidation]}}"""
    ).manifest!!
}
