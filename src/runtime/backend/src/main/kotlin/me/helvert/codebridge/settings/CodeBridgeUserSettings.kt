package me.helvert.codebridge.settings

import me.helvert.codebridge.workspace.VsCodeFiles
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.workspace.Jsonc
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The user's VS Code settings: the user-level `settings.json` every project reads beneath its own
 * `.vscode/settings.json`.
 *
 * CodeBridge's own file rather than VS Code's. The plugin does not need VS Code installed, and an
 * extension writing a user setting should not be editing another application's file. The first
 * time it is needed it starts as a copy of VS Code's, comments and all, so whatever someone set up
 * there -- `files.autoSave`, an extension's options -- applies here too; after that the two are
 * independent.
 *
 * On the backend, where extensions run and read it.
 */
object CodeBridgeUserSettings {
    val file: Path get() = PathManager.getConfigDir().resolve("codebridge/settings.json")

    /** Whether [path], as the VFS spells it, is [file]. */
    fun isFile(path: String): Boolean = NioFiles.toPath(path) == file

    /** Creates [file] if it is missing; the VS Code file it was copied from, if that is what happened. */
    @Synchronized
    fun ensure(): Path? = ensure(file, if (VsCodeFiles.apply()) vsCodeUserSettings() else emptyList())

    internal fun ensure(target: Path, candidates: List<Path>): Path? {
        if (Files.exists(target)) return null
        // A file this cannot read would be reported on every start; better to start empty.
        val source = candidates.firstOrNull { candidate -> Files.isRegularFile(candidate) && readable(candidate) }
        Files.createDirectories(target.parent)
        val partial = Files.createTempFile(target.parent, "settings", ".tmp")
        try {
            Files.writeString(partial, source?.let(Files::readString) ?: EMPTY)
            // Not REPLACE_EXISTING: another IDE on this machine may have just made it, and its copy stands.
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            return null
        } finally {
            Files.deleteIfExists(partial)
        }
        return source
    }

    /**
     * Where VS Code keeps its user `settings.json`, most likely first: the stable build, then
     * Insiders, then VSCodium. Only the default profile; another profile's settings are its own.
     */
    internal fun vsCodeUserSettings(
        os: String = System.getProperty("os.name"),
        env: (String) -> String? = System::getenv,
        home: Path = Path.of(System.getProperty("user.home")),
    ): List<Path> {
        val base = when {
            os.startsWith("Windows") -> env("APPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve("AppData/Roaming")

            os.startsWith("Mac") -> home.resolve("Library/Application Support")
            else -> env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".config")
        }
        return listOf("Code", "Code - Insiders", "VSCodium").map {
            base.resolve(it).resolve("User").resolve("settings.json")
        }
    }

    private fun readable(file: Path): Boolean =
        runCatching { Json.parseToJsonElement(Jsonc.stripToJson(Files.readString(file))).jsonObject }.isSuccess

    private const val EMPTY =
        "// VS Code user settings, for every project. A project's .vscode/settings.json overrides them.\n{\n}\n"
}

/**
 * Edits the user settings file without the "Non-Project Files Protection" dialog.
 *
 * It belongs to no project, so the IDE would otherwise ask before the first keystroke -- as it does
 * not for its own `idea.properties` and VM options, which are exempt the same way. Opening it is
 * already an explicit request to edit it.
 */
internal class CodeBridgeUserSettingsWritingAccess : NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile): Boolean = CodeBridgeUserSettings.isFile(file.path)
}
