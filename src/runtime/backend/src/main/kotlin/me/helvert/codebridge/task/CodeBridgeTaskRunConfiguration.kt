package me.helvert.codebridge.task

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import me.helvert.codebridge.language.HostedLanguageServers
import org.jdom.Element
import java.nio.file.Path
import javax.swing.JComponent

class CodeBridgeTaskConfigurationType : ConfigurationTypeBase(
    ID, "VS Code task", "Tasks from .vscode/tasks.json, and the process and shell tasks VS Code extensions run",
    AllIcons.Actions.Execute,
) {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId(): String = ID
        override fun createTemplateConfiguration(project: Project): RunConfiguration = CodeBridgeTaskRunConfiguration(project, this)
    }

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    companion object {
        const val ID = "CodeBridge.Task"
    }
}

/**
 * A VS Code task as a run configuration, by its [source]: a `tasks.json` entry, read again on every
 * run, or a task an extension ran -- a code lens's Run, often -- kept as it was then, the way a
 * gutter icon's run is kept.
 *
 * A task's `dependsOn` run first, in the same run. The extension host hears of every run while one
 * is running, as it would of a task the workbench started. Those are all that run first: it has no
 * Before launch steps of IntelliJ's, as a task in VS Code has nothing like them.
 */
class CodeBridgeTaskRunConfiguration(project: Project, factory: ConfigurationFactory) :
    RunConfigurationBase<Any>(project, factory, "VS Code task"), WithoutOwnBeforeRunSteps {

    var source: TaskSource? = null

    // Dropped, so that none stored before, or set on the template, runs unseen.
    override fun getBeforeRunTasks(): List<BeforeRunTask<*>> = emptyList()
    override fun setBeforeRunTasks(value: List<BeforeRunTask<*>>) = Unit

    /** One of `tasks.json`'s, kept in line with it by [CodeBridgeTaskConfigurations]; a copy of one is someone's own. */
    var managed: Boolean = false

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Editor()

    override fun checkConfiguration() {
        try { resolve() }
        catch (error: IllegalArgumentException) { throw RuntimeConfigurationException(error.message ?: "This task cannot run", error) }
        catch (error: IllegalStateException) { throw RuntimeConfigurationException(error.message ?: "This task cannot run", error) }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val workspace = workspace()
        val task = try { resolve() } catch (error: RuntimeException) { throw ExecutionException(error.message ?: "This task cannot run", error) }
        return object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                // Only the first run is the one the extension asked for; Rerun reuses this environment.
                val pending = environment.getUserData(CodeBridgeTaskRuns.PENDING)
                environment.putUserData(CodeBridgeTaskRuns.PENDING, null)
                val launch = environment.getUserData(CodeBridgeTaskRuns.LAUNCH_READY)
                environment.putUserData(CodeBridgeTaskRuns.LAUNCH_READY, null)
                val handler = try {
                    TaskProcesses.start(workspace, task, launch, project.service<TaskProblems>())
                } catch (error: Exception) {
                    pending?.started?.completeExceptionally(error)
                    launch?.completeExceptionally(error)
                    throw error as? ExecutionException ?: ExecutionException(error.message, error)
                }
                ProcessTerminatedListener.attach(handler)
                project.service<HostedLanguageServers>().tasks?.started(pending?.dto ?: task.dto, handler, pending?.id)
                pending?.started?.complete(Unit)
                return handler
            }
        }
    }

    internal fun resolve(): ResolvedTask {
        val contributed =
            me.helvert.codebridge.extensions.ProjectExtensions.getInstance(project).enabled().map { it.manifest.raw }
        return TaskSources.resolve(workspace(), source ?: throw IllegalStateException("This configuration names no task"),
            TaskProblemMatchers.builtins() + TaskProblemMatchers.contributions(contributed),
            TaskProblemMatchers.builtinPatterns() + TaskProblemMatchers.patternContributions(contributed)
        )
    }

    private fun workspace(): Path = project.basePath?.let(Path::of) ?: throw IllegalStateException("The project has no workspace directory")

    override fun readExternal(element: Element) {
        super.readExternal(element)
        source = element.getChild(TaskSourceXml.ELEMENT)?.let(TaskSourceXml::read)
        managed = element.getAttributeValue(MANAGED) == "true"
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        source?.let { element.addContent(TaskSourceXml.write(it)) }
        if (managed) element.setAttribute(MANAGED, "true")
    }

    private companion object {
        const val MANAGED = "fromTasksJson"
    }

    /**
     * Read-only. A `tasks.json` task is changed in `tasks.json`, which stays the one place it is
     * written; an extension's task is the extension's.
     */
    private class Editor : SettingsEditor<CodeBridgeTaskRunConfiguration>() {
        private val origin = JBLabel()
        private val command = JBLabel()
        private val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Task:", origin)
            .addLabeledComponent("Command:", command)
            .panel

        override fun resetEditorFrom(configuration: CodeBridgeTaskRunConfiguration) {
            origin.text = when (val source = configuration.source) {
                is TaskSource.TasksJson -> "'${source.label}' in ${source.folder?.let { "$it/.vscode/tasks.json" } ?: "the workspace's tasks.json"}"
                is TaskSource.Extension -> "'${source.name}', run by ${source.extensionId ?: "an extension"}"
                null -> "(none)"
            }
            command.text = runCatching { configuration.resolve().command.describe() }.getOrElse { it.message ?: "(cannot run)" }
        }

        override fun applyEditorTo(configuration: CodeBridgeTaskRunConfiguration) = Unit
        override fun createEditor(): JComponent = panel
    }
}

