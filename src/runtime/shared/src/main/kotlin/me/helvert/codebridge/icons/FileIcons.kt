package me.helvert.codebridge.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import me.helvert.codebridge.icons.FileIcons.PLATFORM
import java.util.*
import javax.swing.Icon

/**
 * File icons the way VS Code picks them, drawn the way the IDE draws its own.
 *
 * Which file gets which icon is VS Code's default file icon theme, Seti: VS Code draws a file's icon
 * from its file icon theme first and from the language's own `contributes.languages[].icon` only when
 * the theme has none, and Seti is the theme a fresh install uses. So this is not a table of ours: it is
 * VS Code's built-in theme, keyed by language id, file extension and file name -- never by extension.
 *
 * What the icon looks like is JetBrains' wherever JetBrains has one it licenses for reuse: the IDE's own
 * file type icons ([PLATFORM]), then those of JetBrains' open-source plugins, which carry their Apache 2.0
 * notice. Seti's glyph everywhere else -- including where JetBrains has an icon in a closed product or in
 * Toolbox, published without a licence.
 *
 * `/fileicons/mapping.properties` holds the lookup, maintained by hand: each entry names `platform/<name>`
 * or an SVG under `/fileicons` -- `jetbrains/<name>` or `seti/<name>` -- with an optional `_dark` twin,
 * which the icon loader switches between by itself as the theme changes. `LICENSE.txt` beside it says
 * where each SVG came from. Images loaded from this module's resources, rather than painted as
 * [Codicons] does, because a file icon is described to the frontend in split mode and only a loaded
 * image can be. This module is loaded on both sides, so the frontend finds the same resource.
 */
object FileIcons {

    private val LOG = Logger.getInstance(FileIcons::class.java)

    /** `language.<id>`, `extension.<ext>` and `name.<file name>`, each to the icon to draw; see [icon]. */
    private val MAPPING: Map<String, String> by lazy {
        runCatching {
            val stream = FileIcons::class.java.getResourceAsStream("/fileicons/mapping.properties")
                ?: error("/fileicons/mapping.properties is missing")
            val properties = Properties().apply { stream.use { load(it) } }
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        }.onFailure { LOG.warn("Could not read the bundled file icon mapping", it) }
            .getOrDefault(emptyMap())
    }

    /**
     * The icon of the language [id], which claims files by [extensions] (`.rs`) and [filenames], or
     * null if the theme has none for it.
     *
     * The language id first, because that is what answers for the language as a whole; a file of it
     * could be named anything. Then what the language claims, in its own order, the way VS Code
     * matches a file: the whole name, then each extension from the longest, so `.d.ts` is `d.ts`
     * before it is `ts`.
     */
    fun forLanguage(id: String, extensions: List<String>, filenames: List<String>): Icon? {
        val path = MAPPING["language.$id"]
            ?: extensions.firstNotNullOfOrNull { forExtension(it.removePrefix(".").lowercase()) }
            ?: filenames.firstNotNullOfOrNull { MAPPING["name.${it.lowercase()}"] }
            ?: return null
        return icon(path)
    }

    /**
     * The icon of a file called [fileName], by name alone, or null if the theme has none for it.
     *
     * VS Code's own order for a file: the whole name, then each extension from the longest. No
     * language comes into it, which is why VS Code draws `Cargo.toml` as a TOML file with nothing
     * installed that knows what TOML is.
     */
    fun forFile(fileName: String): Icon? {
        val lower = fileName.lowercase()
        val path = MAPPING["name.$lower"]
            ?: lower.substringAfter('.', "").takeIf { it.isNotEmpty() }?.let(::forExtension)
            ?: return null
        return icon(path)
    }

    /** The icon a mapping entry names: `platform/<name>`, or an SVG's path under `/fileicons`. */
    internal fun icon(path: String): Icon? = when {
        path.startsWith(PLATFORM_PREFIX) -> PLATFORM[path.removePrefix(PLATFORM_PREFIX)]
            ?: null.also { LOG.warn("The file icon mapping names $path, which is not one of the IDE's") }

        else -> IconLoader.getIcon("/fileicons/$path.svg", FileIcons::class.java.classLoader)
    }

    private const val PLATFORM_PREFIX = "platform/"

    /**
     * The IDE's own file type icons, by their new UI file name, for `platform/<name>` in the mapping.
     *
     * Taken from the running IDE rather than copied: they are the very icons its own files are drawn
     * with, and the classic UI's where that is the UI in use.
     */
    private val PLATFORM: Map<String, Icon> by lazy {
        mapOf(
            "archive" to AllIcons.FileTypes.Archive,
            "bazel" to AllIcons.FileTypes.Bazel,
            "c" to AllIcons.FileTypes.C,
            "config" to AllIcons.FileTypes.Config,
            "cpp" to AllIcons.FileTypes.Cpp,
            "Csharp" to AllIcons.FileTypes.Csharp,
            "css" to AllIcons.FileTypes.Css,
            "csv" to AllIcons.FileTypes.Csv,
            "docker" to AllIcons.FileTypes.Docker,
            "font" to AllIcons.FileTypes.Font,
            "gitignore" to AllIcons.FileTypes.Gitignore,
            "gradle" to AllIcons.FileTypes.Gradle,
            "graphql" to AllIcons.FileTypes.Graphql,
            "groovy" to AllIcons.FileTypes.Groovy,
            "h" to AllIcons.FileTypes.H,
            "html" to AllIcons.FileTypes.Html,
            "ignored" to AllIcons.Vcs.Ignore_file,
            "image" to AllIcons.FileTypes.Image,
            "java" to AllIcons.FileTypes.Java,
            "javaClass" to AllIcons.FileTypes.JavaClass,
            "javaScript" to AllIcons.FileTypes.JavaScript,
            "jenkins" to AllIcons.FileTypes.Jenkins,
            "json" to AllIcons.FileTypes.Json,
            "jupyter" to AllIcons.FileTypes.Jupyter,
            "markdown" to AllIcons.FileTypes.Markdown,
            "openApi" to AllIcons.Webreferences.Openapi,
            "perl" to AllIcons.FileTypes.Perl,
            "properties" to AllIcons.FileTypes.Properties,
            "shell" to AllIcons.Nodes.Console,
            "sql" to AllIcons.FileTypes.Sql,
            "swiftLang" to AllIcons.FileTypes.SwiftLang,
            "terraform" to AllIcons.FileTypes.Terraform,
            "toml" to AllIcons.FileTypes.Toml,
            "vue" to AllIcons.FileTypes.Vue,
            "xml" to AllIcons.FileTypes.Xml,
            "yaml" to AllIcons.FileTypes.Yaml,
        )
    }

    private fun forExtension(extension: String): String? = generateSequence(extension) { rest ->
        rest.substringAfter('.', "").takeIf { it.isNotEmpty() }
    }.firstNotNullOfOrNull { MAPPING["extension.$it"] }
}
