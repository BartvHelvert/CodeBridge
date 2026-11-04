package me.helvert.codebridge.rpc

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.ConcurrentHashMap

/**
 * Which files a custom editor claims, by its `filenamePattern`s, and which one opens a file by
 * default, as VS Code decides both. Shared by the client, which opens files in custom editors, and
 * the backend, which keeps the files they claim out of the system's hands.
 */
object CustomEditorTypes {
    /** The view type `workbench.editorAssociations` names the text editor by. */
    const val TEXT: String = "default"

    private val matchers = ConcurrentHashMap<String, PathMatcher>()

    fun matches(type: CustomEditorTypeDto, name: String, path: String): Boolean = type.patterns.any { matches(it, name, path) }

    /** Whether [pattern] matches the file: one with no `/` the name alone, any other the whole path. */
    fun matches(pattern: String, name: String, path: String): Boolean {
        val wholePath = '/' in pattern
        val matcher = matchers.computeIfAbsent(pattern) {
            // `**/x` must also match `x` at the top, which a Java glob's `**/` does not.
            FileSystems.getDefault().getPathMatcher("glob:" + if (wholePath) "{${pattern},${pattern.removePrefix("**/")}}" else pattern)
        }
        return runCatching { matcher.matches(Path.of(if (wholePath) path.replace('\\', '/') else name)) }.getOrDefault(false)
    }

    /**
     * The custom editor that opens the file by default, or null for the text editor: the one the
     * user associated with it, the most specific pattern first, and otherwise the first that claims
     * it by default. An association naming an editor no extension contributes is passed over.
     */
    fun defaultFor(setup: CustomEditorSetupDto, name: String, path: String): CustomEditorTypeDto? {
        for (viewType in associations(setup, name, path)) {
            if (viewType == TEXT) return null
            setup.types.firstOrNull { it.viewType == viewType }?.let { return it }
        }
        return setup.types.firstOrNull { it.byDefault && matches(it, name, path) }
    }

    /** What the user associated with the file, the most specific pattern -- the longest -- first. */
    fun associations(setup: CustomEditorSetupDto, name: String, path: String): List<String> =
        setup.associations.entries.filter { matches(it.key, name, path) }.sortedByDescending { it.key.length }.map { it.value }

    /** The pattern *Set Default* associates for a file named [name]: `*.ext`, or the name if it has no extension. */
    fun patternFor(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "*" + name.substring(dot) else name
    }
}
