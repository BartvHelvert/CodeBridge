package me.helvert.codebridge.language

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.*
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageWithType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.*
import me.helvert.codebridge.settings.CodeBridgeUserSettings

/**
 * The workbench's own commands, which extensions run and no extension registers, done by the IDE.
 *
 * Each is the nearest IntelliJ has to what the workbench would show, not a copy of it.
 */
internal object CodeBridgeWorkbenchCommands {

    /** IntelliJ's Push dialog, which is where publishing a branch is done in the IDE. */
    private const val PUSH_ACTION = "Vcs.Push"

    /** Open in New Window, on an editor tab and in the Project view. */
    private const val OPEN_IN_NEW_WINDOW_ACTION = "EditSourceInNewWindow"

    /**
     * Runs [command], or answers null if it is not one of these.
     *
     * On the dispatcher meant for blocking -- files are refreshed, providers waited on -- which also
     * takes it off the calling thread whatever that is, the editor thread included: the IDE's own
     * answers start with a read action. A coroutine running on a thread not its dispatcher's, as the
     * host's calls start, would stay where it is if asked for the dispatcher it already names.
     */
    suspend fun execute(project: Project, command: String, arguments: JsonArray): MainThreadResult? =
        withContext(Dispatchers.IO) {
            when (command) {
                WorkbenchCommands.SHOW_REFERENCES -> showReferences(
                    project, WorkbenchCommands.showReferences(arguments)
                        ?: throw IllegalArgumentException("$command takes a resource, a position and locations")
                )
                WorkbenchCommands.OPEN -> open(
                    project, WorkbenchCommands.open(arguments) ?: throw IllegalArgumentException("$command takes a resource")
                )
                in WorkbenchCommands.OPEN_SETTINGS -> openSettings(project, WorkbenchCommands.settingsQuery(arguments))
                WorkbenchCommands.DIFF -> diff(
                    project, WorkbenchCommands.diff(arguments) ?: throw IllegalArgumentException("$command takes two resources")
                )

                WorkbenchCommands.CHANGES -> changes(
                    project, WorkbenchCommands.changes(arguments)
                        ?: throw IllegalArgumentException("$command takes a title and a list of changes")
                )

                WorkbenchCommands.MOVE_EDITOR_TO_NEW_WINDOW -> moveEditorToNewWindow(project)
                WorkbenchCommands.GIT_PUBLISH -> runIdeAction(project, PUSH_ACTION)
                WorkbenchCommands.EXECUTE_DEFINITIONS ->
                    at(project, command, arguments, WorkbenchCommands::locationLinks) { features, file, position ->
                        if (features.canFindDefinitions(file)) blocking {
                            features.definitions(file, position.lineNumber, position.column)
                        }
                        else IdeLocations.definitions(project, file, position)
                    }

                WorkbenchCommands.EXECUTE_TYPE_DEFINITIONS ->
                    at(project, command, arguments, WorkbenchCommands::locationLinks) { features, file, position ->
                        blocking { features.typeDefinitions(file, position.lineNumber, position.column) }
                    }

                WorkbenchCommands.EXECUTE_IMPLEMENTATIONS ->
                    at(project, command, arguments, WorkbenchCommands::locationLinks) { features, file, position ->
                        blocking { features.implementations(file, position.lineNumber, position.column) }
                    }

                WorkbenchCommands.EXECUTE_REFERENCES ->
                    at(project, command, arguments, WorkbenchCommands::locations) { features, file, position ->
                        if (features.canFindReferences(file)) blocking {
                            features.references(file, position.lineNumber, position.column)
                        }
                        else IdeLocations.references(project, file, position)
                    }

                WorkbenchCommands.EXECUTE_HOVER ->
                    at(project, command, arguments, { WorkbenchCommands.hovers(it.firstOrNull()) }) { features, file, position ->
                        blocking { listOfNotNull(features.hover(file, position.lineNumber, position.column)) }
                    }

                WorkbenchCommands.EXECUTE_WORKSPACE_SYMBOLS -> {
                    val query = WorkbenchCommands.query(arguments) ?: throw IllegalArgumentException("$command takes a query")
                    val symbols =
                        blocking { HostedLanguageFeatures.getInstance(project).workspaceSymbols(query).map { it.symbol } }
                    MainThreadResult(WorkbenchCommands.workspaceSymbols(symbols))
                }
                WorkbenchCommands.EXECUTE_DOCUMENT_SYMBOLS -> documentSymbols(
                    project, WorkbenchCommands.resource(arguments) ?: throw IllegalArgumentException("$command takes a resource")
                )
                // `<view>.focus`, `workbench.view.extension.<container>` and the like: the views' own.
                else -> if (me.helvert.codebridge.workbench.HostedTreeViews.getInstance(project).viewCommand(command))
                    MainThreadResult() else null
            }
        }

