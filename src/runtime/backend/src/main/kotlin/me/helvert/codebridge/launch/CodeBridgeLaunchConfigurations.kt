package me.helvert.codebridge.launch

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.serialization.json.JsonPrimitive
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.workbench.MirroredRunConfigurations
import java.nio.file.Path

/** Brings the run configurations in line with `launch.json` once the project is open. */
internal class CodeBridgeLaunchConfigurationsActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeLaunchConfigurations>().start()
}

/**
 * Every configuration and compound of `launch.json` as a run configuration, as VS Code lists them in
 * its Run and Debug view -- in a multi-root workspace, those of every folder and of the
 * `.code-workspace` file, each in the folder it comes from; see [LaunchEntries] and
 * [MirroredRunConfigurations]. A configuration someone made by hand is left alone.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeLaunchConfigurations(project: Project) : MirroredRunConfigurations(project, "launch.json") {

    override fun ours(settings: RunnerAndConfigurationSettings): Boolean =
        (settings.configuration as? CodeBridgeDebugRunConfiguration)?.managed == true

    override fun sync(workspace: Path) {
        val runManager = RunManager.getInstance(project)
        // What a form changed is written first, so that the file then has it.
        for (settings in runManager.allSettings) write(workspace, settings.configuration as? CodeBridgeDebugRunConfiguration ?: continue)
        // A file that does not parse -- half-way through an edit -- leaves things as they were.
        val wanted = LaunchEntries.of(workspace) ?: return
        val layout = DebugLaunch.layout(workspace)
        val type = ConfigurationTypeUtil.findConfigurationType(CodeBridgeDebugConfigurationType::class.java)
        val ours = mutableMapOf<DebugSource, RunnerAndConfigurationSettings>()
        for (settings in runManager.allSettings) {
            val configuration = settings.configuration as? CodeBridgeDebugRunConfiguration ?: continue
            val source = configuration.source
            when {
                !configuration.managed -> {
                    // A run a code lens started, for an entry now listed: the listed one serves it.
                    if (settings.isTemporary && source != null && wanted.any { it.first == source }) runManager.removeConfiguration(settings)
                }
                source == null || wanted.none { it.first == source } -> runManager.removeConfiguration(settings)
                // Its entry turned from launch to attach or back: made again by the other factory.
                (configuration is CodeBridgeAttachRunConfiguration) != DebugSource.attaches(source, workspace) -> runManager.removeConfiguration(settings)
                // A copy of one of ours is someone's own now.
                ours.putIfAbsent(source, settings) != null -> configuration.managed = false
            }
        }
        for ((source, name) in wanted) {
            val folder = runFolder(layout, (source as? DebugSource.LaunchJson)?.folder ?: (source as? DebugSource.Compound)?.folder)
            val settings = ours[source] ?: runManager.createConfiguration(name, type.factory(DebugSource.attaches(source, workspace))).also { created ->
                (created.configuration as CodeBridgeDebugRunConfiguration).apply { this.source = source; managed = true }
                created.folderName = folder
                runManager.addConfiguration(created)
                ours[source] = created
            }
            if (settings.name != name) settings.name = name
            if (settings.folderName != folder) settings.folderName = folder
            if (settings.isTemporary) runManager.makeStable(settings)
        }
        if (runManager.selectedConfiguration == null) wanted.firstOrNull()?.let { (source, _) -> runManager.selectedConfiguration = ours[source] }
    }

    /**
     * Writes what [configuration]'s form changed to its entry; a rename follows the entry to its new
     * name. One the file refuses is said so, and the configuration is put back as the file has it.
     */
    private fun write(workspace: Path, configuration: CodeBridgeDebugRunConfiguration) {
        val changes = configuration.pending ?: return
        configuration.pending = null
        val source = configuration.source
        if (!configuration.managed) return
        val (section, name, folder) = when (source) {
            is DebugSource.LaunchJson -> Triple(LaunchJsonEdit.Section.CONFIGURATIONS, source.name, source.folder)
            is DebugSource.Compound -> Triple(LaunchJsonEdit.Section.COMPOUNDS, source.name, source.folder)
            else -> return
        }
        try {
            LaunchJsonFiles.change(project, workspace, folder, section, name, changes)
        } catch (error: Exception) {
            if (error is ProcessCanceledException) throw error
            Notification(NOTIFICATIONS, "Could not change '$name' in ${DebugLaunch.origin(workspace, folder)}",
                error.message.orEmpty(), NotificationType.ERROR).notify(project)
            return
        }
        val renamed = (changes.set["name"] as? JsonPrimitive)?.content ?: return
        // The IDE takes the earlier checks to rule out the other sources; the compiler does not.
        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        configuration.source = when (source) {
            is DebugSource.LaunchJson -> source.copy(name = renamed)
            is DebugSource.Compound -> source.copy(name = renamed)
            else -> source
        }
    }

    private companion object {
        const val NOTIFICATIONS = "CodeBridge launch.json"
    }
}
