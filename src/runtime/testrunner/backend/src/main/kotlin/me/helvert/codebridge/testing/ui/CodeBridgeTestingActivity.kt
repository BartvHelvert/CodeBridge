package me.helvert.codebridge.testing.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.testing.HostedTesting
import kotlin.time.Duration.Companion.milliseconds

internal class CodeBridgeTestingActivity : ProjectActivity {
    override suspend fun execute(project: Project) = project.service<CodeBridgeTestingUi>().start()
}

/**
 * What ties [HostedTesting] to the IDE for a project: runs an extension starts itself get a tab,
 * and the gutters of files whose tests changed are drawn again.
 */
@Service(Service.Level.PROJECT)
internal class CodeBridgeTestingUi(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val pending = HashSet<String>()
    private var everything = false
    private var scheduled = false

    fun start() {
        val testing = HostedTesting.getInstance(project)
        testing.setRunOpener({ run -> CodeBridgeTestRuns.show(project, run) }, this)
        testing.addChangeListener(::changed, this)
    }

    /**
     * Gathered for a moment, since a diff arrives per controller and a run reports test by test,
     * then the open files among them are highlighted again -- which is when the gutter is drawn.
     */
    private fun changed(files: Set<String>) {
        synchronized(this) {
            if (files.isEmpty()) everything = true else pending += files
            if (scheduled) return
            scheduled = true
        }
        scope.launch {
            delay(200.milliseconds)
            val (all, keys) = synchronized(this@CodeBridgeTestingUi) {
                scheduled = false
                (everything to pending.toSet()).also { everything = false; pending.clear() }
            }
            withContext(Dispatchers.EDT) {
                for (file in shownFiles()) {
                    if (!file.isInLocalFileSystem) continue
                    if (!all && runCatching { UriComponents.key(file.toNioPath()) }.getOrNull() !in keys) continue
                    PsiManager.getInstance(project).findFile(file)?.let {
                        DaemonCodeAnalyzer.getInstance(project).restart(it, "VS Code extension test results changed")
                    }
                }
            }
        }
    }

    /**
     * The files this project's editors show. Not [FileEditorManager]'s open files: in split mode the
     * editors are the frontend's, and this process's manager lists none at startup -- the files
     * restored then never had their gutter drawn. Each still has an editor here, which is what is highlighted.
     */
    private fun shownFiles(): List<VirtualFile> = EditorFactory.getInstance().allEditors
        .filter { it.project == null || it.project == project }
        .mapNotNull { FileDocumentManager.getInstance().getFile(it.document) }
        .distinct()

    override fun dispose() = Unit
}
