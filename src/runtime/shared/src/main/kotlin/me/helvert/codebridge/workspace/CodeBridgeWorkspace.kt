package me.helvert.codebridge.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** One root folder of a workspace: its [name], as `${workspaceFolder:name}` names it, and [index]. */
data class WorkspaceFolder(val name: String, val path: Path, val index: Int)

/**
 * The folders the extensions see as the workspace, as VS Code opens them.
 *
 * Either the project's own directory, VS Code's single-folder workspace; or the folders of the
 * `.code-workspace` [file] in it chosen for the project, VS Code's multi-root workspace. There, the file's `settings`,
 * `launch` and `tasks` are the workspace's own, and each folder's `.vscode/` adds its own beneath
 * them.
 */
class CodeBridgeWorkspace private constructor(
    /** The project's directory. */
    val base: Path,
    /** The `.code-workspace` file; null for a single folder. */
    val file: Path?,
    /** At least one; the project's directory alone when [file] is null. */
    val folders: List<WorkspaceFolder>,
) {
    val isMultiRoot: Boolean get() = file != null

    /** The workspace's name, as VS Code shows it: `name (Workspace)` for a file `name.code-workspace`. */
    val name: String get() = file?.let { "${it.nameWithoutExtension} (Workspace)" } ?: folders.first().name

    /** The folder [path] is in, the innermost when folders nest; null for a path in none. */
    fun folderOf(path: Path): WorkspaceFolder? {
        val normalized = path.toAbsolutePath().normalize()
        return folders.filter { normalized.startsWith(it.path) }.maxByOrNull { it.path.nameCount }
    }

    /** The `.vscode/` [name] of [folder] -- `settings.json`, `launch.json` or `tasks.json`. */
    fun folderFile(folder: WorkspaceFolder, name: String): Path = folder.path.resolve(".vscode").resolve(name)

    /**
     * The [section] of [file] -- `settings`, `launch` or `tasks` -- or null with no file, no such
     * section, or a file that does not parse now.
     */
    fun section(section: String): JsonObject? = file?.let { runCatching { read(it) }.getOrNull()?.get(section) as? JsonObject }

    override fun equals(other: Any?): Boolean = other is CodeBridgeWorkspace && other.base == base && other.file == file && other.folders == folders
    override fun hashCode(): Int = listOf(base, file, folders).hashCode()
    override fun toString(): String = "CodeBridgeWorkspace(${file ?: base}, ${folders.map { it.path }})"

    companion object {
        const val EXTENSION = "code-workspace"

        /** The project directory [base] as a single-folder workspace. */
        fun single(base: Path): CodeBridgeWorkspace {
            val root = base.toAbsolutePath().normalize()
            return CodeBridgeWorkspace(root, null, listOf(WorkspaceFolder(root.fileName?.toString() ?: root.toString(), root, 0)))
        }

        /**
         * The workspace of the project in [base]: the `.code-workspace` file chosen for it, else the
         * directory alone. As in VS Code, a workspace file is used only when chosen -- see
         * [WorkspaceChoices] -- never just because it is there.
         *
         * [problem] hears why a chosen file is not used: it is gone, does not parse, or names no
         * folder that exists. Where VS Code's own files do not apply ([VsCodeFiles]), none is.
         */
        fun of(base: Path, problem: (String) -> Unit = {}): CodeBridgeWorkspace {
            val root = base.toAbsolutePath().normalize()
            if (!VsCodeFiles.apply()) return single(root)
            val name = (WorkspaceChoices.of(root) as? WorkspaceChoices.Choice.File)?.name ?: return single(root)
            val file = root.resolve(name)
            if (!Files.isRegularFile(file)) {
                problem("$name, the VS Code workspace file chosen for this project, is gone, so the project directory is the workspace")
                return single(root)
            }
            val content = try {
                read(file)
            } catch (error: Exception) {
                problem("$name is not a workspace file CodeBridge can read, so the project directory is the workspace: ${error.message}")
                return single(root)
            }
            val folders = folders(file, content)
            if (folders.isEmpty()) {
                problem("$name names no folder that exists, so the project directory is the workspace")
                return single(root)
            }
            return CodeBridgeWorkspace(root, file, folders)
        }

        /** The `.code-workspace` files directly in [base], to choose from, by name. */
        fun candidates(base: Path): List<Path> = try {
            base.listDirectoryEntries("*.$EXTENSION").filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }
        } catch (_: Exception) { emptyList() }

        /** Whether [path] is a workspace file, by its name. */
        fun isWorkspaceFile(path: Path): Boolean = path.extension == EXTENSION

        /** The file as JSON; its comments and trailing commas are allowed, as in VS Code. */
        fun read(file: Path): JsonObject {
            val text = Jsonc.stripToJson(Files.readString(file))
            if (text.isBlank()) return JsonObject(emptyMap())
            return Json.parseToJsonElement(text) as? JsonObject ?: error("not a JSON object")
        }

        /**
         * The `folders` of [content], each `{"path": ...}` -- relative to [file]'s directory, or
         * absolute -- or `{"uri": "file:..."}`, with an optional `name`. Folders that do not exist,
         * and the same folder twice, are left out. A name defaults to the folder's own, as in VS Code.
         */
        internal fun folders(file: Path, content: JsonObject): List<WorkspaceFolder> {
            val directory = file.toAbsolutePath().normalize().parent
            val entries = content["folders"] as? JsonArray ?: return emptyList()
            val seen = HashSet<Path>()
            val folders = mutableListOf<WorkspaceFolder>()
            for (entry in entries) {
                val obj = entry as? JsonObject ?: continue
                val path = try {
                    val relative = (obj["path"] as? JsonPrimitive)?.contentOrNull
                    val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull
                    when {
                        relative != null -> directory.resolve(relative)
                        uri != null && uri.startsWith("file:") -> Path.of(URI(uri))
                        else -> null
                    }
                } catch (_: InvalidPathException) { null } catch (_: IllegalArgumentException) { null }
                    ?.toAbsolutePath()?.normalize() ?: continue
                if (!path.isDirectory() || !seen.add(path)) continue
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: path.fileName?.toString() ?: path.toString()
                folders += WorkspaceFolder(name, path, folders.size)
            }
            return folders
        }
    }
}