    /**
     * `editor.action.showReferences`: VS Code's references peek.
     *
     * One place is simply opened, as Go to Declaration opens a single target. Several are listed in
     * the Find tool window as usages -- IntelliJ's list of places to pick from -- titled after the
     * symbol at the position they were asked about. None shows nothing, where VS Code would show an
     * empty peek.
     */
    private suspend fun showReferences(project: Project, shown: WorkbenchCommands.ShowReferences): MainThreadResult {
        val places = shown.locations.mapNotNull { location -> fileOf(location.resource)?.let { it to location } }
        val editors = CodeBridgeTextEditors.getInstance(project)
        when (places.size) {
            0 -> {}
            1 -> {
                val (file, location) = places.single()
                editors.reveal(file, Position(location.range.startLineNumber, location.range.startColumn), focus = true)
            }

            else -> {
                val usages = readAction { places.mapNotNull { (file, location) -> usage(project, file, location) } }
                val asked = fileOf(shown.resource)
                val presentation = presentation(asked?.let { readAction { symbolAt(it, shown.position) } })
                editors.asClient {
                    UsageViewManager.getInstance(project)
                        .showUsages(UsageTarget.EMPTY_ARRAY, usages.toTypedArray(), presentation)
                }
            }
        }
        return MainThreadResult()
    }

    /**
     * `_workbench.open`: a file in an editor, at the selection when there is one; a web link in the
     * browser, as `openExternal` does; anything else from the extension that provides its scheme.
     */
    internal suspend fun open(project: Project, open: WorkbenchCommands.Open): MainThreadResult {
        val resource = open.resource
        when {
            resource.scheme == UriComponents.FILE -> {
                val file = fileOf(resource) ?: throw IllegalArgumentException("There is no file at ${resource.uri}")
                val at = open.selection?.let { Position(it.startLineNumber, it.startColumn) }
                CodeBridgeTextEditors.getInstance(project).reveal(file, at, focus = !open.preserveFocus)
            }

            me.helvert.codebridge.workbench.HostedWindow.browses(resource.scheme) ->
                me.helvert.codebridge.workbench.HostedClient.getInstance(project).browse(resource.uri)

            else -> require(me.helvert.codebridge.workbench.HostedContentProviders.getInstance(project).open(resource.uri)) {
                "Nothing can open ${resource.uri}"
            }
        }
        return MainThreadResult()
    }

    /**
     * `_workbench.diff`: the two sides in IntelliJ's diff viewer, titled with the label VS Code puts
     * on its diff editor's tab.
     */
    private suspend fun diff(project: Project, diff: WorkbenchCommands.Diff): MainThreadResult {
        val (left, right) = coroutineScope {
            val left = async { sideOf(project, diff.left) }
            val right = async { sideOf(project, diff.right) }
            left.await() to right.await()
        }
        CodeBridgeTextEditors.getInstance(project).asClient {
            DiffManager.getInstance().showDiff(project, request(project, diff.title, left, right))
        }
        return MainThreadResult()
    }

    /**
     * `_workbench.changes`: VS Code's multi-file diff, as a chain in one diff window, which steps
     * from file to file as the multi-file editor scrolls.
     */
    private suspend fun changes(project: Project, changes: WorkbenchCommands.Changes): MainThreadResult {
        val sides = coroutineScope {
            changes.changes.map { change ->
                val original = async { change.original?.let { sideOf(project, it) } ?: DiffSide.NONE }
                val modified = async { change.modified?.let { sideOf(project, it) } ?: DiffSide.NONE }
                Triple(change, original, modified)
            }.map { (change, original, modified) -> Triple(change, original.await(), modified.await()) }
        }
        CodeBridgeTextEditors.getInstance(project).asClient {
            val requests = sides.map { (change, original, modified) ->
                request(project, nameOf(change.label), original, modified)
            }
            if (requests.isEmpty()) return@asClient
            val chain = SimpleDiffRequestChain(requests)
            DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
        }
        return MainThreadResult()
    }

