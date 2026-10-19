package me.helvert.codebridge.ide

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.icons.CachedImageIcon
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.icons.FileIcons
import me.helvert.codebridge.internal.CodeBridgePluginAdvertiserSuppressor
import me.helvert.codebridge.language.*
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.io.path.writeText
import kotlin.test.*

/**
 * Giving a file a type, which is what lets anything else happen to it.
 *
 * VS Code learns a language from `contributes.languages`; IntelliJ has never heard of it, and
 * without a type there is no document, no PSI and nothing to annotate. Two bugs have lived here.
 * The first shipped rejecting every file: the content detector this used to be asked the platform
 * for zero bytes, so it had nothing to judge text from binary by and handed back a null
 * `firstCharsIfText`, which the detector read as "binary, not mine". The second was that for the
 * files anyone installs an extension for in the first place, a detector never runs at all -- the
 * TextMate plugin claims every name it has a grammar for, ahead of detection, so a `.rs` file with
 * rust-analyzer installed was typed `textmate`. Both looked like a theme.
 */
class LanguageFileTypeTest : EditorFixture() {

    // Headless -- a Linux CI runner -- the platform draws every icon as one black pixel unless told
    // otherwise, and these tests are about what the icons draw.
    @BeforeEach
    fun activateIcons() = IconLoader.activate()
    private val installedIds = mutableSetOf<String>()

    /**
     * A light fixture starts the platform, not this plugin, so the overrider has to be registered
     * by hand for the platform's own file type lookup to reach it. Without this the tests that go
     * through `VirtualFile.getFileType` pass on the platform's fallback: an unclaimed text file is
     * plain text, which is exactly what a claimed one used to be. `the plugin registers the
     * overrider` covers the declaration this stands in for.
     */
    @BeforeEach
    fun registerOverrider() = onEdt {
        FileTypeOverrider.EP_NAME.point
            .registerExtension(CodeBridgeLanguageFileTypeOverrider(), fixture.testRootDisposable)
    }

    @AfterEach
    fun removeExtensions() {
        installedIds.forEach { ExtensionInstallationService.getInstance().uninstall(it) }
    }

    @Test
    fun `the plugin registers the overrider`() {
        val descriptor = Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath()
        assertTrue(
            Files.readString(descriptor)
                .contains("""<fileTypeOverrider implementation="me.helvert.codebridge.language.CodeBridgeLanguageFileTypeOverrider"/>"""),
            "The overrider must stay declared for any of this to reach a real IDE",
        )
    }

    @Test
    fun `claims a file an installed extension contributes`() = onEdt {
        install("codebridgebundled", ".codebridgebundled", alias = "CodeBridge Bundled")

        val claimed = types.forName("example.codebridgebundled")

        requireNotNull(claimed)
        assertEquals("CodeBridge.codebridgebundled", claimed.name)
        assertEquals("CodeBridge Bundled", claimed.displayName)
        assertEquals("CodeBridge Bundled (VS Code)", claimed.description)
        assertEquals("codebridgebundled", claimed.defaultExtension)
        assertFalse(claimed.isBinary)
    }