/**
 * A [TaskSource] in a run configuration's XML: ours, element by element, so a saved configuration
 * does not depend on the shape of VS Code's task DTO.
 */
internal object TaskSourceXml {
    const val ELEMENT = "vscode-task"

    fun write(source: TaskSource): Element = Element(ELEMENT).apply {
        when (source) {
            is TaskSource.TasksJson -> {
                setAttribute("from", "tasks.json")
                setAttribute("label", source.label)
                source.folder?.let { setAttribute("folder", it) }
            }
            is TaskSource.Extension -> {
                setAttribute("from", "extension")
                setAttribute("name", source.name)
                source.extensionId?.let { setAttribute("extension", it) }
                addContent(Element("definition").setText(source.definition.toString()))
                addContent(command(source.command))
                source.problemMatchers.forEach { addContent(Element("problemMatcher").setAttribute("name", it)) }
            }
        }
    }

    fun read(element: Element): TaskSource? = when (element.getAttributeValue("from")) {
        "tasks.json" -> element.getAttributeValue("label")?.let { TaskSource.TasksJson(it, element.getAttributeValue("folder")?.takeIf(String::isNotEmpty)) }
        "extension" -> {
            val definition = element.getChildText("definition")
                ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            val command = element.getChild("command")?.let(::command)
            val name = element.getAttributeValue("name")
            if (definition == null || command == null || name == null) null
            else TaskSource.Extension(
                name, element.getAttributeValue("extension"), definition, command,
                element.getChildren("problemMatcher").mapNotNull { it.getAttributeValue("name") })
        }
        else -> null
    }

    private fun command(command: TaskCommand): Element = Element("command").apply {
        command.cwd?.let { setAttribute("cwd", it) }
        when (command) {
            is TaskCommand.Process -> {
                setAttribute("kind", "process")
                setAttribute("program", command.program)
                command.args.forEach { addContent(Element("arg").setAttribute("value", it)) }
            }
            is TaskCommand.Shell -> {
                setAttribute("kind", "shell")
                command.commandLine?.let { setAttribute("commandLine", it) }
                command.executable?.let { setAttribute("executable", it) }
                command.command?.let { addContent(argument("shellCommand", it)) }
                command.args.forEach { addContent(argument("arg", it)) }
                command.shellArgs?.let { args ->
                    addContent(Element("shellArgs").apply { args.forEach { addContent(Element("arg").setAttribute("value", it)) } })
                }
            }
        }
        command.env.forEach { (key, value) ->
            addContent(Element("env").setAttribute("name", key).apply { if (value == null) setAttribute("remove", "true") else setAttribute("value", value) })
        }
    }

    private fun argument(name: String, argument: TaskCommand.Argument) = Element(name).apply {
        setAttribute("value", argument.value)
        argument.quoting?.let { setAttribute("quoting", it.name.lowercase()) }
    }

    private fun command(element: Element): TaskCommand? {
        val cwd = element.getAttributeValue("cwd")
        val env = element.getChildren("env").associate { env ->
            env.getAttributeValue("name") to (if (env.getAttributeValue("remove") == "true") null else env.getAttributeValue("value"))
        }
        return when (element.getAttributeValue("kind")) {
            "process" -> TaskCommand.Process(
                program = element.getAttributeValue("program") ?: return null,
                args = element.getChildren("arg").mapNotNull { it.getAttributeValue("value") },
                cwd = cwd, env = env,
            )
            "shell" -> TaskCommand.Shell(
                commandLine = element.getAttributeValue("commandLine"),
                command = element.getChild("shellCommand")?.let(::argument),
                args = element.getChildren("arg").mapNotNull(::argument),
                executable = element.getAttributeValue("executable"),
                shellArgs = element.getChild("shellArgs")?.getChildren("arg")?.mapNotNull { it.getAttributeValue("value") },
                cwd = cwd, env = env,
            )
            else -> null
        }
    }

    private fun argument(element: Element): TaskCommand.Argument? {
        val value = element.getAttributeValue("value") ?: return null
        val quoting = element.getAttributeValue("quoting")?.let { name -> TaskCommand.Quoting.entries.firstOrNull { it.name.equals(name, true) } }
        return TaskCommand.Argument(value, quoting)
    }
}
