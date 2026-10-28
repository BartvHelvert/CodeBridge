package me.helvert.codebridge.launch

import com.intellij.execution.ExecutionException
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.debug.HostedDebugSession
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.language.CodeBridgeTextEditors
import me.helvert.codebridge.language.HostedLanguageServers
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickItemDto
import me.helvert.codebridge.workbench.HostedQuickInputs
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * A launch resolved as VS Code resolves one before it starts a session: the debugger's extension
 * activated, its configuration providers asked, its variables substituted -- `launch.json`'s inputs
 * asked of the user. [prepare] throws [DebugLaunch.LaunchCanceledException] for a launch that is not
 * to start.
 */
internal class LaunchPreparation(private val project: Project) {

    /** [environment]'s configuration, resolved in a session of its debugger's, which the caller closes. */
    suspend fun prepare(environment: ExecutionEnvironment): HostedDebugSession {
        val profile = environment.runProfile as? CodeBridgeDebugRunConfiguration
            ?: throw ExecutionException("Unsupported launch profile")
        val workspace = profile.workspace()
            ?: throw ExecutionException("The project has no workspace directory")
        val folder = folder(profile)
        val raw = raw(environment)
        val editor = editorContext()
        // As in VS Code, launch.json's inputs serve whatever configuration is started, an extension's too.
        val inputs = runCatching { DebugLaunch.read(workspace, folder).inputs }.getOrDefault(emptyList())
        val settings = DebugLaunch.settings(workspace, folder)
        // The session's folder: the entry's, or the first for one the workspace file holds.
        val sessionFolder = DebugLaunch.folderPath(workspace, folder) ?: DebugLaunch.layout(workspace).folders.first().path
        val extensions = ExtensionInstallationService.getInstance().list()
        val selected = DebugLaunch.selectExtensions(raw["type"]?.jsonPrimitive?.contentOrNull ?: "", extensions)
        val session = project.service<HostedLanguageServers>().debugSession(selected, sessionFolder, settings,
            install = { path -> ExtensionInstallationService.getInstance().install(path) { }.extension })
        try {
            val input = DebugLaunch.inputs(inputs, Prompts(session))
            // Blocking, as the variable substitution it calls back into is.
            withContext(Dispatchers.IO) {
                session.prepare(raw, { config, command -> DebugLaunch.substitute(config, workspace, settings, command, input, editor, folder) },
                    parent = environment.getUserData(CodeBridgeStartDebugging.PARENT))
            }
            return session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    /**
     * `launch.json`'s `inputs`, asked in front of the user: drawn by the frontend, on the
     * client's screen, and waited for. Canceling one cancels the launch, as it does in VS Code.
     */
    private inner class Prompts(private val session: HostedDebugSession) : DebugLaunch.InputPrompts {
        override fun text(input: InputDefinition.PromptString): String? = ask(QuickInputDto(
            id = 0, kind = QuickInputDto.INPUT, title = TITLE, prompt = input.description ?: input.id,
            value = input.default.orEmpty(), password = input.password,
        ))?.value

        override fun pick(input: InputDefinition.PickString): String? {
            if (input.options.isEmpty()) return null
            val chosen = ask(QuickInputDto(
                id = 0, kind = QuickInputDto.PICK, title = TITLE, placeholder = input.description ?: input.id,
                items = input.options.map { QuickItemDto(it.label, picked = it.value == input.default) },
            ))?.chosen?.firstOrNull() ?: return null
            return input.options.getOrNull(chosen)?.value
        }

        override fun command(input: InputDefinition.Command): String? = session.executeCommand(input.command, input.args)

        private fun ask(question: QuickInputDto): HostedQuickInputs.Answer? =
            runBlockingMaybeCancellable { HostedQuickInputs.getInstance(project).ask(question).answer() }
    }

    /**
     * The file and line the user was in, for `${file}` and its kin. Read as the split-mode
     * client, whose editor is the one in front of the user; empty when there is none.
     */
    private suspend fun editorContext(): DebugLaunch.EditorContext {
        var context = DebugLaunch.EditorContext()
        try {
            withTimeoutOrNull(5.seconds) {
                CodeBridgeTextEditors.getInstance(project).asClient {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@asClient
                    val file = FileDocumentManager.getInstance().getFile(editor.document)
                    context = DebugLaunch.EditorContext(
                        file = file?.takeIf { it.isInLocalFileSystem }?.toNioPath(),
                        lineNumber = editor.caretModel.logicalPosition.line + 1,
                        selectedText = editor.selectionModel.selectedText,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        return context
    }

    companion object {
        private const val TITLE = "Launch Input"

        /** The workspace folder [profile]'s entry is in, in a multi-root workspace; see [DebugLaunch]. */
        fun folder(profile: CodeBridgeDebugRunConfiguration): String? = (profile.source as? DebugSource.LaunchJson)?.folder

        /** [environment]'s configuration as written. Run is VS Code's Run Without Debugging, which the extension's providers get to see. */
        fun raw(environment: ExecutionEnvironment): JsonObject {
            val raw = (environment.runProfile as CodeBridgeDebugRunConfiguration).rawConfiguration()
            return if (environment.executor.id == DefaultRunExecutor.EXECUTOR_ID) JsonObject(raw + ("noDebug" to JsonPrimitive(true))) else raw
        }
    }
}
