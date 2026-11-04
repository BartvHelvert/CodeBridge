package me.helvert.codebridge.language

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import me.helvert.codebridge.exthost.VsCodeInstallation
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.settings.VsCodeRuntime
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * The snippets this project's extensions contribute (`contributes.snippets`), by the file they would
 * go into.
 *
 * VS Code matches a snippet to a document by its language id, not by grammar or file type, so this
 * does too: the id the extension host holds the file under, or else the one an extension or VS Code
 * itself gives its name. Snippets need no running extension -- they are data in the package -- so
 * neither does this.
 *
 * The TextMate plugin reads the same files, from the same packages registered as its bundles, but
 * only ever offers them in a TextMate file, and by the grammar scope at the caret; snippets for a
 * language the IDE reads itself, or one no grammar names, never show there.
 */
@Service(Service.Level.PROJECT)
class ContributedSnippets(private val project: Project) {

    /** [snippet] from the extension [source] names, as the workbench says where one came from. */
    internal data class Offered(val snippet: SnippetFile.Snippet, val source: String)

    /** One package's snippets, read once: an installed package's files never change in place. */
    private val read = ConcurrentHashMap<String, List<Offered>>()

    @Volatile
    private var installation: VsCodeInstallation? = null

    /** The prefixes of the snippets that apply to [file]. */
    fun prefixes(file: VirtualFile): Set<String> = forFile(file).flatMapTo(HashSet()) { it.snippet.prefixes }

    /** The snippets that apply to [file], in the order their extensions and files list them. */
    internal fun forFile(file: VirtualFile): List<Offered> {
        val extensions = ProjectExtensions.getInstance(project).enabled()
            .filter { it.manifest.contributes.snippets.isNotEmpty() }
        if (extensions.isEmpty()) return emptyList()
        val language = languageOf(file)
        return extensions.flatMap(::snippets).filter { (snippet) ->
            (snippet.scopes.isEmpty() || language in snippet.scopes) && SnippetFile.applies(snippet, file.name, file.path)
        }
    }

    /**
     * The VS Code language id of [file]: the host's, once it holds the file, else the closest an
     * enabled extension or VS Code's own languages give its name, else `plaintext`, as VS Code opens
     * a file nothing claims.
     */
    internal fun languageOf(file: VirtualFile): String {
        HostedLanguageFeatures.getInstance(project).languageOf(file)?.let { return it }
        val contributed = ProjectExtensions.getInstance(project).enabled().flatMap { it.manifest.contributes.languages }
        closest(contributed, file.name).firstOrNull()?.let { return it }
        val installation = installation ?: VsCodeRuntime.getInstance().installationOrNull()?.also { installation = it }
        return BuiltinLanguages.forName(installation, file.name).firstOrNull() ?: "plaintext"
    }

    private fun snippets(extension: InstalledExtension): List<Offered> =
        read.computeIfAbsent("${extension.directory}|${extension.installedAt}") { read(extension) }

    private fun read(extension: InstalledExtension): List<Offered> {
        val root = extension.directory.toAbsolutePath().normalize()
        val source = extension.manifest.displayName ?: extension.manifest.name
        return extension.manifest.contributes.snippets.flatMap { contribution ->
            // The path is the package's own, so it is held to the package.
            val path = root.resolve(contribution.path).normalize()
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                LOG.warn("${extension.id} contributes snippets from ${contribution.path}, which it does not contain")
                return@flatMap emptyList()
            }
            runCatching { SnippetFile.parse(Files.readString(path), contribution.language) }
                .onFailure { LOG.warn("Could not read the snippets ${extension.id} contributes in ${contribution.path}", it) }
                .getOrDefault(emptyList())
                .map { Offered(it, source) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ContributedSnippets::class.java)

        fun getInstance(project: Project): ContributedSnippets = project.service()
    }
}
