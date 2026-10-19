package me.helvert.codebridge.launch

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import me.helvert.codebridge.completion.CodeBridgeVariableInsertHandler
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.manifest.ContributedSettings
import me.helvert.codebridge.settings.WorkbenchSettings

/**
 * The `${...}` variables of `launch.json` and `tasks.json`, as VS Code completes them: the predefined
 * ones, then what follows `env:`, `config:`, `input:` and `command:` -- the environment, the settings
 * extensions declare, the file's `inputs`, and the variables debuggers declare for their commands.
 *
 * Offered in the files and in a run configuration's form alike.
 */
object LaunchVariables {

    /** A variable being typed: its namespace, null for none yet, and what is typed of its name. */
    data class Typing(val namespace: String?, val prefix: String)

    /** The variable [before] -- the text up to the caret -- ends inside of; null when it ends outside one. */
    fun typing(before: String): Typing? {
        val open = before.lastIndexOf($$"${")
        if (open < 0 || '}' in before.substring(open)) return null
        val typed = before.substring(open + 2)
        return if (':' in typed) Typing(typed.substringBefore(':'), typed.substringAfter(':')) else Typing(null, typed)
    }

    /**
     * What [typing] can be completed with, into [result], whose prefix it sets; false for a namespace
     * there is nothing to offer in. [inputs] are the ids of the `inputs` the text can use.
     */
    fun complete(typing: Typing, result: CompletionResultSet, inputs: () -> List<String>): Boolean {
        val offered = offers(typing.namespace, inputs) ?: return false
        val sink = result.withPrefixMatcher(typing.prefix)
        for ((index, offer) in offered.withIndex()) {
            val element = LookupElementBuilder.create(offer.name)
                .withIcon(AllIcons.Nodes.Variable)
                .withTypeText(offer.description, true)
                .let { if (offer.closes) it.withInsertHandler(CodeBridgeVariableInsertHandler()) else it }
            sink.addElement(PrioritizedLookupElement.withPriority(element, (offered.size - index).toDouble()))
        }
        return true
    }

    /** A whole variable, as it is written, `${input:who}`, and what it is. */
    data class Choice(val text: String, val description: String)

    /**
     * Every variable there is to insert, whole: the predefined ones, the [inputs], then those of
     * the namespaces that list their names -- what commands debuggers declare, settings, and the
     * environment.
     */
    fun choices(inputs: () -> List<String>): List<Choice> =
        listOf(null, "input", "command", "config", "env").flatMap { namespace ->
            offers(namespace, inputs).orEmpty().filter { it.closes }
                .map { offer ->
                    Choice(
                        $$"${$${namespace?.let { "$it:" }.orEmpty()}$${offer.name}}",
                        offer.description
                    )
                }
        }

    private data class Offer(val name: String, val description: String, val closes: Boolean)

    /** What can follow `${` and [namespace]; null for a namespace there is no such list of. */
    private fun offers(namespace: String?, inputs: () -> List<String>): List<Offer>? = when (namespace) {
        null -> PREDEFINED.map { (name, description) -> Offer(name, description, closes = true) } +
                NAMESPACES.map { (name, description) -> Offer("$name:", description, closes = false) }

        "env" -> environmentNames().map { Offer(it, "Environment variable", closes = true) }
        "config" -> settingKeys().map { Offer(it, "Setting", closes = true) }
        "input" -> inputs().map { Offer(it, "Input", closes = true) }
        "command" -> debuggerVariables().map { (name, command) -> Offer(name, command, closes = true) }
        else -> null
    }

    /**
     * The environment's variable names, sorted. Not the `=C:` kind Windows keeps for each drive's
     * current directory, which a process started from `cmd` inherits: no one writes `${env:=C:}`.
     */
    internal fun environmentNames(): List<String> = System.getenv().keys.filterNot { it.startsWith("=") }.sorted()

    private fun installed() = ExtensionInstallationService.getInstance().list().filter { it.enabled }

    private fun settingKeys(): List<String> =
        ContributedSettings.keys(installed().map { it.manifest } + WorkbenchSettings.manifest).sorted()

    private fun debuggerVariables(): List<Pair<String, String>> =
        installed().flatMap { it.manifest.contributes.debuggers }.flatMap { it.variables.toList() }
            .distinctBy { it.first }

    /** VS Code's predefined variables, with its descriptions of them. */
    private val PREDEFINED = listOf(
        "workspaceFolder" to "The path of the folder opened in VS Code",
        "workspaceFolderBasename" to "The name of the folder opened in VS Code without any slashes (/)",
        "file" to "The current opened file",
        "fileWorkspaceFolder" to "The current opened file's workspace folder",
        "relativeFile" to "The current opened file relative to workspaceFolder",
        "relativeFileDirname" to "The current opened file's dirname relative to workspaceFolder",
        "fileBasename" to "The current opened file's basename",
        "fileBasenameNoExtension" to "The current opened file's basename with no file extension",
        "fileExtname" to "The current opened file's extension",
        "fileDirname" to "The current opened file's folder path",
        "fileDirnameBasename" to "The current opened file's folder name",
        "cwd" to "The task runner's current working directory on startup",
        "lineNumber" to "The current selected line number in the active file",
        "columnNumber" to "The current selected column number in the active file",
        "selectedText" to "The current selected text in the active file",
        "execPath" to "The path to the running VS Code executable",
        "defaultBuildTask" to "The name of the default build task",
        "pathSeparator" to "The character used by the operating system to separate components in file paths",
        "userHome" to "The path of the user's home folder",
    )

    private val NAMESPACES = listOf(
        "env" to "An environment variable",
        "config" to "A setting's value",
        "input" to "A value asked of the user, from the file's inputs",
        "command" to "What a command returns",
    )
}
