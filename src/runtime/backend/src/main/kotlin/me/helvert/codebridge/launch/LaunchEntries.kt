package me.helvert.codebridge.launch

import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.workbench.MirroredRunConfigurations
import java.nio.file.Path

/**
 * Every configuration and compound the workspace lists, as VS Code lists them in its Run and Debug
 * view.
 *
 * In a single-folder workspace that is `.vscode/launch.json`, its entries by name. In a multi-root
 * one, the `.code-workspace` file's `launch` and each folder's `.vscode/launch.json`, an entry named
 * `Name (folder)`, or `Name (workspace)` for the file's own.
 */
internal object LaunchEntries {

    /** By source, with the name each is shown by; null when a file that has entries does not parse now. */
    fun of(workspace: Path): List<Pair<DebugSource, String>>? {
        val layout = DebugLaunch.layout(workspace)
        val origins = listOf<String?>(null) + if (layout.isMultiRoot) layout.folders.map { it.name } else emptyList()
        return origins.filter { DebugLaunch.exists(workspace, it) }.flatMap { folder ->
            // A file half-way through an edit: nothing is known of any entry until it parses again.
            val file = runCatching { DebugLaunch.read(workspace, folder) }.getOrElse { return null }
            val configurations = file.configurations.map { it.name }.distinct()
            val compounds = file.compounds.map { it.name }.distinct()
            // A compound named like a configuration is told apart.
            configurations.map { DebugSource.LaunchJson(it, folder) to MirroredRunConfigurations.shown(layout, folder, it) } +
                compounds.map { DebugSource.Compound(it, folder) to MirroredRunConfigurations.shown(layout, folder, if (it in configurations) "$it (compound)" else it) }
        }
    }
}
