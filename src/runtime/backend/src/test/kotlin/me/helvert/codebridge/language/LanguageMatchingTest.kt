package me.helvert.codebridge.language

import me.helvert.codebridge.manifest.LanguageContribution
import me.helvert.codebridge.manifest.VsixManifestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which contributed language a file is, by its name, as VS Code picks it. */
class LanguageMatchingTest {

    private val json = LanguageContribution("json", extensions = listOf(".json"))
    private val jsonc = LanguageContribution(
        "jsonc",
        filenames = listOf("tsconfig.json"),
        filenamePatterns = listOf("*.code-workspace")
    )
    private val docker =
        LanguageContribution("dockerfile", filenamePatterns = listOf("Dockerfile.*", "*.{dockerfile,containerfile}"))
    private val path = LanguageContribution("path-only", filenamePatterns = listOf("**/.github/*.yml"))

    @Test
    fun `file name patterns match as globs`() {
        assertTrue(docker.matches("Dockerfile.dev"))
        assertTrue(docker.matches("web.dockerfile"))
        assertTrue(docker.matches("WEB.Containerfile"), "Case is ignored, as for extensions")
        assertFalse(docker.matches("Dockerfile"))
        assertTrue(jsonc.matches("team.code-workspace"))
        // About a path, which a name cannot answer.
        assertFalse(path.matches("ci.yml"))
    }

    @Test
    fun `the closest claim wins - a name, then a pattern, then the longest extension`() {
        assertEquals(listOf("jsonc", "json"), closest(listOf(json, jsonc), "tsconfig.json").toList())
        assertEquals(listOf("json"), closest(listOf(json, jsonc), "package.json").toList())
        val workspace = LanguageContribution("workspace-json", extensions = listOf(".code-workspace"))
        assertEquals(listOf("jsonc", "workspace-json"), closest(listOf(workspace, jsonc), "a.code-workspace").toList())
        assertNull(json.closeness("notes.txt"))
    }

    @Test
    fun `globs support classes and their negation`() {
        assertTrue(glob("file[0-9].txt").matches("file3.txt"))
        assertFalse(glob("file[!0-9].txt").matches("file3.txt"))
        assertTrue(glob("file[!0-9].txt").matches("fileA.txt"))
    }

    @Test
    fun `the manifest's patterns are read`() {
        val manifest = VsixManifestParser.parse(
            """{"publisher":"p","name":"n","version":"1.0.0","engines":{"vscode":"^1.90.0"},
                "contributes":{"languages":[{"id":"dockerfile","filenamePatterns":["Dockerfile.*"]}]}}"""
        ).manifest!!
        assertEquals(listOf("Dockerfile.*"), manifest.contributes.languages.single().filenamePatterns)
    }
}
