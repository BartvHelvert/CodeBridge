package me.helvert.codebridge.language

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.UnknownFileType
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.manifest.LanguageContribution
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives files an installed extension claims the type of the language it claims them as.
 *
 * VS Code learns a language from `contributes.languages`; IntelliJ has never heard of it. Without a
 * file type there is no `Document` and no PSI, so the file opens but nothing can annotate it -- the
 * server runs, reports problems, and the editor stays blank. That is not hypothetical: it is what a
 * `.codebridgeprobe` file did until this existed.
 *
 * Not a registered `fileType`, and no persisted association: the set of extensions is only known at
 * runtime and changes when the user installs one, and writing associations into the user's File
 * Types settings would outlive the extension that justified them. The cost of staying out of the
 * registry is that the platform cannot find the type by name again, so nothing can be read back
 * from its caches; [CodeBridgeLanguageFileTypeOverrider] answers afresh every time instead.
 *
 * An overrider rather than the content detector this replaced, because a detector never runs for
 * these files. The TextMate plugin claims anything it has a grammar for through
 * `FileTypeIdentifiableByVirtualFile`, which the platform consults before it detects, so an
 * installed rust-analyzer left `.rs` typed `textmate` and the detector was never asked. Overriders
 * are consulted before all of it. Taking the file back is safe because the language it is given
 * still comes from TextMate -- see [CodeBridgeFileLanguageProvider].
 *
 * Each contributed language gets a [CodeBridgeLanguageFileType] of its own, so the status bar names the
 * language instead of calling every contributed file Plain Text or TextMate.
 */
@Service(Service.Level.APP)
class CodeBridgeLanguageFileTypes {

    /**
     * What the installed extensions claim, as of one revision of the extension store.
     *
     * Rebuilt rather than re-read: [ExtensionInstallationService.list] takes a file lock and parses
     * every installed manifest, and this is asked on the file type path, which the platform walks
     * for every file it indexes. The revision counter the store already keeps is what makes one
     * build last until something is installed, enabled or removed.
     *
     * A list and not an index by extension. It is short -- the languages of the extensions the user
     * installed -- and sharing [matches] with the rest of the plugin matters more than the scan:
     * the file the host is told about and the file the editor types must be the same file.
     */
    private class Claims(val revision: Long, val claims: List<Claim>) {
        /** The closest claim, as [HostedLanguageServers] opens the file as its language; the first among equals. */
        fun claiming(name: String): Claim? = claims
            .mapNotNull { claim -> claim.contribution.closeness(name)?.let { claim to it } }
            .maxByOrNull { it.second }?.first
    }

    /**
     * One contributed language, with the images the extension offered for it already found.
     *
     * The paths are part of the identity and not a detail hanging off it, because they are what
     * makes a reinstalled extension's language a different type from the one before it: the
     * directory is a new generation each time, and a type still pointing into the old one would
     * paint from a directory that is about to be swept.
     */
    private data class Claim(val contribution: LanguageContribution, val light: Path?, val dark: Path?)

    @Volatile private var claims: Claims? = null

    /**
     * One type per language and backing language, kept because the platform compares file types by
     * identity: a fresh instance per query would read as the file changing type continuously.
     */
    private val types = ConcurrentHashMap<Pair<Claim, Language>, CodeBridgeLanguageFileType>()

    /**
     * Type names whose reader has been told about them, so it is told exactly once. Guarded by
     * itself, and held while the reader is told: see [announce].
     */
    private val announced = HashSet<String>()

    /** Whether an enabled extension contributes language support for this filename. */
    fun supportsName(name: String): Boolean = claims().claiming(name) != null

    /**
     * A file name of the contributed language whose id or alias is [language], or null if none is.
     *
     * Markdown names the language of a code fence the way VS Code resolves it -- `rust`, not `.rs`
     * -- while everything here is keyed by file name. Any name the language claims will do: what
     * reads the file is chosen by its name, not by what is in it.
     */
    fun nameFor(language: String): String? {
        val contribution = claims().claims.map { it.contribution }.firstOrNull { contribution ->
            contribution.id.equals(language, ignoreCase = true) ||
                contribution.aliases.any { it.equals(language, ignoreCase = true) }
        } ?: return null
        return contribution.extensions.firstOrNull()?.let { "code$it" } ?: contribution.filenames.firstOrNull()
    }

