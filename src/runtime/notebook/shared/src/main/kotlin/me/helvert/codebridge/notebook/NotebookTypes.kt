package me.helvert.codebridge.notebook

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * Which notebook type a file opens as, by its `filenamePattern`s, as VS Code picks: a pattern with
 * no `/` is matched against the name alone, any other against the whole path.
 */
object NotebookTypes {
    private val matchers = ConcurrentHashMap<String, PathMatcher>()

    /** The first of [types] that opens a file called [name] at [path] by default, or null. */
    fun forFile(types: List<NotebookTypeDto>, name: String, path: String): NotebookTypeDto? =
        types.firstOrNull { type -> type.opensByDefault && matches(type, name, path) }

    fun matches(type: NotebookTypeDto, name: String, path: String): Boolean = type.patterns.any { pattern ->
        val wholePath = '/' in pattern
        val matcher = matchers.computeIfAbsent(pattern) {
            // `**/x` must also match `x` at the top, which a Java glob's `**/` does not.
            FileSystems.getDefault().getPathMatcher("glob:" + if (wholePath) "{${pattern},${pattern.removePrefix("**/")}}" else pattern)
        }
        runCatching { matcher.matches(Path.of(if (wholePath) path.replace('\\', '/') else name)) }.getOrDefault(false)
    }
}
