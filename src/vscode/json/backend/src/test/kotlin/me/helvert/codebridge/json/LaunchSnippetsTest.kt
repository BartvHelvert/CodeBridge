package me.helvert.codebridge.json

import kotlinx.serialization.json.Json
import me.helvert.codebridge.language.Snippets
import me.helvert.codebridge.manifest.VsixManifestParser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class LaunchSnippetsTest {

    @Test
    fun `a body is quoted and indented, and a caret-marked string goes in as written`() {
        // CodeLLDB's "Launch", as its package.json has it.
        val body = Json.parseToJsonElement(
            $$"""{"type": "lldb", "request": "launch", "name": "${2:Launch}",
                "program": "^\"\\${workspaceFolder}/${1:<program>}\"", "args": [], "cwd": "^\"\\${workspaceFolder}\""}"""
        )

        val text = LaunchSnippets.stringify(body, "")

        assertEquals(
            $$"{\n\t\"type\": \"lldb\",\n\t\"request\": \"launch\",\n\t\"name\": \"${2:Launch}\",\n" +
                $$"\t\"program\": \"\\${workspaceFolder}/${1:<program>}\",\n\t\"args\": [],\n\t\"cwd\": \"\\${workspaceFolder}\"\n}",
            text,
        )
        // What the user ends up with once the stops keep their defaults.
        assertEquals(
            "{\n\t\"type\": \"lldb\",\n\t\"request\": \"launch\",\n\t\"name\": \"Launch\",\n" +
                $$"\t\"program\": \"${workspaceFolder}/<program>\",\n\t\"args\": [],\n\t\"cwd\": \"${workspaceFolder}\"\n}",
            Snippets.plain(text),
        )
    }

    @Test
    fun `nested arrays and objects are indented a level each`() {
        val text = LaunchSnippets.stringify(Json.parseToJsonElement("""{"a": [1, {"b": null}]}"""), "")

        assertEquals("{\n\t\"a\": [\n\t\t1,\n\t\t{\n\t\t\t\"b\": null\n\t\t}\n\t]\n}", text)
    }

    @Test
    fun `labels written as nls keys are looked up`() {
        val directory = Files.createTempDirectory("launch-snippets")
        directory.resolve("package.nls.json").writeText("""{"probe.launch": "Probe: Launch", "probe.describe": {"message": "Starts it.", "comment": ["for translators"]}}""")
        val manifest = VsixManifestParser.parse(
            """{"publisher": "p", "name": "n", "version": "1.0.0", "contributes": {"debuggers": [{"type": "probe",
                "configurationSnippets": [{"label": "%probe.launch%", "description": "%probe.describe%", "body": {"type": "probe"}}]}]}}"""
        ).manifest!!

        val snippet = LaunchSnippets.of(manifest, LaunchSnippets.nls(directory)).single()

        assertEquals("Probe: Launch", snippet.label)
        assertEquals("Starts it.", snippet.description)
    }
}
