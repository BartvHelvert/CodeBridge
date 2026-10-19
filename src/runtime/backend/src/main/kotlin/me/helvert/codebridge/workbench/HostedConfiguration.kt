package me.helvert.codebridge.workbench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHostInitData
import me.helvert.codebridge.exthost.MainThreadException
import me.helvert.codebridge.exthost.protocol.payload.Configuration
import me.helvert.codebridge.workspace.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Reload saved user and workspace settings without restarting extensions. Invalid edits retain the
 * last snapshot of that file.
 *
 * The layers are VS Code's. In a single-folder workspace the workspace's settings are
 * `.vscode/settings.json`. In a multi-root one ([layout] has a `.code-workspace` file) they are that
 * file's `settings`, and each folder's `.vscode/settings.json` is a layer of its own beneath them,
 * for the resources in that folder.
 */
class HostedConfiguration(
    private val workspace: Path,
    private val defaults: JsonObject,
    private val notify: (String, String, String) -> Unit,
    private val report: (Throwable) -> Unit,
    /** Every key an extension declares; VS Code refuses to write any other. */
    private val registered: Set<String> = defaults.keys,
    /** Told about each change this makes to a settings file, so the user can be told too. */
    private val changed: (String) -> Unit = {},
    /**
     * The user's own `settings.json`, shared by every project; null where there is none.
     *
     * VS Code's user layer: below the workspace's settings, above the extensions' defaults.
     */
    private val userFile: Path? = null,
    /** Told after this writes [userFile], which other projects read too. */
    private val userWritten: () -> Unit = {},
    /**
     * Keys the extensions scope to the application or the machine. VS Code reads those from user
     * settings only: in the workspace's file they are ignored, and writing one there is refused.
     */
    private val userOnly: Set<String> = emptySet(),
    /** Where a write reads the file from and puts it; an editor holding the file, in the IDE. */
    private val files: SettingsFiles = SettingsFiles.DISK,
    /** The workspace's folders, and its `.code-workspace` file if it has one; see [relayout]. */
    initialLayout: CodeBridgeWorkspace = CodeBridgeWorkspace.single(workspace),
    /**
     * Keys a folder's own settings may set in a multi-root workspace: those the extensions scope to a
     * resource, a language or a machine. VS Code ignores the rest there, a window's settings among
     * them, and refuses to write them; null lets a folder set anything.
     */
    private val folderScoped: Set<String>? = null,
    /**
     * Whether the project's own settings files are read and written at all ([VsCodeFiles]). Without,
     * an extension sees what it would in a VS Code window with no folder open: defaults and user
     * settings, and a write with no target goes to the user's.
     */
    private val vsCodeFiles: Boolean = VsCodeFiles.apply(),
) {
    /**
     * How a write reaches a settings file.
     *
     * In the IDE a file open in an editor is edited through that editor, so the editor shows the
     * change at once, can undo it, and never finds the file changed under it.
     */
    interface SettingsFiles {
        /** The file's text as a write should edit it; null when there is no such file. */
        fun read(file: Path): String?

        /** Whether the file has changes an editor has not saved, which a write would overwrite or save. */
        fun dirty(file: Path): Boolean

        /** Replaces [before] with [after] in [file], saved, by the time it returns. */
        suspend fun write(file: Path, before: String, after: String)

        companion object {
            val DISK: SettingsFiles = object : SettingsFiles {
                override fun read(file: Path): String? = if (Files.exists(file)) Files.readString(file) else null
                override fun dirty(file: Path): Boolean = false
                override suspend fun write(file: Path, before: String, after: String) {
                    withContext(Dispatchers.IO) {
                        Files.createDirectories(file.parent)
                        val partial = Files.createTempFile(file.parent, "settings", ".tmp")
                        Files.writeString(partial, after)
                        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    }
                }
            }
        }
    }

    @Volatile
    private var layout: CodeBridgeWorkspace = initialLayout

    /**
     * The workspace's own settings, `.vscode/settings.json` or the `.code-workspace` file's
     * `settings`, without the keys [userOnly] leaves out.
     */
    @Volatile
    var settings: JsonObject = workspaceSettings(readWorkspace())
        private set

    /** Each folder's own settings, in a multi-root workspace; none in a single folder's. */
    @Volatile
    var folderSettings: Map<WorkspaceFolder, JsonObject> =
        if (layout.isMultiRoot) layout.folders.associateWith {
            folderLayer(
                read(
                    layout.folderFile(
                        it,
                        SETTINGS
                    )
                )
            )
        } else emptyMap()
        private set

    /** The user's settings, from [userFile]. A typo there is reported, not a reason no project can start. */
    @Volatile
    var user: JsonObject = userFile?.let { file ->
        try {
            read(file)
        } catch (error: Exception) {
            report(error); null
        }
    }
        ?: buildJsonObject {}
        private set

    /**
     * What a setting is, defaults, user and workspace together, for the settings the workbench reads
     * itself (`files.exclude`, `files.autoSave`). An object setting merges with the one beneath it, as
     * in VS Code, so `files.exclude` keeps its default patterns unless one is set to false.
     * Extensions get the layers apart and merge them in the host.
     */
    @Volatile
    var effective: JsonObject = overlay(overlay(defaults, user), settings)
        private set

    /** [effective] for the resources in [folder], with that folder's own settings over it. */
    fun effectiveFor(folder: WorkspaceFolder?): JsonObject =
        folder?.let(folderSettings::get)?.let { overlay(effective, it) } ?: effective

    @Synchronized
    fun reload() {
        // Each file keeps its last good state on its own: a typo in one must not drop the other.
        val nextUser = userFile?.let { file ->
            try {
                read(file)
            } catch (error: Exception) {
                report(error); user
            }
        } ?: user
        val nextSettings = try {
            workspaceSettings(readWorkspace())
        } catch (error: Exception) {
            report(error); settings
        }
        val nextFolders = if (!layout.isMultiRoot) emptyMap() else layout.folders.associateWith { folder ->
            try {
                folderLayer(read(layout.folderFile(folder, SETTINGS)))
            } catch (error: Exception) {
                report(error); folderSettings[folder] ?: buildJsonObject {}
            }
        }
        if (nextUser == user && nextSettings == settings && nextFolders == folderSettings) return
        val empty = buildJsonObject {}
        // A folder added or taken away changes every key it sets.
        val layers = listOf(user to nextUser, settings to nextSettings) +
                (folderSettings.keys + nextFolders.keys).map { folder ->
                    (folderSettings[folder] ?: empty) to (nextFolders[folder] ?: empty)
                }
        val keys = layers.flatMapTo(linkedSetOf()) { (previous, next) -> changedKeys(previous, next) }
        val overrides = linkedMapOf<String, MutableSet<String>>()
        for ((previous, next) in layers) {
            for (key in changedKeys(previous, next).filter { it.startsWith('[') }) {
                val changedInside =
                    (previous[key] as? JsonObject)?.keys.orEmpty() + (next[key] as? JsonObject)?.keys.orEmpty()
                Regex("\\[([^]]+)]").findAll(key).forEach { match ->
                    overrides.getOrPut(match.groupValues[1]) { linkedSetOf() } += changedInside
                }
            }
        }
        user = nextUser
        settings = nextSettings
        folderSettings = nextFolders
        effective = overlay(overlay(defaults, user), settings)
        notify("ExtHostConfiguration", $$"$acceptConfigurationChanged", buildJsonArray {
            add(snapshot())
            add(buildJsonObject {
                put("keys", JsonArray(keys.map(::JsonPrimitive)))
                put("overrides", buildJsonArray {
                    overrides.forEach { (language, inside) ->
                        add(buildJsonArray { add(language); add(JsonArray(inside.map(::JsonPrimitive))) })
                    }
                })
            })
        }.toString())
    }

    /**
     * The workspace's folders changed: its `.code-workspace` file was edited, made or deleted. The
     * layers are read again for [next], and the extensions told of what that changes.
     */
    @Synchronized
    fun relayout(next: CodeBridgeWorkspace) {
        layout = next
        reload()
    }

    fun snapshot(): JsonObject = buildJsonObject {
        ExtensionHostInitData.emptyConfiguration().forEach { (key, value) -> put(key, value) }
        put("defaults", model(defaults))
        put("userLocal", model(user))
        put("workspace", model(settings))
        put("folders", buildJsonArray {
            folderSettings.forEach { (folder, settings) ->
                add(buildJsonArray {
                    add(ExtensionHostInitData.fileUri(folder.path)); add(
                    model(settings)
                )
                })
            }
        })
    }

    /**
     * `getConfiguration().update(...)`: writes the setting where VS Code would, and reloads.
     *
     * In a single-folder workspace, workspace and folder targets -- and no target -- all go to
     * `.vscode/settings.json`, the one file both name. In a multi-root workspace the workspace target
     * is the `.code-workspace` file's `settings`, and the folder target the `.vscode/settings.json` of
     * the folder holding the write's resource; no target means that folder for a per-resource
     * setting written for a resource, else the workspace, as in VS Code. The edit changes that one
     * key and leaves the rest of the file, comments and all, as it was. The reload runs before the reply, so the extension sees its own
     * change by the time its `update` promise resolves, as it does in VS Code.
     *
     * User and application targets go to [userFile], the one user layer there is. Remote user
     * settings are refused: nothing here is remote, and pretending would lose the value on the next
     * read. So, as in VS Code, are a setting [userOnly] has in user settings only written to the
     * workspace, and a file with unsaved changes in an editor.
     *
     * Returns once the file is written and reloaded. Writes are made one after another, in the order
     * they were asked for, each reading the file the one before it left.
     */
    suspend fun write(write: Configuration.Write) {
        val target = target(write)
        writes.withLock { edit(target, write) }
    }

    /** Where [write] goes, or why it may not be made at all. */
    @Synchronized
    private fun target(write: Configuration.Write): Target {
        val resource = write.resource?.let { runCatching { it.toPath() }.getOrNull() }
        val perResource = folderScoped == null || write.key in folderScoped
        // VS Code's own refusal in a window with no folder open, where a write with no target is the user's.
        if (!vsCodeFiles && (write.target == Configuration.Target.WORKSPACE || write.target == Configuration.Target.WORKSPACE_FOLDER)) {
            throw MainThreadException("Unavailable", "Unable to write to Workspace Settings because no workspace is opened.")
        }
        val target = when (write.target) {
            null if !vsCodeFiles && userFile != null -> Target.User
            Configuration.Target.APPLICATION, Configuration.Target.USER, Configuration.Target.USER_LOCAL ->
                if (userFile != null) Target.User else throw MainThreadException(
                    "Unavailable",
                    "CodeBridge has no user settings here, so '${write.key}' was not changed. Set it in .vscode/settings.json instead."
                )

            Configuration.Target.WORKSPACE -> Target.Workspace
            Configuration.Target.WORKSPACE_FOLDER -> if (!layout.isMultiRoot) Target.Workspace else
                resource?.let(layout::folderOf)?.let(Target::Folder)
                    ?: throw MainThreadException(
                        "Unavailable",
                        "Unable to write to Folder Settings because no resource is provided."
                    )

            null -> resource?.takeIf { layout.isMultiRoot && perResource }?.let(layout::folderOf)?.let(Target::Folder)
                ?: Target.Workspace

            else -> throw MainThreadException("Unavailable", "Settings cannot be written to ${write.target}")
        }
        // VS Code's own wording for each refusal, which extensions and their users will recognise.
        if (!write.key.startsWith('[') && write.key !in registered) {
            throw MainThreadException(
                "Unavailable",
                "Unable to write to ${target.title} Settings because ${write.key} is not a registered configuration."
            )
        }
        if (target != Target.User && write.key in userOnly) {
            throw MainThreadException(
                "Unavailable",
                "Unable to write ${write.key} to ${target.title} Settings. This setting can be written only into User settings."
            )
        }
        if (target is Target.Folder && !write.key.startsWith('[') && !perResource) {
            throw MainThreadException(
                "Unavailable",
                "Unable to write to Folder Settings because ${write.key} does not support the folder resource scope."
            )
        }
        return target
    }

    /** Where a write goes; see [write]. */
    private sealed class Target(val title: String) {
        data object User : Target("User")
        data object Workspace : Target("Workspace")
        data class Folder(val folder: WorkspaceFolder) : Target("Folder")
    }

    /** The file [target] names, the keys above a setting in it, and how the user is told of it. */
    private fun place(target: Target): Triple<Path, List<String>, String> = when (target) {
        Target.User -> Triple(checkNotNull(userFile), emptyList(), "your VS Code user settings")
        Target.Workspace -> layout.file?.let { Triple(it, listOf("settings"), it.fileName.toString()) }
            ?: Triple(settingsFile(), emptyList(), ".vscode/settings.json")

        is Target.Folder -> Triple(
            layout.folderFile(target.folder, SETTINGS),
            emptyList(),
            "${target.folder.name}/.vscode/settings.json"
        )
    }

    /** Held by the write under way, which the next one waits for. */
    private val writes = Mutex()

    /** Edits the file of [target] for [write]. */
    private suspend fun edit(target: Target, write: Configuration.Write) {
        val toUser = target == Target.User
        val (file, above, where) = place(target)
        if (files.dirty(file)) {
            val name = "${target.title.lowercase()} settings"
            throw MainThreadException(
                "Unavailable",
                "Unable to write into $name because the file has unsaved changes. Please save the $name file first and then try again."
            )
        }
        val before = files.read(file) ?: ""
        val path = listOfNotNull(write.language?.let { "[$it]" }, write.key)
        val after = JsoncEdit.set(before, above + path, write.value)
        if (after == before) return
        // Checked before it replaces anything: a file this cannot read back must not be written.
        JsoncEdit.parse(after)
        files.write(file, before, after)
        reload()
        if (toUser) userWritten()
        val shown = path.joinToString(" > ")
        changed(if (write.value == null) "'$shown' was removed from $where" else "'$shown' was set to ${write.value} in $where")
    }

    /** [file] as VS Code reads the workspace layer: without the settings only user settings may set. */
    private fun workspaceSettings(file: JsonObject): JsonObject =
        if (userOnly.isEmpty()) file else JsonObject(file.filterKeys { it !in userOnly })

    /**
     * A folder's own `.vscode/settings.json` as VS Code reads it in a multi-root workspace: only the
     * settings [folderScoped] allows, and language sections.
     */
    private fun folderLayer(file: JsonObject): JsonObject =
        workspaceSettings(if (folderScoped == null) file else JsonObject(file.filterKeys { it.startsWith('[') || it in folderScoped }))

    private fun settingsFile(): Path = workspace.resolve(".vscode/settings.json")

    /** The workspace layer as written: `.vscode/settings.json`, or the `.code-workspace` file's `settings`. */
    private fun readWorkspace(): JsonObject {
        if (!vsCodeFiles) return buildJsonObject {}
        val file = layout.file ?: return read(settingsFile())
        val settings = read(file)["settings"] ?: return buildJsonObject {}
        return settings as? JsonObject ?: throw InvalidSettingsException(file, "its \"settings\" is not a JSON object")
    }

    private fun read(file: Path): JsonObject {
        if (!Files.exists(file)) return buildJsonObject {}
        val text = Jsonc.stripToJson(Files.readString(file))
        // Empty, or nothing but comments, is no settings, as in VS Code -- not an error. A file just
        // created in an editor is saved like that, and at startup an error here stops the workbench.
        if (text.isBlank()) return buildJsonObject {}
        return try {
            Json.parseToJsonElement(text).jsonObject
        } catch (error: IllegalArgumentException) {
            // `SerializationException` is one. A save halfway through typing a key lands here, so
            // it is the file and the first line of the reason, not the parser's stack.
            throw InvalidSettingsException(
                file,
                error.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "not a JSON object")
        }
    }

    companion object {
        private const val SETTINGS = "settings.json"

        private fun changedKeys(previous: JsonObject, next: JsonObject): Set<String> =
            (previous.keys + next.keys).filterTo(linkedSetOf()) { previous[it] != next[it] }

        /**
         * [top] over [base], as VS Code merges its layers: objects key by key, so a workspace's
         * `files.exclude` adds to the user's patterns rather than replacing them, and anything
         * else wholesale.
         */
        internal fun overlay(base: JsonObject, top: JsonObject): JsonObject = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            top.forEach { (key, value) ->
                val under = base[key]
                put(key, if (under is JsonObject && value is JsonObject) overlay(under, value) else value)
            }
        }

        /** Every setting key the extensions declare, with or without a default. */
        fun registered(manifests: List<JsonObject>): Set<String> = manifests.flatMap { manifest ->
            val config = (manifest["contributes"] as? JsonObject)?.get("configuration")
            val sections = config as? JsonArray ?: listOfNotNull(config)
            sections.flatMap { ((it as? JsonObject)?.get("properties") as? JsonObject)?.keys.orEmpty() }
        }.toSet()

        /** Every key an extension scopes to the application or the machine; see [userOnly]. */
        fun userOnly(manifests: List<JsonObject>): Set<String> = manifests.flatMap { manifest ->
            val config = (manifest["contributes"] as? JsonObject)?.get("configuration")
            val sections = config as? JsonArray ?: listOfNotNull(config)
            sections.flatMap { section ->
                ((section as? JsonObject)?.get("properties") as? JsonObject).orEmpty().filter { (_, schema) ->
                    ((schema as? JsonObject)?.get("scope") as? JsonPrimitive)?.contentOrNull in USER_ONLY_SCOPES
                }.keys
            }
        }.toSet()

        private val USER_ONLY_SCOPES = setOf("application", "machine")

        /** Every key an extension scopes to a resource, a language or a machine; see [folderScoped]. */
        fun folderScoped(manifests: List<JsonObject>): Set<String> = manifests.flatMap { manifest ->
            val config = (manifest["contributes"] as? JsonObject)?.get("configuration")
            val sections = config as? JsonArray ?: listOfNotNull(config)
            sections.flatMap { section ->
                ((section as? JsonObject)?.get("properties") as? JsonObject).orEmpty().filter { (_, schema) ->
                    ((schema as? JsonObject)?.get("scope") as? JsonPrimitive)?.contentOrNull in FOLDER_SCOPES
                }.keys
            }
        }.toSet()

        private val FOLDER_SCOPES = setOf("resource", "language-overridable", "machine-overridable")

        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

        fun defaults(manifests: List<JsonObject>): JsonObject = buildJsonObject {
            for (manifest in manifests) {
                val config = (manifest["contributes"] as? JsonObject)?.get("configuration")
                val sections = config as? JsonArray ?: listOfNotNull(config)
                for (section in sections) {
                    val properties = (section as? JsonObject)?.get("properties") as? JsonObject ?: continue
                    properties.forEach { (key, schema) ->
                        (schema as? JsonObject)?.get("default")?.let { put(key, it) }
                    }
                }
            }
        }

        private fun tree(entries: Map<String, JsonElement>): JsonObject = buildJsonObject {
            entries.entries.groupBy { it.key.substringBefore('.') }.forEach { (key, values) ->
                put(
                    key, values.firstOrNull { it.key == key }?.value
                    ?: tree(values.associate { it.key.substringAfter('.') to it.value })
                )
            }
        }

        private fun model(settings: JsonObject): JsonObject = buildJsonObject {
            put("contents", tree(settings))
            put("keys", JsonArray(settings.keys.map(::JsonPrimitive)))
            put("overrides", buildJsonArray {
                settings.filterKeys { it.startsWith('[') }.forEach { (key, value) ->
                    val entries = value as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        put(
                            "identifiers",
                            JsonArray(Regex("\\[([^]]+)]").findAll(key).map { JsonPrimitive(it.groupValues[1]) }
                                .toList())
                        )
                        put("keys", JsonArray(entries.keys.map(::JsonPrimitive)))
                        put("contents", tree(entries))
                    })
                }
            })
        }
    }
}

/** A settings file that does not parse; the previous settings stay in effect. */
internal class InvalidSettingsException(val file: Path, reason: String) :
    Exception("$file is not valid settings JSON, so the previous settings stay in effect: $reason")
