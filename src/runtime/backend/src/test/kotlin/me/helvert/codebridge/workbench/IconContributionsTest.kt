package me.helvert.codebridge.workbench

import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The icons an extension contributes, as GitLens declares its `$(gitlens-*)` ones. */
class IconContributionsTest {

    private fun extension(directory: Path, icons: String, enabled: Boolean = true): InstalledExtension {
        val manifest =
            """{ "publisher": "test", "name": "icons", "version": "1.0.0", "contributes": { "icons": $icons } }"""
        val parsed = requireNotNull(VsixManifestParser.parse(manifest).manifest) { "fixture manifest did not parse" }
        return InstalledExtension(parsed, directory, null, "2026-01-01T00:00:00Z", enabled)
    }

    @Test
    fun `glyphs are read with their font, grouped by font, and other icons by name`() {
        val directory = Files.createTempDirectory("codebridge-icons")
        val font = byteArrayOf(1, 2, 3)
        Files.createDirectories(directory.resolve("dist"))
        Files.write(directory.resolve("dist/glicons.woff2"), font)
        val icons = """{
            "gitlens-graph": { "description": "graph", "default": { "fontPath": "dist/glicons.woff2", "fontCharacter": "\\f102" } },
            "gitlens-commit": { "description": "commit", "default": { "fontPath": "./dist/glicons.woff2", "fontCharacter": "\\F101" } },
            "test-literal": { "description": "literal", "default": { "fontPath": "dist/glicons.woff2", "fontCharacter": "A" } },
            "test-alias": { "description": "alias", "default": "git-branch" }
        }"""

        val read = IconContributions.read(listOf(extension(directory, icons)))

        val only = read.fonts.single()
        assertContentEquals(font, only.font)
        assertEquals(
            mapOf("gitlens-graph" to 0xF102, "gitlens-commit" to 0xF101, "test-literal" to 'A'.code),
            only.glyphs
        )
        assertEquals(mapOf("test-alias" to "git-branch"), read.aliases)
    }

    @Test
    fun `a font outside the extension, a missing one or a disabled extension's is not read`() {
        val directory = Files.createTempDirectory("codebridge-icons")
        Files.write(directory.resolveSibling("outside.woff"), byteArrayOf(1))
        val icons = """{
            "test-outside": { "description": "", "default": { "fontPath": "../outside.woff", "fontCharacter": "\\e001" } },
            "test-missing": { "description": "", "default": { "fontPath": "missing.woff", "fontCharacter": "\\e002" } }
        }"""

        assertEquals(emptyList(), IconContributions.read(listOf(extension(directory, icons))).fonts)

        Files.write(directory.resolve("font.woff"), byteArrayOf(1))
        val disabled = extension(
            directory,
            """{ "test-icon": { "description": "", "default": { "fontPath": "font.woff", "fontCharacter": "\\e001" } } }""",
            enabled = false
        )
        assertEquals(emptyList(), IconContributions.read(listOf(disabled)).fonts)
    }

    @Test
    fun `a font character is a CSS escape or the character itself`() {
        assertEquals(0xF101, IconContributions.character("\\f101"))
        assertEquals(0x1F600, IconContributions.character("😀"))
        assertNull(IconContributions.character(""))
        assertNull(IconContributions.character("\\zz"))
    }
}
