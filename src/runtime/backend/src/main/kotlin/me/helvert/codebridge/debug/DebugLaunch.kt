package me.helvert.codebridge.debug

import me.helvert.codebridge.workspace.VsCodeFiles
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.launch.CompoundConfiguration
import me.helvert.codebridge.launch.InputDefinition
import me.helvert.codebridge.launch.LaunchFile
import me.helvert.codebridge.launch.LaunchJsonParser
import me.helvert.codebridge.workspace.Jsonc
import me.helvert.codebridge.workspace.SimpleSubstitutionContext
import me.helvert.codebridge.workspace.VariableSubstitutor
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Reads and resolves launch configurations.
 *
 * `workspace` is always the project's directory. A `folder` is the name of the workspace folder an
 * entry comes from, in a multi-root workspace -- its `.vscode/launch.json`; null is the workspace's
 * own entries: the `.code-workspace` file's `launch` there, else the one `.vscode/launch.json`.
 */
object DebugLaunch {
    /** The project's workspace, read afresh, as each run reads its configuration afresh. */
    fun layout(workspace: Path): CodeBridgeWorkspace = CodeBridgeWorkspace.of(workspace)

    /** Where [folder]'s entries are written, for messages: `.vscode/launch.json` and the like. */
    fun origin(workspace: Path, folder: String?): String {
        val file = layout(workspace).file
        return when {
            folder != null -> "$folder/.vscode/launch.json"
            file != null -> "the launch section of ${file.fileName}"
            else -> ".vscode/launch.json"
        }
    }

    /** Whether [folder] has launch configurations to read, broken or not; see [DebugLaunch]. */
    fun exists(workspace: Path, folder: String? = null): Boolean {
        val layout = layout(workspace)
        return when {
            !VsCodeFiles.apply() -> false
            folder != null -> layout.folders.firstOrNull { it.name == folder }?.let { Files.isRegularFile(layout.folderFile(it, LAUNCH)) } == true
            layout.isMultiRoot -> layout.section("launch") != null
            else -> Files.isRegularFile(workspace.resolve(".vscode/$LAUNCH"))
        }
    }

    fun read(workspace: Path, folder: String? = null): LaunchFile {
        require(VsCodeFiles.apply()) { "The project's launch.json does not apply without CodeBridge" }
        val layout = layout(workspace)
        val text = when {
            folder != null -> {
                val found = layout.folders.firstOrNull { it.name == folder }
                    ?: throw IllegalArgumentException("There is no workspace folder named '$folder'")
                val path = layout.folderFile(found, LAUNCH)
                require(Files.isRegularFile(path)) { "No .vscode/launch.json in the workspace folder '$folder'" }
                saved(path)
            }
            layout.isMultiRoot -> (layout.section("launch")
                ?: throw IllegalArgumentException("${layout.file?.fileName} has no launch configurations")).toString()
            else -> {
                val path = workspace.resolve(".vscode/$LAUNCH")
                require(Files.isRegularFile(path)) { "No .vscode/launch.json in $workspace" }
                saved(path)
            }
        }
        val result = LaunchJsonParser.parse(text)
        require(result.problems.isEmpty()) { result.problems.joinToString("\n") }
        return result.file
    }

    /**
     * [path]'s text as last saved. That is its document's, when one is loaded and saved: the local
     * file system writes a save out asynchronously, so for a moment after a run configuration's form
     * writes its entry the disk still holds what was there before -- and a rename read back from it
     * was undone. A document with unsaved edits is not what is saved, so the disk is read then, as it
     * is with no IDE about at all.
     */
    private fun saved(path: Path): String {
        if (ApplicationManager.getApplication() == null) return Files.readString(path)
        val manager = FileDocumentManager.getInstance()
        val document = LocalFileSystem.getInstance().findFileByNioFile(path)?.let(manager::getCachedDocument)
        return if (document != null && !manager.isDocumentUnsaved(document)) document.immutableCharSequence.toString()
        else Files.readString(path)
    }

    fun configuration(workspace: Path, name: String, folder: String? = null): JsonObject {
        val matches = read(workspace, folder).configurations.filter { it.name == name }
        require(matches.size == 1) { "Expected one configuration named '$name' in ${origin(workspace, folder)}; found ${matches.size}" }
        return matches.single().raw
    }