    /**
     * One side of a diff: a file on disk, which IntelliJ diffs as itself and so lets be edited, or
     * the text an extension's content provider makes up for its own scheme. [NONE] is nothing.
     */
    private class DiffSide(val file: VirtualFile?, val name: String, val text: String?) {
        companion object {
            val NONE = DiffSide(null, "", null)
        }
    }

    private suspend fun sideOf(project: Project, resource: UriComponents): DiffSide {
        if (resource.scheme == UriComponents.FILE) {
            return withContext(Dispatchers.IO) { fileOf(resource) }?.let { DiffSide(it, it.name, null) } ?: DiffSide.NONE
        }
        val text = me.helvert.codebridge.workbench.HostedContentProviders.getInstance(project).content(resource)
        return DiffSide(null, nameOf(resource), text)
    }

    /** On the EDT: contents for a file are made from its document. */
    private fun request(project: Project, title: String?, left: DiffSide, right: DiffSide): SimpleDiffRequest =
        SimpleDiffRequest(title, contentOf(project, left), contentOf(project, right), null, null)

    private fun contentOf(project: Project, side: DiffSide): DiffContent {
        val factory = DiffContentFactory.getInstance()
        side.file?.let { return factory.create(project, it) }
        val text = side.text ?: return factory.createEmpty()
        // Typed by the name, so a `gitlens:` revision of a Java file is highlighted as Java.
        return factory.create(project, text, FileTypeManager.getInstance().getFileTypeByFileName(side.name))
    }

    private fun nameOf(resource: UriComponents): String = resource.path.substringAfterLast('/')

    /**
     * `workbench.action.moveEditorToNewWindow`: the selected editor in a new IDE window, as IntelliJ's
     * Open in New Window does. Nothing when no editor is selected, as VS Code does nothing.
     */
    private suspend fun moveEditorToNewWindow(project: Project): MainThreadResult {
        CodeBridgeTextEditors.getInstance(project).asClient {
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return@asClient
            // The action rather than the editor manager's implementation, which is the only other way
            // to a new window: `FileEditorManager` has none.
            val action = ActionManager.getInstance().getAction(OPEN_IN_NEW_WINDOW_ACTION)
                ?: throw IllegalStateException("The IDE has no $OPEN_IN_NEW_WINDOW_ACTION action")
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build()
            ActionUtil.performAction(
                action,
                AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
            )
        }
        return MainThreadResult()
    }

    /**
     * Runs an IDE action as the user would, in the project: the nearest IDE equivalent of a command
     * of VS Code's own built-in extensions, which are not hosted here.
     */
    @Suppress("SameParameterValue") // One command maps to an IDE action so far.
    private suspend fun runIdeAction(project: Project, id: String): MainThreadResult {
        CodeBridgeTextEditors.getInstance(project).asClient {
            val action =
                ActionManager.getInstance().getAction(id) ?: throw IllegalStateException("The IDE has no $id action")
            val context = SimpleDataContext.getProjectContext(project)
            ActionUtil.performAction(
                action,
                AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
            )
        }
        return MainThreadResult()
    }

    /**
     * A `vscode.execute…Provider` command about a position in a file: [answer]ed, or nothing for a
     * file the IDE cannot find, as VS Code answers a resource it cannot open.
     */
    private suspend fun <T> at(
        project: Project,
        command: String,
        arguments: JsonArray,
        reply: (List<T>) -> String,
        answer: suspend (HostedLanguageFeatures, VirtualFile, Position) -> List<T>,
    ): MainThreadResult {
        val (resource, position) = WorkbenchCommands.resourceAt(arguments)
            ?: throw IllegalArgumentException("$command takes a resource and a position")
        val file = fileOf(resource) ?: return MainThreadResult(reply(emptyList()))
        return MainThreadResult(reply(answer(HostedLanguageFeatures.getInstance(project), file, position)))
    }

