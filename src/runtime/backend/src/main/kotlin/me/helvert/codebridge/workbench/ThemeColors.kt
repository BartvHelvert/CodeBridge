package me.helvert.codebridge.workbench

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.JBColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.workspace.Jsonc
import java.awt.Color
import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * VS Code theme colours, by id, as IDE colours that follow the light or dark theme.
 *
 * An id an extension contributes (`contributes.colors`) has the defaults it declares, each a colour
 * or another id. VS Code's own ids are its workbench's, not any extension's: the Git decoration
 * colours are the IDE's matching file status colours, so a file Git calls modified is coloured as
 * the IDE colours one; the severity colours are the colour scheme's error, warning and weak warning
 * colours, so an extension drawing in `editorError.foreground` draws as IntelliJ underlines errors;
 * and the few others decorations use have VS Code's default values.
 *
 * Over all of them, `workbench.colorCustomizations` in the user settings, as in VS Code: a colour by
 * id, or a block of them under `[theme]`, which here names the IDE's look and feel, `*` matching
 * anything -- `[*Dark*]` for every dark one.
 */
internal class ThemeColors(manifests: List<JsonObject>) {

    /** Contributed ids, with their `light` and `dark` defaults. */
    private val contributed: Map<String, Pair<String?, String?>> = manifests.flatMap { manifest ->
        (((manifest["contributes"] as? JsonObject)?.get("colors")) as? JsonArray).orEmpty().mapNotNull { entry ->
            val color = entry as? JsonObject ?: return@mapNotNull null
            val id = color.text("id") ?: return@mapNotNull null
            val defaults = color["defaults"] as? JsonObject
            id to (defaults?.text("light") to defaults?.text("dark"))
        }
    }.toMap()

    /** [id] as a colour, or null for one nobody defines. */
    fun resolve(id: String): Color? = resolve(id, 0)

    /** Raw defaults so the frontend can resolve aliases against its own IDE theme. */
    fun webviewDefaults(): Map<String, Map<String, String>> = contributed.mapValues { (_, defaults) ->
        buildMap {
            defaults.first?.let { put("light", it) }
            (defaults.second ?: defaults.first)?.let { put("dark", it) }
        }
    }

    private fun resolve(id: String, depth: Int): Color? {
        if (depth > MAX_DEPTH) return null
        Customizations.current()[id]?.let { hex(it) }?.let { return it }
        FILE_STATUSES[id]?.color?.let { return it }
        SCHEME[id]?.let { key ->
            return JBColor.lazy {
                schemeColor(key) ?: BUILT_IN.getValue(id).let { JBColor(it.first, it.second) }
            }
        }
        BUILT_IN[id]?.let { (light, dark) -> return JBColor(light, dark) }
        val (light, dark) = contributed[id] ?: return null
        val lightColor = light?.let { value(it, depth) }
        val darkColor = dark?.let { value(it, depth) } ?: lightColor
        return if (lightColor == null || darkColor == null) darkColor else JBColor(lightColor, darkColor)
    }

    /** A default: `#rgb`, `#rrggbb` or with alpha, or another colour's id. */
    private fun value(text: String, depth: Int): Color? = if (text.startsWith("#")) hex(text) else resolve(text, depth + 1)