    /**
     * A compound by name, each of whose members names exactly one configuration: in the member's
     * own `folder` when it names one, else beside the compound.
     */
    fun compound(workspace: Path, name: String, folder: String? = null): CompoundConfiguration {
        val file = read(workspace, folder)
        val matches = file.compounds.filter { it.name == name }
        require(matches.size == 1) { "Expected one compound named '$name' in ${origin(workspace, folder)}; found ${matches.size}" }
        val compound = matches.single()
        require(compound.members.isNotEmpty()) { "The compound '$name' names no configurations" }
        for ((memberName, memberFolder) in compound.members) {
            val configurations = if (memberFolder == null || memberFolder == folder) file.configurations
                else read(workspace, memberFolder).configurations
            val count = configurations.count { it.name == memberName }
            require(count == 1) { "The compound '$name' names '$memberName', which matches $count configurations" }
        }
        return compound
    }

    /**
     * The settings `${config:...}` reads for [folder]'s entries: the workspace's, and in a multi-root
     * workspace the folder's own over them.
     */
    fun settings(workspace: Path, folder: String? = null): JsonObject {
        if (!VsCodeFiles.apply()) return buildJsonObject { }
        val layout = layout(workspace)
        if (layout.file == null) return settingsFile(workspace.resolve(".vscode/settings.json"))
        val shared = layout.section("settings") ?: buildJsonObject { }
        val own = folder?.let { name -> layout.folders.firstOrNull { it.name == name } }?.let { settingsFile(layout.folderFile(it, "settings.json")) }
        return if (own == null) shared else JsonObject(shared + own)
    }

    private fun settingsFile(path: Path): JsonObject {
        // An empty file is no settings, as VS Code reads it; `code .` leaves one behind.
        val text = if (Files.exists(path)) Jsonc.stripToJson(Files.readString(path)) else ""
        return if (text.isBlank()) buildJsonObject { } else Json.parseToJsonElement(text).jsonObject
    }

    /**
     * The folder [folder]'s entries run in, `${workspaceFolder}`: that folder, or the project's
     * directory in a single-folder workspace. Null for the `.code-workspace` file's own entries,
     * which, as in VS Code, name a folder as `${workspaceFolder:name}`.
     */
    fun folderPath(workspace: Path, folder: String?): Path? {
        val layout = layout(workspace)
        return when {
            folder != null -> layout.folders.firstOrNull { it.name == folder }?.path
            layout.isMultiRoot -> null
            else -> layout.folders.first().path
        }
    }

    fun substitute(config: JsonObject, workspace: Path, settings: JsonObject,
                   command: (String) -> String? = { null }, input: (String) -> String? = { null },
                   editor: EditorContext = EditorContext(), folder: String? = null): JsonObject {
        val layout = layout(workspace)
        val substitutor = VariableSubstitutor(SimpleSubstitutionContext(
            workspaceFolder = folderPath(workspace, folder), namedWorkspaceFolders = layout.folders.associate { it.name to it.path },
            currentFile = editor.file, lineNumber = editor.lineNumber, selectedText = editor.selectedText,
            configuration = { (settings[it] as? JsonPrimitive)?.contentOrNull },
            commands = command, inputs = input,
        ))
        val resolved = substitutor.substitute(config).jsonObject
        require(substitutor.unresolved.isEmpty()) { "Unresolved launch variables: ${substitutor.unresolved.joinToString()}" }
        return resolved
    }

    private const val LAUNCH = "launch.json"

    /** What `${file}`, `${lineNumber}` and `${selectedText}` read: the editor the user was in. */
    data class EditorContext(val file: Path? = null, val lineNumber: Int? = null, val selectedText: String? = null)

    /**
     * A DAP message from the client, with a `launch` or `attach` request's arguments replaced by
     * [configuration] -- the entry as the extension resolved it -- and the session's id added as
     * `__sessionId`, which VS Code sends and adapters running child sessions read back.
     *
     * Keys the client added that the configuration lacks, `noDebug` among them, are kept. Any other
     * message passes through untouched, as text.
     */
    fun launchRequest(json: String, configuration: JsonObject, sessionId: String): String {
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json
        if (message["type"]?.jsonPrimitive?.contentOrNull != "request") return json
        if (message["command"]?.jsonPrimitive?.contentOrNull !in listOf("launch", "attach")) return json
        val sent = message["arguments"] as? JsonObject ?: buildJsonObject { }
        val arguments = buildJsonObject {
            sent.forEach { (key, value) -> if (key !in configuration) put(key, value) }
            configuration.forEach { (key, value) -> put(key, value) }
            put("__sessionId", sessionId)
        }
        return JsonObject(message + ("arguments" to arguments)).toString()
    }

