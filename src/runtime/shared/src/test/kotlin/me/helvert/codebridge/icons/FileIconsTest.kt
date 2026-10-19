package me.helvert.codebridge.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import java.awt.image.BufferedImage
import java.util.*
import javax.swing.Icon
import kotlin.test.*

/** File icons found the way VS Code finds them, and drawn with JetBrains' icons where there is one. */
class FileIconsTest {

    // Headless -- a Linux CI runner -- the platform draws every icon as one black pixel unless told
    // otherwise, and these tests are about what the icons draw.
    @BeforeTest
    fun activateIcons() = IconLoader.activate()

    @Test
    fun `every icon the mapping names exists, and Seti's for both themes`() {
        val properties = Properties().apply {
            FileIcons::class.java.getResourceAsStream("/fileicons/mapping.properties")!!.use { load(it) }
        }
        val paths = properties.stringPropertyNames().map { properties.getProperty(it) }.toSet()
        assertTrue(paths.size > 100, "The whole theme, not a handful: got ${paths.size}")
        for (path in paths) {
            if (path.startsWith("platform/")) {
                assertNotNull(FileIcons.icon(path), "$path is one of the IDE's icons")
                continue
            }
            assertNotNull(FileIcons::class.java.getResource("/fileicons/$path.svg"), "$path.svg")
            // JetBrains draws some icons once for both themes; a Seti glyph is coloured for each.
            if (path.startsWith("seti/")) {
                assertNotNull(FileIcons::class.java.getResource("/fileicons/${path}_dark.svg"), "${path}_dark.svg")
            }
        }
    }

    @Test
    fun `a language JetBrains has an icon for is drawn with it`() {
        assertSamePicture(bundled("jetbrains/kotlin"), FileIcons.forLanguage("kotlin", listOf(".cr"), emptyList()))
    }

    @Test
    fun `a language only Seti has an icon for is drawn with Seti's`() {
        assertSamePicture(bundled("seti/crystal"), FileIcons.forLanguage("codebridgeprobe", listOf(".cr"), emptyList()))
    }

    @Test
    fun `a language the theme does not know by id is found by its extensions, longest first`() {
        // `probe.cr` is not in the theme and `cr` is, which is how `.d.ts` reaches `ts`.
        assertSamePicture(bundled("seti/crystal"), FileIcons.forLanguage("codebridgeprobe", listOf(".probe.cr"), emptyList()))
    }

    @Test
    fun `then by its file names, ignoring case as VS Code does`() {
        assertSame(AllIcons.FileTypes.Bazel, FileIcons.forLanguage("codebridgeprobe", emptyList(), listOf("BUILD.bazel")))
    }

    @Test
    fun `a language the theme has nothing for gets nothing`() {
        // Not a generic file icon: a language with no icon of its own falls back further.
        assertNull(FileIcons.forLanguage("codebridgeprobe", listOf(".codebridgeprobe"), listOf("CodeBridgeprobefile")))
    }

    @Test
    fun `a file is found by its name alone, with no language involved`() {
        // VS Code's own Cargo.toml: no TOML support is built into VS Code, and it still has an icon.
        // The IDE's own TOML icon, where Seti only had its generic gear.
        assertSame(AllIcons.FileTypes.Toml, FileIcons.forFile("Cargo.toml"))
        assertSamePicture(bundled("seti/license"), FileIcons.forFile("LICENSE"))
        assertSamePicture(bundled("seti/crystal"), FileIcons.forFile("example.probe.cr"))
        // A dot file's name is its extension, as VS Code reads it.
        assertSamePicture(bundled("seti/babel"), FileIcons.forFile(".babelrc"))
        assertNull(FileIcons.forFile("example.codebridgeprobe"))
        assertNull(FileIcons.forFile("CodeBridgeprobefile"))
    }

    @Test
    fun `every bundled icon is a file tree row`() {
        val properties = Properties().apply {
            FileIcons::class.java.getResourceAsStream("/fileicons/mapping.properties")!!.use { load(it) }
        }
        // The IDE's own are left out: a test runs with placeholders for them, which paint nothing.
        val bundled =
            properties.stringPropertyNames().map { properties.getProperty(it) }.filterNot { it.startsWith("platform/") }
        for (path in bundled.toSet()) {
            val icon = bundled(path)
            assertEquals(16, icon.iconWidth, path)
            assertEquals(16, icon.iconHeight, path)
            assertTrue(painted(icon).any { it ushr 24 != 0 }, "$path is drawn")
        }
    }

    private fun bundled(path: String): Icon = assertNotNull(FileIcons.icon(path), path)

    private fun assertSamePicture(expected: Icon, actual: Icon?) {
        assertEquals(painted(expected), painted(assertNotNull(actual)))
    }

    private fun painted(icon: Icon): List<Int> {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return (0 until icon.iconWidth).flatMap { x -> (0 until icon.iconHeight).map { image.getRGB(x, it) } }
    }
}
