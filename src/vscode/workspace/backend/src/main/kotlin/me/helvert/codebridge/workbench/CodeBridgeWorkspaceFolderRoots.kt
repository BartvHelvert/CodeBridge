package me.helvert.codebridge.workbench

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectView
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.workspace.WorkspaceFolder
import java.nio.file.Path
import javax.swing.Icon

/**
 * The folders of a `.code-workspace` file that lie outside the project's directory, indexed as the
 * IDE indexes a library: searched, navigated to and listed under External Libraries, each by its
 * workspace folder name. The project's own model is left as it is; the folders follow the file.
 */
internal class CodeBridgeWorkspaceFolderRoots : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        project.service<CodeBridgeWorkspaceFolders>().outside().map { (name, root) -> FolderLibrary(name, root) }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        project.service<CodeBridgeWorkspaceFolders>().outside().map { it.second }

    private class FolderLibrary(private val name: String, private val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
        override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)
        override fun getPresentableText(): String = "$name (VS Code workspace folder)"
        override fun getLocationString(): String = root.presentableUrl
        override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.Folder
        override fun equals(other: Any?): Boolean = other is FolderLibrary && other.root == root && other.name == name
        override fun hashCode(): Int = root.hashCode()
    }
}

/**
 * The project's workspace, read again as its `.code-workspace` file changes or another is chosen:
 * the folders outside the project's directory, for indexing, and the workspace itself, for the
 * Project view to say which is in use.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeWorkspaceFolders(private val project: Project, private val scope: CoroutineScope) : Disposable {

    @Volatile private var cached: List<Pair<String, VirtualFile>>? = null
    @Volatile private var layoutCache: CodeBridgeWorkspace? = null

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val base = project.basePath?.let(Path::of) ?: return
                if (events.any { event -> event.path.endsWith(".${CodeBridgeWorkspace.EXTENSION}") && Path.of(event.path).parent == base }) refresh()
            }
        })
        project.messageBus.connect(this).subscribe(CodeBridgeWorkspaceChoice.TOPIC, CodeBridgeWorkspaceChoice.Listener { refresh() })
    }

    /** By folder name; none in a single-folder workspace. */
    fun outside(): List<Pair<String, VirtualFile>> = cached ?: read().also { cached = it }

    /** The project's workspace as last read; null for a project with no directory. */
    fun layout(): CodeBridgeWorkspace? = layoutCache ?: base()?.let(CodeBridgeWorkspace::of)?.also { layoutCache = it }

    private fun base(): Path? = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }

    private fun read(): List<Pair<String, VirtualFile>> {
        val base = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } ?: return emptyList()
        return outsideFolders(base)
            .mapNotNull { folder -> LocalFileSystem.getInstance().refreshAndFindFileByNioFile(folder.path)?.let { folder.name to it } }
    }

    private fun refresh() {
        scope.launch(Dispatchers.IO) {
            val layout = base()?.let(CodeBridgeWorkspace::of)
            if (layout != layoutCache) {
                layoutCache = layout
                // The project view says which workspace is in use; see [CodeBridgeWorkspaceNodeDecorator].
                scope.launch(Dispatchers.EDT) { ProjectView.getInstance(project).refresh() }
            }
            val previous = cached.orEmpty()
            val next = read()
            if (next == previous) return@launch
            cached = next
            scope.launch(Dispatchers.EDT) {
                WriteAction.run<RuntimeException> {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "VS Code workspace folders",
                        previous.map { it.second }, next.map { it.second }, "CodeBridge workspace folders")
                }
            }
        }
    }

    override fun dispose() = Unit

    companion object {
        /** The workspace folders of the project in [base] that are not inside it, which the IDE indexes already. */
        fun outsideFolders(base: Path): List<WorkspaceFolder> = CodeBridgeWorkspace.of(base).folders.filterNot { it.path.startsWith(base) }
    }
}
