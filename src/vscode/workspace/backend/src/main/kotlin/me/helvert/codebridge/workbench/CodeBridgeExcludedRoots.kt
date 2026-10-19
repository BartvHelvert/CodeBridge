package me.helvert.codebridge.workbench

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.*
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.settings.WorkbenchSettings
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration.Companion.milliseconds

/** [CodeBridgeExcludedFolders]' directories, excluded from the project. */
internal class CodeBridgeExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> = CodeBridgeExcludedFolders.getInstance(project).urls()
}

/**
 * The directories VS Code leaves out of search, excluded from the project so the IDE neither
 * indexes nor searches them either; see [ExcludedFolders] for which they are.
 *
 * Nothing is written to the project's model: the user's own marks stay theirs, and these follow the
 * settings and ignore files they come from. They are saved with the project, so a reopened project
 * has them before its first scan, and found again in the background each time it opens.
 *
 * Read again when an ignore file, a settings file or the workspace changes; a directory created
 * since -- `target`, the first time a crate is built -- is checked as it appears.
 */
@Service(Service.Level.PROJECT)
@State(name = "CodeBridgeExcludedFolders", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class CodeBridgeExcludedFolders(private val project: Project, private val scope: CoroutineScope) :
    PersistentStateComponent<CodeBridgeExcludedFolders.Saved> {

    class Saved {
        var paths: MutableList<String> = mutableListOf()
    }

    @Volatile
    private var excluded: Set<Path> = emptySet()
    @Volatile
    private var urls: Array<String> = emptyArray()

    /** One scan or check at a time, so each starts from what the one before it found. */
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private var pendingScan: Job? = null

    /** After [loadState], so the first scan starts from what was saved. */
    override fun initializeComponent() {
        project.messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = changed(events)
        })
        project.messageBus.connect(scope).subscribe(CodeBridgeWorkspaceChoice.TOPIC, CodeBridgeWorkspaceChoice.Listener { rescan() })
        rescan(0)
    }

    fun urls(): Array<String> = urls

    /** Finds the directories again, after [delayMillis], replacing a scan already waiting. */
    @Synchronized
    fun rescan(delayMillis: Long = 300) {
        pendingScan?.cancel()
        pendingScan = scope.launch(worker) {
            delay(delayMillis.milliseconds)
            scan()
        }
    }

    private fun changed(events: List<VFileEvent>) {
        val base = base() ?: return
        val created = mutableListOf<Path>()
        for (event in events) {
            val path = runCatching { Path.of(event.path) }.getOrNull() ?: continue
            val name = path.fileName?.toString() ?: continue
            when {
                CodeBridgeUserSettings.isFile(event.path) -> return rescan()
                !path.startsWith(base) -> continue
                name in IGNORE_FILES || path.endsWith(".git/info/exclude") -> return rescan()
                name == "settings.json" && path.parent?.fileName?.toString() == ".vscode" -> return rescan()
                CodeBridgeWorkspace.isWorkspaceFile(path) && path.parent == base -> return rescan()
                // A directory renamed or moved may now match a rule, or stop matching one.
                event is VFilePropertyChangeEvent && event.isRename && event.file.isDirectory -> return rescan()
                event is VFileMoveEvent && event.file.isDirectory -> return rescan()
                event is VFileCreateEvent && event.isDirectory && excluded.none(path::startsWith) -> created.add(path)
            }
        }
        if (created.isNotEmpty()) scope.launch(worker) { check(created) }
    }

    /** Excludes those of [created] that should be, without reading every folder again. */
    private fun check(created: List<Path>) {
        val folders = folders() ?: return
        val found = created.mapNotNullTo(linkedSetOf()) { folders.excludedAt(it) }
        if (found.any { dir -> excluded.none(dir::startsWith) }) update(outermost(excluded + found))
    }

    private fun scan() {
        if (project.isDisposed) return
        val folders = folders() ?: return
        val found = try {
            folders.scan()
        } catch (error: Exception) {
            LOG.warn("Could not find the folders VS Code would leave out of search", error)
            return
        }
        // Gone since, as `target` after `cargo clean`, but excluded still for when it comes back.
        val gone = excluded.filter { !Files.exists(it) && folders.excludedAt(it) == it }
        update(outermost(found + gone))
    }

    @Synchronized
    private fun update(next: Set<Path>) {
        if (next == excluded) return
        LOG.info("Excluded folders: ${next.joinToString { it.toString() }}")
        excluded = next
        urls = urlsOf(next)
        scope.launch(Dispatchers.EDT) {
            WriteAction.run<RuntimeException> {
                ProjectRootManagerEx.getInstanceEx(project)
                    .makeRootsChange(
                        EmptyRunnable.getInstance(),
                        RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED
                    )
            }
        }
    }

    /**
     * The scanner for the project's workspace folders inside its directory, each with its effective
     * settings; null for a project with no directory. Folders outside it are libraries, not content.
     */
    private fun folders(): ExcludedFolders? {
        val base = base() ?: return null
        val layout = CodeBridgeWorkspace.of(base)
        val declared = listOf(WorkbenchSettings.raw)
        val configuration = HostedConfiguration(
            base,
            HostedConfiguration.defaults(declared),
            { _, _, _ -> },
            { LOG.warn("Could not read settings", it) },
            userFile = CodeBridgeUserSettings.file,
            initialLayout = layout,
            folderScoped = HostedConfiguration.folderScoped(declared)
        )
        return ExcludedFolders(layout.folders.filter { it.path.startsWith(base) }, configuration::effectiveFor)
    }

    private fun base(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }

    override fun getState(): Saved = Saved().apply { paths = excluded.map { it.toString() }.toMutableList() }

    override fun loadState(state: Saved) {
        val paths = state.paths.mapNotNullTo(linkedSetOf()) { runCatching { Path.of(it) }.getOrNull() }
        excluded = paths
        urls = urlsOf(paths)
    }

    companion object {
        private val LOG = Logger.getInstance(CodeBridgeExcludedFolders::class.java)
        private val IGNORE_FILES = setOf(".gitignore", ".ignore")

        fun getInstance(project: Project): CodeBridgeExcludedFolders = project.service()

        private fun urlsOf(paths: Set<Path>): Array<String> = paths.map {
            VirtualFileManager.constructUrl(LocalFileSystem.PROTOCOL, it.invariantSeparatorsPathString)
        }.toTypedArray()

        /** [paths] without those inside another of them. */
        internal fun outermost(paths: Set<Path>): Set<Path> =
            paths.filterTo(linkedSetOf()) { path -> paths.none { it != path && path.startsWith(it) } }
    }
}