    companion object {
        private const val MAX_DEPTH = 8

        @Volatile
        private var cached: Pair<List<String>, ThemeColors>? = null

        /** The colours the enabled extensions contribute, rebuilt only when that set changes. */
        fun installed(): ThemeColors {
            val manifests = ExtensionInstallationService.getInstance().list().filter { it.enabled }
            val ids = manifests.map { "${it.id}@${it.manifest.version}" }
            cached?.takeIf { it.first == ids }?.let { return it.second }
            return ThemeColors(manifests.map { it.manifest.raw }).also { cached = ids to it }
        }

        private val FILE_STATUSES: Map<String, FileStatus> = mapOf(
            "gitDecoration.addedResourceForeground" to FileStatus.ADDED,
            "gitDecoration.modifiedResourceForeground" to FileStatus.MODIFIED,
            "gitDecoration.stageModifiedResourceForeground" to FileStatus.MODIFIED,
            "gitDecoration.deletedResourceForeground" to FileStatus.DELETED,
            "gitDecoration.stageDeletedResourceForeground" to FileStatus.DELETED,
            "gitDecoration.renamedResourceForeground" to FileStatus.UNKNOWN,
            "gitDecoration.untrackedResourceForeground" to FileStatus.UNKNOWN,
            "gitDecoration.ignoredResourceForeground" to FileStatus.IGNORED,
            "gitDecoration.conflictingResourceForeground" to FileStatus.MERGED_WITH_CONFLICTS,
        )

        /** VS Code's severity colours, as the colour scheme draws each severity. */
        private val SCHEME: Map<String, TextAttributesKey> = mapOf(
            "editorError.foreground" to CodeInsightColors.ERRORS_ATTRIBUTES,
            "editorWarning.foreground" to CodeInsightColors.WARNINGS_ATTRIBUTES,
            "editorInfo.foreground" to CodeInsightColors.WEAK_WARNING_ATTRIBUTES,
            "problemsErrorIcon.foreground" to CodeInsightColors.ERRORS_ATTRIBUTES,
            "problemsWarningIcon.foreground" to CodeInsightColors.WARNINGS_ATTRIBUTES,
            "problemsInfoIcon.foreground" to CodeInsightColors.WEAK_WARNING_ATTRIBUTES,
        )

        /** The underline a severity is drawn with, else its stripe mark or its text. */
        private fun schemeColor(key: TextAttributesKey): Color? = runCatching {
            EditorColorsManager.getInstance().globalScheme.getAttributes(key)
                ?.let { it.effectColor ?: it.errorStripeColor ?: it.foregroundColor }
        }.getOrNull()

        /** VS Code's defaults, light then dark. */
        private val BUILT_IN: Map<String, Pair<Color, Color>> = mapOf(
            "list.errorForeground" to (Color(0xB01011) to Color(0xF88070)),
            "list.warningForeground" to (Color(0x855F00) to Color(0xCCA700)),
            "list.deemphasizedForeground" to (Color(0x8E8E90) to Color(0x8C8C8C)),
            "editorError.foreground" to (Color(0xE51400) to Color(0xF14C4C)),
            "editorWarning.foreground" to (Color(0xBF8803) to Color(0xCCA700)),
            "editorInfo.foreground" to (Color(0x1A85FF) to Color(0x3794FF)),
            "problemsErrorIcon.foreground" to (Color(0xE51400) to Color(0xF14C4C)),
            "problemsWarningIcon.foreground" to (Color(0xBF8803) to Color(0xCCA700)),
            "problemsInfoIcon.foreground" to (Color(0x1A85FF) to Color(0x3794FF)),
            "charts.red" to (Color(0xE51400) to Color(0xF14C4C)),
            "charts.blue" to (Color(0x1A85FF) to Color(0x3794FF)),
            "charts.yellow" to (Color(0xBF8803) to Color(0xCCA700)),
            "charts.orange" to (Color(0xD18616) to Color(0xD18616)),
            "charts.green" to (Color(0x388A34) to Color(0x89D185)),
            "charts.purple" to (Color(0x652D90) to Color(0xB180D7)),
        )

        fun hex(text: String): Color? {
            val digits = text.removePrefix("#")
            val expanded = if (digits.length == 3 || digits.length == 4) digits.map { "$it$it" }.joinToString("") else digits
            val value = expanded.toLongOrNull(16) ?: return null
            return when (expanded.length) {
                6 -> Color(value.toInt())
                8 -> Color((value shr 8).toInt() and 0xFFFFFF or ((value and 0xFF).toInt() shl 24), true)
                else -> null
            }
        }

        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    /**
     * `workbench.colorCustomizations`, read again once the settings file has changed -- and looked
     * at for that at most [RECHECK_NANOS] apart, since colours are resolved per decorated range.
     */
    internal object Customizations {
        private const val RECHECK_NANOS = 1_000_000_000L

        private data class Read(
            val checked: Long,
            val stamp: FileTime?,
            val theme: String,
            val colours: Map<String, String>
        )

        @Volatile
        private var read = Read(0L, null, "", emptyMap())

        fun current(): Map<String, String> {
            val last = read
            val now = System.nanoTime()
            // None outside the IDE, as in a unit test: then no theme's block applies.
            val theme = runCatching { LafManager.getInstance()?.currentUIThemeLookAndFeel?.name }.getOrNull().orEmpty()
            if (last.checked != 0L && now - last.checked < RECHECK_NANOS && last.theme == theme) return last.colours
            val modified = runCatching { Files.getLastModifiedTime(CodeBridgeUserSettings.file) }.getOrNull()
            if (last.checked != 0L && modified == last.stamp && last.theme == theme) {
                read = last.copy(checked = now)
                return last.colours
            }
            val text = runCatching { Files.readString(CodeBridgeUserSettings.file) }.getOrNull()
            return of(text, theme).also { read = Read(now, modified, theme, it) }
        }

        /** The customizations [settings] makes under the look and feel called [theme]. */
        fun of(settings: String?, theme: String): Map<String, String> {
            val root =
                settings?.let { runCatching { Json.parseToJsonElement(Jsonc.stripToJson(it)) }.getOrNull() } as? JsonObject
            val customizations = root?.get("workbench.colorCustomizations") as? JsonObject ?: return emptyMap()
            val colours = HashMap<String, String>()
            for ((key, value) in customizations) {
                if (!key.startsWith("[")) (value as? JsonPrimitive)?.contentOrNull?.let { colours[key] = it }
            }
            // A theme's block overrides the colours for every theme.
            for ((key, value) in customizations) {
                if (!key.startsWith("[") || !matches(key.removeSurrounding("[", "]"), theme)) continue
                (value as? JsonObject)?.forEach { (id, colour) ->
                    (colour as? JsonPrimitive)?.contentOrNull?.let {
                        colours[id] = it
                    }
                }
            }
            return colours
        }

        private fun matches(pattern: String, theme: String): Boolean =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE).matches(theme)
    }
}
