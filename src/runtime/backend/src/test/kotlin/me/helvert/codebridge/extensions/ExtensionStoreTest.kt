package me.helvert.codebridge.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.*

class ExtensionStoreTest {
    @TempDir
    lateinit var temporary: Path

    private val root get() = temporary.resolve("installed")
    private fun store() = ExtensionStore(root, "win32-x64")

    private fun vsix(
        name: String = "demo", version: String = "1.0.0", publisher: String = "acme",
        dependencies: List<String> = emptyList(), platform: String? = null,
        extras: List<Pair<String, String>> = listOf("extension/main.js" to "module.exports = {}"),
        manifestXml: String? = null, icon: String? = null,
    ): Path {
        val manifest = buildJsonObject {
            put("publisher", publisher); put("name", name); put("version", version)
            put("main", "main.js")
            if (icon != null) put("icon", icon)
            put("engines", buildJsonObject { put("vscode", "^1.90.0") })
            put("extensionDependencies", JsonArray(dependencies.map(::JsonPrimitive)))
        }

        @Suppress("HttpUrlsUsage") // an XML namespace, not a link
        val xml = manifestXml
            ?: """<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011"><Metadata><Identity Id="$name" ${platform?.let { "TargetPlatform=\"$it\"" } ?: ""}/></Metadata></PackageManifest>"""
        return archive(
            listOf(
                "extension/package.json" to manifest.toString(),
                "extension.vsixmanifest" to xml
            ) + extras
        )
    }

