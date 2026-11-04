package me.helvert.codebridge.workbench

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.rpc.CustomEditorSetupDto
import me.helvert.codebridge.rpc.CustomEditorTypeDto
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Files
import java.nio.file.Path

/**
 * The custom editors the project's enabled extensions contribute (`contributes.customEditors`),
 * read from their manifests without running any: which files each claims, and whether it opens
 * them by default or only when asked to. Only installed extensions count, as only they run.
 *
 * With them, the user's own choices among them, `workbench.editorAssociations`, read from the
 * settings files without a running host as well.
 */
internal object CustomEditorContributions {
    const val ASSOCIATIONS: String = "workbench.editorAssociations"
    private val LOG = Logger.getInstance(CustomEditorContributions::class.java)

    /** [project]'s custom editors and the user's associations. */
    fun setup(project: Project): CustomEditorSetupDto = CustomEditorSetupDto(types(project), associations(project))

    /** The custom editors of [project]'s enabled extensions, first claim first. */
    fun types(project: Project): List<CustomEditorTypeDto> =
        ProjectExtensions.getInstance(project).enabled().flatMap { types(it.id, it.manifest.raw) }.distinctBy { it.viewType }

    /** `contributes.customEditors` of extension [extensionId]'s [manifest]. */
    fun types(extensionId: String, manifest: JsonObject): List<CustomEditorTypeDto> {
        val editors = ((manifest["contributes"] as? JsonObject)?.get("customEditors") as? JsonArray).orEmpty()
        return editors.filterIsInstance<JsonObject>().mapNotNull { editor ->
            val viewType = editor.string("viewType") ?: return@mapNotNull null
            val patterns = (editor["selector"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.string("filenamePattern") }
            // One with no file pattern is opened by its extension alone, through `vscode.openWith`.
            if (patterns.isEmpty()) return@mapNotNull null
            CustomEditorTypeDto(
                viewType,
                editor.string("displayName") ?: viewType,
                extensionId,
                patterns,
                // VS Code's default when none is given.
                byDefault = editor.string("priority") != "option",
            )
        }
    }

    /**
     * `workbench.editorAssociations` for [project]: the user's, with the workspace's over them, as
     * VS Code merges an object setting. A settings file that does not parse adds nothing.
     */
    fun associations(project: Project): Map<String, String> {
        val base = project.basePath?.let(Path::of) ?: return emptyMap()
        val settings = try {
            HostedConfiguration(
                base, buildJsonObject {}, { _, _, _ -> }, { LOG.info("Could not read the editor associations", it) },
                userFile = CodeBridgeUserSettings.file.takeIf(Files::isRegularFile),
                initialLayout = CodeBridgeWorkspace.of(base),
            ).effective
        } catch (error: Exception) {
            LOG.info("Could not read the editor associations", error)
            return emptyMap()
        }
        return associations(settings)
    }

    /** The associations in [settings], leaving out any that names no editor. */
    fun associations(settings: JsonObject): Map<String, String> =
        (settings[ASSOCIATIONS] as? JsonObject).orEmpty().mapNotNull { (pattern, editor) ->
            (editor as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { pattern to it }
        }.toMap()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
