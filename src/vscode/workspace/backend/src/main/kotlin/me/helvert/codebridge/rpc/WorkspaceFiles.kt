package me.helvert.codebridge.rpc

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.workspace.JsoncEdit
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Files
import java.nio.file.Path

/**
 * The workspace's `launch.json`, `tasks.json` and `settings.json`, made as VS Code makes them when
 * there are none yet.
 *
 * In a single-folder workspace they are the project's `.vscode/` files. In a multi-root one there
 * is one of each per folder, and the `.code-workspace` file's `launch`, `tasks` and `settings` are
 * the workspace's own; a target names which, a folder by its name or null for the file.
 *
 * A new `launch.json` starts from a debugger's `initialConfigurations`, as VS Code's "create a
 * launch.json file" does with a debugger that declares them; otherwise it starts empty, for the
 * debuggers' templates and completions to fill. A debugger that builds its first configurations in
 * code is not asked.
 */
internal object WorkspaceFiles {

    const val LAUNCH = "launch.json"
    const val TASKS = "tasks.json"
    const val SETTINGS = "settings.json"
    private val NAMES = setOf(LAUNCH, TASKS, SETTINGS)

    private val pretty = Json { prettyPrint = true }

    /** What a file name is opened as: a file, and where in it its content starts. */
    data class Opened(val file: VirtualFile, val created: Boolean, val offset: Int? = null)

    /** The folders a file can be opened for, by name, in a multi-root workspace; null in a single-folder one. */
    fun targets(base: Path): List<String>? = CodeBridgeWorkspace.of(base).takeIf { it.isMultiRoot }?.folders?.map { it.name }

    /** Whether [target]'s [name] has content already. */
    fun exists(base: Path, name: String, target: String?): Boolean {
        val layout = CodeBridgeWorkspace.of(base)
        val file = layout.file
        return when {
            target != null -> folderFile(layout, name, target)?.let(Files::exists) == true
            file != null -> layout.section(section(name)) != null
            else -> Files.exists(base.resolve(".vscode").resolve(name))
        }
    }

    /** `type` and label of each enabled debugger with `initialConfigurations` to start from. */
    fun launchStarts(): List<Pair<String, String>> =
        ExtensionInstallationService.getInstance().list().asSequence().filter { it.enabled }
            .flatMap { it.manifest.contributes.debuggers }
            .filter { (it.initialConfigurations as? JsonArray)?.isNotEmpty() == true }
            .distinctBy { it.type }
            .map { it.type to (it.label ?: it.type) }
            .toList()

    /** [target]'s [name], made first if there is none; null for a name that is none of the three, or an unknown folder. */
    fun open(base: Path, name: String, debugger: String?, target: String? = null): Opened? {
        val prepared = prepare(base, name, debugger, target) ?: return null
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(prepared.path) ?: return null
        return Opened(file, prepared.created, prepared.offset)
    }

    /** [Opened], before the IDE is told of the file. */
    data class Prepared(val path: Path, val created: Boolean, val offset: Int? = null)

    /** [open], on disk alone. */
    fun prepare(base: Path, name: String, debugger: String?, target: String? = null): Prepared? {
        if (name !in NAMES) return null
        val layout = CodeBridgeWorkspace.of(base)
        val workspaceFile = layout.file
        if (target == null && workspaceFile != null) return prepareSection(workspaceFile, name, debugger)
        val path = if (target != null) folderFile(layout, name, target) ?: return null else base.resolve(".vscode").resolve(name)
        val created = !Files.exists(path)
        if (created) {
            Files.createDirectories(path.parent)
            // An empty object with room to type in, as VS Code makes one.
            val text = if (name == SETTINGS) "{\n}" else pretty.encodeToString(JsonObject.serializer(), initial(name, debugger))
            Files.writeString(path, text + "\n")
        }
        return Prepared(path, created)
    }

    /** The `.code-workspace` file, with [name]'s section added if it has none, at that section. */
    private fun prepareSection(workspaceFile: Path, name: String, debugger: String?): Prepared {
        val key = section(name)
        val before = Files.readString(workspaceFile)
        val missing = runCatching { JsoncEdit.parse(before)[key] == null }.getOrDefault(false)
        val text = if (!missing) before else JsoncEdit.set(before, listOf(key), initial(name, debugger)).also { after ->
            Files.writeString(workspaceFile, after)
        }
        return Prepared(workspaceFile, missing, text.indexOf("\"$key\"").takeIf { it >= 0 })
    }

    private fun folderFile(layout: CodeBridgeWorkspace, name: String, target: String): Path? =
        layout.folders.firstOrNull { it.name == target }?.let { layout.folderFile(it, name) }

    /** The `.code-workspace` key that holds what [name] holds in a folder. */
    private fun section(name: String): String = name.removeSuffix(".json")

    private fun initial(name: String, debugger: String?): JsonObject = when (name) {
        LAUNCH -> {
            val configurations = debugger?.let { type ->
                ExtensionInstallationService.getInstance().list().filter { it.enabled }
                    .flatMap { it.manifest.contributes.debuggers }
                    .firstOrNull { it.type == type }?.initialConfigurations as? JsonArray
            }
            buildJsonObject {
                put("version", "0.2.0")
                put("configurations", configurations ?: JsonArray(emptyList()))
            }
        }
        TASKS -> buildJsonObject {
            put("version", "2.0.0")
            putJsonArray("tasks") {}
        }
        else -> buildJsonObject { }
    }
}
