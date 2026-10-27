import java.io.File

/**
 * The extension host the plugin runs on: VSCodium's remote-extension-host package, pinned.
 *
 * Changing the version is a deliberate, tested upgrade -- extension-host rpcIds drift between builds
 * and a mismatch misroutes calls silently -- and changing it means replacing every hash below with
 * the `.sha256` published beside each asset. The hashes are pinned here rather than fetched with the
 * archive, so a replaced release asset fails the build instead of shipping.
 */
object VsCodium {
    const val VERSION = "1.143.17183"

    val packages = mapOf(
        "win32-x64" to "a21426e7d2f914c601264c5fb896dda36fed24bd3e363b0f1ed51f532a331216",
        "linux-x64" to "34dbad673e150f9a5bc6d1190754afcbc4bc173b71922300452eaef3f5cda6ed",
        "linux-arm64" to "b2c53fa9c515505a85d705a006338c756f6028bcc579134e95d19966e7125080",
        "darwin-x64" to "9ae7cd592c3b9ce2215e2c7d2b19c1164be31425149d27e703278d4301333036",
        "darwin-arm64" to "9676699d1ad3ff731a59e0c8645a4f7ac56c9f2a752629e77be96ce8b70500fb",
    )

    /**
     * The same VSCodium build's web server package. It carries what the REH package leaves out,
     * having no editor of its own: the built-in extensions VS Code's workbench loads rather than its
     * extension host -- the ones that name languages, `java` for `.java` among them -- and the
     * workbench itself, which registers the theme colors. Identical on every platform, so one
     * platform's package serves all.
     */
    val webPackage = "linux-x64" to "3ac6b8d71b147f0c55d1a230c6654883b401041018b44e9defa18a71c946f73e"

    val webAsset = "vscodium-reh-web-${webPackage.first}-$VERSION.tar.gz"

    fun url(asset: String) = "https://github.com/VSCodium/vscodium/releases/download/$VERSION/$asset"

    /**
     * Where downloads go: the cache every checkout shares (see the root build script), not `build`,
     * so neither `clean` nor a new worktree costs five 113 MB fetches. Version-qualified, so a bumped
     * pin cannot be confused with the previous one; the checksum keeps a checkout from using
     * another's half-written download.
     */
    fun downloads(sharedCacheDirectory: File) = sharedCacheDirectory.resolve("vscodium/$VERSION")
}