    private fun archive(entries: List<Pair<String, String>>): Path {
        val path = Files.createTempFile(temporary, "extension-", ".vsix")
        ZipArchiveOutputStream(path).use { zip ->
            for ((name, text) in entries) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(text.toByteArray())
                zip.closeArchiveEntry()
            }
        }
        return path
    }

    @Test
    fun `install persists manifest and contents across store instances`() {
        val result = store().install(vsix())
        assertNull(result.previousVersion)
        assertEquals("module.exports = {}", Files.readString(result.extension.directory.resolve("main.js")))
        val restored = store().list().single()
        assertEquals("acme.demo", restored.id)
        assertEquals("1.0.0", restored.manifest.version)
        assertEquals("^1.90.0", restored.manifest.engineRange)
        assertEquals(result.extension.directory, restored.directory)
        assertFalse(Files.exists(restored.directory.resolve("extension.vsixmanifest")))
    }

    @Test
    fun `replacement publishes clean generation while keeping old files available`() {
        val initial = store().install(vsix(extras = listOf("extension/old.js" to "old")))
        val update = store().install(vsix(version = "2.0.0", extras = listOf("extension/new.js" to "new")))
        assertEquals("1.0.0", update.previousVersion)
        assertEquals("2.0.0", store().list().single().manifest.version)
        assertNotEquals(initial.extension.directory, update.extension.directory)
        assertFalse(Files.exists(update.extension.directory.resolve("old.js")))
        assertEquals("old", Files.readString(initial.extension.directory.resolve("old.js")))
    }

    @Test
    fun `reinstall and identifier case changes do not duplicate extensions`() {
        store().install(vsix(publisher = "Acme"))
        val result = store().install(vsix())
        assertEquals("1.0.0", result.previousVersion)
        assertEquals(1, store().list().size)
    }

    @Test
    fun `disabling is persisted and survives a reinstall`() {
        store().install(vsix())
        assertTrue(store().list().single().enabled, "a freshly installed extension is enabled")

        assertTrue(store().setEnabled("acme.demo", false))
        assertFalse(store().list().single().enabled, "disabling must outlive the store instance")

        // Upgrading is not a decision to switch the extension back on.
        store().install(vsix(version = "2.0.0"))
        val after = store().list().single()
        assertEquals("2.0.0", after.manifest.version)
        assertFalse(after.enabled, "a reinstall must not quietly re-enable a disabled extension")

        assertTrue(store().setEnabled("ACME.DEMO", true), "the id is matched case-insensitively")
        assertTrue(store().list().single().enabled)
    }

    @Test
    fun `listing again parses nothing new until the registry changes`() {
        // Hot: every theme colour a decoration resolves lists the extensions.
        val store = store()
        store.install(vsix())
        val first = store.list()
        assertSame(first, store.list(), "Unchanged, the parsed list is kept")

        assertTrue(store.setEnabled("acme.demo", false))
        assertEquals(listOf(false), store.list().map { it.enabled })
        // Another store over the same directory -- another IDE -- changing it is seen too.
        store().setEnabled("acme.demo", true)
        assertEquals(listOf(true), store.list().map { it.enabled })
        store().uninstall("acme.demo")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `enabling an extension that is not installed reports it`() {
        assertFalse(store().setEnabled("acme.absent", false))
    }

    @Test
    fun `an extension's own icon is found inside its directory`() {
        val installed = store().install(
            vsix(
                icon = "images/logo.png",
                extras = listOf("extension/main.js" to "{}", "extension/images/logo.png" to "not really a png")
            ),
        ).extension
        assertEquals(installed.directory.resolve("images").resolve("logo.png"), installed.iconFile)
    }

    @Test
    fun `an extension without an icon has none`() {
        assertNull(store().install(vsix()).extension.iconFile)
    }

    @Test
    fun `an icon pointing outside the extension is refused`() {
        // The manifest ships inside the package, so its paths are the publisher's input, not ours.
        val installed = store().install(vsix(icon = "../../../../secrets.png")).extension
        Files.createDirectories(installed.directory.parent)
        assertNull(installed.iconFile, "an icon path must not escape the extension directory")
    }

    @Test
    fun `an icon naming a file that is not there has none`() {
        assertNull(store().install(vsix(icon = "missing.png")).extension.iconFile)
    }

    @Test
    fun `failed extraction preserves previous install and removes incomplete generation`() {
        val installed = store().install(vsix()).extension
        val registry = Files.readString(root.resolve("extensions.json"))
        assertFailsWith<IllegalArgumentException> {
            store().install(vsix(version = "2.0.0", extras = listOf("extension/../../escaped.js" to "bad")))
        }
        assertEquals(registry, Files.readString(root.resolve("extensions.json")))
        assertEquals(installed.directory, store().list().single().directory)
        Files.list(root.resolve("packages")).use { assertEquals(1L, it.count()) }
        assertFalse(Files.exists(root.resolve("escaped.js")))
    }

    @Test
    fun `canceling extraction leaves no published or partial install`() {
        var checks = 0
        assertFailsWith<CancellationException> {
            store().install(vsix()) { if (++checks == 3) throw CancellationException("canceled") }
        }
        assertTrue(store().list().isEmpty())
        Files.list(root.resolve("packages")).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `missing manifest is rejected`() {
        assertFailsWith<IllegalArgumentException> { store().install(archive(listOf("readme.txt" to "not an extension"))) }
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `invalid identity and version are rejected`() {
        assertFailsWith<IllegalArgumentException> { store().install(vsix(publisher = "../../escape")) }
        assertFailsWith<IllegalArgumentException> { store().install(vsix(version = "not-a-version")) }
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `wrong platform is rejected and matching platform is persisted`() {
        assertFailsWith<IllegalArgumentException> { store().install(vsix(platform = "darwin-arm64")) }
        assertTrue(store().list().isEmpty())
        store().install(vsix(platform = "win32-x64"))
        assertEquals("win32-x64", store().list().single().targetPlatform)
    }

    @Test
    fun `universal platform is accepted`() {
        assertEquals("universal", store().install(vsix(platform = "universal")).extension.targetPlatform)
    }

    @Test
    fun `XML external entities are refused`() {
        val secret = temporary.resolve("secret.txt")
        Files.writeString(secret, "not for the extension")
        val xml =
            """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "${secret.toUri()}">]><PackageManifest><Metadata><Identity Id="&xxe;"/></Metadata></PackageManifest>"""
        assertFails { store().install(vsix(manifestXml = xml)) }
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `dependency status changes after dependency is installed`() {
        store().install(vsix(dependencies = listOf("Acme.Dependency")))
        var installed = store().list()
        assertEquals(listOf("Acme.Dependency"), installed.single().missingDependencies(installed.map { it.id }.toSet()))
        store().install(vsix(name = "dependency"))
        installed = store().list()
        assertTrue(
            installed.first { it.id == "acme.demo" }.missingDependencies(installed.map { it.id }.toSet()).isEmpty()
        )
    }

    @Test
    fun `duplicate normalized archive paths are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            store().install(vsix(extras = listOf("extension/main.js" to "one", "extension/./main.js" to "two")))
        }
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `corrupt registry is reported without overwriting it`() {
        store().install(vsix())
        Files.writeString(root.resolve("extensions.json"), "broken")
        assertFails { store().list() }
        assertFails { store().install(vsix(name = "another")) }
        assertEquals("broken", Files.readString(root.resolve("extensions.json")))
    }

    @Test
    fun `real codelldb VSIX installs without activation or network`() {
        val path = Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", "codelldb-1.12.3.vsix")
        assertTrue(Files.exists(path), "codelldb fixture not present at $path")
        val installed = store().install(path).extension
        assertEquals("vadimcn.vscode-lldb", installed.id)
        assertEquals("1.12.3", installed.manifest.version)
        assertTrue(Files.exists(installed.directory.resolve(assertNotNull(installed.manifest.main))))
    }
}
