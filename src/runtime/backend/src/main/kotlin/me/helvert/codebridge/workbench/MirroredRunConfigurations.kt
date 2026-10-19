package me.helvert.codebridge.workbench

import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Run configurations that follow a file of the workspace -- `.vscode/launch.json`, `.vscode/tasks.json`
 * -- and the `.code-workspace` file, which has a section of the same: brought in line as they are
 * saved, and one of [ours] edited, renamed or removed by hand is put back as the files have it.
 *
 * In a multi-root workspace each is in the folder of the workspace folder whose file has it, and the
 * workspace file's own are at the top; see [runFolder]. In a single-folder one there is one file, and
 * no folder.
 */
internal abstract class MirroredRunConfigurations(protected val project: Project, private val file: String) :
    Disposable {

    private val started = AtomicBoolean()
    private val pending = AtomicBoolean()

    /** Whether [settings] is one this keeps in line with the files. */
    protected abstract fun ours(settings: RunnerAndConfigurationSettings): Boolean

    /** Changes only what differs, so that its own changes, heard back, change nothing more. Editor thread. */
    internal abstract fun sync(workspace: Path)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val bus = project.messageBus.connect(this)
        // A workspace file chosen anew lists another set of entries.
        bus.subscribe(CodeBridgeWorkspaceChoice.TOPIC, CodeBridgeWorkspaceChoice.Listener { schedule() })
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any {
                        it.path.replace('\\', '/')
                            .let { path -> path.endsWith("/.vscode/$file") || path.endsWith(".code-workspace") }
                    }) schedule()
            }
        })
        // Someone changed one of ours by hand: it is put back as the file has it.
        bus.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) = changed(settings)
            override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) = changed(settings)
        })
        schedule()
    }

    private fun changed(settings: RunnerAndConfigurationSettings) {
        if (ours(settings)) schedule()
    }

    /** Once per burst of changes, on the editor thread, where the run manager is changed. */
    private fun schedule() {
        if (!pending.compareAndSet(false, true)) return
        Scope.getInstance(project).scope.launch(Dispatchers.EDT) {
            pending.set(false)
            val workspace = project.basePath?.let(Path::of) ?: return@launch
            runCatching { sync(workspace) }.onFailure {
                LOG.warn(
                    "Could not bring run configurations in line with $file",
                    it
                )
            }
        }
    }

    override fun dispose() = Unit

    /** Where a sync runs: on the editor thread, and no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(MirroredRunConfigurations::class.java)

        /** What an entry of the workspace file's own is told apart by, in a multi-root workspace. */
        const val WORKSPACE = "workspace"

        /**
         * The run configuration folder for the entries of [folder]'s file: in a multi-root workspace
         * the workspace folder's name. The workspace's own -- null -- are in none, as is everything
         * in a single-folder workspace.
         */
        fun runFolder(layout: CodeBridgeWorkspace, folder: String?): String? = folder.takeIf { layout.isMultiRoot }

        /** How an entry of [folder]'s file is named: `Name (folder)` in a multi-root workspace, as VS Code names it. */
        fun shown(layout: CodeBridgeWorkspace, folder: String?, name: String): String =
            if (layout.isMultiRoot) "$name (${folder ?: WORKSPACE})" else name
    }
}
