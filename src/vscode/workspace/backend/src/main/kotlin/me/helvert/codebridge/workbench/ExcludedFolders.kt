package me.helvert.codebridge.workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.helvert.codebridge.workspace.WorkspaceFolder
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The directories of workspace folders that VS Code leaves out of search: those `files.exclude` or
 * `search.exclude` name, and, with `search.useIgnoreFiles`, those a `.gitignore` or `.ignore` file
 * ignores -- build output such as Cargo's `target`, whatever the language.
 *
 * Directories only. An ignored file -- a `.env`, a local config -- is still something to search
 * for, and excluding it from the project would hide it from the IDE altogether.
 *
 * [settings] are a folder's effective settings. An excluded directory is not looked inside: as in
 * git, a `!` rule cannot bring back what is under an ignored directory.
 */
internal class ExcludedFolders(
    private val folders: List<WorkspaceFolder>,
    private val settings: (WorkspaceFolder) -> JsonObject,
    /** As git's `core.ignorecase`, which is on wherever the file system ignores case. */
    private val ignoreCase: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {
    private class Rules(val globs: List<WorkspaceGlob>, val useIgnoreFiles: Boolean)

    private val rules = HashMap<WorkspaceFolder, Rules>()

    /** The ignore files each directory holds, read once. */
    private val ignoreFiles = HashMap<Path, List<IgnoreFile>>()

    /** Every excluded directory under [folders], outermost only. */
    fun scan(): Set<Path> {
        val excluded = linkedSetOf<Path>()
        for (folder in folders) {
            if (!Files.isDirectory(folder.path)) continue
            val chains = HashMap<Path, List<IgnoreFile>>()
            Files.walkFileTree(folder.path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val inherited = dir.parent?.let(chains::get).orEmpty()
                    if (dir != folder.path && excluded(folder, dir, inherited)) {
                        excluded.add(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    chains[dir] = inherited + ignoreFilesOf(folder, dir)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    chains.remove(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes) = FileVisitResult.CONTINUE
                override fun visitFileFailed(file: Path, error: IOException) = FileVisitResult.CONTINUE
            })
        }
        return excluded
    }

    /**
     * The outermost directory from its folder down to [dir] that is excluded; null when none is, or
     * [dir] is in no folder. [dir] need not exist: a `target` deleted by `cargo clean` stays excluded
     * for when it is built again.
     */
    fun excludedAt(dir: Path): Path? {
        val path = dir.toAbsolutePath().normalize()
        val folder = folders.filter { path.startsWith(it.path) }.maxByOrNull { it.path.nameCount } ?: return null
        var chain = ignoreFilesOf(folder, folder.path)
        var current = folder.path
        for (name in folder.path.relativize(path)) {
            if (name.toString().isEmpty()) break
            current = current.resolve(name)
            if (excluded(folder, current, chain)) return current
            chain = chain + ignoreFilesOf(folder, current)
        }
        return null
    }

    /** Whether [dir], in [folder] and under the ignore files of [chain], is excluded. */
    private fun excluded(folder: WorkspaceFolder, dir: Path, chain: List<IgnoreFile>): Boolean {
        val rules = rulesOf(folder)
        val relative = folder.path.relativize(dir).toString().replace('\\', '/')
        val absolute = dir.toString().replace('\\', '/')
        if (rules.globs.any { it.matches(relative) || it.matches(absolute) }) return true
        if (!rules.useIgnoreFiles) return false
        // The deepest file with a rule for it decides, and in a file the last rule that matches.
        return chain.fold(null as Boolean?) { verdict, file -> file.verdict(dir) ?: verdict } == true
    }

    private fun rulesOf(folder: WorkspaceFolder): Rules = rules.getOrPut(folder) {
        val effective = settings(folder)
        // Only patterns set to true: one set to an object holds only when a sibling exists, which
        // is a condition on files, and VS Code's folders are never excluded on that.
        val patterns = listOf("files.exclude", "search.exclude").flatMap { key ->
            (effective[key] as? JsonObject)?.filterValues { (it as? JsonPrimitive)?.booleanOrNull == true }?.keys.orEmpty()
        }
        Rules(
            patterns.distinct().mapNotNull { runCatching { WorkspaceGlob(it) }.getOrNull() },
            (effective["search.useIgnoreFiles"] as? JsonPrimitive)?.booleanOrNull != false
        )
    }

    /**
     * The ignore files [dir] holds, least binding first, as ripgrep -- which VS Code searches with --
     * ranks them: the repository's `.git/info/exclude`, then `.gitignore`, then `.ignore`.
     */
    private fun ignoreFilesOf(folder: WorkspaceFolder, dir: Path): List<IgnoreFile> {
        if (!rulesOf(folder).useIgnoreFiles) return emptyList()
        return ignoreFiles.getOrPut(dir) {
            listOf(
                dir.resolve(".git/info/exclude"),
                dir.resolve(".gitignore"),
                dir.resolve(".ignore")
            ).mapNotNull { file ->
                if (!Files.isRegularFile(file)) return@mapNotNull null
                val text = runCatching { Files.readString(file) }.getOrNull() ?: return@mapNotNull null
                IgnoreFile(dir, text, ignoreCase).takeIf { it.hasRules }
            }
        }
    }
}

/**
 * One `.gitignore`-style file, read as git reads it, for the directories under [base], where it is.
 *
 * Every path it is asked about is a directory, so a trailing `/`, which limits a rule to
 * directories, changes nothing. A rule with a `/` before its end is anchored to [base]; one without
 * matches at any depth.
 */
internal class IgnoreFile(private val base: Path, text: String, ignoreCase: Boolean = false) {
    private class Rule(val glob: WorkspaceGlob, val negated: Boolean)

    private val rules: List<Rule> = text.lineSequence().mapNotNull { rule(it, ignoreCase) }.toList()

    val hasRules: Boolean get() = rules.isNotEmpty()

    /** Whether this file ignores [dir]: false when a `!` rule re-includes it, null when no rule names it. */
    fun verdict(dir: Path): Boolean? {
        if (!dir.startsWith(base) || dir == base) return null
        val relative = base.relativize(dir).toString().replace('\\', '/')
        return rules.lastOrNull { it.glob.matches(relative) }?.let { !it.negated }
    }

    private companion object {
        fun rule(line: String, ignoreCase: Boolean): Rule? {
            var text = line.trimEnd('\r')
            // Trailing spaces go, unless the last is escaped.
            while (text.endsWith(' ') && !text.endsWith("\\ ")) text = text.dropLast(1)
            if (text.isEmpty() || text.startsWith('#')) return null
            val negated = text.startsWith('!')
            if (negated) text = text.drop(1)
            text = text.trimEnd('/')
            if (text.isEmpty()) return null
            val anchored = '/' in text
            val pattern = if (anchored) text.removePrefix("/") else "**/$text"
            return runCatching { Rule(WorkspaceGlob(pattern, ignoreCase, gitignore = true), negated) }.getOrNull()
        }
    }
}
