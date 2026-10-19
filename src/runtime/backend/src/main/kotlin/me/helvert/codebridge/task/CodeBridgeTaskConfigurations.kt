package me.helvert.codebridge.task

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import me.helvert.codebridge.workbench.MirroredRunConfigurations
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Path

/** Brings the run configurations in line with `tasks.json` once the project is open. */
internal class CodeBridgeTaskConfigurationsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeTaskConfigurations>().start()
}

/**
 * Every task of `tasks.json` as a run configuration, as VS Code offers them in Run Task -- in a
 * multi-root workspace, those of every folder and of the `.code-workspace` file, each in the folder
 * it comes from; see [TaskSources.listed] and [MirroredRunConfigurations]. A task an extension ran
 * keeps its temporary configuration, as [CodeBridgeTaskRuns] makes it.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeTaskConfigurations(project: Project) : MirroredRunConfigurations(project, "tasks.json") {

    override fun ours(settings: RunnerAndConfigurationSettings): Boolean =
        (settings.configuration as? CodeBridgeTaskRunConfiguration)?.managed == true

    override fun sync(workspace: Path) {
        // A file that does not parse -- half-way through an edit -- leaves things as they were.
        val wanted = TaskSources.listed(workspace) ?: return
        val layout = CodeBridgeWorkspace.of(workspace)
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(CodeBridgeTaskConfigurationType::class.java)
        val ours = mutableMapOf<TaskSource, RunnerAndConfigurationSettings>()
        for (settings in runManager.allSettings) {
            val configuration = settings.configuration as? CodeBridgeTaskRunConfiguration ?: continue
            val source = configuration.source
            when {
                !configuration.managed -> {
                    // A run of a listed task, from before it was listed: the listed one serves it.
                    if (settings.isTemporary && source != null && TaskSources.served(workspace, source) in wanted) runManager.removeConfiguration(settings)
                }
                source == null || source !in wanted -> runManager.removeConfiguration(settings)
                // A copy of one of ours is someone's own now.
                ours.putIfAbsent(source, settings) != null -> configuration.managed = false
            }
        }
        for (source in wanted) {
            val name = shown(layout, source.folder, source.label)
            val folder = runFolder(layout, source.folder)
            val settings = ours[source] ?: runManager.createConfiguration(name, type.configurationFactories.single()).also { created ->
                (created.configuration as CodeBridgeTaskRunConfiguration).apply { this.source = source; managed = true }
                created.folderName = folder
                runManager.addConfiguration(created)
                ours[source] = created
            }
            if (settings.name != name) settings.name = name
            if (settings.folderName != folder) settings.folderName = folder
            if (settings.isTemporary) runManager.makeStable(settings)
        }
    }
}