    /**
     * During an install many threads type files at once. One handed the type while another was
     * still registering the reader's editor highlighter built the platform's default highlighter,
     * which threw "token type ... is not registered" for every TextMate token.
     */
    @Test
    fun `no thread gets a claimed type before its reader has registered for it`() {
        val registered = java.util.concurrent.atomic.AtomicBoolean()
        val reader = object : CodeBridgeFileLanguageProvider {
            override fun languageFor(fileName: String): Language? =
                PlainTextLanguage.INSTANCE.takeIf { fileName.endsWith(".codebridgeslow") }

            override fun claimed(fileType: FileType) {
                Thread.sleep(300)
                registered.set(true)
            }
        }
        onEdt {
            val area = ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl
            if (!area.hasExtensionPoint(CodeBridgeFileLanguageProvider.EP_NAME)) {
                area.registerExtensionPoint(
                    CodeBridgeFileLanguageProvider.EP_NAME, CodeBridgeFileLanguageProvider::class.java.name,
                    ExtensionPoint.Kind.INTERFACE, fixture.testRootDisposable
                )
            }
            CodeBridgeFileLanguageProvider.EP_NAME.point.registerExtension(reader, fixture.testRootDisposable)
            install("codebridgeslow", ".codebridgeslow")
        }

        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val seen = (1..4).map {
                pool.submit<Boolean> {
                    val type =
                        ReadAction.computeBlocking<FileType?, RuntimeException> { types.forName("example.codebridgeslow") }
                    type != null && registered.get()
                }
            }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf(true, true, true, true), seen)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Registering TextMate's highlighter notifies the platform's listeners on the same thread, and
     * the editor highlighter updater types every open file again -- straight back into the
     * overrider. Marking the type told only after the reader returned made that a loop, which ended
     * in a StackOverflowError the first time a project opened with a claimed file in the editor.
     */
    @Test
    fun `a reader that asks for the type while being told of it is told once`() = onEdt {
        var told = 0
        val reader = object : CodeBridgeFileLanguageProvider {
            override fun languageFor(fileName: String): Language? =
                PlainTextLanguage.INSTANCE.takeIf { fileName.endsWith(".codebridgereentrant") }

            override fun claimed(fileType: FileType) {
                told++
                assertSame(fileType, types.forName("example.codebridgereentrant"))
            }
        }
        val area = ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl
        if (!area.hasExtensionPoint(CodeBridgeFileLanguageProvider.EP_NAME)) {
            area.registerExtensionPoint(
                CodeBridgeFileLanguageProvider.EP_NAME, CodeBridgeFileLanguageProvider::class.java.name,
                ExtensionPoint.Kind.INTERFACE, fixture.testRootDisposable
            )
        }
        CodeBridgeFileLanguageProvider.EP_NAME.point.registerExtension(reader, fixture.testRootDisposable)
        install("codebridgereentrant", ".codebridgereentrant")

        assertNotNull(types.forName("example.codebridgereentrant"))
        assertEquals(1, told)
    }

    @Test
    fun `leaves a file no extension contributes alone`() = onEdt {
        install("codebridgebundled", ".codebridgebundled")

        assertNull(types.forName("example.unclaimed"))
    }

    @Test
    fun `the editor gives the file its language type and retains text PSI`() = onEdt {
        // The claim in isolation is not the one that matters; this is. It goes through the
        // platform's own file type lookup, which is what was silently failing.
        install("codebridgeprobe", ".codebridgeprobe")
        val file = file("detected.codebridgeprobe", "ERROR something")
        fixture.openFileInEditor(file)

        assertEquals("CodeBridge.codebridgeprobe", file.fileType.name)
        assertEquals("codebridgeprobe", file.fileType.displayName)
        assertEquals(file.fileType, fixture.file.fileType)
        assertEquals(PlainTextLanguage.INSTANCE, fixture.file.language)
        assertEquals("ERROR something", fixture.editor.document.text)
    }

    @Test
    fun `different languages have distinct types and a repeated claim reuses the type`() = onEdt {
        install("first", ".codebridgefirst")
        install("second", ".codebridgesecond")
        val firstType = types.forName("example.codebridgefirst")
        val secondType = types.forName("example.codebridgesecond")

        assertEquals("CodeBridge.first", firstType?.name)
        assertEquals("CodeBridge.second", secondType?.name)
        assertNotEquals(firstType, secondType)
        // Identity, not equality: the platform reads a new instance as the file changing type.
        assertSame(firstType, types.forName("example.codebridgefirst"))
    }

    @Test
    fun `disabled and uninstalled extensions no longer claim files`() = onEdt {
        install("disabled", ".codebridgedisabled")
        val service = ExtensionInstallationService.getInstance()
        assertEquals("CodeBridge.disabled", types.forName("example.codebridgedisabled")?.name)

        // Each of these moves the store's revision, which is what the claim cache is keyed by.
        service.setEnabled("codebridge.probe-disabled", false)
        assertNull(types.forName("example.codebridgedisabled"))
        service.setEnabled("codebridge.probe-disabled", true)
        assertEquals("CodeBridge.disabled", types.forName("example.codebridgedisabled")?.name)
        service.uninstall("codebridge.probe-disabled")
        assertNull(types.forName("example.codebridgedisabled"))
    }

