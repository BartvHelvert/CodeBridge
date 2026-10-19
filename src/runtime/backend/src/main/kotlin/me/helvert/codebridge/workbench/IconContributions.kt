package me.helvert.codebridge.workbench

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.extensions.ExtensionInstallationListener
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.icons.ContributedIcons
import me.helvert.codebridge.rpc.ContributedIconsDto
import me.helvert.codebridge.rpc.IconFontDto
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path

/**
 * The icons enabled extensions contribute (`contributes.icons`): an id, written as `$(id)` wherever
 * a codicon can be, and either a glyph of an icon font the extension ships or another icon's id.
 *
 * ```json
 * "gitlens-graph": { "description": "...", "default": { "fontPath": "dist/glicons.woff2", "fontCharacter": "\\f102" } }
 * ```
 */
internal object IconContributions {

    fun read(extensions: List<InstalledExtension>): ContributedIconsDto {
        val glyphs = LinkedHashMap<Path, MutableMap<String, Int>>()
        val aliases = LinkedHashMap<String, String>()
        for (extension in extensions.filter { it.enabled }) {
            val icons = (extension.manifest.raw["contributes"] as? JsonObject)?.get("icons") as? JsonObject ?: continue
            for ((id, value) in icons) {
                when (val default = (value as? JsonObject)?.get("default")) {
                    is JsonPrimitive -> default.contentOrNull?.takeIf { it.isNotBlank() }?.let { aliases[id] = it }
                    is JsonObject -> {
                        val font = font(extension, (default["fontPath"] as? JsonPrimitive)?.contentOrNull) ?: continue
                        val character =
                            character((default["fontCharacter"] as? JsonPrimitive)?.contentOrNull) ?: continue
                        glyphs.getOrPut(font) { LinkedHashMap() }[id] = character
                    }

                    else -> {}
                }
            }
        }
        val fonts = glyphs.mapNotNull { (path, ids) ->
            val bytes =
                runCatching { Files.readAllBytes(path) }.onFailure { LOG.info("Could not read icon font $path", it) }
                    .getOrNull()
            bytes?.let { IconFontDto(it, ids) }
        }
        return ContributedIconsDto(fonts, aliases)
    }

    /** The font file [path] names, as long as it is inside [extension] and of a size an icon font is. */
    private fun font(extension: InstalledExtension, path: String?): Path? {
        if (path.isNullOrBlank()) return null
        val resolved = runCatching { extension.directory.resolve(path).normalize() }.getOrNull() ?: return null
        if (!resolved.startsWith(extension.directory) || !Files.isRegularFile(resolved)) return null
        if (Files.size(resolved) > MAX_FONT_BYTES) return null
        return resolved
    }

    /**
     * The code point `fontCharacter` names: CSS's escape, `\f102`, which is how the schema asks for
     * it, or the character itself.
     */
    internal fun character(value: String?): Int? {
        if (value.isNullOrEmpty()) return null
        if (value.startsWith("\\") && value.length > 1) return value.substring(1).toIntOrNull(16)
        return value.codePointAt(0)
    }

    private const val MAX_FONT_BYTES = 4L * 1024 * 1024

    private val LOG = logger<IconContributions>()
}

/** Keeps this side's [ContributedIcons] to what the enabled extensions contribute: the Project view and debug tabs are drawn here. */
@Service(Service.Level.APP)
internal class BackendContributedIcons(private val scope: CoroutineScope) : Disposable {

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ExtensionInstallationService.CHANGED, ExtensionInstallationListener { refresh() })
        refresh()
    }

    private fun refresh() {
        if (!ContributedIcons.replace(IconContributions.read(ExtensionInstallationService.getInstance().list()))) return
        // An icon named before its extension's were read looks itself up when painted.
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) { Window.getWindows().forEach(Window::repaint) }
    }

    override fun dispose() {}
}

/** Reads the contributions once a project opens, rather than when an icon is first painted. */
internal class BackendContributedIconsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<BackendContributedIcons>()
    }
}
