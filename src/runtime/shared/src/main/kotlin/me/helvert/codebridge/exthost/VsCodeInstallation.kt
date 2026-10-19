package me.helvert.codebridge.exthost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.exthost.protocol.RpcIdTable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * An installed VS Code, used purely as a source of the extension host and as a Node runtime.
 *
 * We never launch the editor. [executable] is run with `ELECTRON_RUN_AS_NODE=1`, which is the only
 * practical way to satisfy the host's dependencies: most of them live in `node_modules.asar`, and
 * asar support is a patch Electron applies to `fs` that a plain Node has no idea about.
 *
 * VS Code is a **runtime dependency the user provides**, never something we ship. The binary
 * distribution is covered by Microsoft's licence terms, which grant use but no right to
 * redistribute. [resolve] finds the user's own copy, or says precisely why it could not.
 */
data class VsCodeInstallation(
    val executable: Path,
    val appDirectory: Path,
    val kind: Kind = Kind.DESKTOP,
) {

    /** Which shape of distribution this came from. */
    enum class Kind {
        /** An installed editor: an Electron binary beside a `resources/app` holding an asar. */
        DESKTOP,

        /**
         * A remote-extension-host package: `out/` and `node_modules` at the root beside the `node`
         * that runs them. No Electron, no asar, and the half of VS Code we actually use.
         */
        SERVER,
    }

    private val productJson: JsonObject? by lazy { readJson(appDirectory.resolve("product.json")) }
    private val packageJson: JsonObject? by lazy { readJson(appDirectory.resolve("package.json")) }

    val version: String get() = packageJson.string("version") ?: "unknown"
    val commit: String get() = productJson.string("commit") ?: ""
    val nameLong: String get() = productJson.string("nameLong") ?: "Visual Studio Code"
    val uriScheme: String get() = productJson.string("urlProtocol") ?: "vscode"

    val extensionHostProcessJs: Path
        get() = appDirectory.resolve("out/vs/workbench/api/node/extensionHostProcess.js")

    /**
     * The extensions VS Code ships inside itself, one directory each: `configuration-editing`, which
     * says `launch.json` is `jsonc`, and the like. The server package carries only some of them.
     */
    val builtinExtensions: Path get() = appDirectory.resolve("extensions")

    /**
     * The manifests of the built-ins VS Code's workbench loads rather than its host -- `java`, which
     * says `.java` is `java`, and the rest of the languages -- which the build takes from the same
     * VSCodium release's web package, the server package having none. Read, never run.
     */
    val workbenchExtensions: Path get() = appDirectory.resolve("workbench-extensions")

    /** The installation root as the user would recognise it, rather than `resources/app`. */
    val root: Path get() = executable.parent ?: appDirectory

    fun launchSpec(pipeName: String, parentPid: Long): ExtensionHostLaunchSpec =
        ExtensionHostLaunchSpec(
            executable = executable,
            appDirectory = appDirectory,
            pipeName = pipeName,
            parentPid = parentPid,
            runtime = when (kind) {
                Kind.DESKTOP -> ExtensionHostLaunchSpec.Runtime.ELECTRON
                Kind.SERVER -> ExtensionHostLaunchSpec.Runtime.NODE
            },
        )

    /**
     * Files the extension host cannot start without, that are absent.
     *
     * Checked up front because each one fails late and unhelpfully otherwise: a missing
     * `nls.messages.json` aborts startup with a missing-message error before the host ever connects
     * to our pipe, which presents as a connection timeout rather than as a bad installation.
     */
    fun missingFiles(): List<Path> = listOf(
        executable,
        appDirectory.resolve("out/bootstrap-fork.js"),
        appDirectory.resolve("out/nls.messages.json"),
        extensionHostProcessJs,
    ).filterNot { it.exists() }

    /**
     * The rpcId table of *this* build, read from its own bundle.
     *
     * Falls back to the table bundled with the plugin only if extraction fails, since a table from
     * a different build misroutes calls silently rather than failing.
     */
    fun rpcIdTable(): RpcIdTable =
        runCatching { RpcIdTable.extractFrom(extensionHostProcessJs) }
            .getOrElse { RpcIdTable.bundled() }

    private fun readJson(path: Path): JsonObject? = runCatching {
        Json.parseToJsonElement(path.readText()) as? JsonObject
    }.getOrNull()

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    /** What looking for an installation turned out to find. */
    sealed interface Diagnosis {

        /** Ready to run the extension host. */
        data class Usable(val found: VsCodeInstallation) : Diagnosis

        /** There is no runtime to run: none bundled, and nothing pointed at. */
        data object NotFound : Diagnosis

        /** A location was configured, but there is nothing there. */
        data class NoSuchPath(val path: Path) : Diagnosis

        /** Something is there, but it is not a VS Code installation. */
        data class NotAnInstallation(val path: Path) : Diagnosis

        /** A VS Code installation, but one the extension host cannot be started from. */
        data class Incomplete(val path: Path, val missing: List<Path>) : Diagnosis

        val installation: VsCodeInstallation? get() = (this as? Usable)?.found

        /** Plain text, suitable for a settings panel or a notification. */
        val message: String
            get() = when (this) {
                is Usable -> "${found.nameLong} ${found.version} at ${found.root}"
                NotFound -> "This installation of CodeBridge has no extension host. Reinstall the plugin."
                is NoSuchPath -> "There is nothing at $path."
                is NotAnInstallation ->
                    "$path is not a VS Code installation. Choose the folder containing the VS Code executable."

                is Incomplete -> "$path looks like a damaged VS Code installation; missing " +
                        missing.joinToString { it.fileName.toString() } + "."
            }
    }

    companion object {

        /** Points at an installation directory, overriding the bundled runtime. */
        const val OVERRIDE_ENV: String = "CODEBRIDGE_VSCODE_HOME"

        /**
         * Judges a configured location, and never silently replaces it with another.
         *
         * Nothing is searched for. An installation the plugin did not ship is one whose
         * extension-host rpcIds nobody checked, and running it would misroute calls silently, so a
         * configured path that does not work is reported rather than worked around. Where no path is
         * configured the answer is [Diagnosis.NotFound] and the caller falls back to the bundled
         * runtime -- see `VsCodeRuntime`.
         */
        fun resolve(configured: Path? = null): Diagnosis {
            val explicit = configured
                ?: System.getenv(OVERRIDE_ENV)?.takeIf { it.isNotBlank() }?.let(Path::of)
            return explicit?.let(::inspect) ?: Diagnosis.NotFound
        }

        /** Judges one candidate directory, with a reason when it cannot be used. */
        fun inspect(root: Path): Diagnosis {
            if (!root.exists()) return Diagnosis.NoSuchPath(root)
            val installation = from(root) ?: return Diagnosis.NotAnInstallation(root)
            val missing = installation.missingFiles()
            return if (missing.isEmpty()) Diagnosis.Usable(installation)
            else Diagnosis.Incomplete(root, missing)
        }

        /**
         * Builds an installation from [root], or `null` when it does not look like one.
         *
         * Recent Windows builds keep each version in a commit-named directory beside the launcher,
         * so the newest `resources/app` under the install root is the one to use.
         */
        fun from(root: Path): VsCodeInstallation? {
            if (!root.exists()) return null
            serverAt(root)?.let { return it }
            val executable = executableIn(root) ?: return null
            val appDirectory = appDirectoriesIn(root).maxByOrNull { Files.getLastModifiedTime(it) } ?: return null
            return VsCodeInstallation(executable, appDirectory, Kind.DESKTOP)
        }

        /**
         * A remote-extension-host package, recognised by `out/` sitting directly at the root beside
         * the `node` that runs it. Checked before the desktop layout because it is flatter: there
         * is no `resources/app` indirection to look through.
         */
        private fun serverAt(root: Path): VsCodeInstallation? {
            if (!root.resolve("out").resolve("bootstrap-fork.js").exists()) return null
            val node = listOf("node.exe", "node")
                .map { root.resolve(it) }
                .firstOrNull { it.exists() } ?: return null
            return VsCodeInstallation(node, root, Kind.SERVER)
        }

        private fun executableIn(root: Path): Path? =
            listOf("Code.exe", "code", "MacOS/Electron", "code-insiders")
                .map { root.resolve(it) }
                .firstOrNull { it.exists() }

        private fun appDirectoriesIn(root: Path): List<Path> {
            val direct = root.resolve("resources").resolve("app")
            if (direct.resolve("out").resolve("bootstrap-fork.js").exists()) return listOf(direct)

            if (!root.isDirectory()) return emptyList()
            return root.listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.resolve("resources").resolve("app") }
                .filter { it.resolve("out").resolve("bootstrap-fork.js").exists() }
        }
    }
}