    @Test
    fun `plugin suggestions follow enabled extension support`() = onEdt {
        install("suggestion", ".codebridgesuggestion")
        val supported = file("example.codebridgesuggestion", "content")
        val unsupported = file("example.unclaimed", "content")
        val suppressor = CodeBridgePluginAdvertiserSuppressor()
        val service = ExtensionInstallationService.getInstance()

        assertTrue(suppressor.isSuppressedFor(fixture.project, supported))
        assertFalse(suppressor.isSuppressedFor(fixture.project, unsupported))
        assertFalse(suppressor.isSuppressedFor(fixture.project, supported.parent))
        service.setEnabled("codebridge.probe-suggestion", false)
        assertFalse(suppressor.isSuppressedFor(fixture.project, supported))
        service.setEnabled("codebridge.probe-suggestion", true)
        assertTrue(suppressor.isSuppressedFor(fixture.project, supported))
        service.uninstall("codebridge.probe-suggestion")
        assertFalse(suppressor.isSuppressedFor(fixture.project, supported))
    }

    @Test
    fun `native filename associations take precedence`() = onEdt {
        install("text-claim", ".txt")
        val file = file("native.txt", "content")
        fixture.openFileInEditor(file)

        // The IDE's own name table wins: it reads `.txt` better than a VS Code extension will, and
        // an overrider that did not stand back here would take `.json` and `.py` away too.
        assertSame(PlainTextFileType.INSTANCE, file.fileType)
        assertNull(types.forName("native.txt"))
    }

    @Test
    fun `a language is drawn with the icon it declared`() = onEdt {
        install("iconed", ".codebridgeiconed", languageIcon = true)

        val icon = iconOf("example.codebridgeiconed")

        // The IDE has no icon for a language it has never heard of, and a table of them here is the
        // hard-coding this whole path exists to avoid. The extension says, as it does in VS Code.
        assertNotSame(
            monogramOf("example.codebridgeiconed").current(),
            icon,
            "A declared icon must win over the drawn fallback"
        )
        assertEquals(16, icon.iconWidth, "Whatever size it was published at, a file tree row is 16px")
        assertEquals(16, icon.iconHeight)
        assertPaintsWithinItsBounds(icon)
    }

