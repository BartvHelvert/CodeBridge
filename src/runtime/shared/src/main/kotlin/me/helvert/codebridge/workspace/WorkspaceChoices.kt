package me.helvert.codebridge.workspace

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import me.helvert.codebridge.workspace.WorkspaceChoices.Companion.NONE
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Which `.code-workspace` file, if any, each project is opened with -- the user's choice, as in VS
 * Code, where opening a folder never opens a workspace file by itself.
 *
 * Kept per project directory, application-wide like the list of recent projects: the paths that read
 * it ([CodeBridgeWorkspace.of], tasks, launch configurations) know the directory, not the project. Without
 * an application -- a plain unit test -- an in-memory table stands in.
 */
@State(name = "CodeBridgeWorkspaceFiles", storages = [Storage("codebridge.workspaces.xml")])
class WorkspaceChoices : SimplePersistentStateComponent<WorkspaceChoices.Choices>(Choices()) {

    class Choices : BaseState() {
        /** By normalized project directory: the workspace file's name, or [NONE] for the folder alone. */
        var files by map<String, String>()

        /** Marks the state changed, so it is saved. */
        fun changed() = incrementModificationCount()
    }

    /** What was chosen for a project. */
    sealed interface Choice {
        /** Not asked yet, or not answered: the folder alone, and the user is asked. */
        data object Undecided : Choice

        /** The folder alone, by choice. */
        data object FolderOnly : Choice

        /** The `.code-workspace` file of this name in the project directory. */
        data class File(val name: String) : Choice
    }

    @Synchronized
    private fun read(base: Path): Choice = when (val value = state.files[key(base)]) {
        null -> Choice.Undecided
        NONE -> Choice.FolderOnly
        else -> Choice.File(value)
    }

    @Synchronized
    private fun write(base: Path, choice: Choice) {
        when (choice) {
            Choice.Undecided -> state.files.remove(key(base))
            Choice.FolderOnly -> state.files[key(base)] = NONE
            is Choice.File -> state.files[key(base)] = choice.name
        }
        state.changed()
    }

    companion object {
        private const val NONE = ""
        private val inMemory = ConcurrentHashMap<String, Choice>()

        private fun key(base: Path): String = base.toAbsolutePath().normalize().toString()

        private fun service(): WorkspaceChoices? =
            ApplicationManager.getApplication()?.getService(WorkspaceChoices::class.java)

        fun of(base: Path): Choice = service()?.read(base) ?: inMemory[key(base)] ?: Choice.Undecided

        fun choose(base: Path, choice: Choice) {
            val service = service()
            if (service != null) service.write(base, choice)
            else if (choice == Choice.Undecided) inMemory.remove(key(base)) else inMemory[key(base)] = choice
        }
    }
}
