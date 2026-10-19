package me.helvert.codebridge.manifest

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** `%key%` strings in a manifest, and the `package.nls.json` beside it that supplies them. */
class ManifestStringsTest {

    @TempDir
    lateinit var extension: Path

    @Test
    fun `strings are read plain or from a message beside a translator's comment`() {
        extension.resolve("package.nls.json").writeText(
            """
            {
              // JSONC, as extensions write it
              "command.open": "Open Logs",
              "view.name": {"message": "Dependencies", "comment": ["The tree of crates"]},
              "not.text": 3,
              "no.message": {"comment": "nothing to show"},
            }
            """.trimIndent(),
        )

        assertEquals(
            mapOf("command.open" to "Open Logs", "view.name" to "Dependencies"),
            ManifestStrings.nls(extension)
        )
    }

    @Test
    fun `an extension without strings, or with unreadable ones, has none`() {
        assertEquals(emptyMap(), ManifestStrings.nls(extension))

        extension.resolve("package.nls.json").writeText("{ not json")
        assertEquals(emptyMap(), ManifestStrings.nls(extension))

        extension.resolve("package.nls.json").writeText("""["a list"]""")
        assertEquals(emptyMap(), ManifestStrings.nls(extension))
    }

    @Test
    fun `a directory named like the file is not read`() {
        extension.resolve("package.nls.json").createDirectory()
        assertEquals(emptyMap(), ManifestStrings.nls(extension))
    }

    @Test
    fun `only a whole percent-wrapped key is replaced, and an unknown one is left as written`() {
        val nls = mapOf("title" to "Open Logs", "" to "empty key")

        assertEquals("Open Logs", ManifestStrings.localized("%title%", nls))
        assertEquals("%missing%", ManifestStrings.localized("%missing%", nls))
        assertEquals("Run %title% now", ManifestStrings.localized("Run %title% now", nls))
        assertEquals("100%", ManifestStrings.localized("100%", nls))
        // Too short to hold a key, so not one: `%%` is two percent signs.
        assertEquals("%%", ManifestStrings.localized("%%", nls))
        assertEquals("%", ManifestStrings.localized("%", nls))
        assertEquals("Plain", ManifestStrings.localized("Plain", emptyMap()))
    }

    @Test
    fun `a whole manifest is localized however deep its strings sit`() {
        val manifest = Json.parseToJsonElement(
            """{"displayName": "%name%", "version": 3, "contributes": {"commands": [{"title": "%open%", "id": "open"}]}}""",
        )

        assertEquals(
            Json.parseToJsonElement(
                """{"displayName": "GitLens", "version": 3, "contributes": {"commands": [{"title": "Open", "id": "open"}]}}""",
            ),
            ManifestStrings.localized(manifest, mapOf("name" to "GitLens", "open" to "Open")),
        )
    }
}