    @Test
    fun `a language VS Code's icon theme knows gets VS Code's icon, over its own`() = onEdt {
        install("rust", ".codebridgerust", languageIcon = true)

        // VS Code draws a declared language icon only where its file icon theme has none, and the theme
        // has one for Rust. A loaded image, like the monogram, so split mode can describe it.
        val icon = iconOf("example.codebridgerust")
        assertIs<CachedImageIcon>(icon)
        assertNotSame(monogramOf("example.codebridgerust").current(), icon)
        assertFalse(painted(icon).any { it == Color.RED.rgb }, "The declared icon, a red square, must not win")
        assertEquals(16, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
        assertPaintsWithinItsBounds(icon)
    }

    @Test
    fun `a file nothing claims gets VS Code's icon for its name`() = onEdt {
        // The test IDE has no TOML support, like a fresh VS Code: the icon comes from the name.
        // The IDE's own TOML icon, so whatever the IDE draws a TOML file with.
        assertSame(AllIcons.FileTypes.Toml, CodeBridgeFileIconProvider().getIcon(file("Cargo.toml", "[package]"), 0, null))
    }

    @Test
    fun `a file the IDE has a type of its own for keeps the IDE's icon`() = onEdt {
        // The theme has an icon for `.zip`, and the platform reads archives itself.
        assertNull(CodeBridgeFileIconProvider().getIcon(file("example.zip", ""), 0, null))
    }

    @Test
    fun `a claimed file's name comes before its language, as in VS Code`() = onEdt {
        install("codebridgelicensed", "", filename = "LICENSE")
        val file = file("LICENSE", "MIT")
        assertIs<CodeBridgeLanguageFileType>(file.fileType)

        val icon = assertNotNull(CodeBridgeFileIconProvider().getIcon(file, 0, null))
        assertEquals(
            painted(IconLoader.getIcon("/fileicons/seti/license.svg", FileIcons::class.java.classLoader)),
            painted(icon)
        )
        // And a claimed name the theme does not know is left to the type, which goes by the language.
        install("codebridgeunnamed", ".codebridgeunnamed")
        assertNull(CodeBridgeFileIconProvider().getIcon(file("example.codebridgeunnamed", "text"), 0, null))
    }

    @Test
    fun `an icon published at poster size still paints the size it claims`() = onEdt {
        // The bug this exists for: `IconUtil.resizeSquared` refuses to shrink by more than ten, so
        // an icon published at 600px -- rust-analyzer's is exactly that -- reported 16 and painted
        // 60, straight over the file name beside it. Asking an icon how big it is does not catch
        // that; only painting it does.
        install("poster", ".codebridgeposter", languageIcon = true, iconPixels = 600)

        assertPaintsWithinItsBounds(iconOf("example.codebridgeposter"))
    }

    @Test
    fun `an extension's marketplace icon is not a file icon`() = onEdt {
        install("borrowed", ".codebridgeborrowed", packageIcon = true)

        // Reaching for `package.json`'s own `icon` when a language declares none is wrong twice
        // over: VS Code never draws it on a file, and it is one image for both themes -- and they
        // are marketplace tiles, so on a dark file tree it is usually a white block.
        // rust-analyzer is exactly this case, and gets a monogram like every other silent language.
        assertMonogram("example.codebridgeborrowed")
    }

    @Test
    fun `a language whose extension ships no image is drawn its initial`() = onEdt {
        install("plain", ".codebridgeplain", alias = "Rustish")

        val icon = iconOf("example.codebridgeplain")

        // Not the platform's text icon: that is what every claimed file used to share, which made
        // one contributed language indistinguishable from the next.
        val monogram = assertMonogram("example.codebridgeplain")
        assertEquals("R", monogram.letter, "The letter comes from the name the extension gave it")
        assertPaintsWithinItsBounds(icon)
    }

    @Test
    fun `the monogram is a loaded image, so split mode can send it to the frontend`() = onEdt {
        install("remote", ".codebridgeremote", alias = "Remote")

        // The bug this exists for: a class painting itself has no model the backend can send, so
        // the platform logged "Failed to serialize a model for icon" on every file tree repaint.
        val icon = assertMonogram("example.codebridgeremote").current()
        assertIs<CachedImageIcon>(icon)
        assertEquals(16, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
        assertPaintsWithinItsBounds(icon)
    }

    @Test
    fun `the monogram has a variant for each theme`() = onEdt {
        install("themed", ".codebridgethemed")
        val monogram = monogramOf("example.codebridgethemed")

        // The same hue cannot carry a thin ring against both a light and a dark tree.
        assertNotEquals(monogram.svg(dark = false), monogram.svg(dark = true))
        assertTrue(monogram.svg(dark = false).contains("<path d=\"M"), "The letter is drawn as an outline")
    }

    @Test
    fun `a language with no usable name still gets a tile`() = onEdt {
        install("-", ".codebridgenameless")

        // Nothing to take a letter from, and an icon that throws would take the file tree with it.
        assertEquals("?", assertMonogram("example.codebridgenameless").letter)
    }

    @Test
    fun `the monogram is a washed disc, not a solid tile`() = onEdt {
        install("disc", ".codebridgedisc")
        val icon = iconOf("example.codebridgedisc")

        val pixels = painted(icon)
        val size = icon.iconWidth
        fun alphaAt(x: Int, y: Int) = pixels[x * size + y] ushr 24

        // A solid square is the heaviest thing a row can hold, and read as shouting beside the
        // platform's own outlined icons. Corners clear is what makes it a disc.
        for ((x, y) in listOf(0 to 0, size - 1 to 0, 0 to size - 1, size - 1 to size - 1)) {
            assertEquals(0, alphaAt(x, y), "A disc leaves ($x, $y) clear; a tile would not")
        }
        // And the inside is a wash rather than a fill, which is the rest of the weight: the ring
        // and the letter are opaque, everything between them is not.
        val washed = pixels.count { (it ushr 24) in 20..120 }
        assertTrue(washed >= size, "Only $washed washed pixels: the disc is painted, not tinted")
    }

    @Test
    fun `two languages are drawn in different colours, and one keeps its own`() = onEdt {
        install("alpha", ".codebridgealpha")
        install("beta", ".codebridgebeta")

        val alpha = painted(iconOf("example.codebridgealpha"))
        val beta = painted(iconOf("example.codebridgebeta"))

        assertNotEquals(alpha, beta, "Two languages sharing a colour would defeat the point of it")
        // Derived from the language id rather than remembered, so it survives a restart unstored.
        assertEquals(alpha, painted(iconOf("example.codebridgealpha")))
    }

    @Test
    fun `an icon pointing outside the extension is refused`() = onEdt {
        install("escaping", ".codebridgeescaping", languageIcon = true, iconPath = "../../../escape.png")

        // The path is out of the package's own manifest, so it is untrusted: an extension must not
        // be able to name a file it does not ship.
        assertMonogram("example.codebridgeescaping")
    }

    @Test
    fun `exact filenames get the contributed type`() = onEdt {
        install("build-file", "", filename = "CodeBridgeBuildfile")
        val file = file("CodeBridgeBuildfile", "content")
        fixture.openFileInEditor(file)

        assertEquals("CodeBridge.build-file", file.fileType.name)
        assertEquals("", file.fileType.defaultExtension)
    }

    private fun install(
        language: String,
        extension: String,
        alias: String? = null,
        filename: String? = null,
        languageIcon: Boolean = false,
        packageIcon: Boolean = false,
        iconPath: String = "icons/probe.png",
        iconPixels: Int = 32,
    ) {
        val manifest = buildJsonObject {
            put("publisher", "codebridge"); put("name", "probe-$language"); put("version", "1.0.0")
            put("main", "main.js")
            put("engines", buildJsonObject { put("vscode", "^1.90.0") })
            put("extensionDependencies", JsonArray(emptyList()))
            put("activationEvents", buildJsonArray { add(JsonPrimitive("onLanguage:$language")) })
            if (packageIcon) put("icon", iconPath)
            put("contributes", buildJsonObject {
                put("languages", buildJsonArray {
                    add(buildJsonObject {
                        put("id", language)
                        if (extension.isNotEmpty()) put("extensions", buildJsonArray { add(JsonPrimitive(extension)) })
                        if (alias != null) put("aliases", buildJsonArray { add(JsonPrimitive(alias)) })
                        if (filename != null) put("filenames", buildJsonArray { add(JsonPrimitive(filename)) })
                        if (languageIcon) put("icon", buildJsonObject {
                            put("light", iconPath); put("dark", iconPath)
                        })
                    })
                })
            })
        }

        @Suppress("HttpUrlsUsage") // an XML namespace, not a link
        val xml = """<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">""" +
                """<Metadata><Identity Id="probe-$language"/></Metadata></PackageManifest>"""
        val vsix = Files.createTempFile(directory, "probe-", ".vsix")
        val entries = mutableListOf(
            "extension/package.json" to manifest.toString().toByteArray(),
            "extension.vsixmanifest" to xml.toByteArray(),
            "extension/main.js" to "module.exports = {}".toByteArray(),
        )
        // Published at a size that suits a marketplace page, which is the point: it has to be
        // scaled down before it can sit in a file tree row.
        if (languageIcon || packageIcon) entries += "extension/icons/probe.png" to png(iconPixels)
        ZipArchiveOutputStream(vsix).use { zip ->
            for ((name, bytes) in entries) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        ExtensionInstallationService.getInstance().install(vsix) {}
        installedIds.add("codebridge.probe-$language")
    }

    private fun file(name: String, text: String): VirtualFile {
        val path: Path = directory.resolve(name).also { it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun iconOf(name: String): Icon = requireNotNull(requireNotNull(types.forName(name)).icon)

    private fun monogramOf(name: String) = (types.forName(name) as CodeBridgeLanguageFileType).monogram

    /** The file's icon is its drawn monogram, and the monogram is what is returned for further checks. */
    private fun assertMonogram(name: String): CodeBridgeMonogram {
        val monogram = monogramOf(name)
        assertSame(assertNotNull(monogram.current()), iconOf(name), "$name should be drawn its monogram")
        return monogram
    }

    /** The icon's own pixels, so two of them can be compared without caring how they were made. */
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

    /**
     * Paints [icon] onto a canvas far larger than it claims to need, and fails on anything outside.
     *
     * A file tree lays a row out from `getIconWidth`; an icon that paints past that draws over the
     * name next to it rather than being clipped.
     */
    private fun assertPaintsWithinItsBounds(icon: Icon) {
        val canvas = 4 * maxOf(icon.iconWidth, icon.iconHeight, 1)
        val image = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        for (x in 0 until canvas) {
            for (y in 0 until canvas) {
                if (x < icon.iconWidth && y < icon.iconHeight) continue
                assertEquals(
                    0, image.getRGB(x, y) ushr 24,
                    "Painted at ($x, $y), outside the ${icon.iconWidth}x${icon.iconHeight} it reports",
                )
            }
        }
    }

    /** A solid square, the smallest thing the platform will read back as an image. */
    private fun png(size: Int): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { paint = Color.RED; fillRect(0, 0, size, size); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val types get() = CodeBridgeLanguageFileTypes.getInstance()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-file-types") }
}
