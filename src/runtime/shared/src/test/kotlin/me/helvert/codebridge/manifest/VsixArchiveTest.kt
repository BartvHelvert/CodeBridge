package me.helvert.codebridge.manifest

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VsixArchiveTest {

    private fun vsix(vararg entries: Pair<String, String>): Path {
        val file = Files.createTempFile("test", ".vsix")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private val minimalManifest = """
        { "publisher": "acme", "name": "demo", "version": "1.0.0", "main": "out/main.js" }
    """.trimIndent()

    @Test
    fun `reads the manifest out of the archive`() {
        val archive = vsix(
            "extension.vsixmanifest" to "<PackageManifest/>",
            VsixArchive.MANIFEST_ENTRY to minimalManifest,
        )

        val manifest = assertNotNull(VsixArchive.readManifest(archive).manifest)
        assertEquals("acme.demo", manifest.id)
    }

    @Test
    fun `a zip that is not a VSIX is reported rather than throwing`() {
        val result = VsixArchive.readManifest(vsix("readme.txt" to "hello"))
        assertEquals(null, result.manifest)
        assertContains(result.problems.single(), VsixArchive.MANIFEST_ENTRY)
    }

    @Test
    fun `unpacking strips the extension prefix so the target becomes the extension root`() {
        val archive = vsix(
            "extension.vsixmanifest" to "<PackageManifest/>",
            "[Content_Types].xml" to "<Types/>",
            VsixArchive.MANIFEST_ENTRY to minimalManifest,
            "extension/out/main.js" to "console.log(1)",
        )
        val target = createTempDirectory("install")

        val written = VsixArchive.unpack(archive, target)

        assertTrue(target.resolve("package.json").exists())
        assertEquals("console.log(1)", target.resolve("out/main.js").readText())
        // OPC packaging files belong to the archive, not to the extension.
        assertTrue(!target.resolve("extension.vsixmanifest").exists())
        assertEquals(2, written.size)
    }

    @Test
    fun `refuses an entry that would escape the install directory`() {
        val archive = vsix(
            VsixArchive.MANIFEST_ENTRY to minimalManifest,
            "extension/../../evil.js" to "owned",
        )
        val target = createTempDirectory("install")

        val failure = assertFailsWith<IllegalArgumentException> { VsixArchive.unpack(archive, target) }
        assertContains(failure.message.orEmpty(), "outside")
        assertTrue(!target.parent.resolve("evil.js").exists())
    }
}
