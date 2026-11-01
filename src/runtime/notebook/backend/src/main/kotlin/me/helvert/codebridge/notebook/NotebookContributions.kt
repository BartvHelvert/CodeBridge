package me.helvert.codebridge.notebook

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.manifest.VsixManifestParser
import me.helvert.codebridge.settings.VsCodeRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * What the project's extensions say about notebooks, without running any: the notebook types they
 * contribute (`contributes.notebooks`), and a file extension for each language they define, which
 * lets the frontend draw a cell as a file of that language would be.
 *
 * VS Code's own extensions count as well. `.ipynb` is its built-in `ipynb` extension's, and most
 * languages a cell is written in are named by the workbench's built-ins, whose manifests the runtime
 * carries for that reason.
 */
internal object NotebookContributions {
    private val builtins = ConcurrentHashMap<Path, List<JsonObject>>()

    /** The notebook types of [project]'s enabled extensions and of the built-in ones, first claim first. */
    fun types(project: Project): List<NotebookTypeDto> =
        manifests(project).flatMap { manifest -> notebooks(manifest) }.distinctBy { it.viewType }

    /** A file extension, with its dot, for each language id, the first manifest to name one winning. */
    fun fileExtensions(project: Project): Map<String, String> {
        val extensions = linkedMapOf<String, String>()
        for (manifest in manifests(project)) {
            for (language in (contributes(manifest)["languages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                val id = (language["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                val extension = (language["extensions"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: continue
                extensions.putIfAbsent(id, extension)
            }
        }
        return extensions
    }

    /** The folders of the built-in extensions in [directory] that contribute a notebook type. */
    fun builtinSerializers(directory: Path): List<Path> {
        if (!directory.isDirectory()) return emptyList()
        return runCatching {
            directory.listDirectoryEntries().sorted().filter { extension ->
                val manifest = extension.resolve("package.json").takeIf(Files::isRegularFile) ?: return@filter false
                val raw = VsixManifestParser.parse(Files.readString(manifest)).manifest?.raw ?: return@filter false
                (contributes(raw)["notebooks"] as? JsonArray)?.isNotEmpty() == true
            }
        }.onFailure { LOG.warn("Could not read the extensions built into $directory", it) }.getOrDefault(emptyList())
    }

    /** `contributes.notebooks` of one manifest. */
    internal fun notebooks(manifest: JsonObject): List<NotebookTypeDto> =
        (contributes(manifest)["notebooks"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { notebook ->
            val type = (notebook["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val patterns = (notebook["selector"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .mapNotNull { (it["filenamePattern"] as? JsonPrimitive)?.contentOrNull }
            // A type with no file pattern is opened by its extension alone, as the interactive window is.
            if (patterns.isEmpty()) return@mapNotNull null
            NotebookTypeDto(
                type,
                (notebook["displayName"] as? JsonPrimitive)?.contentOrNull ?: type,
                patterns,
                opensByDefault = (notebook["priority"] as? JsonPrimitive)?.contentOrNull != "option",
            )
        }

    private fun contributes(manifest: JsonObject): JsonObject = manifest["contributes"] as? JsonObject ?: JsonObject(emptyMap())

    /** The project's enabled extensions' manifests, then the built-ins of the runtime that hosts them. */
    private fun manifests(project: Project): List<JsonObject> {
        val installed = ProjectExtensions.getInstance(project).enabled().map { it.manifest.raw }
        val installation = VsCodeRuntime.getInstance().installationOrNull() ?: return installed
        return buildList {
            addAll(installed)
            addAll(builtins.computeIfAbsent(installation.builtinExtensions, ::read))
            addAll(builtins.computeIfAbsent(installation.workbenchExtensions, ::read))
        }
    }

    private fun read(directory: Path): List<JsonObject> {
        if (!directory.isDirectory()) return emptyList()
        return runCatching {
            directory.listDirectoryEntries().sorted().mapNotNull { extension ->
                val manifest = extension.resolve("package.json").takeIf(Files::isRegularFile) ?: return@mapNotNull null
                VsixManifestParser.parse(Files.readString(manifest)).manifest?.raw
            }
        }.onFailure { LOG.warn("Could not read the extensions built into $directory", it) }.getOrDefault(emptyList())
    }

    private val LOG = Logger.getInstance(NotebookContributions::class.java)
}
