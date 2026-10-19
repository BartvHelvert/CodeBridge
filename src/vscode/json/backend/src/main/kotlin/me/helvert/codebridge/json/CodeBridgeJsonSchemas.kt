package me.helvert.codebridge.json

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.jetbrains.jsonSchema.remote.JsonFileResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.helvert.codebridge.extensions.ExtensionInstallationListener
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.settings.WorkbenchSettings
import me.helvert.codebridge.workspace.CodeBridgeWorkspace

/**
 * Completion, documentation and validation in `launch.json`, `tasks.json` and `settings.json`, from
 * the JSON schemas the enabled extensions make up between them (see [WorkspaceSchemas]), and in any
 * file an extension declares a schema for ([ContributedJsonSchemas]).
 */
internal class CodeBridgeJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> {
        val schemas = CodeBridgeSchemaFiles.getInstance().current()
        return listOf(
            Provider("VS Code launch.json", { it.launch }) { file -> isWorkspaceFile(file, "launch.json") },
            Provider("VS Code tasks.json", { it.tasks }) { file -> isWorkspaceFile(file, "tasks.json") },
            Provider("VS Code workspace settings", { it.workspaceSettings }) { file ->
                isWorkspaceFile(
                    file,
                    "settings.json"
                )
            },
            Provider("VS Code workspace", { it.codeWorkspace }) { file -> file.extension == CodeBridgeWorkspace.EXTENSION },
            Provider("VS Code user settings", { it.userSettings }) { file ->
                CodeBridgeUserSettings.isFile(file.path)
            },
        ) + schemas.contributed.map(::ContributedProvider)
    }

    private fun isWorkspaceFile(file: VirtualFile, name: String) = file.name == name && file.parent?.name == ".vscode"

    private class Provider(
        private val name: String,
        private val schema: (CodeBridgeSchemaFiles.Schemas) -> VirtualFile,
        private val applies: (VirtualFile) -> Boolean,
    ) : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = applies(file)
        override fun getName(): String = name
        override fun getSchemaFile(): VirtualFile = schema(CodeBridgeSchemaFiles.getInstance().current())
        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
        override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
    }

    /** A schema an extension declares in `contributes.jsonValidation`, on the web or in the extension. */
    private class ContributedProvider(private val contribution: ContributedJsonSchemas.Contribution) :
        JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = contribution.files.matches(file.path)
        override fun getName(): String = "${contribution.extension} (${
            when (val schema = contribution.schema) {
                is ContributedJsonSchemas.Schema.Remote -> schema.url
                is ContributedJsonSchemas.Schema.Local -> schema.path.fileName
            }
        })"

        override fun getSchemaFile(): VirtualFile? = when (val schema = contribution.schema) {
            is ContributedJsonSchemas.Schema.Remote -> JsonFileResolver.urlToFile(schema.url)
            is ContributedJsonSchemas.Schema.Local -> LocalFileSystem.getInstance().findFileByNioFile(schema.path)
        }

        override fun getSchemaType(): SchemaType =
            if (contribution.schema is ContributedJsonSchemas.Schema.Remote) SchemaType.remoteSchema else SchemaType.embeddedSchema

        override fun getRemoteSource(): String? = (contribution.schema as? ContributedJsonSchemas.Schema.Remote)?.url
    }
}

/**
 * The schemas for the extensions installed now, made once per change to them.
 *
 * In-memory files: a schema is nobody's to open or edit, and a new file per change is what tells
 * the IDE's schema cache it has changed, once [JsonSchemaService.reset] has had it look again.
 */
@Service(Service.Level.APP)
internal class CodeBridgeSchemaFiles {
    class Schemas(
        val revision: Long,
        /** What [launch] holds, for [CodeBridgeLaunchCompletion] to read the shapes from. */
        val launchSchema: JsonObject,
        /** Every debugger's `configurationSnippets`, for [CodeBridgeLaunchCompletion] to offer. */
        val launchSnippets: List<LaunchSnippet>,
        val launch: VirtualFile,
        val tasks: VirtualFile,
        val workspaceSettings: VirtualFile,
        val userSettings: VirtualFile,
        /** Every enabled extension's `contributes.jsonValidation`. */
        val contributed: List<ContributedJsonSchemas.Contribution>,
        /** A `.code-workspace` file; see [WorkspaceSchemas.codeWorkspace]. */
        val codeWorkspace: VirtualFile,
    )

    @Volatile
    private var cached: Schemas? = null

    fun current(): Schemas {
        val installations = ExtensionInstallationService.getInstance()
        val revision = installations.changes.value
        cached?.takeIf { it.revision == revision }?.let { return it }
        val enabled = installations.list().filter { it.enabled }
        val manifests = enabled.map { it.manifest }
        val launch = WorkspaceSchemas.launch(manifests)
        val tasks = TaskSchemas.tasks(manifests)
        val workspaceSettings = WorkspaceSchemas.settings(manifests + WorkbenchSettings.manifest, workspace = true)
        return Schemas(
            revision,
            launch,
            enabled.flatMap { LaunchSnippets.of(it.manifest, LaunchSnippets.nls(it.directory)) },
            file("vscode-launch.schema.json", launch),
            file("vscode-tasks.schema.json", tasks),
            file("vscode-workspace-settings.schema.json", workspaceSettings),
            file(
                "vscode-user-settings.schema.json",
                WorkspaceSchemas.settings(manifests + WorkbenchSettings.manifest, workspace = false)
            ),
            enabled.flatMap { ContributedJsonSchemas.of(it.id, it.manifest, it.directory) },
            file("vscode-code-workspace.schema.json", WorkspaceSchemas.codeWorkspace(launch, tasks, workspaceSettings)),
        ).also { cached = it }
    }

    private fun file(name: String, schema: JsonObject): VirtualFile =
        LightVirtualFile(name, schema.toString()).apply { isWritable = false }

    companion object {
        fun getInstance(): CodeBridgeSchemaFiles = ApplicationManager.getApplication().getService(CodeBridgeSchemaFiles::class.java)
    }
}

/** Has every open project read the schemas again once extensions are installed, removed or toggled. */
internal class CodeBridgeJsonSchemaInstallationListener : ExtensionInstallationListener {
    override fun extensionsChanged() {
        Scope.getInstance().scope.launch(Dispatchers.EDT) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset()
            }
        }
    }

    /** Where the schemas are read again: in every project, so for as long as the application. */
    @Service(Service.Level.APP)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(): Scope = service()
        }
    }
}