    /** The type for a file called [name], or null if no enabled extension claims that name. */
    fun forName(name: String): FileType? {
        val claim = claims().claiming(name) ?: return null
        // Only names nothing else answers for. An extension contributing `.json` or `.py` must not
        // take the file away from the IDE's own language, which reads it far better than a VS Code
        // extension will; the same goes for an association the user made by hand. This is the name
        // table only -- deliberately, because the type TextMate would give the file is not in it,
        // and that one this does mean to replace.
        val existing = FileTypeManager.getInstance().getFileTypeByFileName(name)
        if (existing !== UnknownFileType.INSTANCE) {
            LOG.debug("Not claiming $name as ${claim.contribution.id}: the IDE already types it as ${existing.name}")
            return null
        }
        var reader: CodeBridgeFileLanguageProvider? = null
        var language: Language? = null
        for (candidate in CodeBridgeFileLanguageProvider.EP_NAME.extensionsIfPointIsRegistered) {
            language = candidate.languageFor(name) ?: continue
            reader = candidate
            break
        }
        val read = language ?: PlainTextLanguage.INSTANCE
        val type = types.computeIfAbsent(claim to read) { (claimed, with) ->
            LOG.debug("Claiming files like $name as ${claimed.contribution.id}, read as ${with.id}")
            CodeBridgeLanguageFileType(claimed.contribution, with, CodeBridgeLanguageIcon(claimed.light, claimed.dark))
        }
        // Once per name, and only to the reader that answered for it. A name can be minted twice --
        // as plain text before the reader had a grammar for it, then as the reader's language once
        // it did -- and it is the second one that has anything to register.
        if (reader != null) announce(reader, type)
        return type
    }

    /**
     * Tells [reader] of [type] if it has not been told yet, and returns only once it has been.
     *
     * Every thread asking for the type waits for that, not just the first: during an install many
     * threads type files at once, and one handed the type while the first was still registering
     * TextMate's editor highlighter built the platform's default one instead, which fails on every
     * token TextMate gives it -- and the platform kept answering that for the rest of the session.
     *
     * Marked before the reader is told, not after, because telling it re-enters this on the same
     * thread: registering an extension notifies the platform's listeners synchronously, the editor
     * highlighter updater types every open file again, and that lands back here through the
     * overrider. The monitor is reentrant, so marking afterwards registered the same type over and
     * over until the stack ran out. A failed registration is unmarked so it is tried again.
     */
    private fun announce(reader: CodeBridgeFileLanguageProvider, type: CodeBridgeLanguageFileType) {
        synchronized(announced) {
            if (!announced.add(type.name)) return
            try {
                reader.claimed(type)
            } catch (e: Throwable) {
                announced -= type.name
                throw e
            }
        }
    }

    private fun claims(): Claims {
        val service = ExtensionInstallationService.getInstance()
        val revision = service.changes.value
        claims?.takeIf { it.revision == revision }?.let { return it }
        // Not cached on failure: a store that could not be read once is not a store with nothing in
        // it, and the revision will not change to say so later.
        val built = runCatching {
            service.list().filter { it.enabled }.flatMap { extension ->
                extension.manifest.contributes.languages.map { claim(extension, it) }
            }
        }.onFailure { LOG.warn("Could not list installed extensions; no file will be claimed", it) }
            .getOrNull() ?: return Claims(revision, emptyList())
        return Claims(revision, built).also { claims = it }
    }

    /**
     * A contributed language and the images to draw its files with.
     *
     * The language's own pair, and nothing else. The extension's marketplace icon is the obvious
     * thing to reach for when a language declares none, and it is wrong twice over: VS Code never
     * draws it on a file, and it is a single image with no dark variant -- rust-analyzer's is a
     * mostly white 600px tile, which on a dark file tree is a white block. A language that declared
     * no icon gets VS Code's own for it, or a drawn monogram; see [CodeBridgeLanguageFileType].
     *
     * Resolved once here rather than per paint, and only ever to a file the extension ships.
     */
    private fun claim(extension: InstalledExtension, contribution: LanguageContribution): Claim {
        val declared = contribution.icon
        return Claim(
            contribution = contribution,
            light = declared?.let { inside(extension.directory, it.light) },
            dark = declared?.let { inside(extension.directory, it.dark) },
        )
    }

    /**
     * [relative] resolved under [root], or null if it does not stay there or is not a file.
     *
     * The path comes out of the extension's own manifest and is untrusted, exactly as the package
     * icon `InstalledExtension.iconFile` resolves is.
     */
    private fun inside(root: Path, relative: String): Path? {
        val resolved = runCatching { root.resolve(relative).normalize() }.getOrNull() ?: return null
        if (!resolved.startsWith(root)) return null
        return resolved.takeIf { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeLanguageFileTypes::class.java)

        fun getInstance(): CodeBridgeLanguageFileTypes =
            ApplicationManager.getApplication().getService(CodeBridgeLanguageFileTypes::class.java)
    }
}