    /** The `seq` of a client's `launch` or `attach` request; null for any other message. */
    fun startRequestSeq(json: String): Int? {
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        if (message["type"]?.jsonPrimitive?.contentOrNull != "request") return null
        if (message["command"]?.jsonPrimitive?.contentOrNull !in listOf("launch", "attach")) return null
        return message["seq"]?.jsonPrimitive?.intOrNull
    }

    /**
     * Why the adapter refused to start the program, when [json] is its failed answer to request
     * [seq]; null for any other message.
     *
     * An adapter may refuse before it sends `initialized` -- CodeLLDB does when the program does not
     * exist -- and a client that waits for `initialized` before it reads that answer, as IntelliJ's
     * does, waits forever. The command is not checked: CodeLLDB answers with an empty one.
     */
    fun startFailure(json: String, seq: Int): String? {
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        if (message["type"]?.jsonPrimitive?.contentOrNull != "response") return null
        if (message["request_seq"]?.jsonPrimitive?.intOrNull != seq) return null
        if (message["success"]?.jsonPrimitive?.booleanOrNull != false) return null
        val error = ((message["body"] as? JsonObject)?.get("error") as? JsonObject)?.get("format")?.jsonPrimitive?.contentOrNull
        return message["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error ?: "The debug adapter could not start the program"
    }

    /**
     * Whether [json] is the adapter's `exited` or `terminated` event: the program, or the session,
     * has ended. A client stops showing the program's output at either.
     */
    fun isEnding(json: String): Boolean {
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
        return message["type"]?.jsonPrimitive?.contentOrNull == "event" && message["event"]?.jsonPrimitive?.contentOrNull in ENDING
    }

    private val ENDING = setOf("exited", "terminated")

    /**
     * [json], with a `terminated` event's `restart` dropped when it asks for none.
     *
     * VS Code restarts a session only on a truthy `restart`. IntelliJ's client takes any value as a
     * request to restart, which it does not do, and so never stops the session -- and java-debug
     * ends each one with `"restart": false`: a test run that had finished kept running.
     */
    fun withoutFalseRestart(json: String): String {
        if (!json.contains("\"restart\"")) return json
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json
        if (message["type"]?.jsonPrimitive?.contentOrNull != "event" || message["event"]?.jsonPrimitive?.contentOrNull != "terminated") return json
        val body = message["body"] as? JsonObject ?: return json
        val restart = body["restart"] ?: return json
        val asked = when (restart) {
            JsonNull -> false
            is JsonPrimitive -> if (restart.isString) restart.content.isNotEmpty()
            else restart.booleanOrNull ?: (restart.doubleOrNull != 0.0)

            else -> true
        }
        if (asked) return json
        return JsonObject(message + ("body" to JsonObject(body - "restart"))).toString()
    }

    /**
     * [json], with a request's `source.path` written as the system writes paths -- with [separator].
     *
     * VS Code sends a file's native path. IntelliJ's client sends `C:/dir/File.java`, which an
     * adapter can take for a URI with the scheme `C`: java-debug does, finds no such file, and so
     * never verifies a breakpoint in it.
     */
    fun withNativeSourcePath(json: String, separator: Char = java.io.File.separatorChar): String {
        if (separator != '\\' || !json.contains("\"source\"")) return json
        val message = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json
        if (message["type"]?.jsonPrimitive?.contentOrNull != "request") return json
        val arguments = message["arguments"] as? JsonObject ?: return json
        val source = arguments["source"] as? JsonObject ?: return json
        val path = (source["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return json
        // A drive or UNC path; anything else, a URI among them, is left as it is.
        if (!WINDOWS_PATH.matches(path) || !path.contains('/')) return json
        val native = JsonObject(source + ("path" to JsonPrimitive(path.replace('/', '\\'))))
        return JsonObject(message + ("arguments" to JsonObject(arguments + ("source" to native)))).toString()
    }

    private val WINDOWS_PATH = Regex("""^([A-Za-z]:[\\/]|[\\/]{2}[^\\/]).*""")

    /** What ends a session whose start failed: the reason in its console, then `terminated`. */
    fun startFailedEvents(reason: String): List<String> = listOf(
        buildJsonObject {
            put("seq", 0); put("type", "event"); put("event", "output")
            put("body", buildJsonObject { put("category", "stderr"); put("output", "$reason\n") })
        }.toString(),
        buildJsonObject { put("seq", 0); put("type", "event"); put("event", "terminated") }.toString(),
    )

    /**
     * [resolved] once variables are substituted: only then must `debugServer` be the port number
     * the extension host connects to in place of the extension's own adapter. A string there is
     * silently ignored by the host, so it is refused instead.
     */
    fun validate(config: JsonObject, resolved: Boolean = false) {
        require(config["type"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) { "Missing debugger type" }
        require(config["request"]?.jsonPrimitive?.content in listOf("launch", "attach")) { "Only launch and attach requests are supported" }
        for (key in listOf("preLaunchTask", "postDebugTask")) {
            val value = config[key]
            require(value == null || value == JsonNull || (value as? JsonPrimitive)?.isString == true) { "$key must name a task" }
        }
        val server = config["debugServer"]
        if (resolved && server != null && server != JsonNull) {
            require((server as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull in 1..65535) { "debugServer must be a port number" }
        }
    }

    /** The task to run once a session of [config] has ended, as VS Code runs it. */
    fun postDebugTask(config: JsonObject): String? = (config["postDebugTask"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** How the user answers an input; null when they cancel, which cancels the launch. */
    interface InputPrompts {
        fun text(input: InputDefinition.PromptString): String?
        fun pick(input: InputDefinition.PickString): String?
        fun command(input: InputDefinition.Command): String?
    }

    /**
     * A launch that is not to start, which VS Code takes as "do not start" rather than as an error:
     * the user dismissed an input, a `${command:...}` -- a process picker -- answered nothing, or an
     * extension's debug configuration provider resolved the configuration to nothing.
     */
    class LaunchCanceledException(id: String) : RuntimeException("The launch was canceled at '$id'")

    /**
     * `${input:id}` for one launch: each input is asked once, however often it is used, in the
     * order the configuration uses them. An id `launch.json` does not define stays unresolved.
     */
    fun inputs(definitions: List<InputDefinition>, prompts: InputPrompts): (String) -> String? {
        val answers = mutableMapOf<String, String>()
        return { id ->
            answers[id] ?: definitions.firstOrNull { it.id == id }?.let { input ->
                val answer = when (input) {
                    is InputDefinition.PromptString -> prompts.text(input)
                    is InputDefinition.PickString -> prompts.pick(input)
                    is InputDefinition.Command -> prompts.command(input)
                } ?: throw LaunchCanceledException(id)
                answer.also { answers[id] = it }
            }
        }
    }

    fun selectExtensions(type: String, installed: List<InstalledExtension>): List<InstalledExtension> {
        val owners = installed.filter { it.manifest.contributes.debugger(type) != null }
        require(owners.size == 1) {
            if (owners.isEmpty()) "No installed extension provides debugger '$type'. Install its VSIX in Settings → Tools → CodeBridge."
            else "Multiple installed extensions provide debugger '$type': ${owners.joinToString { it.id }}"
        }
        val byId = installed.associateBy { it.id }
        val result = linkedMapOf<String, InstalledExtension>()
        fun add(extension: InstalledExtension) {
            if (result.putIfAbsent(extension.id, extension) != null) return
            for (id in extension.manifest.extensionDependencies) {
                add(byId[id.lowercase(Locale.ROOT)] ?: error("Missing dependency '$id' required by ${extension.id}; install its VSIX first"))
            }
        }
        add(owners.single())
        return result.values.toList()
    }

    /** Preserve adapter-specific values, including nested arrays, booleans and explicit nulls. */
    fun arguments(config: JsonObject): Map<String, Any?> = config.mapValues { convert(it.value) }
    private fun convert(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { convert(it.value) }
        is JsonArray -> value.map(::convert)
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.double
    }
}
