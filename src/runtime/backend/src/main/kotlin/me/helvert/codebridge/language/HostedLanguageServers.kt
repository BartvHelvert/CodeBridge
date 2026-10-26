package me.helvert.codebridge.language

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.*
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.helvert.codebridge.debug.ExtensionHostDebugAdapters
import me.helvert.codebridge.debug.HostedBreakpoints
import me.helvert.codebridge.debug.HostedDebugSession
import me.helvert.codebridge.extensions.ExtensionInstallationListener
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.exthost.*
import me.helvert.codebridge.exthost.protocol.payload.*
import me.helvert.codebridge.language.HostedLanguageServers.Companion.HOST_STORAGE
import me.helvert.codebridge.launch.CodeBridgeDebugSessions
import me.helvert.codebridge.launch.CodeBridgeStartDebugging
import me.helvert.codebridge.settings.VsCodeRuntime
import me.helvert.codebridge.settings.VsCodeUnavailableException
import me.helvert.codebridge.settings.CodeBridgeUserSettings
import me.helvert.codebridge.task.HostedTasks
import me.helvert.codebridge.testing.HostedTesting
import me.helvert.codebridge.workbench.*
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Project lifetime, activation and discovery run off the editor thread. */
@Service(Service.Level.PROJECT)
class HostedLanguageServers(private val project: Project, private val scope: CoroutineScope) : Disposable {
    /**
     * Where the host is started and stopped and documents are opened and synced, one task at a time
     * and in the order they were queued. On the IO pool: starting a host blocks for as long as it takes.
     */
    private val worker = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var host: ExtensionHostSession? = null
    private var hostInstallation: VsCodeInstallation? = null
    private var extensions = emptyList<InstalledExtension>()
    private var locations = emptyMap<String, Path>()
    private var storage: Path? = null
    /** Every debug session running in the host; a code lens can start one while another runs. */
    private val debugSessions = java.util.concurrent.CopyOnWriteArrayList<HostedDebugSession>()
    /** The host's adapters, by the handle the host names each by, whichever session started it. */
    @Volatile private var debugAdapters: ExtensionHostDebugAdapters? = null
    /** The running host's tasks, which a task's run configuration reports its runs to. */
    @Volatile var tasks: HostedTasks? = null
        private set
    @Volatile private var terminals: HostedTerminals? = null
    @Volatile private var quickOpen: HostedQuickOpen? = null
    @Volatile private var authentication: HostedAuthentication? = null
    @Volatile
    private var urls: HostedUrls? = null
    private val debugLock = Any()
    private val debugRegistrations = mutableListOf<MainThreadCall>()
    private val installingPayload = java.util.concurrent.atomic.AtomicInteger()
    /** Keep the outcome, not just the attempt: another caller must wait and see the same failure. */
    private val activated = mutableMapOf<String, Deferred<String>>()
    /** Files that matched a language, so they can be served again after a restart. */
    private val requested = linkedMapOf<String, VirtualFile>()

    /**
     * Every file open in an editor, claimed or not, so that an extension installed later can claim
     * one already open: [requested] holds only the files something claimed when they were opened.
     */
    private val opened = linkedSetOf<VirtualFile>()
    /** At most one edit queued per file: the task reads what it finds, so a dropped one costs nothing. */
    private val syncing = ConcurrentHashMap.newKeySet<VirtualFile>()
    @Volatile private var disposed = false
    private val features get() = HostedLanguageFeatures.getInstance(project)
    private val output get() = HostedOutputChannels.getInstance(project)
    private val progress get() = HostedProgress.getInstance(project)
    private val statusBar get() = HostedStatusBar.getInstance(project)
    private val treeViews get() = HostedTreeViews.getInstance(project)
    private val webviews get() = HostedWebviews.getInstance(project)
    private val decorations get() = HostedDecorations.getInstance(project)
    private val messages get() = HostedMessages.getInstance(project)
    private val testing get() = HostedTesting.getInstance(project)
    private val dialogs = HostedDialogs(project)
    @Volatile private var workbench: HostedWorkbench? = null
    /** Documents extensions open for themselves; see [HostedDocuments]. Internal for the fixture. */
    internal val documents = HostedDocuments(project, scope, worker,
        languageOf = { file ->
            contributedLanguages(ProjectExtensions.getInstance(project).enabled(), file).firstOrNull()
        },
        activate = { language -> activate(setOf(language)) },
        extensionOf = { language ->
            ProjectExtensions.getInstance(project).enabled()
                .flatMap { it.manifest.contributes.languages }.firstOrNull { it.id == language }?.extensions?.firstOrNull()
        })

