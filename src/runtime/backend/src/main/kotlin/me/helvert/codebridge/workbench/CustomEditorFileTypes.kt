package me.helvert.codebridge.workbench

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.INativeFileType
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.extensions.ExtensionInstallationListener
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.rpc.CustomEditorSetupDto
import me.helvert.codebridge.rpc.CustomEditorTypeDto
import me.helvert.codebridge.rpc.CustomEditorTypes
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.workspace.JsoncEdit
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The project's custom editors and the user's associations among them, kept for the file type
 * path, which cannot afford to read the extensions' manifests and the settings every time it is
 * asked, and for the client, which follows [changes].
 *
 * Read again whenever what the project runs or its settings change; files typed by what it read
 * are then typed afresh, in one file-type change.
 */
@Service(Service.Level.PROJECT)
internal class CustomEditorDefaults(private val project: Project, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<CustomEditorSetupDto?>(null)
    /** One reread at a time, so a slow one is never overtaken by the next. */
    private val rereading = Dispatchers.IO.limitedParallelism(1)

    init {
        val listener = ExtensionInstallationListener { reread() }
        val connection = project.messageBus.connect(scope)
        connection.subscribe(ExtensionInstallationService.CHANGED, listener)
        connection.subscribe(ProjectExtensions.CHANGED, listener)
        // The user's settings are outside the project, and saved by any project; the workspace's are
        // `.vscode/settings.json`, or a `.code-workspace` file.
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isSettingsFile(it.path) }) reread()
            }
        })
    }

    /** What decides the editors files open in, read the first time it is asked for. */
    fun setup(): CustomEditorSetupDto =
        current.value ?: CustomEditorContributions.setup(project).also { current.compareAndSet(null, it) }

    /** [setup], then each change to it. */
    fun changes(): Flow<CustomEditorSetupDto> = current.onStart { setup() }.filterNotNull().distinctUntilChanged()

    /** The custom editor that opens [file] by default, if one does. */
    fun defaultType(file: VirtualFile): CustomEditorTypeDto? = CustomEditorTypes.defaultFor(setup(), file.name, file.path)

    /**
     * Makes [viewType] what files matching [pattern] open in, in the user's settings, as VS Code's
     * *Set Default* does: a write VS Code would make too, in the file both read. Null once written and
     * applied, else why it was not.
     */
    suspend fun setDefault(pattern: String, viewType: String): String? = withContext(Dispatchers.IO) {
        val file = CodeBridgeUserSettings.file
        CodeBridgeUserSettings.ensure()
        val files = EditorSettingsFiles(project)
        if (files.dirty(file)) return@withContext "Your VS Code user settings have unsaved changes. Save them, then set the default editor again."
        val before = files.read(file) ?: ""
        val after = JsoncEdit.set(before, listOf(CustomEditorContributions.ASSOCIATIONS, pattern), JsonPrimitive(viewType))
        if (after == before) return@withContext null
        try {
            // Checked before it replaces anything: a file this cannot read back must not be written.
            JsoncEdit.parse(after)
            files.write(file, before, after)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LOG.info("Could not set the default editor for $pattern", error)
            return@withContext "Could not change your VS Code user settings: ${error.message}"
        }
        // Applied before this returns, rather than once the file's change is heard: a file opened
        // in between opened in the old default, and its editor was replaced as the types changed.
        if (current.value != null) apply()
        null
    }

    private fun isSettingsFile(path: String): Boolean =
        CodeBridgeUserSettings.isFile(path) || path.endsWith("/.vscode/settings.json") || path.endsWith(".${CodeBridgeWorkspace.EXTENSION}")

    private fun reread() {
        if (current.value == null) return
        scope.launch { apply() }
    }

    /** Reads the setup again and, if it changed, types files afresh by it. */
    private suspend fun apply() = withContext(rereading) {
        val before = current.value ?: return@withContext
        val after = CustomEditorContributions.setup(project)
        current.value = after
        if (after == before) return@withContext
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            WriteAction.run<RuntimeException> {
                FileTypeManagerEx.getInstanceEx().makeFileTypesChange("CodeBridge custom editors changed") {}
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CustomEditorDefaults::class.java)

        fun getInstance(project: Project): CustomEditorDefaults = project.service()
    }
}

/**
 * Keeps a file a custom editor opens by default out of the system's hands.
 *
 * The IDE gives some files, PDFs and office documents among them, to the application the system
 * associates with them instead of opening them in an editor: navigation asks for the file's type,
 * and a native one is handed over without any editor being asked. A split-mode backend cannot hand
 * anything over, so such a file did not open at all, and no client ever heard of it to show it in
 * its custom editor. Typed as [CustomEditorFileType] instead, it opens in an editor like any other
 * file, which the client then replaces with the custom editor.
 *
 * Only a file whose own type is native: every other file already opens in an editor.
 */
internal class CustomEditorFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (file.isDirectory) return null
        // By name, as the type the file would have without this: asking the file again would ask this.
        val native = FileTypeManager.getInstance().getFileTypeByFileName(file.nameSequence)
        if (native !is INativeFileType) return null
        if (claimingType(file) == null) return null
        return CustomEditorFileType.of(native)
    }
}

/**
 * The custom editor that opens [file] by default in any open project. The file type is the
 * application's and knows no project, and a file of one project in a custom editor of another is
 * a rare enough pair to type it for both.
 */
private fun claimingType(file: VirtualFile): CustomEditorTypeDto? =
    ProjectManager.getInstanceIfCreated()?.openProjects.orEmpty()
        .filter { !it.isDisposed && it.isInitialized }
        .firstNotNullOfOrNull { CustomEditorDefaults.getInstance(it).defaultType(file) }

/**
 * The type of a file a custom editor opens by default whose own type, [native], would have the
 * system open it: binary, as [native] is, drawn with its icon, and not native.
 */
internal class CustomEditorFileType private constructor(private val native: FileType) : FileType {
    override fun getName(): String = "CodeBridge.CustomEditor.${native.name}"
    override fun getDisplayName(): String = native.displayName
    override fun getDescription(): String = "Files a custom editor opens"
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon? = native.icon
    override fun isBinary(): Boolean = true

    companion object {
        /** One type for each native type, as the platform compares file types by identity. */
        private val types = ConcurrentHashMap<FileType, CustomEditorFileType>()

        fun of(native: FileType): CustomEditorFileType = types.computeIfAbsent(native, ::CustomEditorFileType)
    }
}

/**
 * The editor a [CustomEditorFileType] file opens in on the backend, which no other editor accepts
 * as binary. Only a stand-in: a client shows the custom editor in its place, and in one process the
 * custom editor hides it.
 */
internal class CustomEditorPlaceholderProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file.fileType is CustomEditorFileType

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CustomEditorPlaceholder(file, CustomEditorDefaults.getInstance(project).defaultType(file))

    override fun getEditorTypeId(): String = "codebridge-custom-editor-placeholder"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.NONE
}

private class CustomEditorPlaceholder(private val file: VirtualFile, type: CustomEditorTypeDto?) : UserDataHolderBase(), FileEditor {
    private val label = JBLabel(
        if (type != null) "${file.name} opens in ${type.displayName}." else "${file.name} opens in a custom editor.",
        SwingConstants.CENTER,
    ).apply {
        border = JBUI.Borders.empty(8)
        foreground = UIUtil.getContextHelpForeground()
    }

    override fun getComponent(): JComponent = label
    override fun getPreferredFocusedComponent(): JComponent = label
    override fun getName(): String = "Custom Editor"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}
