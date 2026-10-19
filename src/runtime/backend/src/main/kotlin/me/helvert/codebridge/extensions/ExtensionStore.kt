package me.helvert.codebridge.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.helvert.codebridge.manifest.ManifestStrings
import me.helvert.codebridge.manifest.VsixArchive
import me.helvert.codebridge.manifest.VsixManifest
import me.helvert.codebridge.manifest.VsixManifestParser
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.*
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class InstalledExtension(
    val manifest: VsixManifest,
    val directory: Path,
    val targetPlatform: String?,
    val installedAt: String,
    /** A disabled extension stays installed but is not loaded into an extension host. */
    val enabled: Boolean = true,
    /**
     * The IntelliJ plugin this extension comes with ([ExtensionSource]), or null for one the user
     * installed. Such an extension is installed and removed with that plugin, never on its own.
     */
    val providedBy: String? = null,
) {
    val id: String get() = manifest.id.lowercase(Locale.ROOT)

    /**
     * The extension's own icon file, or null when it has none or points somewhere it should not.
     *
     * `icon` is a path out of the package's own manifest, so it is untrusted: resolve it and refuse
     * anything that does not stay inside the extension directory.
     */
    val iconFile: Path?
        get() {
            val relative = manifest.icon?.takeIf { it.isNotBlank() } ?: return null
            val resolved = runCatching { directory.resolve(relative).normalize() }.getOrNull() ?: return null
            if (!resolved.startsWith(directory)) return null
            return resolved.takeIf { Files.isRegularFile(it) }
        }
    fun missingDependencies(installedIds: Set<String>): List<String> =
        manifest.extensionDependencies.filter { it.lowercase(Locale.ROOT) !in installedIds }
}

data class ExtensionInstallResult(
    val extension: InstalledExtension,
    val previousVersion: String?,
    val problems: List<String>,
)

/**
 * One active version per extension. Package directories are immutable generations; an atomic
 * registry replacement publishes an install only after extraction and validation succeed.
 * Old generations remain available to running hosts and can be collected once host ownership exists.
 */
class ExtensionStore(root: Path, private val targetPlatform: String = currentTargetPlatform()) {
    private val root = root.toAbsolutePath().normalize()
    private val packages = this.root.resolve("packages")
    private val registry = this.root.resolve("extensions.json")
    private val json = Json { prettyPrint = true }

    @Serializable
    // `enabled` defaults to true so a registry written before it existed still reads back.
    private data class Entry(val id: String, val directory: String, val targetPlatform: String?,
                             val installedAt: String, val enabled: Boolean = true)

    /**
     * The installed extensions, parsed again only once the registry has changed.
     *
     * Hot: decorations resolve a theme colour per range, and every colour asks this. Parsed afresh
     * each time, that was GitLens' 1MB `package.json` per line of its blame column, a second for a
     * short file. The registry says everything a parse depends on -- package directories are never
     * changed once installed, and each install gets a new one -- and is replaced atomically, so its
     * text can be read without the lock to see whether what was parsed still holds.
     */
    @Synchronized
    fun list(): List<InstalledExtension> {
        val current = registryText()
        listed?.takeIf { it.first == current }?.let { return it.second }
        return locked {
            val text = registryText()
            val extensions = readEntries(text).map(::installed).sortedBy { it.id }
            listed = text to extensions
            extensions
        }
    }

    /** The registry's text the last [list] was parsed from, and what it parsed to. */
    @Volatile
    private var listed: Pair<String?, List<InstalledExtension>>? = null

