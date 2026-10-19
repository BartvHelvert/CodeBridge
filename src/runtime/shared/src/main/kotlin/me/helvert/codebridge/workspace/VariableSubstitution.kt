package me.helvert.codebridge.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Path

/**
 * Supplies the values that VS Code's `${...}` variables expand to.
 *
 * `command` and `input` are separated out because they are the two resolvers that may need to
 * prompt the user or call into an extension, and are therefore the two that can legitimately
 * fail. Returning `null` from any resolver leaves the variable unexpanded and records it in
 * [VariableSubstitutor.unresolved].
 */
interface SubstitutionContext {
    val workspaceFolder: Path?
    val namedWorkspaceFolders: Map<String, Path>
    val currentFile: Path?
    val lineNumber: Int?
    val selectedText: String?
    val execPath: Path?

    fun env(name: String): String?
    fun config(key: String): String?
    fun command(id: String): String?
    fun input(id: String): String?
}

data class SimpleSubstitutionContext(
    override val workspaceFolder: Path? = null,
    override val namedWorkspaceFolders: Map<String, Path> = emptyMap(),
    override val currentFile: Path? = null,
    override val lineNumber: Int? = null,
    override val selectedText: String? = null,
    override val execPath: Path? = null,
    val environment: (String) -> String? = { System.getenv(it) },
    val configuration: (String) -> String? = { null },
    val commands: (String) -> String? = { null },
    val inputs: (String) -> String? = { null },
) : SubstitutionContext {
    override fun env(name: String): String? = environment(name)
    override fun config(key: String): String? = configuration(key)
    override fun command(id: String): String? = commands(id)
    override fun input(id: String): String? = inputs(id)
}

/**
 * Expands VS Code's `${...}` variables.
 *
 * One instance represents one resolution pass, so that [unresolved] describes exactly that pass.
 * Substitution is a single left-to-right scan and results are not re-scanned, matching VS Code:
 * a value that expands to something containing `${...}` is left alone.
 */
class VariableSubstitutor(private val context: SubstitutionContext) {


    /** Variable expressions that no resolver could supply a value for, in encounter order. */
    val unresolved: Set<String> field = LinkedHashSet()

    fun substitute(text: String): String = VARIABLE.replace(text) { match ->
        val expression = match.groupValues[1]
        resolve(expression) ?: run {
            unresolved += expression
            match.value
        }
    }

    /** Recursively substitutes into every string value. Object keys are left untouched. */
    fun substitute(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> substitute(value) })
        is JsonArray -> JsonArray(element.map { substitute(it) })
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(substitute(element.content)) else element
    }


    private fun resolve(expression: String): String? {
        val separator = expression.indexOf(':')
        if (separator >= 0) {
            val prefix = expression.substring(0, separator)
            val argument = expression.substring(separator + 1)
            when (prefix) {
                // VS Code expands an undefined environment variable to the empty string.
                "env" -> return context.env(argument) ?: ""
                "config" -> return context.config(argument)
                "command" -> return context.command(argument)
                "input" -> return context.input(argument)
                "workspaceFolder" -> return context.namedWorkspaceFolders[argument]?.toString()
            }
        }
        return resolveSimple(expression)
    }

    private fun resolveSimple(name: String): String? {
        val workspace = context.workspaceFolder
        val file = context.currentFile
        return when (name) {
            "workspaceFolder" -> workspace?.toString()
            "workspaceFolderBasename" -> workspace?.fileName?.toString()
            "cwd" -> workspace?.toString()
            "file" -> file?.toString()
            "fileWorkspaceFolder" -> workspace?.toString()
            "fileBasename" -> file?.fileName?.toString()
            "fileBasenameNoExtension" -> file?.fileName?.toString()?.substringBeforeLast('.')
            "fileExtname" -> file?.fileName?.toString()?.let { basename ->
                if (basename.contains('.')) "." + basename.substringAfterLast('.') else ""
            }

            "fileDirname" -> file?.parent?.toString()
            "fileDirnameBasename" -> file?.parent?.fileName?.toString()
            "relativeFile" -> relativize(workspace, file)
            "relativeFileDirname" -> relativize(workspace, file?.parent)
            "lineNumber" -> context.lineNumber?.toString()
            "selectedText" -> context.selectedText
            "execPath" -> context.execPath?.toString()
            "pathSeparator", "/" -> File.separator
            "userHome" -> System.getProperty("user.home")
            else -> null
        }
    }

    /** VS Code falls back to the absolute path when the file lies outside the workspace folder. */
    private fun relativize(workspace: Path?, path: Path?): String? {
        if (path == null) return null
        if (workspace == null) return path.toString()
        return try {
            val relative = workspace.relativize(path).toString()
            if (relative.startsWith("..")) path.toString() else relative
        } catch (_: IllegalArgumentException) {
            path.toString()
        }
    }

    companion object {
        private val VARIABLE = Regex("""\$\{([^{}]*)}""")

        /** Variable expressions in [text], without expanding anything. */
        fun findVariables(text: String): List<String> =
            VARIABLE.findAll(text).map { it.groupValues[1] }.toList()

        /** Variable expressions anywhere in [element], without expanding anything. */
        fun findVariables(element: JsonElement): List<String> = when (element) {
            is JsonObject -> element.values.flatMap { findVariables(it) }
            is JsonArray -> element.flatMap { findVariables(it) }
            is JsonPrimitive -> if (element.isString) findVariables(element.content) else emptyList()
        }
    }
}