    /**
     * The providers are asked, and waited on, by blocking calls the IDE's own callers make too.
     * Waiting on a reply is not computing, so it waits on the dispatcher meant for blocking.
     */
    private suspend fun <T> blocking(ask: () -> T): T = withContext(Dispatchers.IO) { ask() }

    /**
     * `_executeDocumentSymbolProvider`: the outline of a file, from the extensions that claim it, as
     * VS Code asks its providers; else from IntelliJ's own structure, which is all a file IntelliJ
     * owns has. A file the IDE cannot find has no symbols, as VS Code answers a resource it cannot
     * open with none.
     */
    private suspend fun documentSymbols(project: Project, resource: UriComponents): MainThreadResult {
        val file = fileOf(resource) ?: return MainThreadResult(DocumentSymbols.toJson(emptyList()))
        val symbols = HostedLanguageFeatures.getInstance(project).documentSymbols(file) ?: IdeDocumentSymbols.of(project, file)
        return MainThreadResult(DocumentSymbols.toJson(symbols))
    }

    /**
     * `workbench.action.openSettings` and its kin: the user `settings.json`, where settings are kept
     * here, at the first setting the query names -- `gitlens.launchpad` finds `"gitlens.launchpad.…"`.
     * The Settings UI would list every match; a file can only be opened at one.
     */
    private suspend fun openSettings(project: Project, query: List<String>): MainThreadResult {
        CodeBridgeUserSettings.ensure()
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(CodeBridgeUserSettings.file)
            ?: throw IllegalStateException("Could not find ${CodeBridgeUserSettings.file}")
        val at = query.firstOrNull()?.let { term -> readAction { settingAt(file, term) } }
        CodeBridgeTextEditors.getInstance(project).reveal(file, at, focus = true)
        return MainThreadResult()
    }

    /** The first key in [file] that starts with [prefix], as a position on its opening quote. */
    private fun settingAt(file: VirtualFile, prefix: String): Position? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = document.charsSequence.indexOf("\"$prefix", ignoreCase = true).takeIf { it >= 0 } ?: return null
        val line = document.getLineNumber(offset)
        return Position(line + 1, offset - document.getLineStartOffset(line) + 1)
    }

    /** Not in a read action: it refreshes, to find files the IDE has not seen, such as a toolchain's. */
    private fun fileOf(resource: UriComponents): VirtualFile? {
        if (resource.scheme != UriComponents.FILE) return null
        val path = runCatching { resource.toPath() }.getOrNull() ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    /** The place as a usage, spanning the range the extension gave or, failing that, its line. */
    private fun usage(project: Project, file: VirtualFile, location: LanguageFeatures.Location): Usage? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val range = symbolRange(document, location.range) ?: run {
            val line = location.range.startLineNumber - 1
            if (line !in 0 until document.lineCount) return null
            TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
        }
        return UntypedUsage(UsageInfo(psi, range.startOffset, range.endOffset))
    }

    /**
     * A place with no usage type, so the Find tool window does not group it under one.
     *
     * The command names places and nothing about them: VS Code lists them by file alone. Left to
     * itself the usage view would guess a type from the PSI, find none in a file it cannot parse,
     * and file all of them under "Unclassified". Answering null skips the level for these usages
     * only, whatever the user's "Group by usage type" setting.
     */
    private class UntypedUsage(info: UsageInfo) : UsageInfo2UsageAdapter(info), UsageWithType {
        override fun getUsageType(): UsageType? = null
    }

    /** The identifier at the position the references were asked about, to title them with. */
    private fun symbolAt(file: VirtualFile, position: Position): String? {
        val text = FileDocumentManager.getInstance().getDocument(file)?.let { document ->
            val line = position.lineNumber - 1
            if (line !in 0 until document.lineCount) return null
            document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
        } ?: return null
        val column = (position.column - 1).coerceIn(0, text.length)
        var start = column
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        var end = column
        while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++
        return text.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun presentation(symbol: String?) = UsageViewPresentation().apply {
        tabName = symbol ?: "References"
        tabText = symbol?.let { "References to $it" } ?: "References"
        toolwindowTitle = "References"
        codeUsagesString = "References"
    }
}