    init {
        // Another workspace chosen: the host starts again in it, as VS Code reloads its window when it
        // opens another folder or workspace. Taken up live, the switch changed every setting the
        // workspace's own file held -- rust-analyzer then asked to restart its server. Editing the
        // chosen file's folders is still taken up live, as VS Code takes it up.
        project.messageBus.connect(this).subscribe(CodeBridgeWorkspaceChoice.TOPIC, CodeBridgeWorkspaceChoice.Listener {
            if (!disposed) scope.launch(worker) {
                if (host == null) return@launch
                stop()
                if (!project.isDisposed) {
                    requested.values.toList().forEach(::attach)
                    wakeStartupExtensions()
                }
            }
        })
        // What is installed changed, in every project, or what this project runs, here alone.
        val extensionsChanged = ExtensionInstallationListener {
            if (!disposed && installingPayload.get() == 0) scope.launch(worker) {
                stop()
                if (!project.isDisposed) {
                    // A file already open when its extension arrives has been detected as untyped
                    // and the verdict cached, so it would stay untyped -- no document, no PSI, and
                    // nothing to annotate. Ask the platform to look again. Rebuilding file types
                    // publishes to the message bus and so needs write access of its own: on the EDT
                    // alone this raises "write access is allowed inside write-action only".
                    scope.launch(Dispatchers.EDT) {
                        WriteAction.run<RuntimeException> {
                            FileTypeManagerEx.getInstanceEx()
                                .makeFileTypesChange("CodeBridge extensions changed") {}
                        }
                    }
                    // Installing an extension is also the ordinary way an already-open file acquires
                    // language support, and the platform does not re-deliver `fileOpened` for it --
                    // so every open file is offered again, not only those something claimed before.
                    opened.toList().forEach(::attach)
                    // An extension that runs at startup is started with the window, file or not.
                    wakeStartupExtensions()
                }
            }
        }
        project.messageBus.connect(this).subscribe(ExtensionInstallationService.CHANGED, extensionsChanged)
        project.messageBus.connect(this).subscribe(ProjectExtensions.CHANGED, extensionsChanged)
        // Document sync. What the extension's server must see is the editor's buffer, not the file
        // on disk; without these it answers against the text the file had when it was opened.
        // Both signals are application-wide -- the platform has no project-scoped document listener
        // -- so every edit and save in every project arrives here and is dropped unless this project
        // mirrored that file. Everything is handed to [worker], which is also where documents are
        // opened, so the host is never told about an edit before the document it belongs to.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) = undoing(event.document)
            override fun documentChanged(event: DocumentEvent) = edited(event.document)
        }, this)
        // `FileDocumentManagerListener.TOPIC`, which is the one that delivers.
        //
        // Reading `FileDocumentManagerImpl` suggests otherwise: the only `afterDocumentSaved` call
        // site there is on the subinterface's topic, `FileDocumentManagerListenerBackgroundable`.
        // Moving the subscription there on that reading stopped saves arriving at all. What the
        // bytecode does not show at that call site is `FileDocumentManagerListenerBackgroundableBridge`,
        // a nested class that forwards between the two -- asynchronously, which is why subscribing
        // to the subinterface was not merely wrong but intermittently wrong.
        //
        // Saves carry more weight here than any other document event: rust-analyzer runs
        // `cargo check` on save and on nothing else, so every error diagnostic it ever reports is
        // downstream of this line. An override of a default method that nothing calls produces no
        // warning of any kind, so `DocumentSavedTest` is what holds this, not the compiler.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun afterDocumentSaved(document: Document) = saved(document)
            })
        // The user's settings live outside every project, so no workspace watcher sees them change:
        // saved in an editor here, written by an extension in another project, or edited outside.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { CodeBridgeUserSettings.isFile(it.path) }) workbench?.userSettingsChanged()
                }
            })
    }

    fun fileOpened(file: VirtualFile) {
        if (disposed) return
        scope.launch(worker) { attach(file) }
    }

    /**
     * A file the user is looking at, which may never have been reported as opened.
     *
     * `fileOpened` is not a reliable account of what is on screen. In split mode, tabs the client
     * restores for a reopened project have been seen to raise `beforeFileOpened` and then never
     * `fileOpened`, leaving files in the editor that nothing had served -- no host, no highlighting,
     * and nothing in the log to say why. An editor being created for a file, and a tab being
     * selected, are checked as well.
     *
     * Unlike [fileOpened] this leaves a file the host already shows alone: it runs on every tab
     * switch, and reopening a document re-sends all of its text.
     *
     * [selected] when the user selected its tab, rather than an editor merely being created for it:
     * that file's editor becomes the host's active one, which extensions read as
     * `window.activeTextEditor`. Restored tabs get their editors after the selected one, and taking
     * each of those as the user's choice left the active editor on whichever came last.
     */
    fun fileShown(file: VirtualFile, selected: Boolean = false) {
        if (disposed) return
        submit {
            // Remembered even for a file the host does not show yet, so that opening it activates it.
            if (selected) features.documentSelected(file)
            // Shown, not merely mirrored: a file an extension opened for itself is held with no editor.
            if (!features.isShown(file)) attach(file)
        }
    }

    /**
     * The files served, or waiting to be, for tests and diagnosis.
     *
     * Read on [worker], which owns the map; being queued behind everything submitted before it is
     * also what makes the answer include those submissions.
     */
    fun requestedFiles(): Set<VirtualFile> =
        runBlockingMaybeCancellable { withTimeout(10.seconds) { withContext(worker) { requested.values.toSet() } } }

    /** Called once the last editor for [file] has gone; splits are filtered out by the listener. */
    fun fileClosed(file: VirtualFile) {
        submit {
            // Nothing left to serve it for, so it must not be reopened by a restart.
            requested.entries.removeIf { it.value == file }
            opened -= file
            autosaved -= file
            features.documentClosed(file)
            // An untitled buffer is discarded with its editor, as VS Code discards one left unsaved.
            documents.editorClosed(file)
        }
    }

    /**
     * Queues one sync for an edited document.
     *
     * Runs inside the write action that made the edit, so it does nothing but a map lookup: the
     * text is read, and the host is called, on [worker]. Consecutive keystrokes collapse into the
     * one queued task because that task sends whatever the document holds when it runs, which is
     * also what makes a dropped intermediate edit harmless.
     */
    private fun edited(document: Document) {
        val file = mirrored(document) ?: return
        // Before the guard below, which lets only the first keystroke of a burst through: every
        // edit pushes the save back.
        saveLater(document)
        if (!syncing.add(file)) return
        val queued = submit {
            syncing.remove(file)
            features.documentChanged(file, snapshot(document))
        }
        if (!queued) syncing.remove(file)
    }

    /**
     * Runs [block] once the extension host holds what [document] holds now.
     *
     * Behind any sync already queued, on the same worker, and after one more of its own: a command
     * an item runs once it is inserted reads the document in the host, and called straight away it
     * would overtake the edit the insertion made.
     */
    fun afterSync(document: Document, block: () -> Unit) {
        val file = mirrored(document)
        submit {
            if (file != null) features.documentChanged(file, snapshot(document))
            block()
        }
    }

    /**
     * Files whose language an installed extension contributes, the only ones autosaved: a file that
     * has only VS Code's built-in language id -- Java, with no Java extension -- is IntelliJ's own,
     * and saves as IntelliJ saves it.
     */
    private val autosaved: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /** Marks [file] as one whose language an extension contributes, which a fixture never attaches. */
    @TestOnly
    internal fun contributedForTest(file: VirtualFile) {
        autosaved += file
    }

    /**
     * Returns once [worker] has run everything queued before the call, such as the stop an install
     * asks for. Not from the EDT: what is queued may wait on it.
     */
    @TestOnly
    internal fun awaitWorkerForTest() = runBlocking { withContext(worker) {} }

    /** The pending save of each edited document; a later edit replaces it. */
    private val saves = ConcurrentHashMap<Document, Job>()

    /**
     * One of VS Code's own settings the workbench acts on, for a file in [languageId]; see
     * [languageSetting]. Null with no workspace settings to read, as before a host has started.
     */
    internal fun setting(name: String, languageId: String?): JsonElement? =
        (settingsForTest ?: workbench?.configuration?.effective)?.let { languageSetting(it, name, languageId) }

    /** Stands in for the workspace settings in a fixture; see [autosaveMillisForTest]. */
    @Volatile
    @TestOnly
    internal var settingsForTest: JsonObject? = null

    /**
     * Saves an edited document once it has been quiet for [autosaveMillis], whatever made the edit,
     * unless the settings turn `files.autoSave` off.
     *
     * Typing, an accepted completion, a reformat: none of them saves by itself, and an extension that
     * analyses the file on disk goes on reporting on the old text until something does -- rust-analyzer
     * runs `cargo check` on save and on nothing else, so its errors stay stale until the user or
     * IntelliJ saves.
     *
     * Only documents mirrored into a host for a language an extension contributes ([autosaved]);
     * every other file in the IDE saves as it always has. Fixes and undo save at once instead,
     * through [CodeBridgeWorkspaceEdits.save] and [undoing].
     */
    private fun saveLater(document: Document) {
        if (disposed) return
        if (FileDocumentManager.getInstance().getFile(document) !in autosaved) return
        // Read on every edit, so a change to the settings applies to the next keystroke. 0 or less is
        // off: saving is then left to IntelliJ -- Ctrl+S, switching away, or its own idle save.
        val quiet = autosaveMillisForTest ?: workbench?.configuration?.effective?.let(::autosaveMillis) ?: 0
        if (quiet <= 0) return
        val next = scope.launch(Dispatchers.EDT, start = CoroutineStart.LAZY) {
            delay(quiet.milliseconds)
            saveWhenIdle(document, currentCoroutineContext().job)
        }
        saves.put(document, next)?.cancel()
        next.start()
    }

    /**
     * Saves on the editor thread, unless the user is in the middle of something.
     *
     * Deferred rather than forced while a command runs or a completion popup is open: a save there
     * lands halfway through something the user is still doing. Saved without stripping trailing
     * spaces, which would move the caret; see [CodeBridgeWorkspaceEdits.save]. [save] is the job this runs
     * in, so a later edit's save, which replaces it, is left to run instead.
     */
    private fun saveWhenIdle(document: Document, save: Job) {
        if (disposed || saves[document].let { it != null && it !== save }) return
        val manager = FileDocumentManager.getInstance()
        if (!manager.isDocumentUnsaved(document)) { saves.remove(document); return }
        val busy = CommandProcessor.getInstance().currentCommand != null ||
            LookupManager.getInstance(project).activeLookup != null
        if (busy) { saveLater(document); return }
        saves.remove(document)
        CodeBridgeWorkspaceEdits.save(listOf(document))
    }

    /** Saved documents an undo or redo is changing, to be saved again once it has finished. */
    private val resave = linkedSetOf<Document>()

    /**
     * Keeps a saved file saved across undo and redo.
     *
     * A fix is saved as soon as it is applied (see [CodeBridgeWorkspaceEdits.save]), so undoing it has to be
     * saved too -- otherwise the extension's on-disk analysis, `cargo check` for rust-analyzer, keeps
     * reporting on the fixed text while the editor shows the original. Only a document that was
     * saved before the undo touched it: undoing typing the user has not saved leaves it unsaved,
     * as it would be anywhere else in the IDE.
     *
     * Noted here, before the change, because that is the last moment the document's saved state
     * still describes the text the undo is replacing; and saved later, on the editor thread once the
     * undo command is over, since a document cannot be saved from inside the change.
     */
    private fun undoing(document: Document) {
        if (disposed || project.isDisposed) return
        if (!UndoManager.getInstance(project).isUndoOrRedoInProgress) return
        val manager = FileDocumentManager.getInstance()
        if (manager.isDocumentUnsaved(document) || mirrored(document) == null) return
        if (resave.isEmpty()) scope.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
            val documents = resave.toList()
            resave.clear()
            CodeBridgeWorkspaceEdits.save(documents)
        }
        resave += document
    }

    private fun saved(document: Document) {
        val file = mirrored(document) ?: return
        // Queued behind the edit that preceded the save, so the host has the saved text by now.
        submit { features.documentSaved(file) }
    }

    /** The file behind [document], or null unless this project mirrored it into its host. */
    private fun mirrored(document: Document): VirtualFile? {
        if (disposed || project.isDisposed) return null
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        return file.takeIf { features.isMirrored(it) }
    }

    /** Queues work on [worker], reporting whether it was taken: nothing is once the project closes. */
    private fun submit(block: () -> Unit): Boolean {
        if (disposed) return false
        return !scope.launch(worker) { if (!disposed && !project.isDisposed) block() }.isCancelled
    }

    /**
     * Starts or joins the project host for one file and activates the extensions that claim it.
     *
     * Runs only on [worker], which is what lets [requested] go unsynchronized.
     */
    private fun attach(file: VirtualFile) {
        if (disposed || project.isDisposed) return
        // An in-memory file is served only as the untitled buffer an extension made it for, or the
        // document an extension provided -- known to the host by the URI it was provided for, as
        // VS Code opens one in an editor. Java Projects follows the editor into a library class
        // that way. Anything else has no path, so nothing could be mirrored for it.
        if (file is LightVirtualFile && features.languageOf(file) == null &&
            HostedContentProviders.getInstance(project).uriOf(file) == null
        ) return
        opened += file
        val name = file.name
        var startup: HostedProgress.Own? = null
        runCatching {
            val installed = ProjectExtensions.getInstance(project).enabled()
            val contributed = contributedLanguages(installed, file)
            if (contributed.isEmpty()) autosaved -= file else autosaved += file
            val languages = contributed.ifEmpty { builtinLanguages(installed, file) }
            if (languages.isEmpty()) {
                // Nothing to start for a file no extension claims. An extension may still hold it,
                // though, through `openTextDocument`; then the host is told it is on screen too, which
                // is also what `showTextDocument` is waiting to hear.
                if (!features.isShown(file)) features.languageOf(file)?.let { held ->
                    features.documentOpened(file, held, snapshot(file))
                }
                return@runCatching
            }
            // Before the host starts, so completion is registered for this file's language whether
            // or not startup is slow, and long before anyone can invoke a lookup in it.
            CodeBridgeCompletionLanguages.getInstance().observe(file.fileType)
            requested[name] = file
            // Only the host's start is shown, which VS Code has behind it before any file opens. Not
            // activation, which VS Code shows nothing for: an extension's `activate` may await its
            // whole workspace being loaded, and reports that with progress of its own. Not while the
            // runtime is missing either -- that start fails at once, and the download that ends the
            // wait shows progress of its own.
            if (host == null && runtimeAvailable()) {
                startup = progress.own(startingTitle(installed.filter { wakes(it, languages) }))
            }
            if (host == null) start(installed) { step -> startup?.text(step) }
            startup?.end()
            activate(languages)
            // The extension's own client synchronizes this upstream and publishes what comes back;
            // its features pick up documents that are already open when it starts, so the order of
            // this against activation does not matter.
            // A document an extension already holds keeps the language it was opened as; the host
            // refuses a second one for the same document.
            features.documentOpened(file, features.languageOf(file) ?: languages.first(), snapshot(file))
        }.onFailure { failure ->
            startup?.end()
            // No runtime is a property of the platform or the installation, reported on the
            // settings page; not worth a stack trace for every file opened.
            if (failure is VsCodeUnavailableException) LOG.info("Language support has no extension host: ${failure.message}")
            else LOG.warn("Could not start hosted language support", failure)
            stop()
        }
    }

    /**
     * Starts the host for the extensions that run with the window -- `*` and `onStartupFinished`,
     * GitLens and Error Lens -- and activates them, as VS Code does in every window it opens. Once
     * the project is open and indexed, as [HostedStartupExtensions] calls it, so the host does not
     * compete with indexing; nothing at all when no enabled extension asks to run at startup.
     *
     * Also starts the host for an extension whose `workspaceContains:` matches the workspace --
     * rust-analyzer's `Cargo.toml` -- which the host then activates itself as it starts.
     */
    fun startupFinished() {
        submit(::wakeStartupExtensions)
    }

    /** Runs only on [worker]; see [startupFinished]. */
    private fun wakeStartupExtensions() {
        if (disposed || project.isDisposed) return
        var startup: HostedProgress.Own? = null
        val hadHost = host != null
        runCatching {
            val installed = ProjectExtensions.getInstance(project).enabled()
            val waking = installed.filter { startupEvent(it) != null }
            // Only whether a host is needed for them: a host evaluates `workspaceContains:` itself
            // as it starts, and one already running has done so.
            val containing =
                if (host != null) emptyList() else installed.filter { it !in waking && containsWorkspace(it) }
            if (waking.isEmpty() && containing.isEmpty()) return@runCatching
            if (host == null && runtimeAvailable()) startup = progress.own(startingTitle(waking + containing))
            if (host == null) start(installed) { step -> startup?.text(step) }
            startup?.end()
            val session = host ?: return@runCatching
            for (extension in extensions) {
                val event = startupEvent(extension) ?: continue
                if (extension.id in activated) continue
                val identifier = ExtensionHostInitData.identifier(extension.id)
                val reason = buildJsonObject {
                    // VS Code activates `*` extensions as part of its startup, the others after it.
                    put("startup", event == "*"); put("activationEvent", event); put("extensionId", identifier)
                }
                // Not awaited, for the reason [activate] gives.
                activated[extension.id] = activation(session, extension, "[$identifier,$reason]")
            }
        }.onFailure { failure ->
            startup?.end()
            if (failure is VsCodeUnavailableException) LOG.info("Startup extensions have no extension host: ${failure.message}")
            else LOG.warn("Could not start the extensions that run at startup", failure)
            // A host something else started is left to it: its files are still being served.
            if (!hadHost) stop()
        }
    }

    /** Whether [extension] has code and a `workspaceContains:` event the workspace satisfies. */
    private fun containsWorkspace(extension: InstalledExtension): Boolean {
        if (extension.manifest.main == null) return false
        val base = project.basePath ?: return false
        return HostedWorkspace.containsAny(
            CodeBridgeWorkspace.of(Path.of(base)),
            extension.manifest.activationEvents,
            WORKSPACE_CONTAINS_TIMEOUT
        )
    }

    /** The startup event [extension] activates on, `*` before `onStartupFinished`, or null for neither. */
    private fun startupEvent(extension: InstalledExtension): String? {
        val manifest = extension.manifest
        if (manifest.main == null) return null
        return STARTUP_EVENTS.firstOrNull { it in manifest.activationEvents }
    }

    /**
     * The languages enabled extensions contribute for [file]'s name, the closest claim first -- the
     * one it is opened as, as VS Code picks among them.
     */
    private fun contributedLanguages(installed: List<InstalledExtension>, file: VirtualFile): Set<String> =
        closest(installed.flatMap { it.manifest.contributes.languages }, file.name)

    /**
     * The languages VS Code's built-in extensions give [file]'s name, such as `jsonc` for
     * `launch.json`, so an extension's provider for them is asked here as it is in VS Code.
     *
     * Only for a host that is running, or one an enabled extension would start for them: unlike a
     * language an installed extension contributes, a built-in one is no sign that anything installed
     * cares about the file, and starting a host to open `settings.json` in it would be all cost.
     */
    private fun builtinLanguages(installed: List<InstalledExtension>, file: VirtualFile): Set<String> {
        val installation = hostInstallation ?: runCatching { VsCodeRuntime.getInstance().installation() }.getOrNull()
        val languages = BuiltinLanguages.forName(installation, file.name)
        if (languages.isEmpty() || host != null || installed.any { wakes(it, languages) }) return languages
        return emptySet()
    }

    /**
     * Activates the extensions a document of [languages] wakes, once each per host, on [worker].
     *
     * Not awaited: `LanguageClient.start()` may wait for IntelliJ's initialize, which cannot happen
     * while [worker] is blocked here.
     */
    private fun activate(languages: Set<String>) {
        val session = host ?: return
        for (extension in extensions) {
            if (!wakes(extension, languages) || extension.id in activated) continue
            val identifier = ExtensionHostInitData.identifier(extension.id)
            val reason = buildJsonObject {
                put("startup", false); put("activationEvent", "onLanguage:${languages.first()}")
                put("extensionId", identifier)
            }
            activated[extension.id] = activation(session, extension, "[$identifier,$reason]")
        }
    }

    /** The host's answer to activating [extension] with [args], asked for now; a failure is logged as well. */
    private fun activation(session: ExtensionHostSession, extension: InstalledExtension, args: String): Deferred<String> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.call("ExtHostExtensionService", $$"$activate", args)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not activate ${extension.id}", failure)
                throw failure
            }
        }

    /**
     * Activates [extensionId] for its view [viewId] (`onView:`), starting the host first if none
     * runs, as showing a view does in VS Code. Returns once the extension's `activate` has.
     */
    suspend fun activateView(extensionId: String, viewId: String) = activate(extensionId, "onView:$viewId")

    /**
     * Activates [extensionId] for [event] -- `onView:<id>`, `onWebviewPanel:<viewType>` -- starting
     * the host first if none runs. Returns once the extension's `activate` has.
     *
     * On [worker], behind whatever starts or stops the host before it.
     */
    suspend fun activate(extensionId: String, event: String) {
        check(!disposed && !project.isDisposed) { "Project is closed" }
        val activation = withContext(worker) {
            var startup: HostedProgress.Own? = null
            try {
                check(!disposed && !project.isDisposed) { "Project is closed" }
                val installed = ProjectExtensions.getInstance(project).enabled()
                val extension = installed.firstOrNull { it.id == extensionId }
                    ?: throw IllegalStateException("$extensionId is not enabled")
                if (host == null) {
                    if (runtimeAvailable()) startup = progress.own(startingTitle(listOf(extension)))
                    try { start(installed) { step -> startup?.text(step) } } catch (failure: Throwable) { stop(); throw failure }
                }
                val session = checkNotNull(host) { "The extension host is not running" }
                // As for a file: the host's start is shown, the activation is not.
                startup?.end()
                if (extension.manifest.main == null) return@withContext null
                val identifier = ExtensionHostInitData.identifier(extension.id)
                val reason = buildJsonObject {
                    put("startup", false); put("activationEvent", event); put("extensionId", identifier)
                }
                activated.getOrPut(extension.id) { activation(session, extension, "[$identifier,$reason]") }
            } catch (failure: Throwable) {
                startup?.end()
                throw failure
            }
        }
        activation?.await()
    }

    /** Runs [command] as an extension's `executeCommand` would; fails when no host is running. */
    suspend fun executeCommand(command: String, arguments: JsonArray): MainThreadResult =
        (workbench ?: throw IllegalStateException("The extension host is not running")).executeCommand(command, arguments)

    /**
     * Runs [command] with the arguments [argumentsJson] spells, waiting for its answer as JSON: for a
     * split-mode test, which cannot hand a JSON array across its driver. Null when no host runs yet.
     */
    @TestOnly
    @Suppress("unused") // Called by the split-mode UI tests through the IDE driver.
    fun executeCommandForTest(command: String, argumentsJson: String): String? {
        if (host == null) return null
        val arguments = Json.parseToJsonElement(argumentsJson) as JsonArray
        return runBlockingMaybeCancellable { withTimeout(30.seconds) { executeCommand(command, arguments) } }.json
    }

    private fun wakes(extension: InstalledExtension, languages: Set<String>): Boolean {
        val manifest = extension.manifest
        return manifest.main != null && (
            manifest.contributes.languages.any { it.id in languages } ||
                manifest.activationEvents.any { it == "*" || it == "onStartupFinished" || it == "onLanguage" || it.removePrefix("onLanguage:") in languages }
            )
    }

    private fun label(extension: InstalledExtension) = extension.manifest.displayName ?: extension.manifest.name

    /** Names the extensions, since "rust-analyzer" says far more to a user than "extension host". */
    private fun startingTitle(waking: List<InstalledExtension>) =
        if (waking.isEmpty()) "Starting VS Code extension host" else "Starting ${waking.joinToString(", ", transform = ::label)}"

    /** Debug providers and language clients must run in the same host (e.g. Java debug commands). */
    suspend fun debugSession(selected: List<InstalledExtension>, workspace: Path, settings: JsonObject,
                             install: (Path) -> InstalledExtension): HostedDebugSession {
        check(!disposed) { "Project is closed" }
        // Made on [worker], which may finish making it after the caller has given up: then it is closed.
        var made: HostedDebugSession? = null
        val session = withTimeoutOrNull(DEBUG_SESSION_SECONDS.seconds) { withContext(worker) {
            val hadHost = host != null
            try {
                check(!disposed && !project.isDisposed) { "Project is closed" }
                if (host == null) start(ProjectExtensions.getInstance(project).enabled())
                val sessionHost = checkNotNull(host)
                check(selected.all { selection -> extensions.any { it.id == selection.id && it.enabled } }) { "A debugger extension is disabled or missing" }
                lateinit var session: HostedDebugSession
                session = HostedDebugSession(checkNotNull(hostInstallation), selected, workspace,
                    checkNotNull(storage), settings, install = { path ->
                        installingPayload.incrementAndGet()
                        try { install(path) } finally { installingPayload.decrementAndGet() }
                    }, sharedHost = sessionHost, sharedLocations = locations,
                    onClose = { debugSessions.remove(session) }, sharedAdapters = debugAdapters)
                // Registrations may predate the launch configuration, because a language activated first.
                synchronized(debugLock) {
                    debugRegistrations.forEach(session::handle)
                    debugSessions.add(session)
                }
                made = session
                session
            } catch (error: Throwable) {
                if (!hadHost) stop()
                throw error
            }
        } }
        if (session == null) {
            made?.close()
            throw TimeoutException("No debug session within $DEBUG_SESSION_SECONDS seconds")
        }
        return session
    }

    /** [status] hears each step before it runs, so a progress bar can say what is being waited on. */
    private fun start(installed: List<InstalledExtension>, status: (String) -> Unit = {}) {
        // Nothing between here and the host's first log line said where the time went, and this
        // runs on [worker], so every file waiting to be served waits for all of it.
        val phases = Phases()
        val workspace = Path.of(project.basePath ?: return).toAbsolutePath().normalize()
        val installation = VsCodeRuntime.getInstance().installation()
        phases.mark("runtime")
        hostInstallation = installation
        status("Preparing extension storage")
        val root = PathManager.getSystemDir().resolve(HOST_STORAGE)
        Files.createDirectories(root)
        removeLegacyStorage()
        pruneAbandonedStorage(root, live)
        phases.mark("sweeping old host storage")
        val storage = claimStorage(root, live)
        this.storage = storage
        extensions = installed
        status("Copying ${installed.size} extension(s)")
        locations = installed.associate { it.id to storage.resolve("extensions").resolve(it.id) }
        // Private copies allow platform payload installation without mutating the extension store.
        for (extension in installed) Files.walk(extension.directory).use { paths -> paths.forEach { source ->
            check(!disposed) { "Project host startup canceled" }
            require(!Files.isSymbolicLink(source)) { "Extension contains a symbolic link: $source" }
            val target = locations.getValue(extension.id).resolve(extension.directory.relativize(source).toString())
            if (Files.isDirectory(source)) Files.createDirectories(target)
            else Files.copy(source, target)
        } }
        // A private copy per host start, of everything installed. rust-analyzer alone ships a 38MB
        // server binary and its debug symbols beside it.
        phases.mark("copying ${installed.size} extension(s)")
        status("Reading extension settings")
        val userSettings = prepareUserSettings()
        lateinit var session: ExtensionHostSession
        // Why a `.code-workspace` file is not used, now or when it changes: worth a word, since the
        // folders the extensions see would otherwise differ from VS Code's without saying so.
        val workspaceProblem: (String) -> Unit = { problem ->
            LOG.warn(problem)
            messages.notify(Messages.Severity.WARNING, problem, source = "VS Code workspace")
        }
        val workbench = HostedWorkbench(
            workspace, PathManager.getSystemDir().resolve("codebridge/state"),
            installed.map { it.manifest.raw },
            { service, method, args -> session.call(service, method, args) },
            { message, error -> if (error == null) LOG.warn(message) else LOG.warn(message, error) },
            // Extensions' own logs, shown in the output panel beside their channels.
            object : HostedLogging.Files {
                override fun opened(resource: String, name: String, file: Path, extensionId: String?) =
                    output.logRegistered(resource, name, file, extensionId)
                override fun closed(resource: String) = output.logDeregistered(resource)
            },
            // An extension editing the project's settings file is worth a word: it is a file people
            // commit, and a menu click that changed it would otherwise do so silently.
            settingsChanged = { messages.notify(Messages.Severity.INFO, it, source = "VS Code settings") },
            userSettings = userSettings,
            // Through the VFS, so every project's listener above hears of it, and an open editor too.
            userSettingsWritten = { LocalFileSystem.getInstance().refreshNioFiles(listOf(CodeBridgeUserSettings.file)) },
            builtInCommands = { command, arguments -> CodeBridgeWorkbenchCommands.execute(project, command, arguments) },
            contextChanged = { key, value -> HostedContextKeys.getInstance(project).set(key, value) },
            settingsFiles = EditorSettingsFiles(project),
            // A `.code-workspace` file in the project makes it VS Code's multi-root workspace.
            initialLayout = CodeBridgeWorkspace.of(workspace, workspaceProblem),
            workspaceProblem = workspaceProblem)
        this.workbench = workbench
        // Reads every manifest's `contributes.configuration` to build the defaults the host is
        // handed; a large extension contributes hundreds of settings.
        phases.mark("workbench defaults")
        val workspaceData = ExtensionHostInitData.workspaceData(workbench.storage.workspaceId, workbench.layout)
        session = ExtensionHostSession(
            installation,
            installed.map { ExtensionHostInitData.describeExtension(it.manifest, locations.getValue(it.id)) }, storage,
            object : MainThreadHandler {
                override fun handle(call: MainThreadCall): String? = this@HostedLanguageServers.handle(call)
                override suspend fun answer(call: MainThreadCall): MainThreadResult =
                    // Asynchronous because the reply is the user's answer, which may take minutes.
                    if (call.service == Messages.SERVICE) messages.handle(call)
                    // Asynchronous because it is applied on the editor thread, which may itself be
                    // waiting on the host -- a code action running the command that sent this.
                    else if (call.service == WorkspaceEdit.SERVICE && call.method == WorkspaceEdit.TRY_APPLY)
                        features.applyWorkspaceEdit(call.argsJson)
                    // The same, and `showTextDocument` also waits for the file to be mirrored.
                    else if (call.service == TextEditors.SERVICE)
                        CodeBridgeTextEditors.getInstance(project).handle(call.method, call.argsJson)
                    // Asynchronous because a document is opened on [worker], behind the edits before it.
                    else if (call.service == Documents.MAIN_THREAD) documents.handle(call)
                    // Asynchronous because the answer is whether the run started, which takes the EDT.
                    else if (call.service == DebugService.SERVICE && call.method == DebugService.START_DEBUGGING)
                        CodeBridgeStartDebugging.start(project, call.argsJson)
                    else if (call.service == DebugService.SERVICE && call.method == DebugService.STOP_DEBUGGING)
                        CodeBridgeDebugSessions.stop(project, call.argsJson)
                    // Asynchronous because the reply is the adapter's, which answers when it will.
                    else if (call.service == DebugService.SERVICE && call.method == DebugService.CUSTOM_REQUEST)
                        customDebugRequest(call.argsJson)
                    // Asynchronous because the reply is the user's choice.
                    else if (call.service == QuickOpen.SERVICE) (quickOpen ?: throw stopping()).handle(call)
                    // Asynchronous because the reply is the file the user picks.
                    else if (call.service == Dialogs.SERVICE) dialogs.handle(call)
                    // Asynchronous because `vscode.tests.runTests` is answered once the run is over.
                    else if (call.service == Testing.SERVICE) testing.handle(call)
                    else if (call.service == Tasks.SERVICE) (tasks ?: throw stopping()).handle(call)
                    // Asynchronous because a terminal is made on the editor thread, and `createTerminal`
                    // is answered only once the host has taken the terminal's id.
                    else if (call.service == Terminals.SERVICE) (terminals ?: throw stopping()).handle(call)
                    // Asynchronous because signing in waits on the user, and on the provider's host calls.
                    else if (call.service == Authentication.SERVICE) (authentication ?: throw stopping()).handle(call)
                    // Asynchronous because a link is answered once the client has opened it, which may ask the user.
                    else if (HostedWindow.handles(call)) HostedWindow.handle(
                        call,
                        HostedClient.getInstance(project)::browse
                    )
                    // Asynchronous because the clipboard is the client's, which answers when it has.
                    else if (call.service == Clipboard.SERVICE) HostedClient.getInstance(project).clipboard(call)
                    else if (call.service == Urls.SERVICE) (urls ?: throw stopping()).handle(call)
                    // Asynchronous because unpacking a debugger's platform package takes seconds, and
                    // every call the host makes meanwhile would queue behind it.
                    else if (isInstallFromVsix(call)) installPayload(call)
                    // Before the workbench, whose file system is the files on disk alone.
                    else if (extensionFileSystem(call)) MainThreadResult()
                    else workbench.handle(call) ?: MainThreadResult(handle(call))
            },
            workspace = workspaceData, configuration = workbench.configuration.snapshot(),
            globalStorageHome = workbench.storage.globalHome, workspaceStorageHome = workbench.storage.workspaceHome,
            report = { LOG.warn(it) },
        )
        host = session
        val adapters = ExtensionHostDebugAdapters(session, HostedBreakpoints.getInstance(project).adapters)
        debugAdapters = adapters
        session.termination.invokeOnCompletion { adapters.hostClosed() }
        val terminals = HostedTerminals(project, workspace) { service, method, args -> session.call(service, method, args) }
        this.terminals = terminals
        quickOpen = HostedQuickOpen(project) { service, method, args -> session.call(service, method, args) }
        authentication = HostedAuthentication({ service, method, args -> session.call(service, method, args) },
            installed.map { it.manifest.raw }, { extensionId, event -> activate(extensionId, event) }, messages::show,
            AuthenticationAccess(PathManager.getSystemDir().resolve("codebridge/state/authentication.json"))
        )
        urls = HostedUrls(
            installation.uriScheme,
            { service, method, args -> session.call(service, method, args) },
            { extensionId, event -> activate(extensionId, event) },
            messages::show,
            owner = project
        )
        tasks = HostedTasks(project, terminals) { service, method, args -> session.call(service, method, args) }
        features.hostStarted(session)
        output.hostStarted(session)
        progress.hostStarted(session)
        statusBar.hostStarted(session)
        treeViews.hostStarted(session, workbench::executeCommand)
        webviews.hostStarted(session)
        HostedContentProviders.getInstance(project).hostStarted(session)
        decorations.hostStarted { service, method, args -> session.call(service, method, args, true) }
        testing.hostStarted { service, method, args, cancellable -> session.call(service, method, args, cancellable) }
        status("Watching the workspace")
        workbench.startWatching()
        // Registers a native watch for every directory under the workspace that no exclude covers.
        phases.mark("watching the workspace")
        check(!disposed && !project.isDisposed) { "Project is closed" }
        status("Launching the extension host")
        session.start()
        phases.mark("launching the host process")
        status("Waiting for the extension host")
        check(session.awaitInitialized(30, TimeUnit.SECONDS)) { "Extension host initialization timed out" }
        phases.mark("host handshake")
        workbench.initialized()
        features.hostInitialized()
        HostedBreakpoints.getInstance(project).hostStarted { method, args -> session.call(DebugService.EXT_HOST, method, args) }
        HostedContextKeys.getInstance(project).configuration = { workbench.configuration.effectiveFor(null) }
        LOG.info("Extension host ready in $phases")
        scope.launch {
            val error = session.termination.await()
            withContext(worker) {
                if (!disposed && host === session) { LOG.warn("Language extension host stopped", error); stop() }
            }
        }
    }

    /**
     * What each step of starting a host cost, for a log line that can be read at a glance.
     *
     * Wall clock rather than sampling: the question is only ever which step the user waited on, and
     * the steps are few enough to name.
     */
    private class Phases {
        private val started = System.nanoTime()
        private var last = started
        private val steps = StringBuilder()

        fun mark(name: String) {
            val now = System.nanoTime()
            if (steps.isNotEmpty()) steps.append(", ")
            steps.append(name).append(' ').append((now - last) / 1_000_000).append("ms")
            last = now
        }

        override fun toString(): String = "${(last - started) / 1_000_000}ms: $steps"
    }

    /** Read under a read action: this runs on [worker], never on the editor thread. */
    private fun snapshot(file: VirtualFile): HostedLanguageFeatures.Snapshot =
        ReadAction.computeBlocking<HostedLanguageFeatures.Snapshot, RuntimeException> {
            val document = FileDocumentManager.getInstance().getDocument(file)
            snapshot(document)
        }

    private fun snapshot(document: Document?): HostedLanguageFeatures.Snapshot =
        ReadAction.computeBlocking<HostedLanguageFeatures.Snapshot, RuntimeException> {
            HostedLanguageFeatures.Snapshot(
                text = document?.text ?: "",
                dirty = document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document),
            )
        }

    /**
     * Creates an untitled buffer holding [content] and shows it, as `openTextDocument({ content })`
     * followed by `showTextDocument` would, answering with the editor id the host would be given.
     *
     * For the split-mode test, through the IDE driver: whether an in-memory file the backend made
     * can be opened in the client is the one thing about untitled buffers a single process cannot
     * show. No host is involved; see [HostedLanguageFeatures.mirrorWithoutHostForTest].
     */
    @TestOnly
    @Suppress("unused") // called from the split-mode UI tests, in the backend process
    fun showUntitledForTest(content: String): String? {
        features.mirrorWithoutHostForTest()
        val options = buildJsonObject { put("content", content) }
        return runBlockingMaybeCancellable {
            withTimeout(30.seconds) {
                val created = documents.handle(MainThreadCall(Documents.MAIN_THREAD, Documents.TRY_CREATE, "[$options]")).json
                CodeBridgeTextEditors.getInstance(project).handle(TextEditors.SHOW, """[$created,{"preserveFocus":false}]""")
            }
        }.json
    }

    private fun isInstallFromVsix(call: MainThreadCall): Boolean =
        call.service == "MainThreadCommands" && call.method == $$"$executeCommand" &&
            runCatching { Args.of(call.method, call.argsJson).strOrNull(0) }.getOrNull() == INSTALL_FROM_VSIX

    /**
     * A debugger's platform package, which it downloads and installs when it activates -- CodeLLDB
     * does -- long before any session. Answered once it is unpacked, as VS Code answers the command.
     */
    private suspend fun installPayload(call: MainThreadCall): MainThreadResult {
        val running = extensions
        val at = locations
        // Held until the unpack is done, so the installation it makes does not restart this host.
        installingPayload.incrementAndGet()
        return try {
            withContext(Dispatchers.IO) {
                HostedDebugSession.installPayload(call.argsJson, running, at) { path ->
                    ExtensionInstallationService.getInstance().install(path) { }.extension
                }
            }
            MainThreadResult()
        } finally {
            installingPayload.decrementAndGet()
        }
    }

    /** `DebugSession.customRequest`, sent to the adapter of the session the extension named. */
    private suspend fun customDebugRequest(argsJson: String): MainThreadResult {
        val request = DebugService.customRequest(argsJson)
        val session = debugSessions.firstOrNull { it.id == request.sessionId }
            ?: throw IllegalStateException("No debug session ${request.sessionId}")
        return MainThreadResult(session.customRequest(request.command, request.arguments))
    }

    /** Whether [start] would find a runtime now, without paying for the attempt to find out. */
    private fun runtimeAvailable(): Boolean = VsCodeRuntime.getInstance().installationOrNull() != null

    /**
     * Answers a `MainThreadFileSystem` call about a file system an extension provides -- registering
     * one, or making sure its extension runs before reading from it, which it does once registered --
     * returning whether it was one.
     */
    private fun extensionFileSystem(call: MainThreadCall): Boolean {
        if (call.service != "MainThreadFileSystem") return false
        val contents = HostedContentProviders.getInstance(project)
        if (contents.handleFileSystem(call.method, call.argsJson)) return true
        return call.method == $$"$ensureActivation" && contents.providesFileSystem(
            Args.of(call.method, call.argsJson).str(0)
        )
    }

    /** Why a call for a service that only lives while a host runs finds none. */
    private fun stopping() = IllegalStateException("The extension host is stopping")

    private fun handle(call: MainThreadCall): String? = synchronized(debugLock) {
        if (features.handle(call.service, call.method, call.argsJson)) return@synchronized null
        // Routed by service name rather than by the "was it consumed" flag the rest uses, because
        // `$register` is the one call here whose result the host reads.
        if (call.service == "MainThreadOutputService") return@synchronized output.handle(call)
        if (call.service == "MainThreadProgress") { progress.handle(call); return@synchronized null }
        if (call.service == "MainThreadStatusBar") { statusBar.handle(call); return@synchronized null }
        if (call.service == TreeViews.SERVICE) { treeViews.handle(call); return@synchronized null }
        // Routed by service name: `$postMessage` is answered with whether the message went out.
        if (call.service in WEBVIEW_SERVICES) return@synchronized webviews.handle(call)
        if (call.service == Decorations.SERVICE) { decorations.handle(call); return@synchronized null }
        if (call.service == DocumentContentProviders.SERVICE) {
            HostedContentProviders.getInstance(project).handle(call); return@synchronized null
        }
        if (call.method == $$"$getTools" && call.service in setOf("MainThreadLanguageModelTools", "MainThreadChatSkills")) return@synchronized "[]"
        if (call.service == DebugService.SERVICE && call.method == DebugService.GET_PROTOCOL_BREAKPOINT)
            return@synchronized HostedBreakpoints.getInstance(project).protocolBreakpoint(call.argsJson)
        if (debugAdapters?.handle(call) == true) return@synchronized null
        if (call.service == DebugService.SERVICE && (call.method.startsWith($$"$register") || call.method.startsWith(
                $$"$unregister"
            ))) {
            debugRegistrations.add(call)
            // Every running session follows the registrations, as each asks providers of its own.
            debugSessions.forEach { it.handle(call) }
            return@synchronized null
        }
        if (call.service == "MainThreadExtensionService") reportActivation(call)
        val activeDebug = debugSessions.lastOrNull()
        if (activeDebug != null) activeDebug.handle(call) else when {
                call.method == $$"$getInitialState" -> "{\"isFocused\":true,\"isActive\":true}"
                call.service == "MainThreadErrors" -> { LOG.warn(call.argsJson); null }
                // Answered with no value rather than refused. A published extension calls this
                // during activation -- CodeLLDB does -- and an error here rejects the promise it
                // was awaiting, so the loud failure cost more than the missing command did. What
                // remains unimplemented is now visible in the log instead of fatal in the host.
                call.service == "MainThreadCommands" && call.method == $$"$executeCommand" -> {
                    LOG.warn("Workbench command is not implemented: ${call.argsJson.take(200)}")
                    null
                }
                else -> null
        }
    }

    /**
     * Logs what the host measured while starting an extension.
     *
     * The host times activation itself and hands the numbers over; they were being dropped. This is
     * the only breakdown of where an extension's startup went that does not need a profiler
     * attached to the host, and it separates the three things that look identical from outside:
     * loading the extension's code, running its `activate()`, and waiting for the promise it
     * returned -- which is where an extension waiting on *us* would show up.
     */
    private fun reportActivation(call: MainThreadCall) {
        runCatching {
            ExtensionActivations.failure(call)?.let { failure ->
                LOG.warn(failure.message)
                messages.notify(Messages.Severity.ERROR, failure.message, source = failure.extensionId,
                    detail = failure.missingDependency?.let { "Install and enable '$it' in VS Code Extensions." })
                return@runCatching
            }
            val args = Args.of(call.method, call.argsJson)
            when (call.method) {
                $$"$onDidActivateExtension" -> {
                    val id = args.objOrNull(0)?.get("value")?.jsonPrimitive?.contentOrNull ?: "An extension"
                    LOG.info(
                        "$id activated: ${args.longOrNull(1)}ms loading its code, " +
                            "${args.longOrNull(2)}ms in activate(), " +
                            "${args.longOrNull(3)}ms until the promise it returned resolved"
                    )
                }
                // The host's own startup marks, which say what it did before any extension ran.
                $$"$setPerformanceMarks" -> {
                    val marks = args.arrayOrNull(0).orEmpty().filterIsInstance<JsonObject>()
                        .joinToString(", ") {
                            "${it["name"]?.jsonPrimitive?.contentOrNull} ${it["startTime"]?.jsonPrimitive?.contentOrNull}ms"
                        }
                    if (marks.isNotEmpty()) LOG.info("Extension host startup: $marks")
                }
            }
        }.onFailure { LOG.debug("Could not read ${call.method}", it) }
    }

    private fun stop() {
        workbench?.close(); workbench = null
        val old = host; host = null; hostInstallation = null
        debugSessions.toList().forEach { it.close() }; debugSessions.clear()
        debugAdapters = null
        tasks?.close(); tasks = null
        terminals?.close(); terminals = null
        quickOpen?.close(); quickOpen = null
        authentication?.close(); authentication = null
        urls?.close(); urls = null
        synchronized(debugLock) { debugRegistrations.clear() }
        old?.close(); activated.clear(); extensions = emptyList()
        releaseStorage()
        documents.hostStopped()
        if (!project.isDisposed) {
            features.hostStopped()
            output.hostStopped()
            progress.hostStopped()
            statusBar.hostStopped()
            treeViews.hostStopped()
            webviews.hostStopped()
            decorations.hostStopped()
            HostedContentProviders.getInstance(project).hostStopped()
            HostedContextKeys.getInstance(project).hostStopped()
            HostedBreakpoints.getInstance(project).hostStopped()
            CodeBridgeEditorDecorations.getInstance(project).hostStopped()
            testing.hostStopped()
            messages.hostStopped()
            HostedClient.getInstance(project).hostStopped()
        }
    }

    /**
     * The user's settings file, made the first time any project needs it, or null if it cannot be.
     *
     * Made from VS Code's when there is one. Logged, not announced: copying them is what the user
     * would expect, and a notification about it on first start is only noise.
     */
    private fun prepareUserSettings(): Path? {
        val imported = try { CodeBridgeUserSettings.ensure() } catch (error: Exception) {
            LOG.warn("Could not create the VS Code user settings file ${CodeBridgeUserSettings.file}", error)
            return null
        }
        if (imported != null) LOG.info("Copied the VS Code user settings from $imported")
        // Known to the VFS, and watched, so a change made outside the IDE is noticed as well.
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(CodeBridgeUserSettings.file)
        userSettingsWatch.compareAndSet(null, LocalFileSystem.getInstance().addRootToWatch(CodeBridgeUserSettings.file.toString(), false))
        return CodeBridgeUserSettings.file
    }

    private val userSettingsWatch = java.util.concurrent.atomic.AtomicReference<LocalFileSystem.WatchRequest?>()

    override fun dispose() {
        userSettingsWatch.getAndSet(null)?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
        disposed = true
        workbench?.close()
        host?.close() // Unblocks initialization on the worker.
        releaseStorage()
    }

    /**
     * Gives up this session's storage without deleting it, so the next start can sweep it.
     *
     * Deliberately not a delete. `exthost-stdout.log` lives in there, and a host that has just
     * stopped is exactly the one whose log someone wants -- `SplitModeExtensionTest` reads it out of
     * this directory to explain a failure, and so does anyone diagnosing an extension by hand.
     * Keeping the last generation costs one copy on disk until a host starts again.
     */
    private fun releaseStorage() {
        live.remove(storage ?: return)
    }

    companion object {
        /** How long a debug session waits for the host to start and the session to be made. */
        private const val DEBUG_SESSION_SECONDS = 90L

        /** The activation events VS Code fires as a window opens, in the order it fires them. */
        private val STARTUP_EVENTS = listOf("*", "onStartupFinished")

        /** VS Code's: a `workspaceContains:` glob search that takes longer activates the extension anyway. */
        private val WORKSPACE_CONTAINS_TIMEOUT = 7.seconds

        private val LOG = Logger.getInstance(HostedLanguageServers::class.java)

        private const val INSTALL_FROM_VSIX = "workbench.extensions.command.installFromVSIX"

        private val WEBVIEW_SERVICES = setOf(Webviews.SERVICE, Webviews.PANELS, Webviews.VIEWS)

        /** The default for `files.autoSaveDelay`; VS Code's is 1000. */
        internal const val AUTOSAVE_DELAY_MILLIS = 300L

        /**
         * How long an edited document has to be quiet before [settings] have it saved, or 0 for never.
         *
         * VS Code's own settings: `files.autoSave: "afterDelay"` saves after `files.autoSaveDelay`.
         * Unlike VS Code's desktop default, no setting means `afterDelay` too, after
         * [AUTOSAVE_DELAY_MILLIS]: an extension that analyses the file on disk -- rust-analyzer's
         * `cargo check` -- otherwise reports on stale text until something saves, and IntelliJ's
         * own idle save is far slower. Any other value leaves saving to IntelliJ: `onFocusChange`
         * and `onWindowChange` are what it does already, and `off` is for a large project, where
         * every pause restarts a long check, or tools watching the disk (a dev server, a test
         * watcher) that react to every save.
         */
        internal fun autosaveMillis(settings: JsonObject): Long {
            val mode = (settings["files.autoSave"] as? JsonPrimitive)?.contentOrNull ?: "afterDelay"
            if (mode != "afterDelay") return 0
            return (settings["files.autoSaveDelay"] as? JsonPrimitive)?.longOrNull ?: AUTOSAVE_DELAY_MILLIS
        }

        /** Stands in for the workspace settings in a fixture, which has no host and so no workbench to read them from. */
        @Volatile @TestOnly
        internal var autosaveMillisForTest: Long? = null

        /**
         * What [settings] say [name] is for a file in [languageId]: a language block that names it --
         * `"[rust]"`, or `"[rust][toml]"` for several at once -- over the setting itself, as VS Code
         * reads a `language-overridable` setting.
         */
        internal fun languageSetting(settings: JsonObject, name: String, languageId: String?): JsonElement? {
            if (languageId != null) {
                val block = settings.entries.firstOrNull { (key, value) ->
                    value is JsonObject && name in value && LANGUAGE_BLOCK.matches(key) &&
                            LANGUAGE_IN_BLOCK.findAll(key).any { it.groupValues[1] == languageId }
                }
                (block?.value as? JsonObject)?.get(name)?.let { return it }
            }
            return settings[name]
        }

        private val LANGUAGE_BLOCK = Regex("""^(\[[^\[\]]+])+$""")
        private val LANGUAGE_IN_BLOCK = Regex("""\[([^\[\]]+)]""")

        /**
         * Per-session scratch: one directory per started host, holding a private copy of every
         * installed extension so the host can write platform payloads into one without mutating the
         * extension store. Nothing here outlives the host that made it -- persistent extension state
         * is [HostedWorkbench]'s `codebridge/state`, which this must never be confused with.
         */
        private const val HOST_STORAGE = "codebridge/hosts"

        /** What `HOST_STORAGE` was called while it was assumed to be an LSP concern. */
        private const val LEGACY_HOST_STORAGE = "vsxbridge/lsp"

        /**
         * Storage directories belonging to a host running right now.
         *
         * Application-wide, because the system directory is shared by every project in the IDE and
         * the sweep below must not delete a sibling project's live copies. Anything under the root
         * that is not in here belongs to a run that has ended -- normally or by a crash.
         */
        private val live = ConcurrentHashMap.newKeySet<Path>()

        /**
         * Removes host storage no live host owns, and the directory this one used to be called.
         *
         * Storage used to accumulate without limit: a directory per host start, each a full copy of
         * every installed extension, and nothing ever deleted one. Sweeping here rather than at stop
         * bounds that at one abandoned generation while leaving the last host's `exthost-stdout.log`
         * readable for as long as anyone is likely to want it.
         *
         * Never throws -- leftovers are not worth failing a host start over, and the next start
         * tries again.
         */
        internal fun pruneAbandonedStorage(root: Path, keep: Set<Path>) {
            runCatching {
                Files.list(root).use { entries ->
                    entries.filter { it !in keep }.forEach { stale ->
                        runCatching { NioFiles.deleteRecursively(stale) }
                            .onFailure { LOG.debug("Could not remove abandoned host storage at $stale", it) }
                    }
                }
            }.onFailure { LOG.warn("Could not sweep abandoned host storage under $root", it) }
        }

        /**
         * A fresh storage directory under [root], added to [live]: the lowest-numbered one no host
         * holds, so one IDE's host runs from the same path every time.
         *
         * Not a random name: Windows asks whether a program may accept connections once per path,
         * and redhat.java runs a server from the copy of its own JRE in here -- a new directory
         * each start asked again after every IDE launch. A slot the sweep could not empty, its
         * files still held by a process outliving its host, is passed over.
         */
        internal fun claimStorage(root: Path, live: MutableSet<Path>): Path = synchronized(live) {
            generateSequence(0) { it + 1 }.map { root.resolve("host-$it") }.first { slot ->
                slot !in live && runCatching { Files.createDirectory(slot) }.isSuccess
            }.also(live::add)
        }

        /** Removes what [HOST_STORAGE] was called before, once, on the way past. */
        private fun removeLegacyStorage() {
            val legacy = PathManager.getSystemDir().resolve(LEGACY_HOST_STORAGE)
            if (!Files.isDirectory(legacy)) return
            runCatching { NioFiles.deleteRecursively(legacy) }
                .onFailure { LOG.warn("Could not remove the former host storage directory $legacy", it) }
        }
    }
}

/**
 * Starts the extensions that run with the window once the project is open and indexed; see
 * [HostedLanguageServers.startupFinished].
 */
internal class HostedStartupExtensions : ProjectActivity {
    override suspend fun execute(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            if (!project.isDisposed) project.service<HostedLanguageServers>().startupFinished()
        }
    }
}