    private fun registryText(): String? = try {
        Files.readString(registry)
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    @Synchronized
    fun install(vsix: Path, checkCanceled: () -> Unit = {}): ExtensionInstallResult = locked {
        checkCanceled()
        val parsed = VsixArchive.readManifest(vsix)
        val manifest = parsed.manifest ?: throw IllegalArgumentException(parsed.problems.joinToString("\n"))
        validateIdentity(manifest)
        val platform = readTargetPlatform(vsix)
        require(platform == null || platform == "universal" || platform == targetPlatform) {
            "This VSIX targets $platform; this machine requires $targetPlatform or a universal package."
        }
        val entries = readEntries()
        val id = manifest.id.lowercase(Locale.ROOT)
        val existing = entries.firstOrNull { it.id == id }
        val previous = existing?.let(::installed)
        // Reinstalling an extension the user had disabled must not quietly re-enable it.
        val entry = Entry(id, UUID.randomUUID().toString(), platform, Instant.now().toString(),
                          enabled = existing?.enabled ?: true)
        val destination = packageDirectory(entry.directory)
        var committed = false
        try {
            Files.createDirectories(destination)
            VsixArchive.unpack(vsix, destination, checkCanceled)
            val extracted = installed(entry)
            // As written, before `package.nls.json` fills in its strings.
            val written = VsixManifestParser.parse(Files.readString(destination.resolve("package.json"))).manifest
            require(written?.raw == manifest.raw) { "The extracted manifest differs from the archive manifest" }
            checkCanceled()
            writeEntries(entries.filterNot { it.id == id } + entry)
            committed = true
            ExtensionInstallResult(extracted, previous?.manifest?.version, parsed.problems)
        } finally {
            if (!committed) deleteGeneration(destination)
        }
    }

    /**
     * Removes an installed extension. Returns false when it was not installed.
     *
     * The registry is rewritten before the files go, so a failure part-way leaves an orphaned
     * directory rather than a registry entry pointing at something half-deleted.
     */
    @Synchronized
    fun uninstall(id: String): Boolean = locked {
        val entries = readEntries()
        val entry = entries.firstOrNull { it.id == id.lowercase(Locale.ROOT) } ?: return@locked false
        writeEntries(entries.filterNot { it.id == entry.id })
        deleteGeneration(packageDirectory(entry.directory))
        true
    }

    /**
     * Enables or disables an installed extension. Returns false when it was not installed.
     *
     * Only the registry changes: the package stays on disk, so re-enabling costs nothing and a
     * disabled extension can still be inspected.
     */
    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean = locked {
        val entries = readEntries()
        val entry = entries.firstOrNull { it.id == id.lowercase(Locale.ROOT) } ?: return@locked false
        if (entry.enabled == enabled) return@locked true
        writeEntries(entries.map { if (it.id == entry.id) it.copy(enabled = enabled) else it })
        true
    }

    private fun installed(entry: Entry): InstalledExtension {
        val directory = packageDirectory(entry.directory)
        val parsed = VsixManifestParser.parse(
            Files.readString(directory.resolve("package.json")),
            ManifestStrings.nls(directory)
        )
        val manifest = parsed.manifest ?: error("Cannot read installed extension ${entry.id}: ${parsed.problems.joinToString()}")
        validateIdentity(manifest)
        require(manifest.id.lowercase(Locale.ROOT) == entry.id) { "Installed extension identity does not match registry: ${entry.id}" }
        return InstalledExtension(manifest, directory, entry.targetPlatform, entry.installedAt, entry.enabled)
    }

    private fun readEntries(text: String? = registryText()): List<Entry> {
        if (text == null) return emptyList()
        val entries = json.decodeFromString<List<Entry>>(text)
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate extensions in registry" }
        return entries
    }

    private fun writeEntries(entries: List<Entry>) {
        val temporary = Files.createTempFile(root, "registry-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(entries.sortedBy { it.id }))
            // A failed atomic replacement preserves the previous registry; do not fall back to truncating it.
            Files.move(temporary, registry, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun <T> locked(operation: () -> T): T {
        Files.createDirectories(packages)
        return FileChannel.open(root.resolve("install.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use { operation() }
        }
    }

    private fun packageDirectory(name: String): Path {
        require(UUID.fromString(name).toString() == name) { "Invalid package directory in registry" }
        return packages.resolve(name)
    }

    private fun deleteGeneration(directory: Path) {
        require(directory.toAbsolutePath().normalize().parent == packages) { "Cleanup target outside extension packages" }
        if (Files.exists(directory)) Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun validateIdentity(manifest: VsixManifest) {
        require(IDENTIFIER.matches(manifest.publisher) && IDENTIFIER.matches(manifest.name)) {
            "Extension publisher and name must contain only letters, digits, underscores or hyphens"
        }
        require(VERSION.matches(manifest.version)) { "Invalid extension version: ${manifest.version}" }
    }

    @Suppress("HttpUrlsUsage") // XML feature names, not links
    private fun readTargetPlatform(vsix: Path): String? {
        val xml = VsixArchive.readEntry(vsix, "extension.vsixmanifest") ?: return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val identities = document.getElementsByTagNameNS("*", "Identity")
        val identity = identities.item(0) as? org.w3c.dom.Element ?: return null
        return identity.getAttribute("TargetPlatform").takeIf { it.isNotBlank() }
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
        private val VERSION = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?")

        fun currentTargetPlatform(): String {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val system = when {
                os.startsWith("windows") -> "win32"
                os.startsWith("mac") || os.startsWith("darwin") -> "darwin"
                os.startsWith("linux") -> if (Files.exists(Path.of("/etc/alpine-release"))) "alpine" else "linux"
                else -> "unknown"
            }
            val arch = when (val value = System.getProperty("os.arch").lowercase(Locale.ROOT)) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                "x86", "i386", "i686" -> "ia32"
                "arm", "armv7l" -> "armhf"
                else -> value
            }
            return "$system-$arch"
        }
    }
}
