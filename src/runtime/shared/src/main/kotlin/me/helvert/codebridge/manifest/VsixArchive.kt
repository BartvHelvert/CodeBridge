package me.helvert.codebridge.manifest

import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * A VSIX is a zip. Alongside OPC packaging files it carries the extension itself under
 * `extension/`, and installing one means unpacking that directory - there are no install scripts,
 * no lifecycle hooks and nothing to execute.
 */
object VsixArchive {

    const val MANIFEST_ENTRY: String = "extension/package.json"

    private const val CONTENT_PREFIX = "extension/"

    fun readManifest(vsix: Path): VsixManifestParser.Result {
        val text = readEntry(vsix, MANIFEST_ENTRY)
            ?: return VsixManifestParser.Result(
                null,
                listOf("${vsix.fileName} is not a VSIX: it contains no $MANIFEST_ENTRY"),
            )
        return VsixManifestParser.parse(text)
    }

    fun readEntry(vsix: Path, entry: String): String? =
        ZipFile.builder().setPath(vsix).get().use { zip ->
            val found = zip.getEntry(entry) ?: return null
            zip.getInputStream(found).use {
                val bytes = it.readNBytes(4 * 1024 * 1024 + 1)
                require(bytes.size <= 4 * 1024 * 1024) { "$entry exceeds 4 MiB" }
                bytes.toString(Charsets.UTF_8)
            }
        }

    /**
     * Unpacks everything under `extension/` into [target], which becomes the extension root -
     * the directory holding `package.json`, and the value VS Code exposes as `extensionPath`.
     *
     * Entries that would escape [target] are refused. VSIX files are downloaded from a registry
     * over the network, so the archive is untrusted input and a crafted entry name must not be
     * able to write outside the install directory.
     */
    fun unpack(vsix: Path, target: Path): List<Path> = unpack(vsix, target) {}

    fun unpack(vsix: Path, target: Path, checkCanceled: () -> Unit): List<Path> {
        val root = target.toAbsolutePath().normalize()
        Files.createDirectories(root)
        val written = mutableListOf<Path>()
        val destinations = mutableSetOf<Path>()
        var unpackedBytes = 0L

        ZipFile.builder().setPath(vsix).get().use { zip ->
            for (entry in zip.entries) {
                checkCanceled()
                if (!entry.name.startsWith(CONTENT_PREFIX)) continue

                val relative = entry.name.removePrefix(CONTENT_PREFIX).replace('\\', '/')
                if (relative.isEmpty()) continue

                val destination = root.resolve(relative).normalize()
                require(destination.startsWith(root)) {
                    "refusing to unpack ${entry.name}: it resolves outside $root"
                }
                require(!entry.isUnixSymlink) { "Symbolic links are not supported in VSIX archives: ${entry.name}" }
                var ancestor: Path? = destination
                while (ancestor != null && ancestor.startsWith(root)) {
                    require(!Files.isSymbolicLink(ancestor)) { "Refusing to unpack through symbolic link: $ancestor" }
                    ancestor = ancestor.parent
                }

                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                    continue
                }

                require(destinations.add(destination)) { "Duplicate archive entry: ${entry.name}" }
                require(destinations.size <= 100_000) { "VSIX contains too many files" }
                destination.parent?.let { Files.createDirectories(it) }
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            unpackedBytes += count
                            require(unpackedBytes <= 2L * 1024 * 1024 * 1024) { "VSIX expands beyond 2 GiB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (entry.unixMode and 0b001001001 != 0 && Files.getFileStore(destination)
                        .supportsFileAttributeView("posix")
                ) {
                    val permissions = Files.getPosixFilePermissions(destination).toMutableSet()
                    if (entry.unixMode and 64 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
                    if (entry.unixMode and 8 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
                    if (entry.unixMode and 1 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
                    Files.setPosixFilePermissions(destination, permissions)
                }
                written.add(destination)
            }
        }
        return written
    }
}
