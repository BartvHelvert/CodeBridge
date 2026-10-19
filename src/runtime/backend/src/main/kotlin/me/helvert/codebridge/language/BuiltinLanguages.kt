package me.helvert.codebridge.language

import com.intellij.openapi.diagnostic.Logger
import me.helvert.codebridge.exthost.VsCodeInstallation
import me.helvert.codebridge.manifest.LanguageContribution
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * The languages VS Code's own extensions define, read from the runtime that hosts the extensions.
 *
 * VS Code calls `launch.json` `jsonc` because its built-in `configuration-editing` extension says
 * so, and an extension's completion provider for `jsonc` is asked there for that reason alone. Read
 * from the runtime rather than written down here, so the names are the ones that build of VS Code
 * uses. They only name files for the extension host; they claim no file type, so the IDE keeps its
 * own JSON support for these files.
 */
internal object BuiltinLanguages {
    private val read = ConcurrentHashMap<Path, List<LanguageContribution>>()

    /**
     * The built-in languages of [installation], read once per installation: the host's built-ins,
     * then the workbench's, which name the languages of every file type VS Code knows by itself.
     */
    fun of(installation: VsCodeInstallation?): List<LanguageContribution> = installation?.let {
        read.computeIfAbsent(it.builtinExtensions, ::read) + read.computeIfAbsent(it.workbenchExtensions, ::read)
    }.orEmpty()

    /**
     * The ids of the built-in languages that claim a file called [name], the closest first -- the
     * one it is opened as. See [closeness].
     */
    fun forName(installation: VsCodeInstallation?, name: String): Set<String> = closest(of(installation), name)

    internal fun read(directory: Path): List<LanguageContribution> {
        if (!directory.isDirectory()) return emptyList()
        return runCatching {
            directory.listDirectoryEntries().sorted().flatMap { extension ->
                val manifest =
                    extension.resolve("package.json").takeIf(Files::isRegularFile) ?: return@flatMap emptyList()
                VsixManifestParser.parse(Files.readString(manifest)).manifest?.contributes?.languages.orEmpty()
            }
        }.onFailure { LOG.warn("Could not read the languages built into $directory", it) }.getOrDefault(emptyList())
    }

    private val LOG = Logger.getInstance(BuiltinLanguages::class.java)
}
