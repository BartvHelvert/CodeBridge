package me.helvert.codebridge.task

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The problems tasks' problem matchers find in their output, drawn in the editor as an extension's
 * diagnostics are ([HostedLanguageFeatures.fileTaskProblems]).
 *
 * Filed by matcher owner, as VS Code's marker service files them: a run replaces what the last run
 * with the same owner found, whichever task that was, and they stay after the run has ended.
 */
@Service(Service.Level.PROJECT)
class TaskProblems(private val project: Project) : TaskProblemSink {

    private val filed = ConcurrentHashMap<String, ConcurrentHashMap<Path, List<Marker>>>()

    /**
     * Has [task]'s problem matchers read [handler]'s output, until it ends. [cwd] is the directory it
     * runs in, which a `${cwd}` prefix names. Called before the handler starts, so no line is missed;
     * a matcher that could not be read is said so on the console, once the run has a console to say it in.
     */
    fun attach(handler: ProcessHandler, workspace: Path, task: ResolvedTask, cwd: Path) {
        val errors = task.problems.errors
        if (task.problems.matchers.isEmpty() && errors.isEmpty()) return
        val layout = runCatching { CodeBridgeWorkspace.of(workspace) }.getOrNull()
        val files =
            TaskProblemFiles(task.folder ?: workspace, cwd, layout?.folders?.associate { it.name to it.path }.orEmpty())
        val collector = TaskProblemCollector(
            task.problems.matchers,
            task.isBackground,
            { name, matcher -> files.resolve(name, matcher.fileLocation) },
            this
        )
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                errors.forEach { handler.notifyTextAvailable("Problem matcher: $it\n", ProcessOutputTypes.STDERR) }
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.SYSTEM) collector.output(outputType, event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                collector.done()
            }
        })
    }

    override fun files(owner: String): Set<Path> = filed[owner]?.keys?.toSet().orEmpty()

    override fun publish(owner: String, file: Path, markers: List<Marker>) {
        if (project.isDisposed) return
        if (markers.isEmpty()) filed[owner]?.remove(file)
        else filed.computeIfAbsent(owner) { ConcurrentHashMap() }[file] = markers
        LOG.debug("Task problems from '$owner' in $file: ${markers.size}")
        HostedLanguageFeatures.getInstance(project).fileTaskProblems(owner, file, markers)
    }

    override fun isOpen(file: Path): Boolean {
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return false
        return FileEditorManager.getInstance(project).isFileOpen(virtualFile)
    }

    private companion object {
        private val LOG = Logger.getInstance(TaskProblems::class.java)
    }
}
