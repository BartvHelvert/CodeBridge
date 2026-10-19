// Each kind of answer is logged the same way, so the messages repeat by design.
@file:Suppress("LoggingSimilarMessage")

package me.helvert.codebridge.language

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.ide.structureView.StructureViewFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.ExtensionHostSession
import me.helvert.codebridge.exthost.MainThreadResult
import me.helvert.codebridge.exthost.protocol.payload.*
import me.helvert.codebridge.language.HostedLanguageFeatures.Companion.EDIT_REPORT_EVERY
import me.helvert.codebridge.language.HostedLanguageFeatures.Companion.HELD_COMPLETION_SECONDS
import me.helvert.codebridge.language.HostedLanguageFeatures.Companion.SEMANTIC_TOKEN_TIMEOUT_SECONDS
import me.helvert.codebridge.launch.CodeBridgeDebugOrigins
import me.helvert.codebridge.workbench.HostedContentProviders
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Language features as the VS Code workbench sees them.
 *
 * Neither of the interception approaches this replaced could serve an ordinary published extension:
 * one needed the language server on child-process stdio, the other needed an unbundled
 * `vscode-languageclient`. The workbench needs neither, because extensions surface their features
 * *to it* -- providers are registered over `MainThreadLanguageFeatures`, diagnostics are pushed over
 * `MainThreadDiagnostics` -- whether they are backed by a language server or written in plain
 * JavaScript. This holds what arrives, and the annotator reads it back.
 *
 * Fed from [HostedLanguageServers.handle], which owns the host and the main-thread channel.
 */
@Service(Service.Level.PROJECT)
class HostedLanguageFeatures(
    val project: Project,
    /** The project's, for work started here that nobody waits for: a command an editor gesture runs, say. */
    internal val scope: CoroutineScope,
) {

    /** The editor's copy of a file: the text to mirror, and whether it differs from what is on disk. */
    data class Snapshot(val text: String, val dirty: Boolean)

    /**
     * Problems by resource, then by the collection that reported them.
     *
     * Two levels, because one extension routinely owns several collections and they speak about the
     * same file independently. rust-analyzer is the case that made this matter: it pushes
     * `cargo check` results under one owner and answers pulled requests under another, so filing
     * both under the resource alone let each erase the other -- every time flycheck reported the
     * file clean it wiped the live analysis, and the squiggle vanished a moment after appearing.
     */
    private val problems = ConcurrentHashMap<String, ConcurrentHashMap<String, List<Marker>>>()

    /**
     * Problems tasks' problem matchers found, filed as [problems] are, by resource and then owner.
     * Apart from them because no host reported them: they outlive a host restart, as the run that
     * found them does, and stay until the next run of a task with the same matcher owner.
     */
    private val taskProblems = ConcurrentHashMap<String, ConcurrentHashMap<String, List<Marker>>>()
    /**
     * The mirrored documents, indexed both ways.
     *
     * Diagnostics arrive by URI and have to find a file to repaint; the editor reports edits by
     * file and has to find the URI the host knows it by -- and must do so on the keystroke path,
     * which is why the file side is a plain lookup rather than a URI rebuilt each time. [uris]
     * holds the exact spelling the document was opened with, because the host matches documents by
     * string and would not recognise the same file spelled another way.
     */
    private val files = ConcurrentHashMap<String, VirtualFile>()
    private val uris = ConcurrentHashMap<VirtualFile, String>()
    private val hoverProviders = ConcurrentHashMap<Int, JsonElement>()
    private val completionProviders = ConcurrentHashMap<Int, JsonElement>()
    /** The completion providers that resolve items, whose lists are worth keeping in the host. */
    private val resolvingCompletionProviders: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val definitionProviders = ConcurrentHashMap<Int, JsonElement>()
    private val typeDefinitionProviders = ConcurrentHashMap<Int, JsonElement>()
    private val implementationProviders = ConcurrentHashMap<Int, JsonElement>()
    private val hierarchyProviders = Hierarchies.Kind.entries.associateWith { ConcurrentHashMap<Int, JsonElement>() }
    private val highlightProviders = ConcurrentHashMap<Int, JsonElement>()
    private val selectionRangeProviders = ConcurrentHashMap<Int, JsonElement>()
    private val linkedEditingProviders = ConcurrentHashMap<Int, JsonElement>()
    private val documentColorProviders = ConcurrentHashMap<Int, JsonElement>()
    private val documentLinkProviders = ConcurrentHashMap<Int, DocumentLinks.Registration>()

    /**
     * Each file's links, for the text they were asked about. Asked for by what follows them -- Ctrl
     * hovering or clicking -- for the text as it stands, rather than as the document changes: nothing
     * draws a link until then.
     */
    private val heldLinks = ConcurrentHashMap<VirtualFile, HeldLinks>()
    private val evaluatableProviders = ConcurrentHashMap<Int, JsonElement>()
    private val referenceProviders = ConcurrentHashMap<Int, JsonElement>()
    private val renameProviders = ConcurrentHashMap<Int, LanguageFeatures.RenameRegistration>()
    private val codeActionProviders = ConcurrentHashMap<Int, CodeActions.Registration>()
    private val signatureHelpProviders = ConcurrentHashMap<Int, SignatureHelp.Registration>()
    private val inlineCompletionProviders = ConcurrentHashMap<Int, InlineCompletions.Registration>()
    /**
     * Formatters, whole-document and range, which are separate registrations under separate handles.
     *
     * Unlike every other provider here, their selectors are read: offering to format a file takes it
     * away from the IDE's own formatter, so only a provider that claims the file may do so.
     */
    private val formattingProviders = ConcurrentHashMap<Int, Formatting.Registration>()
    private val rangeFormattingProviders = ConcurrentHashMap<Int, Formatting.Registration>()
    private val onTypeFormattingProviders = ConcurrentHashMap<Int, OnTypeFormatting.Registration>()
    /**
     * Semantic token providers, against the legend each one's numbers are read with.
     *
     * Two maps, because the whole-document and range variants are separate registrations answering
     * separate methods, and an extension that offers both offers them under different handles.
     */
    private val semanticTokenProviders = ConcurrentHashMap<Int, SemanticTokens.Registration>()
    private val semanticTokenRangeProviders = ConcurrentHashMap<Int, SemanticTokens.Registration>()

    private val inlayHintProviders = ConcurrentHashMap<Int, InlayHints.Registration>()

    /**
     * Each file's hints, at offsets into the text they were asked about.
     *
     * Asked for in the background and only read by the highlighting pass, which must not wait on an
     * extension: everything wanting to write to the document queues behind a pass.
     */
    private val inlays = ConcurrentHashMap<VirtualFile, HeldInlays>()

    /** Files whose hints changed since their last redraw; see [restartDaemon]. */
    private val inlaysChanged = ConcurrentHashMap.newKeySet<VirtualFile>()

    /**
     * Files with a hint request outstanding, and the debounce in front of each; as for colouring.
     * True for one asked about again while its request was out, which the answer cannot speak for.
     */
    private val pendingInlays = ConcurrentHashMap<VirtualFile, Boolean>()
    private val inlayJobs = ConcurrentHashMap<VirtualFile, Job>()

    private val codeLensProviders = ConcurrentHashMap<Int, CodeLenses.Registration>()

    /** Each file's lenses, as [inlays] holds hints: asked for in the background, read by code vision. */
    private val lenses = ConcurrentHashMap<VirtualFile, HeldLenses>()

    /** Files with a lens request outstanding, and the debounce in front of each; as for hints. */
    private val pendingLenses = ConcurrentHashMap<VirtualFile, Boolean>()
    private val lensJobs = ConcurrentHashMap<VirtualFile, Job>()

    private val documentSymbolProviders = ConcurrentHashMap<Int, DocumentSymbols.Registration>()

    /**
     * Each file's outline: the last answer, read by the breadcrumbs bar, the sticky lines pass and
     * the Structure view. Asked for by what reads it, as the document changes; see [currentOutline].
     */
    private val heldOutlines = ConcurrentHashMap<VirtualFile, HeldOutline>()

    /** The symbol request out for each file, which a second reader of the same text waits on too. */
    private val outlineRequests = ConcurrentHashMap<VirtualFile, OutlineRequest>()

    /** The document stamp each file's outline was last asked about, so a pass asks once rather than once a line. */
    private val outlineAskedAt = ConcurrentHashMap<VirtualFile, Long>()

    /** Moved on whenever every outline is forgotten, so an answer still out then is not kept. */
    private val outlineGeneration = AtomicLong()

    /** The debounce in front of a request nothing in the IDE asked for: a provider appearing, a retry of nothing. */
    private val symbolJobs = ConcurrentHashMap<VirtualFile, Job>()

    private val foldingRangeProviders = ConcurrentHashMap<Int, FoldingRanges.Registration>()

    /** Each file's folds, as [heldOutlines] holds its outline: asked for in the background, read by the folding pass. */
    private val heldFolds = ConcurrentHashMap<VirtualFile, HeldFolds>()

    /** Files whose folds changed since their last redraw; see [restartDaemon]. */
    private val foldsChanged = ConcurrentHashMap.newKeySet<VirtualFile>()

    /** Files with a folding request outstanding, and the debounce in front of each; as for hints. */
    private val pendingFolds = ConcurrentHashMap<VirtualFile, Boolean>()
    private val foldJobs = ConcurrentHashMap<VirtualFile, Job>()

    /** Workspace symbol providers, which claim no files: a query is about the whole workspace. */
    private val workspaceSymbolProviders = ConcurrentHashMap<Int, WorkspaceSymbols.Registration>()

    /** The last workspace symbol query, shared by everything asking it; see [workspaceSymbols]. */
    private var symbolSearch: SymbolSearch? = null

    /** The last colouring received for each file; what the annotator draws. */
    private val colours = ConcurrentHashMap<VirtualFile, List<SemanticTokens.Token>>()

    /** Where [colours] are drawn: straight onto each document, with no highlighting pass involved. */
    private val highlights = CodeBridgeSemanticHighlights(project, scope)

    /** The text each file's drawn colouring was placed against, to tell when an edit has moved it since. */
    private val colouredText = ConcurrentHashMap<VirtualFile, String>()

    /**
     * The answer each file's colouring last came from, while the host holds it to diff against.
     *
     * One per file, and only ever touched by the one request a file has out at a time.
     */
    private val heldColours = ConcurrentHashMap<VirtualFile, HeldColours>()

    /** Files with a request outstanding, so a slow provider is not asked the same thing five times. */
    private val pendingColours = ConcurrentHashMap<VirtualFile, Boolean>()

    /** The debounce, per file: a keystroke cancels the request the one before it was waiting to make. */
    private val colourJobs = ConcurrentHashMap<VirtualFile, Job>()

    /** Files with a redraw already on its way, so a burst of answers costs one daemon restart. */
    private val repaintJobs = ConcurrentHashMap<VirtualFile, Job>()

    /** What a redraw brings in, for timing it from the answer to the pass that draws it. */
    enum class Redraw { DIAGNOSTICS }

    /**
     * An answer waiting to be drawn, and when each step towards that happened.
     *
     * [summary] says what arrived and how long it took to arrive; the rest of the line is only known
     * once an annotator draws it. [since] is the edit it answers, when there was one.
     */
    private class RedrawTiming(val summary: String, val since: Long?, val answeredAt: Long) {
        @Volatile var restartedAt = 0L
    }

    private val redrawTimings = ConcurrentHashMap<Pair<VirtualFile, Redraw>, RedrawTiming>()

    /** When each file was last sent to the host, so a diagnostic can say how late it was. */
    private val editedAt = ConcurrentHashMap<VirtualFile, Long>()

    /** Edits accepted per file, so the log can show typing reaching the host without a line each. */
    private val edits = ConcurrentHashMap<VirtualFile, AtomicLong>()

    /**
     * The text last mirrored for each file.
     *
     * What the colouring is moved against when the text changes, and what a range provider is told
     * the extent of. Held here because both happen away from a read action -- on the sync worker
     * and on a coroutine -- where there is no editor document to ask.
     */
    private val mirrored = ConcurrentHashMap<VirtualFile, String>()

    /**
     * Held while [mirrored] and [colours] are brought into line with each other.
     *
     * The colouring for a file is always about the text [mirrored] holds, and two threads move
     * them: an edit replaces the text and moves the tokens it has, and an answer replaces the
     * tokens, moving them onto whatever text arrived while it was on its way. Interleaved, an
     * answer could file tokens about the old text just after the edit had moved everything else.
     */
    private val colourLock = Any()

    /** The language id each mirrored document was opened as, which is what a selector names. */
    private val languageIds = ConcurrentHashMap<VirtualFile, String>()

    /** Documents the host has been told about, so the extension's own client can synchronize them. */
    @Volatile private var documents: ExtensionHostDocuments? = null

    /**
     * How a request reaches the host: service, method, arguments, and whether it takes a token.
     *
     * A function rather than the session itself, so the feature tests can answer as an extension
     * would. What a completion looks like once IntelliJ has drawn it is only observable in a real
     * IDE, and a real IDE is the one place an extension host cannot be started.
     */
    @Volatile private var callHost: (suspend (String, String, String, Boolean) -> String)? = null

    /**
     * The same, for the methods that answer in bytes rather than JSON.
     *
     * Only semantic tokens do, and only because their result is a flat array of numbers big enough
     * that VS Code encodes it as a `VSBuffer`; everything else on this protocol replies in JSON.
     */
    @Volatile private var callHostForBytes: (suspend (String, String, String, Boolean) -> ByteArray?)? = null

    /** Whether a host is running to be asked anything; cheap, for deciding whether to ask at all. */
    val hostRunning: Boolean get() = callHost != null

    fun hostStarted(session: ExtensionHostSession) {
        this.callHost = { service, method, args, cancellable -> session.call(service, method, args, cancellable) }
        this.callHostForBytes = { service, method, args, cancellable ->
            session.callForBytes(service, method, args, cancellable)
        }
        this.documents = ExtensionHostDocuments(
            reportTabFailure = {
                LOG.warn("Could not publish the editor tab model; pull-based features will be idle", it)
            },
            callHost = { service, method, args -> session.call(service, method, args) },
        )
    }

    /** Answers with [call]: a test suspends in it to stand for a reply still on its way. */
    @TestOnly
    fun callHostForTest(call: suspend (String, String, String, Boolean) -> String) {
        this.callHost = call
    }

    @TestOnly
    fun callHostForBytesTest(call: suspend (String, String, String, Boolean) -> ByteArray?) {
        this.callHostForBytes = call
    }

    /**
     * Puts a mirror in place without a host behind it.
     *
     * The editor half of document sync -- which events arrive, on which thread, and what survives
     * a split or a reopened file -- is only observable inside a real IDE, and a real IDE is the one
     * place an extension host cannot be started. This lets the fixture record what would have been
     * sent instead.
     */
    @TestOnly
    fun mirrorForTest(documents: ExtensionHostDocuments) {
        this.documents = documents
    }

    /**
     * A mirror that answers every call and sends nothing, unless a host is already mirroring.
     *
     * For the split-mode test, which reaches this through the IDE driver and cannot hand over a
     * mirror of its own.
     */
    @TestOnly
    fun mirrorWithoutHostForTest() {
        if (documents == null) documents = ExtensionHostDocuments { _, _, _ -> "" }
    }

    /** The host has finished its handshake; see [ExtensionHostDocuments.hostInitialized]. */
    fun hostInitialized() {
        documents?.hostInitialized()
    }

    fun hostStopped() {
        documents = null
        callHost = null
        callHostForBytes = null
        // Not withdrawn: the mirror collection went with the host.
        project.serviceIfCreated<CodeBridgeIdeProblems>()?.hostStopped()
        files.clear()
        uris.clear()
        hoverProviders.clear()
        completionProviders.clear()
        resolvingCompletionProviders.clear()
        // Nothing to release: the lists went with the host.
        heldCompletions.clear()
        definitionProviders.clear()
        typeDefinitionProviders.clear()
        implementationProviders.clear()
        hierarchyProviders.values.forEach { it.clear() }
        highlightProviders.clear()
        selectionRangeProviders.clear()
        linkedEditingProviders.clear()
        documentColorProviders.clear()
        project.serviceIfCreated<CodeBridgeDocumentColors>()?.hostStopped()
        documentLinkProviders.clear()
        // Not released: the host that held their lists is gone.
        heldLinks.clear()
        evaluatableProviders.clear()
        referenceProviders.clear()
        renameProviders.clear()
        codeActionProviders.clear()
        signatureHelpProviders.clear()
        // Not freed: the lists went with the host.
        inlineCompletionProviders.clear()
        // Dropped, not released: its list ids belong to the host that has gone, and a new host may
        // hand out the same ids for lists of its own.
        CodeBridgeCodeActionCache.getInstance(project).take()
        formattingProviders.clear()
        rangeFormattingProviders.clear()
        onTypeFormattingProviders.clear()
        languageIds.clear()
        semanticTokenProviders.clear()
        semanticTokenRangeProviders.clear()
        inlayHintProviders.clear()
        inlayJobs.values.forEach(Job::cancel)
        inlayJobs.clear()
        pendingInlays.clear()
        codeLensProviders.clear()
        workspaceSymbolProviders.clear()
        // Dropped, not released: the host that held its lists is gone.
        synchronized(this) { symbolSearch = null }
        lensJobs.values.forEach(Job::cancel)
        lensJobs.clear()
        pendingLenses.clear()
        documentSymbolProviders.clear()
        symbolJobs.values.forEach(Job::cancel)
        symbolJobs.clear()
        foldingRangeProviders.clear()
        foldJobs.values.forEach(Job::cancel)
        foldJobs.clear()
        pendingFolds.clear()
        colours.clear()
        colouredText.clear()
        highlights.forgetAll()
        // Not released: the host that held them is gone.
        heldColours.clear()
        redrawTimings.clear()
        pendingColours.clear()
        mirrored.clear()
        editedAt.clear()
        edits.clear()
        colourJobs.values.forEach(Job::cancel)
        colourJobs.clear()
        repaintJobs.values.forEach(Job::cancel)
        repaintJobs.clear()
        val stale = problems.keys.toList()
        problems.clear()
        stale.forEach(::rehighlight)
        // After the redraws above were cancelled, or these would be too.
        forgetAllInlays()
        forgetAllCodeLenses()
        forgetAllOutlines()
        forgetAllFolds()
    }

    /**
     * Records a main-thread call if it carries language features.
     *
     * Returns true when the call was consumed, purely so the caller can read the intent; every
     * one of these is a notification the host does not wait on.
     */
    fun handle(service: String, method: String, argsJson: String): Boolean {
        try {
            @Suppress("IntroduceWhenSubject") // dispatches on the service and the method together
            when {
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_HOVER -> {
                    val registration = LanguageFeatures.hoverRegistration(argsJson)
                    hoverProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a hover provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_COMPLETIONS -> {
                    val (registration, _, resolves) = LanguageFeatures.completionRegistration(argsJson)
                    completionProviders[registration.handle] = registration.selector
                    if (resolves) resolvingCompletionProviders.add(registration.handle)
                    LOG.debug("An extension registered a completion provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_DEFINITION -> {
                    val registration = LanguageFeatures.definitionRegistration(argsJson)
                    definitionProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a definition provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_TYPE_DEFINITION -> {
                    val registration = LanguageFeatures.locationRegistration(method, argsJson)
                    typeDefinitionProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a type definition provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_IMPLEMENTATION -> {
                    val registration = LanguageFeatures.locationRegistration(method, argsJson)
                    implementationProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered an implementation provider")
                }
                service == "MainThreadLanguageFeatures" && Hierarchies.Kind.entries.any { it.register == method } -> {
                    val kind = Hierarchies.Kind.entries.first { it.register == method }
                    val registration = Hierarchies.registration(kind, argsJson)
                    hierarchyProviders.getValue(kind)[registration.handle] = registration.selector
                    LOG.debug("An extension registered a ${kind.name.lowercase()} hierarchy provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_HIGHLIGHTS -> {
                    val registration = LanguageFeatures.highlightRegistration(argsJson)
                    highlightProviders[registration.handle] = registration.selector
                }
                service == "MainThreadLanguageFeatures" && method == SelectionRanges.REGISTER -> {
                    val registration = SelectionRanges.registration(argsJson)
                    selectionRangeProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a selection range provider")
                }

                service == "MainThreadLanguageFeatures" && method == LinkedEditing.REGISTER -> {
                    val registration = LinkedEditing.registration(argsJson)
                    linkedEditingProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a linked editing range provider")
                }

                service == "MainThreadLanguageFeatures" && method == DocumentColors.REGISTER -> {
                    val registration = DocumentColors.registration(argsJson)
                    documentColorProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a document colour provider")
                    CodeBridgeDocumentColors.getInstance(project).requestEverywhere()
                }

                service == "MainThreadLanguageFeatures" && method == DocumentLinks.REGISTER -> {
                    val registration = DocumentLinks.registration(argsJson)
                    documentLinkProviders[registration.handle] = registration
                    LOG.debug("An extension registered a document link provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_EVALUATABLE -> {
                    val registration = LanguageFeatures.evaluatableRegistration(argsJson)
                    evaluatableProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered an evaluatable expression provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_REFERENCES -> {
                    val registration = LanguageFeatures.referenceRegistration(argsJson)
                    referenceProviders[registration.handle] = registration.selector
                    LOG.debug("An extension registered a reference provider")
                }
                service == "MainThreadLanguageFeatures" && method == LanguageFeatures.REGISTER_RENAME -> {
                    val registration = LanguageFeatures.renameRegistration(argsJson)
                    renameProviders[registration.handle] = registration
                    LOG.debug("An extension registered a rename provider")
                }
                service == "MainThreadLanguageFeatures" && method == CodeActions.REGISTER -> {
                    val registration = CodeActions.registration(argsJson)
                    codeActionProviders[registration.handle] = registration
                    LOG.debug("An extension registered a code action provider")
                }
                service == "MainThreadLanguageFeatures" && method == SignatureHelp.REGISTER -> {
                    val registration = SignatureHelp.registration(argsJson)
                    signatureHelpProviders[registration.handle] = registration
                    LOG.debug("An extension registered a signature help provider")
                }
                service == "MainThreadLanguageFeatures" && method == InlineCompletions.REGISTER -> {
                    val registration = InlineCompletions.registration(argsJson)
                    inlineCompletionProviders[registration.handle] = registration
                    LOG.debug("An extension registered an inline completion provider")
                }
                service == "MainThreadLanguageFeatures" && method == Formatting.REGISTER_DOCUMENT -> {
                    val registration = Formatting.registration(method, argsJson)
                    formattingProviders[registration.handle] = registration
                    LOG.info("${registration.extensionName ?: "An extension"} registered a document formatter")
                }
                service == "MainThreadLanguageFeatures" && method == Formatting.REGISTER_RANGE -> {
                    val registration = Formatting.registration(method, argsJson)
                    rangeFormattingProviders[registration.handle] = registration
                    LOG.info("${registration.extensionName ?: "An extension"} registered a range formatter")
                }
                service == "MainThreadLanguageFeatures" && method == OnTypeFormatting.REGISTER -> {
                    val registration = OnTypeFormatting.registration(argsJson)
                    onTypeFormattingProviders[registration.handle] = registration
                    LOG.debug("An extension registered an on-type formatter for ${registration.triggers}")
                }
                service == "MainThreadLanguageFeatures" && method == SemanticTokens.REGISTER -> {
                    val registration = SemanticTokens.registration(argsJson)
                    semanticTokenProviders[registration.handle] = registration
                    LOG.info("An extension registered a semantic token provider with " +
                        "${registration.legend.types.size} token type(s)")
                    requestSemanticTokensEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == SemanticTokens.REGISTER_RANGE -> {
                    val registration = SemanticTokens.rangeRegistration(argsJson)
                    semanticTokenRangeProviders[registration.handle] = registration
                    requestSemanticTokensEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == SemanticTokens.EMIT_EVENT -> {
                    // "What I said before has changed." For a language server that is usually the
                    // moment its project finished loading, and the point at which a file stops
                    // being coloured by the grammar alone. The event names the provider whose
                    // answer went stale; every open file is repainted regardless, because the next
                    // pass asks every provider anyway and the answer is pulled, not carried here.
                    LOG.debug("A semantic token provider reported a change: " +
                        "${SemanticTokens.emittedEventHandle(argsJson)}")
                    requestSemanticTokensEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == InlayHints.REGISTER -> {
                    val registration = InlayHints.registration(argsJson)
                    inlayHintProviders[registration.handle] = registration
                    LOG.info("${registration.displayName ?: "An extension"} registered an inlay hint provider")
                    requestInlayHintsEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == InlayHints.EMIT_EVENT -> {
                    // As for semantic tokens: the provider's hints have changed, and the new ones
                    // are pulled rather than carried here.
                    LOG.debug("An inlay hint provider reported a change: ${InlayHints.emittedEventHandle(argsJson)}")
                    requestInlayHintsEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == CodeLenses.REGISTER -> {
                    val registration = CodeLenses.registration(argsJson)
                    codeLensProviders[registration.handle] = registration
                    LOG.info("An extension registered a code lens provider")
                    requestCodeLensesEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == DocumentSymbols.REGISTER -> {
                    val registration = DocumentSymbols.registration(argsJson)
                    documentSymbolProviders[registration.handle] = registration
                    LOG.info("${registration.label ?: "An extension"} registered a document symbol provider")
                    requestDocumentSymbolsEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == FoldingRanges.REGISTER -> {
                    val registration = FoldingRanges.registration(argsJson)
                    foldingRangeProviders[registration.handle] = registration
                    LOG.info("An extension registered a folding range provider")
                    requestFoldingRangesEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == FoldingRanges.EMIT_EVENT -> {
                    // Under the provider's event handle, not its own: the new ranges are pulled
                    // rather than carried here, and every provider is asked anyway.
                    LOG.debug("A folding range provider reported a change: ${FoldingRanges.emittedEventHandle(argsJson)}")
                    requestFoldingRangesEverywhere()
                }
                service == "MainThreadLanguageFeatures" && method == WorkspaceSymbols.REGISTER -> {
                    val registration = WorkspaceSymbols.registration(argsJson)
                    workspaceSymbolProviders[registration.handle] = registration
                    LOG.debug("An extension registered a workspace symbol provider")
                }
                service == "MainThreadLanguageFeatures" && method == CodeLenses.EMIT_EVENT -> {
                    LOG.debug("A code lens provider reported a change: ${CodeLenses.emittedEventHandle(argsJson)}")
                    requestCodeLensesEverywhere()
                }
                // `$unregister(handle)` for every provider, whatever kind it was; only the hover and
                // completion registrations have a named counterpart, and extensions use neither.
                service == "MainThreadLanguageFeatures" && method.startsWith($$"$unregister") -> {
                    LanguageFeatures.unregisteredHandle(method, argsJson)?.let {
                        hoverProviders.remove(it)
                        completionProviders.remove(it)
                        resolvingCompletionProviders.remove(it)
                        definitionProviders.remove(it)
                        typeDefinitionProviders.remove(it)
                        implementationProviders.remove(it)
                        hierarchyProviders.values.forEach { providers -> providers.remove(it) }
                        highlightProviders.remove(it)
                        selectionRangeProviders.remove(it)
                        linkedEditingProviders.remove(it)
                        // Drawn, so the swatches it gave come off with it.
                        if (documentColorProviders.remove(it) != null) CodeBridgeDocumentColors.getInstance(project)
                            .requestEverywhere()
                        // The links it found go with it; the rest are asked for again when next wanted.
                        if (documentLinkProviders.remove(it) != null) forgetAllLinks()
                        evaluatableProviders.remove(it)
                        referenceProviders.remove(it)
                        renameProviders.remove(it)
                        codeActionProviders.remove(it)
                        signatureHelpProviders.remove(it)
                        inlineCompletionProviders.remove(it)
                        formattingProviders.remove(it)
                        rangeFormattingProviders.remove(it)
                        onTypeFormattingProviders.remove(it)
                        workspaceSymbolProviders.remove(it)
                        // Drawn, like colour, so the hints it gave come off with it; what the
                        // remaining providers say is asked for again.
                        if (inlayHintProviders.remove(it) != null) {
                            forgetAllInlays()
                            requestInlayHintsEverywhere()
                        }
                        if (codeLensProviders.remove(it) != null) {
                            forgetAllCodeLenses()
                            requestCodeLensesEverywhere()
                        }
                        if (documentSymbolProviders.remove(it) != null) {
                            forgetAllOutlines()
                            requestDocumentSymbolsEverywhere()
                        }
                        if (foldingRangeProviders.remove(it) != null) {
                            forgetAllFolds()
                            requestFoldingRangesEverywhere()
                        }
                        // Semantic tokens are drawn rather than merely offered, so a provider going
                        // away leaves colour on screen that nothing stands behind any more.
                        val drawn = semanticTokenProviders.remove(it) ?: semanticTokenRangeProviders.remove(it)
                        if (drawn != null) {
                            colours.clear()
                            colouredText.clear()
                            heldColours.values.removeIf { held -> held.handle == it }
                            highlights.forgetAll()
                        }
                    }
                }
                service == "MainThreadDiagnostics" && method == Diagnostics.CHANGE_MANY -> accept(argsJson)
                service == "MainThreadDiagnostics" && method == Diagnostics.CLEAR -> {
                    // One collection going away, not every problem in the project.
                    val cleared = Diagnostics.clearedOwner(argsJson)
                    val stale = problems.keys.toList()
                    for (key in stale) {
                        problems[key]?.remove(cleared)
                        problems.remove(key, EMPTY_PROBLEMS)
                    }
                    LOG.info("Diagnostics cleared for every file by '$cleared'")
                    stale.forEach(::rehighlight)
                }
                else -> return false
            }
        } catch (failure: Exception) {
            LOG.warn("Could not read $service.$method", failure)
        }
        return true
    }

    /**
     * The problems currently reported for [file], newest push wins.
     *
     * The annotator is registered for every language, so this runs against every file the daemon
     * touches, in projects that have no extensions installed at all. Both guards earn their place:
     * the empty check keeps the common case to one map read, and [key] walks to an nio path, which
     * a file on an in-memory, jar or http filesystem cannot produce and throws for.
     */
    fun problemsFor(file: VirtualFile): List<Marker> {
        if (problems.isEmpty() && taskProblems.isEmpty()) return emptyList()
        val key = runCatching { key(file) }.getOrNull() ?: return emptyList()
        // Every collection's findings, and ordered by owner so that what is drawn does not depend on
        // which of them happened to answer first.
        fun of(filed: Map<String, Map<String, List<Marker>>>) =
            filed[key]?.entries?.sortedBy { it.key }?.flatMap { it.value }.orEmpty()
        return of(problems) + of(taskProblems)
    }

    /**
     * Files what a task's problem matcher [owner] found in [file], replacing what it said before;
     * null withdraws it. See [me.helvert.codebridge.task.TaskProblems].
     */
    fun fileTaskProblems(owner: String, file: java.nio.file.Path, markers: List<Marker>?) {
        val uri = UriComponents.of(file).uri
        val key = UriComponents.key(uri)
        if (markers.isNullOrEmpty()) {
            if (taskProblems[key]?.remove(owner) == null) return
            taskProblems.remove(key, EMPTY_PROBLEMS)
        } else if (taskProblems.computeIfAbsent(key) { ConcurrentHashMap() }.put(owner, markers) == markers) return
        rehighlight(key, uri)
    }

    /**
     * Whether an extension offers to answer hovers in [file]: one is mirrored and some provider's
     * selector claims it. Asking about any provider at all would put a hover next to IntelliJ's own
     * in every file once an extension registers a catch-all one, as GitLens does.
     */
    fun hasHoverProviders(file: VirtualFile): Boolean =
        hoverProviders.isNotEmpty() && claiming(file, hoverProviders).isNotEmpty()

    fun hasHighlightProviders(): Boolean = highlightProviders.isNotEmpty()

    /** Whether an extension says what a debugger hover in [file] evaluates; then only it does. */
    fun hasEvaluatableProviders(file: VirtualFile): Boolean =
        evaluatableProviders.isNotEmpty() && claiming(file, evaluatableProviders).isNotEmpty()

    /**
     * What a debugger hover at [line] and [column], 1-based, evaluates, as the workbench asks it:
     * the first extension to answer decides. Blocking, and off the editor thread.
     */
    fun evaluatableExpression(file: VirtualFile, line: Int, column: Int): LanguageFeatures.EvaluatableExpression? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        for (handle in claiming(file, evaluatableProviders)) {
            val reply = awaitCancellable("Evaluatable expression provider $handle", HOVER_TIMEOUT_SECONDS) {
                // Cancellable: the host appends the token itself, so it is absent from the arguments.
                call(EXT_HOST_LANGUAGE_FEATURES, LanguageFeatures.PROVIDE_EVALUATABLE,
                    LanguageFeatures.provideEvaluatableExpression(handle, resource, Position(line, column)), true)
            } ?: continue
            LanguageFeatures.evaluatableExpression(reply)?.let { return it }
        }
        return null
    }

    fun documentHighlights(file: VirtualFile, line: Int, column: Int): List<LanguageFeatures.DocumentHighlight> {
        val call = callHost ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        for (handle in claiming(file, highlightProviders)) {
            val reply = awaitCancellable("Document highlight provider $handle", HIGHLIGHT_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, LanguageFeatures.PROVIDE_HIGHLIGHTS,
                    LanguageFeatures.provideHighlights(handle, resource, Position(line, column)), true)
            } ?: continue
            val highlights = LanguageFeatures.documentHighlights(reply)
            if (highlights.isNotEmpty()) return highlights
        }
        return emptyList()
    }

    /** Whether any extension has offered to colour, so the annotator can leave early if not. */
    /** Whether an extension says how a selection in [file] grows. */
    fun hasSelectionRangeProviders(file: VirtualFile): Boolean =
        selectionRangeProviders.isNotEmpty() && claiming(file, selectionRangeProviders).isNotEmpty()

    /**
     * The ranges a selection at [line] and [column], 1-based, grows through, from every provider that
     * claims [file] -- VS Code joins them too. Blocking, and off the editor thread; the caller picks
     * the smallest that encloses what is selected, so their order does not matter.
     */
    fun selectionRanges(file: VirtualFile, line: Int, column: Int): List<Range> {
        val call = callHost ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        return claiming(file, selectionRangeProviders).flatMap { handle ->
            val reply = awaitCancellable("Selection range provider $handle", HIGHLIGHT_TIMEOUT_SECONDS) {
                call(
                    EXT_HOST_LANGUAGE_FEATURES, SelectionRanges.PROVIDE,
                    SelectionRanges.provide(handle, resource, Position(line, column)), true
                )
            } ?: return@flatMap emptyList()
            SelectionRanges.ranges(reply)
        }
    }

    /**
     * Whether an extension says which ranges of [file] are edited together, and `editor.linkedEditing`
     * is on for its language.
     */
    fun editsLinked(file: VirtualFile): Boolean {
        if (linkedEditingProviders.isEmpty() || claiming(file, linkedEditingProviders).isEmpty()) return false
        val enabled = project.service<HostedLanguageServers>().setting(LinkedEditing.SETTING, languageIds[file])
        return (enabled as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * The ranges edited together with the one at [position] in [text], from the first provider that
     * names any, as VS Code takes them -- waited for in the host first. Null where none does, or the
     * host did not catch up. Blocking.
     */
    fun linkedEditingRanges(file: VirtualFile, text: String, position: Position): LinkedEditing.Ranges? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        if (!awaitMirrored(file, text)) return null
        for (handle in claiming(file, linkedEditingProviders)) {
            val reply = awaitCancellable("Linked editing range provider $handle", HIGHLIGHT_TIMEOUT_SECONDS) {
                call(
                    EXT_HOST_LANGUAGE_FEATURES,
                    LinkedEditing.PROVIDE,
                    LinkedEditing.provide(handle, resource, position),
                    true
                )
            } ?: continue
            LinkedEditing.ranges(reply)?.let { return it }
        }
        return null
    }

    /** Whether an extension finds colours in [file]. */
    fun hasDocumentColorProviders(file: VirtualFile): Boolean =
        documentColorProviders.isNotEmpty() && claiming(file, documentColorProviders).isNotEmpty()

    /**
     * The colours every provider that claims [file] finds in it, joined, as VS Code joins them; null
     * when none answered. Asked about the text the host holds, which the caller names as [text] to
     * tell whether the answer is still about it. Blocking.
     */
    fun documentColors(file: VirtualFile, text: String): List<DocumentColors.Found>? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        val answers = claiming(file, documentColorProviders).mapNotNull { handle ->
            awaitCancellable("Document colour provider $handle", DOCUMENT_COLOR_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, DocumentColors.PROVIDE, DocumentColors.provide(handle, resource), true)
            }?.let(DocumentColors::colors)
        }
        if (answers.isEmpty()) return null
        LOG.debug("Document colours for ${file.name}: ${answers.sumOf { it.size }} in ${text.length} characters")
        return answers.flatten()
    }

    /**
     * The edits that write the colour at [range] as [color] instead, from the first provider claiming
     * [file] that offers a way to write it -- once the host holds [text], which the range is about.
     * Null when none does. Blocking.
     */
    fun colorPresentation(file: VirtualFile, text: String, range: Range, color: java.awt.Color): List<TextEdit>? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        if (!awaitMirrored(file, text)) return null
        val (red, green, blue, alpha) = color.getRGBComponents(null).map(Float::toDouble)
        for (handle in claiming(file, documentColorProviders)) {
            val reply = awaitCancellable("Colour presentation provider $handle", DOCUMENT_COLOR_TIMEOUT_SECONDS) {
                call(
                    EXT_HOST_LANGUAGE_FEATURES,
                    DocumentColors.PRESENT,
                    DocumentColors.present(handle, resource, red, green, blue, alpha, range),
                    true
                )
            } ?: continue
            DocumentColors.presentation(reply, range)?.let { return it }
        }
        return null
    }

    /** The files the host has in an editor. */
    fun shownFiles(): List<VirtualFile> = files.values.filter(::isShown).distinct()

    /** Whether an extension finds links in [file]. */
    fun hasDocumentLinkProviders(file: VirtualFile): Boolean =
        documentLinkProviders.isNotEmpty() && claiming(
            file,
            documentLinkProviders.mapValues { it.value.selector }).isNotEmpty()

    /** One link, over offsets into the text it was found in, and the provider that found it. */
    data class PlacedLink(val start: Int, val end: Int, val link: DocumentLinks.Link, val handle: Int)

    /** Links, the text they were found in, and the lists the host keeps them in. */
    private class HeldLinks(val text: String, val links: List<PlacedLink>, val lists: List<Pair<Int, Int>>) {
        fun release(features: HostedLanguageFeatures) =
            lists.forEach { (handle, list) -> features.releaseDocumentLinks(handle, list) }
    }

    /**
     * The links in [file] as [text] stands, from every provider that claims it, as VS Code joins
     * them. Asked once per text: what was found is held until the text changes. Blocking, and off
     * the editor thread; nothing while the host holds other text than [text], since an answer would
     * be about that.
     */
    fun documentLinks(file: VirtualFile, text: CharSequence): List<PlacedLink> {
        heldLinks[file]?.takeIf { it.text.contentEquals(text) }?.let { return it.links }
        val call = callHost ?: return emptyList()
        val asked = mirrored[file]?.takeIf { it.contentEquals(text) } ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        val handles = claiming(file, documentLinkProviders.mapValues { it.value.selector })
        if (handles.isEmpty()) return emptyList()
        // In the project's scope rather than the caller's, so that a caller giving up does not
        // withdraw them: the list a resolving provider makes is then released once it arrives.
        val requests = handles.associateWith { handle ->
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                within(DOCUMENT_LINK_TIMEOUT_SECONDS) {
                    DocumentLinks.links(call(EXT_HOST_LANGUAGE_FEATURES, DocumentLinks.PROVIDE, DocumentLinks.provide(handle, resource), true))
                }
            }
        }
        val answers = try {
            runBlockingMaybeCancellable {
                requests.mapValues { (handle, request) ->
                    try {
                        request.await()
                    } catch (failure: Exception) {
                        // Not this caller's cancellation, which ends the wait instead: the request's own.
                        coroutineContext.ensureActive()
                        LOG.debug("Document link provider $handle failed", failure)
                        null
                    }
                }
            }
        } catch (cancelled: Throwable) {
            // The host keeps a resolving provider's list until told; one nobody will read is let go
            // of once it arrives.
            requests.forEach { (handle, request) ->
                scope.launch {
                    val answer = try {
                        request.await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    answer?.cacheId?.let { releaseDocumentLinks(handle, it) }
                }
            }
            throw cancelled
        }
        val lines = LineOffsets(asked)
        val placed = answers.flatMap { (handle, answer) ->
            answer?.links.orEmpty().mapNotNull { link ->
                val start = lines.offset(link.range.startLineNumber, link.range.startColumn) ?: return@mapNotNull null
                val end = lines.offset(link.range.endLineNumber, link.range.endColumn) ?: return@mapNotNull null
                if (end > start) PlacedLink(start, end, link, handle) else null
            }
        }
        val lists = answers.mapNotNull { (handle, answer) -> answer?.cacheId?.let { handle to it } }
        LOG.debug("Document links for ${file.name}: ${handles.size} provider(s) answered with ${placed.size} link(s)")
        heldLinks.put(file, HeldLinks(asked, placed, lists))?.release(this)
        return placed
    }

    /**
     * What [placed] leads to, asking the provider that found it when it left that to be worked out:
     * VS Code resolves a link only once it is followed. Null for one that leads nowhere.
     */
    suspend fun resolveDocumentLink(placed: PlacedLink): DocumentLinks.Link? {
        if (placed.link.target != null) return placed.link
        val cacheId = placed.link.cacheId ?: return null
        val call = callHost ?: return null
        return DocumentLinks.resolved(within(DOCUMENT_LINK_TIMEOUT_SECONDS) {
            call(EXT_HOST_LANGUAGE_FEATURES, DocumentLinks.RESOLVE, DocumentLinks.resolve(placed.handle, cacheId), true)
        })
    }

    private fun releaseDocumentLinks(handle: Int, cacheId: Int) =
        tell(DocumentLinks.RELEASE, DocumentLinks.release(handle, cacheId)) { "release document links $cacheId of provider $handle" }

    /** Lets go of every file's links, for a provider that has gone; they are asked for again when wanted. */
    private fun forgetAllLinks() {
        for (file in heldLinks.keys.toList()) heldLinks.remove(file)?.release(this)
    }

    fun hasSemanticTokenProviders(): Boolean =
        semanticTokenProviders.isNotEmpty() || semanticTokenRangeProviders.isNotEmpty()

    /**
     * Asks for [file]'s colouring once the document has been quiet for [quietMillis].
     *
     * Debounced per file, and superseding: a keystroke cancels the request the previous keystroke
     * was waiting to make. Without that, every character typed costs a full-document round trip
     * whose reply then restarts the daemon -- the language server doing real work, to produce an
     * answer about text the user has already replaced, at the cost of the pass that was drawing the
     * last one.
     */
    private fun requestSemanticTokens(file: VirtualFile, text: String, quietMillis: Long) {
        if (!hasSemanticTokenProviders()) return
        // Started only once it is in the map, and removed only if it is still the job that is in
        // there. Launching eagerly would let a zero-delay request finish before it was recorded,
        // leaving an entry behind that nothing ever takes out again.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            refreshSemanticTokens(file, text)
        }
        job.invokeOnCompletion { colourJobs.remove(file, job) }
        colourJobs.put(file, job)?.cancel()
        job.start()
    }

    /**
     * Waits for the debounce and the request behind it to settle.
     *
     * The push model is the part of this that a fixture cannot see: the editor test drives a
     * document event and then has to know when the answer to it has landed. Waiting on the two
     * pieces of bookkeeping is exact, where a sleep would be a guess.
     */
    /**
     * Waits for coalesced redraws to be issued.
     *
     * A redraw is deferred so that a diagnostic push and the colouring behind it cost one daemon
     * restart rather than two that cancel each other. An editor fixture has to let that land before
     * it highlights, or the restart arrives in the middle of the pass it is measuring -- which the
     * fixture rejects, for the same reason the real daemon suffers from it.
     */
    @TestOnly
    fun awaitRepaintsForTest(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline && repaintJobs.isNotEmpty()) Thread.sleep(5)
    }

    /** The colour highlighters drawn on [file] now. EDT only. */
    @TestOnly
    fun drawnColoursForTest(file: VirtualFile): List<RangeHighlighter> = highlights.drawnForTest(file)

    /**
     * Draws one token on the file at [path] as though a provider had answered with it.
     *
     * For the split-mode test, which reaches this through the IDE driver: it asks whether colour drawn
     * on the backend reaches the client, and an extension host behind it would only make a failure
     * ambiguous between "nothing answered" and "nothing was forwarded".
     */
    @TestOnly
    @Suppress("unused") // called from the split-mode UI tests, in the backend process
    fun drawColourForTest(path: String, line: Int, character: Int, length: Int, type: String): Boolean {
        val file = LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(path)) ?: return false
        highlights.draw(file, listOf(SemanticTokens.Token(line, character, length, type, emptySet())), null)
        return true
    }

    /** How many times highlighting has been restarted for a push, for tests that a push was or was not redrawn. */
    private val restarts = AtomicLong()

    @TestOnly
    fun restartsForTest(): Long = restarts.get()

    @TestOnly
    fun awaitSemanticTokensForTest(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): String? {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (colourJobs.isEmpty() && pendingColours.isEmpty()) return null
            Thread.sleep(5)
        }
        return "waiting on ${colourJobs.size} debounce job(s) ${colourJobs.values.map { it.toString() }} " +
            "and ${pendingColours.size} request(s); providers=${semanticTokenProviders.keys} " +
            "range=${semanticTokenRangeProviders.keys} mirrored=${mirrored.size}"
    }

    /**
     * Every mirrored document, for a provider that has only just appeared or changed its mind.
     *
     * A file that already has a request waiting keeps it rather than having it pushed back. The
     * waiting one asks anyway, and superseding it was costly: rust-analyzer announces a change a few
     * milliseconds after every edit, so the edit's own short wait was replaced by this longer one
     * each time, and colour arrived ~165ms after typing instead of ~65ms.
     */
    private fun requestSemanticTokensEverywhere() {
        if (!hasSemanticTokenProviders()) return
        for ((_, file) in files) {
            if (colourJobs.containsKey(file)) continue
            val text = mirrored[file] ?: continue
            requestSemanticTokens(file, text, REGISTRATION_QUIET_MILLIS)
        }
    }

    /**
     * Asks the providers, and draws the answer if it is new.
     *
     * No cancellation and one request per file at a time. Drawing touches no highlighting pass --
     * [CodeBridgeSemanticHighlights] puts the colours straight on the document -- so an answer can land at
     * any moment without costing the daemon anything.
     */
    private fun refreshSemanticTokens(file: VirtualFile, text: String) {
        val candidates = semanticCandidates(file)
        if (candidates.isEmpty()) return
        val resource = resourceOf(file) ?: return
        if (pendingColours.putIfAbsent(file, true) != null) return
        // The marker has to come back off whatever happens next. A provider that throws on the way
        // out -- rather than failing its request -- would otherwise leave this file
        // marked as "already being asked" forever, and it would never be coloured again.
        val since = editedAt[file]
        val askedAt = System.nanoTime()
        // Not in the debounce's job: an edit supersedes the wait before a request, never the request.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val tokens = try {
                askSemanticTokens(file, candidates, resource, wholeOf(text))
            } catch (cancelled: CancellationException) {
                pendingColours.remove(file)
                throw cancelled
            } catch (failure: Exception) {
                pendingColours.remove(file)
                LOG.debug("No semantic tokens for ${file.name}", failure)
                return@launch
            }
            pendingColours.remove(file)
            val (current, placed, redraw) = synchronized(colourLock) {
                // Closed while the question was out: nothing is drawing this file any more, and the
                // answer the host kept for it will never be diffed against.
                val current = mirrored[file] ?: run {
                    heldColours.remove(file)?.let(::releaseColours)
                    return@launch
                }
                // The answer is about the text as it was asked about. Anything typed since has
                // already moved the tokens held here, so this one has to be moved the same way.
                val placed = if (current == text) tokens else SemanticTokens.rebase(tokens, text, current)
                // When the answer changed, or the text did since the colouring on screen was drawn:
                // the editor moves highlighters with an edit by its own rules, which are not quite
                // the ones [SemanticTokens.rebase] uses -- a word typed at the end of keeps its
                // colour here, and not there -- so after an edit the answer is drawn even if it is
                // what is held. By identity, because [mirrored] holds a new string for every edit.
                // `orEmpty` so a file no provider has anything to say about, answered with nothing,
                // draws nothing.
                val changed = colours.put(file, placed).orEmpty() != placed
                @Suppress("StringReferentialEquality")
                val redraw = changed || (colouredText[file] !== current && placed.isNotEmpty())
                if (redraw) colouredText[file] = current
                Triple(current, placed, redraw)
            }
            val asked = since?.let { " asked ${(askedAt - it) / 1_000_000}ms after the last edit," } ?: ""
            if (redraw) {
                val answeredAt = System.nanoTime()
                val summary = "Semantic tokens for ${file.name}:$asked the extension answered in " +
                    "${(answeredAt - askedAt) / 1_000_000}ms with ${tokens.size} token(s)"
                highlights.draw(file, placed, current) { count ->
                    val now = System.nanoTime()
                    val total = since?.let { "; ${(now - it) / 1_000_000}ms after the last edit in all" } ?: ""
                    LOG.info("$summary; $count highlighter(s) drawn ${(now - answeredAt) / 1_000_000}ms later$total")
                }
            } else {
                LOG.info("Semantic tokens for ${file.name}:$asked the extension answered in " +
                    "${(System.nanoTime() - askedAt) / 1_000_000}ms with nothing new to draw")
            }
            // A debounce that fired while this was out found the file already being asked about and
            // stood down, so its edit has not been asked about by anything. Unless a later keystroke
            // has one waiting, ask now: a moved answer is a good stand-in, not the real one.
            if (current != text && !colourJobs.containsKey(file)) requestSemanticTokens(file, current, 0)
        }
    }

    /**
     * The extent of a document, in the workbench's 1-based coordinates.
     *
     * Only a range provider is given this, and only when an extension registered no whole-document
     * one. Computed from the mirrored text rather than the editor's document, because this runs on
     * a coroutine rather than under a read action.
     */
    private fun wholeOf(text: CharSequence): Range {
        var lines = 1
        var lastLineStart = 0
        for (index in text.indices) {
            if (text[index] == NEWLINE) {
                lines++
                lastLineStart = index + 1
            }
        }
        return Range(1, 1, lines, text.length - lastLineStart + 1)
    }

    /**
     * Asks each provider in turn until one has something to say.
     *
     * The first non-empty answer wins, as it does in VS Code -- two providers colouring the same
     * span disagree rather than add.
     *
     * Never on the workbench thread, which is also where every call *from* the host
     * is served on -- `MainThreadDiagnostics.$changeMany` included. Decoding there would put the cost
     * of turning tens of thousands of integers into tokens directly in front of the diagnostics that
     * would have drawn the user's errors; a reply resumes this on the coroutine's own dispatcher.
     */
    private suspend fun askSemanticTokens(
        file: VirtualFile,
        candidates: List<Candidate>,
        resource: UriComponents,
        whole: Range,
    ): List<SemanticTokens.Token> {
        var index = 0
        var diff = true
        while (index < candidates.size) {
            val call = callHostForBytes ?: return emptyList()
            val candidate = candidates[index]
            // The last whole answer this provider gave for the file, if this one is asked for the
            // difference from it. Taken out of the map, because the host forgets it once it replies.
            val previous = heldColours[file]
                ?.takeIf { diff && !candidate.ranged && it.handle == candidate.handle }
                ?.also { heldColours.remove(file, it) }
            val method = if (candidate.ranged) SemanticTokens.PROVIDE_RANGE else SemanticTokens.PROVIDE
            val args = if (candidate.ranged) SemanticTokens.provideRange(candidate.handle, resource, whole)
                else SemanticTokens.provide(candidate.handle, resource, previous?.resultId ?: 0)
            val bytes = try {
                within(SEMANTIC_TOKEN_TIMEOUT_SECONDS) { call(EXT_HOST_LANGUAGE_FEATURES, method, args, true) }
            } catch (cancelled: CancellationException) {
                previous?.let(::releaseColours)
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Semantic token provider ${candidate.handle} failed", failure)
                // A provider that threw leaves the host holding the answer it was asked to
                // diff against; one that answered has already dropped it, and a second release
                // is harmless.
                previous?.let(::releaseColours)
                index++
                diff = true
                continue
            }
            val decoded = SemanticTokens.decode(bytes, candidate.legend, previous?.data)
            val held = HeldColours(candidate.handle, decoded.resultId, decoded.data)
            when {
                // A delta that did not fit what it claims to be a diff of: ask again in full
                // rather than colour the file with a mixture of two answers.
                previous != null && bytes != null && decoded.data == null -> {
                    releaseColours(held)
                    diff = false
                }
                decoded.tokens.isEmpty() -> {
                    releaseColours(held)
                    index++
                    diff = true
                }
                else -> {
                    // A result id means the host kept this answer for the next request to be
                    // diffed against. It is kept here too, until then; anything it replaces,
                    // or that cannot serve as a base, is released rather than left in the host
                    // for as long as the provider lives. The range variant caches nothing.
                    if (candidate.ranged || decoded.data == null) releaseColours(held)
                    else heldColours.put(file, held)?.let(::releaseColours)
                    return decoded.tokens
                }
            }
        }
        return emptyList()
    }

    /** An answer the host is holding to diff the next request against, with the numbers it diffs. */
    private class HeldColours(val handle: Int, val resultId: Int, val data: IntArray?)

    /** Tells the host to let go of an answer; nothing is waited on, and an id of 0 is nothing held. */
    private fun releaseColours(held: HeldColours) {
        if (held.resultId == 0) return
        tell(SemanticTokens.RELEASE, SemanticTokens.release(held.handle, held.resultId)) { "release semantic tokens ${held.resultId}" }
    }

    /** One provider to ask, and which of the two methods answers for it. */
    private data class Candidate(val handle: Int, val legend: SemanticTokens.Legend, val ranged: Boolean)

    /**
     * Who to ask, in order.
     *
     * Whole-document providers only, unless there are none: an extension that registered both --
     * and the ones that matter, TypeScript and rust-analyzer among them, register both -- would
     * otherwise be asked the same question twice over, and the range call would be answered for the
     * whole file anyway.
     */
    private fun semanticCandidates(file: VirtualFile): List<Candidate> {
        val full = claiming(file, semanticTokenProviders.mapValues { it.value.selector })
            .mapNotNull { handle -> semanticTokenProviders[handle]?.let { Candidate(handle, it.legend, ranged = false) } }
        if (full.isNotEmpty()) return full
        return claiming(file, semanticTokenRangeProviders.mapValues { it.value.selector })
            .mapNotNull { handle -> semanticTokenRangeProviders[handle]?.let { Candidate(handle, it.legend, ranged = true) } }
    }

    /**
     * The providers among [selectors] whose selector claims [file], in registration order.
     *
     * VS Code asks only these, and so does this. Asking the rest is not merely wasted: providers
     * here are mostly asked one after another, so every extension registered for some other
     * language -- the CSS, HTML and TypeScript support the runtime brings with it -- added a round
     * trip in front of the one that had something to say.
     *
     * A file that was never mirrored -- the only kind without a language id -- asks no one: the
     * host has no such document, and every provider would fail with "Unable to retrieve document".
     * The reference provider asks about every file in the IDE, so that was any Ctrl+hover outside
     * an extension's files.
     */
    private fun claiming(file: VirtualFile, selectors: Map<Int, JsonElement?>): List<Int> {
        val entries = selectors.entries.sortedBy { it.key }
        val languageId = languageIds[file] ?: return emptyList()
        val path = resourceOf(file)?.path ?: return emptyList()
        return entries.filter { DocumentSelector.matches(it.value, languageId, path) }.map { it.key }
    }

    fun hasInlayHintProviders(): Boolean = inlayHintProviders.isNotEmpty()

    /** One hint, at an offset into the text it is about. */
    data class PlacedInlay(val offset: Int, val hint: InlayHints.Hint, val handle: Int)

    /**
     * Hints, the text they were placed against, and the lists the host keeps for them.
     *
     * [lists] are only those whose hints carry a command: the host deletes a command along with its
     * list, so a list is kept until the hints it gave are replaced or their file closes. A list
     * whose hints only lead to places is let go as soon as it is read.
     */
    private class HeldInlays(val text: String, val hints: List<PlacedInlay>, val lists: List<Pair<Int, Int>> = emptyList()) {
        fun release(features: HostedLanguageFeatures) = lists.forEach { (handle, list) -> features.releaseInlayHints(handle, list) }
    }

    /**
     * The hints held for [file], moved onto [text].
     *
     * Usually [text] is what they were asked about. While the next answer is on its way it is not,
     * and the hints are moved as the text was: those before the edit stay, those after it move with
     * it, and those inside it are dropped, since what they described has gone.
     */
    fun inlayHints(file: VirtualFile, text: CharSequence): List<PlacedInlay> =
        inlays[file]?.let { moved(it, text) }.orEmpty()

    private fun moved(held: HeldInlays, text: CharSequence): List<PlacedInlay> {
        if (held.hints.isEmpty() || held.text.contentEquals(text)) return held.hints
        val change = Documents.changeBetween(held.text, text)
        val growth = change.newEnd - change.oldEnd
        return held.hints.mapNotNull {
            when {
                it.offset <= change.start -> it
                it.offset >= change.oldEnd -> it.copy(offset = it.offset + growth)
                else -> null
            }
        }
    }

    /**
     * Asks for [file]'s hints once its document has been quiet for [quietMillis], as [requestSemanticTokens] does.
     *
     * [retryNothing] asks once more if every provider answers with nothing; see [refreshInlayHints].
     */
    private fun requestInlayHints(file: VirtualFile, text: String, quietMillis: Long, retryNothing: Boolean) {
        if (!hasInlayHintProviders()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            refreshInlayHints(file, text, retryNothing, coroutineContext.job)
        }
        job.invokeOnCompletion { inlayJobs.remove(file, job) }
        inlayJobs.put(file, job)?.cancel()
        job.start()
    }

    /** Every document shown in an editor, for a provider that has just appeared or changed its mind. */
    private fun requestInlayHintsEverywhere() {
        if (!hasInlayHintProviders()) return
        for ((_, file) in files) {
            if (inlayJobs.containsKey(file) || !isShown(file)) continue
            val text = mirrored[file] ?: continue
            requestInlayHints(file, text, REGISTRATION_QUIET_MILLIS, retryNothing = true)
        }
    }

    /**
     * Asks every provider that claims [file] at once, and keeps what they say between them.
     *
     * VS Code shows every provider's hints, not the first one's. The whole document is asked about,
     * where VS Code asks about what is on screen: the IDE draws hints in one pass over the file, and
     * the host drops any hint outside the range it was given.
     *
     * With [retryNothing], an answer of nothing from every provider is asked about once more. A
     * language server abandons a request whose document changed under it with `ContentModified`,
     * and VS Code's language client reports that as no hints at all -- the same `undefined` the
     * host sends for a file with none. rust-analyzer does this to the request its own "hints are
     * ready" refresh provoked, as it finishes loading the workspace, and then says nothing more;
     * the file showed no hints until it was edited. The request is failed as the change is applied,
     * so one asked after it is answered from the new state. Not for edits: the next one asks anyway,
     * and a file with no hints would cost a second question per keystroke.
     *
     * [asking] is the job this is asked from, which an answer that arrives at once finds still running.
     */
    private fun refreshInlayHints(file: VirtualFile, text: String, retryNothing: Boolean, asking: Job) {
        val call = callHost ?: return
        val handles = claiming(file, inlayHintProviders.mapValues { it.value.selector })
        if (handles.isEmpty()) return
        val resource = resourceOf(file) ?: return
        // Already being asked: say so, and the answer is followed by another question. A provider
        // that reports a change -- rust-analyzer, once it has indexed a file it was asked about on
        // open -- means the answer on its way is stale, even though the text is not, and without
        // this the file kept that answer, usually no hints at all, until the next edit.
        while (pendingInlays.putIfAbsent(file, false) != null) {
            if (pendingInlays.replace(file, true) != null) {
                LOG.debug("Inlay hints for ${file.name}: asked again while a request is out; asking once it is answered")
                return
            }
        }
        val askedAt = System.nanoTime()
        val whole = wholeOf(text)
        // Not in the debounce's job: an edit supersedes the wait before a request, never the request.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val answers = try {
                coroutineScope {
                    handles.map { handle -> async(start = CoroutineStart.UNDISPATCHED) { askInlayHints(call, handle, resource, whole) } }
                        .awaitAll()
                }
            } catch (failure: Throwable) {
                pendingInlays.remove(file)
                throw failure
            }
            // Only once the answer is filed, so that nothing waiting on the request sees it settled
            // with the old hints still held.
            var askedAgain: Boolean
            var nothing: Boolean
            val current = try {
                nothing = answers.all { it.hints.isEmpty() }
                val summary = "Inlay hints for ${file.name}: ${handles.size} provider(s) answered in " +
                    "${(System.nanoTime() - askedAt) / 1_000_000}ms with ${answers.sumOf { it.hints.size }} hint(s)"
                keepInlayHints(file, text, answers, summary)
            } finally {
                askedAgain = pendingInlays.remove(file) == true
            }
            // As for colouring: an edit, or a provider's change, whose own request stood down for
            // this one is asked about now.
            if (current == null || inlayJobs[file].let { it != null && it !== asking }) return@launch
            when {
                askedAgain -> requestInlayHints(file, current, 0, retryNothing = true)
                current != text -> requestInlayHints(file, current, 0, retryNothing = false)
                retryNothing && nothing -> requestInlayHints(file, current, 0, retryNothing = false)
            }
        }
    }

    /** One provider's hints, its list kept while a click may need it; nothing, for a provider that failed. */
    private suspend fun askInlayHints(
        call: suspend (String, String, String, Boolean) -> String,
        handle: Int,
        resource: UriComponents,
        whole: Range,
    ): InlayAnswer = try {
        val offered = InlayHints.offered(within(INLAY_HINT_TIMEOUT_SECONDS) {
            call(EXT_HOST_LANGUAGE_FEATURES, InlayHints.PROVIDE, InlayHints.provide(handle, resource, whole), true)
        })
        // Kept while a click may still need it: to run a command, which the host deletes
        // with its list, or to resolve a hint, which it looks up there.
        val clickable = inlayHintProviders[handle]?.supportsResolve == true ||
            offered.hints.any { hint -> hint.parts.any { it.command != null } }
        val kept = offered.cacheId?.let { list ->
            if (clickable) handle to list else null.also { releaseInlayHints(handle, list) }
        }
        InlayAnswer(handle, offered.hints, kept)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LOG.debug("Inlay hint provider $handle failed", failure)
        InlayAnswer(handle, emptyList(), null)
    }

    /**
     * Files the providers' answers about [text], and returns the text the file holds now -- or null
     * for a file closed while the question was out, which nothing shows any more.
     */
    private fun keepInlayHints(file: VirtualFile, text: String, answers: List<InlayAnswer>, summary: String): String? {
        val held = HeldInlays(text, place(text, answers.flatMap { answer -> answer.hints.map { answer.handle to it } }), answers.mapNotNull { it.kept })
        val current = mirrored[file] ?: return null.also {
            LOG.info("$summary, for a file closed meanwhile")
            held.release(this)
        }
        val previous = inlays[file]
        val shown = previous?.let { moved(it, current) }.orEmpty()
        if (moved(held, current).map(::uncached) == shown.map(::uncached)) {
            // What is on screen already, but for the ids clicking it resolves by, which name the
            // list each answer arrives in. A redraw costs a highlighting pass, and is not worth one
            // for that: the drawn hints keep their list, and this one goes back.
            LOG.info("$summary; nothing new to draw")
            if (previous == null) inlays[file] = held
            else {
                inlays[file] = HeldInlays(current, shown, previous.lists)
                held.release(this)
            }
            return current
        }
        inlays[file] = held
        // Clicking a hint drawn from the previous answer finds nothing once this is drawn instead.
        previous?.release(this)
        LOG.info("$summary; redrawing")
        redrawInlays(file)
        return current
    }

    /** [placed] without the id it is resolved by, which every answer renews. */
    private fun uncached(placed: PlacedInlay) = placed.copy(hint = placed.hint.copy(cacheId = null))

    /** One provider's answer: its hints, and the list kept for them in the host, if any is. */
    private class InlayAnswer(val handle: Int, val hints: List<InlayHints.Hint>, val kept: Pair<Int, Int>?)

    /**
     * The 1-based positions of [hints], each with the provider that gave it, as offsets into [text];
     * a column past the end of its line is clamped to it.
     */
    private fun place(text: String, hints: List<Pair<Int, InlayHints.Hint>>): List<PlacedInlay> {
        if (hints.isEmpty()) return emptyList()
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            text.forEachIndexed { index, char -> if (char == NEWLINE) add(index + 1) }
        }
        return hints.mapNotNull { (handle, hint) ->
            val line = hint.position.lineNumber - 1
            if (line < 0 || line >= lineStarts.size) return@mapNotNull null
            val start = lineStarts[line]
            val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length
            PlacedInlay((start + hint.position.column - 1).coerceIn(start, end), hint, handle)
        }.sortedBy { it.offset }
    }

    /** What clicking part [part] of [placed] does, as [InlayHints.click] encodes it; null for nothing. */
    fun inlayClick(placed: PlacedInlay, part: Int): String? =
        InlayHints.click(placed.hint, part, placed.handle.takeIf { inlayHintProviders[it]?.supportsResolve == true })

    /**
     * Asks a provider to fill in one of its hints, as VS Code does when one is hovered.
     *
     * Null when the provider added nothing, or no longer holds the hint -- its list has been
     * replaced by a newer answer.
     */
    suspend fun resolveInlayHint(handle: Int, cacheId: Pair<Int, Int>): InlayHints.Hint? {
        val call = callHost ?: return null
        return InlayHints.resolved(within(INLAY_RESOLVE_TIMEOUT_SECONDS) {
            call(EXT_HOST_LANGUAGE_FEATURES, InlayHints.RESOLVE, InlayHints.resolve(handle, cacheId), true)
        })
    }

    private fun releaseInlayHints(handle: Int, cacheId: Int) =
        tell(InlayHints.RELEASE, InlayHints.release(handle, cacheId)) { "release inlay hints $cacheId of provider $handle" }

    /** Has [file] redrawn with the hints held for it now -- its own, or a decoration's text. */
    internal fun redrawInlays(file: VirtualFile) {
        inlaysChanged += file
        repaint(file)
    }

    /** Takes every file's hints off, for a provider or a host that has gone. */
    private fun forgetAllInlays() {
        val hinted = inlays.keys.toList()
        // Released one by one rather than cleared, so an answer landing meanwhile is not lost unreleased.
        // Nothing is sent for a host that has gone: by then there is no one to send it to.
        for (file in hinted) inlays.remove(file)?.release(this)
        hinted.forEach(::redrawInlays)
    }

    /** Waits for the debounce and the hint request behind it to settle, as [awaitSemanticTokensForTest] does. */
    @TestOnly
    fun awaitInlayHintsForTest(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (inlayJobs.isEmpty() && pendingInlays.isEmpty()) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Waits for the debounces alone, leaving whatever request they made outstanding. */
    @TestOnly
    fun awaitInlayDebouncesForTest(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (inlayJobs.isEmpty()) return true
            Thread.sleep(5)
        }
        return false
    }

    fun hasCodeLensProviders(): Boolean = codeLensProviders.isNotEmpty()

    /** One lens, over offsets into the text it is about, with the provider that gave it. */
    data class PlacedLens(val start: Int, val end: Int, val lens: CodeLenses.Lens, val handle: Int)

    /**
     * Lenses, the text they were placed against, and the lists the host keeps for them.
     *
     * Every list whose lenses are shown is kept, since the host deletes the commands they run along
     * with it; a list is let go once its lenses are replaced, or their file closes.
     */
    private class HeldLenses(val text: String, val lenses: List<PlacedLens>, val lists: List<Pair<Int, Int>>) {
        fun release(features: HostedLanguageFeatures) = lists.forEach { (handle, list) -> features.releaseCodeLenses(handle, list) }
    }

    /**
     * The lenses held for [file], moved onto [text] as [inlayHints] moves hints.
     *
     * A lens is drawn above the line it starts on, and usually spans what it is about -- a whole
     * function -- so it is dropped only when its start was edited away; an edit inside it moves its
     * end alone.
     */
    fun codeLenses(file: VirtualFile, text: CharSequence): List<PlacedLens> =
        lenses[file]?.let { moved(it, text) }.orEmpty()

    private fun moved(held: HeldLenses, text: CharSequence): List<PlacedLens> {
        if (held.lenses.isEmpty() || held.text.contentEquals(text)) return held.lenses
        val change = Documents.changeBetween(held.text, text)
        val growth = change.newEnd - change.oldEnd
        fun shift(offset: Int) = when {
            offset <= change.start -> offset
            offset >= change.oldEnd -> offset + growth
            else -> change.newEnd
        }
        return held.lenses.mapNotNull {
            if (it.start > change.start && it.start < change.oldEnd) null
            else it.copy(start = shift(it.start), end = shift(it.end))
        }
    }

    /** Asks for [file]'s lenses once its document has been quiet for [quietMillis], as [requestInlayHints] does. */
    private fun requestCodeLenses(file: VirtualFile, text: String, quietMillis: Long) {
        if (!hasCodeLensProviders()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            refreshCodeLenses(file, text, coroutineContext.job)
        }
        job.invokeOnCompletion { lensJobs.remove(file, job) }
        lensJobs.put(file, job)?.cancel()
        job.start()
    }

    /** Every document shown in an editor, for a provider that has just appeared or changed its mind. */
    private fun requestCodeLensesEverywhere() {
        if (!hasCodeLensProviders()) return
        for ((_, file) in files) {
            if (lensJobs.containsKey(file) || !isShown(file)) continue
            val text = mirrored[file] ?: continue
            requestCodeLenses(file, text, REGISTRATION_QUIET_MILLIS)
        }
    }

    /**
     * Asks every provider that claims [file] at once, and keeps what they say between them, as
     * [refreshInlayHints] does.
     *
     * A lens offered without a command is resolved straight away. VS Code resolves the lenses on
     * screen as they scroll into view, which this side has no way to hear about, so it resolves them
     * all; one that still has no command is not drawn, as VS Code draws none.
     */
    private fun refreshCodeLenses(file: VirtualFile, text: String, asking: Job) {
        val call = callHost ?: return
        val handles = claiming(file, codeLensProviders.mapValues { it.value.selector })
        if (handles.isEmpty()) return
        val resource = resourceOf(file) ?: return
        // Already being asked: the answer on its way is followed by another question.
        while (pendingLenses.putIfAbsent(file, false) != null) {
            if (pendingLenses.replace(file, true) != null) return
        }
        val askedAt = System.nanoTime()
        // Not in the debounce's job: an edit supersedes the wait before a request, never the request.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val answers = try {
                coroutineScope {
                    handles.map { handle -> async(start = CoroutineStart.UNDISPATCHED) { askCodeLenses(call, handle, resource) } }
                        .awaitAll()
                }
            } catch (failure: Throwable) {
                pendingLenses.remove(file)
                throw failure
            }
            var askedAgain: Boolean
            val current = try {
                val summary = "Code lenses for ${file.name}: ${handles.size} provider(s) answered in " +
                    "${(System.nanoTime() - askedAt) / 1_000_000}ms with ${answers.sumOf { it.lenses.size }} lens(es)"
                keepCodeLenses(file, text, answers, summary)
            } finally {
                askedAgain = pendingLenses.remove(file) == true
            }
            if (current == null || lensJobs[file].let { it != null && it !== asking }) return@launch
            if (askedAgain || current != text) requestCodeLenses(file, current, 0)
        }
    }

    /** One provider's lenses, resolved; never fails, answering with nothing instead. */
    private suspend fun askCodeLenses(
        call: suspend (String, String, String, Boolean) -> String,
        handle: Int,
        resource: UriComponents,
    ): LensAnswer = try {
        val offered = CodeLenses.offered(within(CODE_LENS_TIMEOUT_SECONDS) {
            call(EXT_HOST_LANGUAGE_FEATURES, CodeLenses.PROVIDE, CodeLenses.provide(handle, resource), true)
        })
        resolveCodeLenses(call, handle, offered)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LOG.debug("Code lens provider $handle failed", failure)
        LensAnswer(handle, emptyList(), null)
    }

    private suspend fun resolveCodeLenses(
        call: suspend (String, String, String, Boolean) -> String,
        handle: Int,
        offered: CodeLenses.Offered,
    ): LensAnswer {
        val shown = coroutineScope {
            offered.lenses.map { lens ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    if (lens.title != null || lens.cacheId == null) return@async lens
                    try {
                        val reply = within(CODE_LENS_TIMEOUT_SECONDS) {
                            call(EXT_HOST_LANGUAGE_FEATURES, CodeLenses.RESOLVE, CodeLenses.resolve(handle, lens), true)
                        }
                        CodeLenses.resolved(reply)?.let { lens.copy(title = it.title, tooltip = it.tooltip, command = it.command) } ?: lens
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LOG.debug("Could not resolve a code lens of provider $handle", failure)
                        lens
                    }
                }
            }.awaitAll()
        }.filter { it.title != null }
        val kept = offered.cacheId?.let { list ->
            if (shown.isNotEmpty()) handle to list else null.also { releaseCodeLenses(handle, list) }
        }
        return LensAnswer(handle, shown, kept)
    }

    /** One provider's answer: its lenses, and the list kept for them in the host, if any is. */
    private class LensAnswer(val handle: Int, val lenses: List<CodeLenses.Lens>, val kept: Pair<Int, Int>?)

    /**
     * Files the providers' answers about [text], and returns the text the file holds now -- or null
     * for a file closed while the question was out.
     *
     * An answer that reads the same as what is drawn is let go, and what is drawn kept with its
     * lists: their commands are what the drawn lenses run, and the new ones would only differ in the
     * ids the host renews with every answer.
     */
    private fun keepCodeLenses(file: VirtualFile, text: String, answers: List<LensAnswer>, summary: String): String? {
        val held = HeldLenses(text, placeLenses(text, answers), answers.mapNotNull { it.kept })
        val current = mirrored[file] ?: return null.also {
            LOG.info("$summary, for a file closed meanwhile")
            held.release(this)
        }
        val previous = lenses[file]
        if (previous != null && moved(held, current).map(::readable) == moved(previous, current).map(::readable)) {
            LOG.info("$summary; nothing new to draw")
            held.release(this)
            return current
        }
        lenses[file] = held
        previous?.release(this)
        LOG.info("$summary; redrawing")
        redrawCodeLenses()
        return current
    }

    /** What a lens shows and where, leaving out the ids its command is renewed under. */
    private fun readable(placed: PlacedLens) =
        listOf(placed.start, placed.end, placed.lens.title, placed.lens.tooltip, placed.lens.command != null)

    /** The 1-based ranges of the answers' lenses as offsets into [text], each clamped to its line. */
    private fun placeLenses(text: String, answers: List<LensAnswer>): List<PlacedLens> {
        if (answers.all { it.lenses.isEmpty() }) return emptyList()
        val lines = LineOffsets(text)
        return answers.flatMap { answer ->
            answer.lenses.mapNotNull { lens ->
                val start = lines.offset(lens.range.startLineNumber, lens.range.startColumn) ?: return@mapNotNull null
                val end = lines.offset(lens.range.endLineNumber, lens.range.endColumn) ?: text.length
                PlacedLens(start, end.coerceAtLeast(start), lens, answer.handle)
            }
        }
    }

    /** Where 1-based lines and columns fall in [text], each clamped to its line. */
    private class LineOffsets(private val text: String) {
        private val lineStarts = ArrayList<Int>().apply {
            add(0)
            text.forEachIndexed { index, char -> if (char == NEWLINE) add(index + 1) }
        }

        val length: Int get() = text.length

        /** Where 1-based [line] ends in the text, before its line break. */
        fun lineEnd(line: Int): Int? {
            val index = line - 1
            if (index < 0 || index >= lineStarts.size) return null
            return if (index + 1 < lineStarts.size) lineStarts[index + 1] - 1 else text.length
        }

        fun offset(line: Int, column: Int): Int? {
            val index = line - 1
            if (index < 0 || index >= lineStarts.size) return null
            val start = lineStarts[index]
            val end = if (index + 1 < lineStarts.size) lineStarts[index + 1] - 1 else text.length
            return (start + column - 1).coerceIn(start, end)
        }
    }

    private fun releaseCodeLenses(handle: Int, cacheId: Int) =
        tell(CodeLenses.RELEASE, CodeLenses.release(handle, cacheId)) { "release code lenses $cacheId of provider $handle" }

    /**
     * Has code vision ask [CodeBridgeCodeLensProvider] again, in every editor.
     *
     * Not per file: the signal names an editor or all of them, and every editor's lenses are read
     * from what is held, which costs nothing to read again.
     */
    private fun redrawCodeLenses() {
        scope.launch(Dispatchers.EDT) {
            project.service<CodeVisionHost>().invalidateProvider(
                CodeVisionHost.LensInvalidateSignal(null, CodeBridgeCodeLensProvider.IDS)
            )
        }
    }

    /** Takes every file's lenses off, for a provider or a host that has gone. */
    private fun forgetAllCodeLenses() {
        val shown = lenses.keys.toList()
        for (file in shown) lenses.remove(file)?.release(this)
        if (shown.isNotEmpty()) redrawCodeLenses()
    }

    /** Waits for the debounce and the lens request behind it to settle, as [awaitInlayHintsForTest] does. */
    @TestOnly
    fun awaitCodeLensesForTest(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (lensJobs.isEmpty() && pendingLenses.isEmpty()) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Whether an extension outlines [file], so its breadcrumbs and sticky lines are drawn from that outline. */
    fun outlines(file: VirtualFile): Boolean =
        documentSymbolProviders.isNotEmpty() && claiming(file, documentSymbolProviders.mapValues { it.value.selector }).isNotEmpty()

    /**
     * One symbol of a file's outline, over offsets into the text it was placed against: [start] to
     * [end] is all it spans, [nameStart] where its name is.
     */
    data class OutlineSymbol(
        val start: Int,
        val nameStart: Int,
        val end: Int,
        val name: String,
        val detail: String,
        val kind: Int,
        val children: List<OutlineSymbol>,
    )

    /**
     * An outline, the text it was placed against, and the last document stamp it was moved onto,
     * with where it landed: the sticky lines pass asks once for every line of the document.
     */
    private class HeldOutline(val text: String, val symbols: List<OutlineSymbol>) {
        @Volatile var moved: Pair<Long, List<OutlineSymbol>>? = null
    }

    /**
     * A symbol request about [text], [answer] completing once what it brought is held -- or once
     * it is given up on. [pushed] is one nothing in the IDE asked for, whose answer has to be drawn
     * rather than picked up by whoever asked.
     */
    private class OutlineRequest(
        val text: String,
        val pushed: Boolean,
        val retryNothing: Boolean,
        val generation: Long
    ) {
        val answer = CompletableDeferred<Unit>()
    }

    /**
     * The outline held for [file], moved onto [document] as [codeLenses] moves lenses: a symbol
     * whose name was edited away is dropped, with what it holds. Asks for nothing; see [currentOutline].
     */
    fun outline(file: VirtualFile, document: Document): List<OutlineSymbol> {
        val held = heldOutlines[file] ?: return emptyList()
        val stamp = document.modificationStamp
        held.moved?.let { (at, symbols) -> if (at == stamp) return symbols }
        return moved(held, document.immutableCharSequence).also { held.moved = stamp to it }
    }

    /**
     * [file]'s outline of [document] as it stands, asked for and waited on if what is held is of
     * other text: for the breadcrumbs bar and the sticky lines pass, which run again as the document
     * changes and so need no telling that it has, as IntelliJ's LSP client answers them.
     *
     * Once per document stamp, since the sticky lines pass asks at the end of every line. On the
     * EDT, or with the host too far behind the editor to ask about its text, what is held is moved
     * instead. The wait gives up with the caller's progress, which typing cancels; the request goes
     * on, for the pass that comes after it to pick up.
     */
    fun currentOutline(file: VirtualFile, document: Document): List<OutlineSymbol> {
        val stamp = document.modificationStamp
        if (outlineAskedAt[file] != stamp && !ApplicationManager.getApplication().isDispatchThread && isMirrored(file) && outlines(
                file
            )
        ) {
            val text = document.text
            if (heldOutlines[file]?.text != text && awaitMirrored(file, text)) {
                val asked = askOutline(file, text, pushed = false, retryNothing = false)
                // Only the wait gives up with the caller: the request goes on for the next pass.
                runBlockingMaybeCancellable { asked.await() }
            }
            outlineAskedAt[file] = stamp
        }
        return outline(file, document)
    }

    /**
     * [outline], asking in the background for [document]'s text if what is held is of other text:
     * for the Structure view, which may build its tree on the EDT, and redraws on [CodeBridgeOutlineListener]
     * when the answer changes it.
     */
    @Suppress("DeferredResultUnused") // Not waited for: the listener redraws once the answer arrives.
    fun outlineRequesting(file: VirtualFile, document: Document): List<OutlineSymbol> {
        val stamp = document.modificationStamp
        if (outlineAskedAt[file] != stamp && isMirrored(file) && outlines(file)) {
            val text = document.text
            when {
                heldOutlines[file]?.text == text || outlineRequests[file]?.text == text -> {}
                mirrored[file] == text -> askOutline(file, text, pushed = false, retryNothing = false)
                else -> scope.launch {
                    if (runInterruptible { awaitMirrored(file, text) }) askOutline(
                        file,
                        text,
                        pushed = false,
                        retryNothing = false
                    )
                }
            }
        }
        return outline(file, document)
    }

    private fun moved(held: HeldOutline, text: CharSequence): List<OutlineSymbol> {
        if (held.symbols.isEmpty() || held.text.contentEquals(text)) return held.symbols
        val change = Documents.changeBetween(held.text, text)
        val growth = change.newEnd - change.oldEnd
        fun shift(offset: Int) = when {
            offset <= change.start -> offset
            offset >= change.oldEnd -> offset + growth
            else -> change.newEnd
        }
        fun move(symbols: List<OutlineSymbol>): List<OutlineSymbol> = symbols.mapNotNull {
            if (it.nameStart > change.start && it.nameStart < change.oldEnd) null
            else it.copy(start = shift(it.start), nameStart = shift(it.nameStart), end = shift(it.end), children = move(it.children))
        }
        return move(held.symbols)
    }

    /**
     * Asks for [file]'s symbols after [quietMillis], about the text the host holds by then, for what
     * nothing in the IDE will ask about: a provider appearing, a document reaching the host after
     * its editor's first passes ran, an answer of nothing to try again.
     */
    @Suppress("DeferredResultUnused") // Not waited for: the listener redraws once the answer arrives.
    private fun requestDocumentSymbols(file: VirtualFile, quietMillis: Long, retryNothing: Boolean) {
        if (documentSymbolProviders.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            val text = mirrored[file] ?: return@launch
            askOutline(file, text, pushed = true, retryNothing = retryNothing)
        }
        job.invokeOnCompletion { symbolJobs.remove(file, job) }
        symbolJobs.put(file, job)?.cancel()
        job.start()
    }

    /** Every document shown in an editor, for a provider that has just appeared or gone. */
    private fun requestDocumentSymbolsEverywhere() {
        if (documentSymbolProviders.isEmpty()) return
        for ((_, file) in files) {
            if (symbolJobs.containsKey(file) || !isShown(file)) continue
            requestDocumentSymbols(file, REGISTRATION_QUIET_MILLIS, retryNothing = true)
        }
    }

    /**
     * The request about [text] for [file]: for a reader, the one out already if it asks about the
     * same text, else a new one. A pushed request is always new: what it is for -- a provider that
     * has just appeared -- is what the one out may not have asked.
     */
    private fun askOutline(
        file: VirtualFile,
        text: String,
        pushed: Boolean,
        retryNothing: Boolean
    ): Deferred<Unit> {
        if (!pushed && heldOutlines[file]?.text == text) return CompletableDeferred(Unit)
        val generation = outlineGeneration.get()
        var created: OutlineRequest? = null
        val request = outlineRequests.compute(file) { _, out ->
            if (!pushed && out != null && out.text == text && out.generation == generation) out
            else OutlineRequest(text, pushed, retryNothing, generation).also { created = it }
        }!!
        created?.let { sendOutlineRequest(file, it) }
        return request.answer
    }

    /**
     * Asks the providers that claim [file] for its outline, as [refreshInlayHints] asks for hints.
     *
     * Only the first provider to answer with anything is kept, as VS Code's outline keeps it: two
     * outlines of one file would draw each scope twice. A request that [OutlineRequest.retryNothing]
     * asks once more after an answer of nothing, for the same reason as hints' -- a server still
     * loading answers an open with nothing -- and because document symbols have no event to say
     * when that has changed.
     */
    private fun sendOutlineRequest(file: VirtualFile, request: OutlineRequest) {
        fun settle() {
            outlineRequests.remove(file, request)
            request.answer.complete(Unit)
        }

        val call = callHost
        val handles = claiming(file, documentSymbolProviders.mapValues { it.value.selector })
        val resource = resourceOf(file)
        if (call == null || handles.isEmpty() || resource == null) return settle()
        val askedAt = System.nanoTime()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val answers = try {
                askDocumentSymbols(call, handles, resource)
            } catch (failure: Throwable) {
                settle()
                throw failure
            }
            var nothing: Boolean
            val current = try {
                val symbols = answers.firstOrNull { it.isNotEmpty() }.orEmpty()
                nothing = symbols.isEmpty()
                val summary = "Document symbols for ${file.name}: ${handles.size} provider(s) answered in " +
                    "${millisSince(askedAt)}ms with ${symbols.size} top-level symbol(s)"
                if (request.generation != outlineGeneration.get()) null.also { LOG.debug("$summary, for outlines since forgotten") }
                else keepOutline(file, request, symbols, summary)
            } finally {
                settle()
            }
            if (current != null && request.retryNothing && nothing) {
                requestDocumentSymbols(file, DOCUMENT_SYMBOL_EDIT_QUIET_MILLIS, retryNothing = false)
            }
        }
    }

    /** Asks each of [handles] for the outline of [resource], all at once; a provider that fails answers nothing. */
    private suspend fun askDocumentSymbols(
        call: suspend (String, String, String, Boolean) -> String,
        handles: List<Int>,
        resource: UriComponents
    ): List<List<DocumentSymbols.Symbol>> = coroutineScope {
        handles.map { handle ->
            async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    DocumentSymbols.symbols(within(DOCUMENT_SYMBOL_TIMEOUT_SECONDS) {
                        call(EXT_HOST_LANGUAGE_FEATURES, DocumentSymbols.PROVIDE, DocumentSymbols.provide(handle, resource), true)
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.debug("Document symbol provider $handle failed", failure)
                    emptyList()
                }
            }
        }.awaitAll()
    }

    /**
     * [file]'s outline from the extensions that claim it, for `vscode.executeDocumentSymbolProvider`:
     * the first answer with anything in it, as [sendOutlineRequest] keeps. Null when none claims the
     * file, for the caller to answer some other way; nothing held is read or changed.
     */
    suspend fun documentSymbols(file: VirtualFile): List<DocumentSymbols.Symbol>? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        val handles = claiming(file, documentSymbolProviders.mapValues { it.value.selector })
        if (handles.isEmpty()) return null
        return askDocumentSymbols(call, handles, resource).firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /**
     * Files the outline of the [request]'s text, redrawing what is drawn from it if it differs from
     * the one held, and returns the text the file holds now -- or null for a file closed meanwhile.
     * An answer about older text than the one held is dropped: a request made after it came back first.
     */
    private fun keepOutline(
        file: VirtualFile,
        request: OutlineRequest,
        symbols: List<DocumentSymbols.Symbol>,
        summary: String
    ): String? {
        val held = HeldOutline(request.text, placeOutline(LineOffsets(request.text), symbols))
        val current = mirrored[file] ?: return null.also { LOG.debug("$summary, for a file closed meanwhile") }
        val previous = heldOutlines[file]
        if (previous != null && previous.text == current && request.text != current) {
            LOG.debug("$summary, about text since replaced")
            return current
        }
        // Kept even when it draws the same, so that the next reader of this text need not ask again.
        heldOutlines[file] = held
        if (previous != null && moved(held, current) == moved(previous, current)) {
            LOG.debug("$summary; nothing new to draw")
            return current
        }
        LOG.debug("$summary; redrawing")
        redrawOutline(file, first = previous == null, pushed = request.pushed)
        return current
    }

    /** The symbols as offsets into the text [lines] reads; one whose name is not in it is left out, with what it holds. */
    private fun placeOutline(lines: LineOffsets, symbols: List<DocumentSymbols.Symbol>): List<OutlineSymbol> =
        symbols.mapNotNull { symbol ->
            val range = symbol.range
            val name = symbol.selectionRange
            val nameStart = lines.offset(name.startLineNumber, name.startColumn) ?: return@mapNotNull null
            val start = (lines.offset(range.startLineNumber, range.startColumn) ?: nameStart).coerceAtMost(nameStart)
            val end = (lines.offset(range.endLineNumber, range.endColumn) ?: lines.length).coerceAtLeast(nameStart)
            OutlineSymbol(start, nameStart, end, symbol.name, symbol.detail, symbol.kind, placeOutline(lines, symbol.children))
        }

    /**
     * Redraws what is drawn from [file]'s outline: its sticky lines, breadcrumbs and structure.
     *
     * The bar and the Structure view are told through [CodeBridgeOutlineListener], and read it again. The
     * sticky lines pass draws an outline it asked for itself, but skips a file whose PSI has not
     * changed since it last ran; so an outline nothing in the IDE asked for -- a [pushed] one -- is
     * drawn only once the file is marked as needing the pass, which the platform insists is done off
     * the EDT.
     *
     * Whether a file has a bar at all is decided as it opens, before the host has mirrored it, so
     * the [first] outline a file gets, and the last it loses, has the IDE decide again. Both take
     * internal API, so they are left to a [CodeBridgeOutlineRedrawer]; without one the sticky lines wait for
     * the next edit, and the bar for the file to be reopened.
     *
     * The Structure tool window can also have asked before a symbol provider was registered and
     * kept an unavailable view. Its factory must re-query the builder on that first answer; a
     * model-change notice alone has no listener yet. Later answers update the existing model.
     */
    private fun redrawOutline(file: VirtualFile, first: Boolean, pushed: Boolean) {
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
        if (pushed) scope.launch {
            val redrawers = CodeBridgeOutlineRedrawer.EP_NAME.extensionsIfPointIsRegistered
            if (redrawers.isNotEmpty()) readAction { redrawers.forEach { it.outlinePushed(project, document) } }
            repaint(file)
        }
        scope.launch(Dispatchers.EDT) {
            if (first) {
                project.serviceIfCreated<StructureViewFactory>()?.refreshStructureView()
                CodeBridgeOutlineRedrawer.EP_NAME.extensionsIfPointIsRegistered.forEach { it.outlineAppearedOrWent(project) }
            }
            project.messageBus.syncPublisher(CodeBridgeOutlineListener.TOPIC).outlineChanged(file)
        }
    }

    /** Takes every file's outline off, for a provider or a host that has gone. */
    private fun forgetAllOutlines() {
        outlineGeneration.incrementAndGet()
        outlineAskedAt.clear()
        // Settled now rather than when the host answers, if it ever does: their answers are not kept.
        outlineRequests.values.forEach { it.answer.complete(Unit) }
        outlineRequests.clear()
        val shown = heldOutlines.keys.toList()
        for (file in shown) if (heldOutlines.remove(file) != null) redrawOutline(file, first = true, pushed = true)
    }

    /** Waits for the debounce and the symbol requests behind it to settle, as [awaitInlayHintsForTest] does. */
    @TestOnly
    fun awaitDocumentSymbolsForTest(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (symbolJobs.isEmpty() && outlineRequests.isEmpty()) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Whether an extension folds [file], so its fold regions are drawn from what that extension says. */
    fun folds(file: VirtualFile): Boolean =
        foldingRangeProviders.isNotEmpty() && claiming(file, foldingRangeProviders.mapValues { it.value.selector }).isNotEmpty()

    /**
     * One fold, over offsets into the text it was placed against: from the end of the line it starts
     * on, which stays shown, to the end of the last line it folds away.
     */
    data class PlacedFold(val start: Int, val end: Int, val kind: String?)

    /** Folds, and the text they were placed against. */
    private class HeldFolds(val text: String, val folds: List<PlacedFold>)

    /**
     * The folds held for [file], moved onto [text] as [codeLenses] moves lenses, each end put back
     * at the end of the line it landed on; one left with no line to fold away is dropped.
     */
    fun foldingRanges(file: VirtualFile, text: CharSequence): List<PlacedFold> =
        heldFolds[file]?.let { moved(it, text) }.orEmpty()

    private fun moved(held: HeldFolds, text: CharSequence): List<PlacedFold> {
        if (held.folds.isEmpty() || held.text.contentEquals(text)) return held.folds
        val change = Documents.changeBetween(held.text, text)
        val growth = change.newEnd - change.oldEnd
        fun shift(offset: Int) = when {
            offset <= change.start -> offset
            offset >= change.oldEnd -> offset + growth
            else -> change.newEnd
        }
        fun lineEnd(offset: Int): Int {
            val newline = text.indexOf(NEWLINE, offset.coerceIn(0, text.length))
            return if (newline < 0) text.length else newline
        }
        return held.folds.mapNotNull {
            // Typing at the end of the first line leaves its old end mid-line, and the fold has to
            // start after what was typed.
            val start = lineEnd(shift(it.start))
            val end = lineEnd(shift(it.end))
            if (end <= start) null else it.copy(start = start, end = end)
        }.distinctBy { it.start to it.end }
    }

    /** Asks for [file]'s folding ranges once its document has been quiet for [quietMillis], as [requestInlayHints] does. */
    private fun requestFoldingRanges(file: VirtualFile, text: String, quietMillis: Long, retryNothing: Boolean) {
        if (foldingRangeProviders.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (quietMillis > 0) delay(quietMillis.milliseconds)
            refreshFoldingRanges(file, text, retryNothing, coroutineContext.job)
        }
        job.invokeOnCompletion { foldJobs.remove(file, job) }
        foldJobs.put(file, job)?.cancel()
        job.start()
    }

    /** Every document shown in an editor, for a provider that has just appeared, changed its mind or gone. */
    private fun requestFoldingRangesEverywhere() {
        if (foldingRangeProviders.isEmpty()) return
        for ((_, file) in files) {
            if (foldJobs.containsKey(file) || !isShown(file)) continue
            val text = mirrored[file] ?: continue
            requestFoldingRanges(file, text, REGISTRATION_QUIET_MILLIS, retryNothing = true)
        }
    }

    /**
     * Asks every provider that claims [file] at once, and keeps their ranges between them, as VS
     * Code's folding model does. [retryNothing] asks once more after an answer of nothing, as for
     * document symbols: a server still loading answers an open with nothing, and not every provider
     * says when that has changed.
     */
    private fun refreshFoldingRanges(file: VirtualFile, text: String, retryNothing: Boolean, asking: Job) {
        val call = callHost ?: return
        val handles = claiming(file, foldingRangeProviders.mapValues { it.value.selector })
        if (handles.isEmpty()) return
        val resource = resourceOf(file) ?: return
        while (pendingFolds.putIfAbsent(file, false) != null) {
            if (pendingFolds.replace(file, true) != null) return
        }
        val askedAt = System.nanoTime()
        // Not in the debounce's job: an edit supersedes the wait before a request, never the request.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val ranges = try {
                coroutineScope {
                    handles.map { handle ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                FoldingRanges.ranges(within(FOLDING_RANGE_TIMEOUT_SECONDS) {
                                    call(EXT_HOST_LANGUAGE_FEATURES, FoldingRanges.PROVIDE, FoldingRanges.provide(handle, resource), true)
                                })
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                LOG.debug("Folding range provider $handle failed", failure)
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
            } catch (failure: Throwable) {
                pendingFolds.remove(file)
                throw failure
            }
            var askedAgain: Boolean
            var nothing: Boolean
            val current = try {
                nothing = ranges.all { it.isEmpty() }
                val summary = "Folding ranges for ${file.name}: ${handles.size} provider(s) answered in " +
                    "${millisSince(askedAt)}ms with ${ranges.sumOf { it.size }} range(s)"
                keepFolds(file, text, ranges, summary)
            } finally {
                askedAgain = pendingFolds.remove(file) == true
            }
            if (current == null || foldJobs[file].let { it != null && it !== asking }) return@launch
            when {
                askedAgain -> requestFoldingRanges(file, current, 0, retryNothing = true)
                current != text -> requestFoldingRanges(file, current, 0, retryNothing = false)
                retryNothing && nothing -> requestFoldingRanges(file, current, FOLDING_RANGE_EDIT_QUIET_MILLIS, retryNothing = false)
            }
        }
    }

    /**
     * Files the folds of [text], redrawing them if they differ from those held, and returns the text
     * the file holds now -- or null for a file closed meanwhile.
     */
    private fun keepFolds(file: VirtualFile, text: String, answers: List<List<FoldingRanges.Range>>, summary: String): String? {
        val held = HeldFolds(text, placeFolds(text, answers))
        val current = mirrored[file] ?: return null.also { LOG.debug("$summary, for a file closed meanwhile") }
        val previous = heldFolds[file]
        if (moved(held, current) == previous?.let { moved(it, current) }.orEmpty()) {
            heldFolds[file] = held
            LOG.debug("$summary; nothing new to draw")
            return current
        }
        heldFolds[file] = held
        LOG.debug("$summary; redrawing")
        redrawFolds(file)
        return current
    }

    /**
     * The answers' 1-based line ranges as folds in [text]: those that fold away at least one line,
     * none crossing another, and no more than VS Code draws by default. The answers come in the order
     * their providers were asked, and a range that crosses one already taken is left out, as VS
     * Code's folding model leaves it out.
     */
    private fun placeFolds(text: String, answers: List<List<FoldingRanges.Range>>): List<PlacedFold> {
        if (answers.all { it.isEmpty() }) return emptyList()
        val lines = LineOffsets(text)
        val taken = ArrayList<PlacedFold>()
        for ((rangeStart, rangeEnd, kind) in answers.flatten()) {
            if (taken.size == MAX_FOLDS) break
            if (rangeEnd <= rangeStart) continue
            val start = lines.lineEnd(rangeStart) ?: continue
            val end = lines.lineEnd(rangeEnd) ?: text.length
            if (end <= start) continue
            @Suppress("ConvertTwoComparisonsToRangeCheck") // reads as the two ways two folds can cross
            val crosses = taken.any { other ->
                (start == other.start && end == other.end) ||
                    (start < other.start && other.start < end && end < other.end) ||
                    (other.start < start && start < other.end && other.end < end)
            }
            if (!crosses) taken.add(PlacedFold(start, end, kind))
        }
        return taken.sortedWith(compareBy({ it.start }, { -it.end }))
    }

    /**
     * Has the folding pass build [file]'s folds again. An answer changes no PSI, and the pass keeps
     * what it built until the PSI changes; [restartDaemon] tells the file's editors to drop that.
     */
    private fun redrawFolds(file: VirtualFile) {
        foldsChanged.add(file)
        repaint(file)
    }

    /** Takes every file's folds off, for a provider or a host that has gone. */
    private fun forgetAllFolds() {
        val shown = heldFolds.keys.toList()
        for (file in shown) if (heldFolds.remove(file) != null) redrawFolds(file)
    }

    /** Waits for the debounce and the folding request behind it to settle, as [awaitInlayHintsForTest] does. */
    @TestOnly
    fun awaitFoldingRangesForTest(timeout: Long = 10, unit: TimeUnit = TimeUnit.SECONDS): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (foldJobs.isEmpty() && pendingFolds.isEmpty()) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Whether any extension has offered to complete, so the contributor can leave early if not. */
    fun hasCompletionProviders(): Boolean = completionProviders.isNotEmpty()

    /** Whether any extension has offered to say where a symbol is defined. */
    fun hasDefinitionProviders(): Boolean = definitionProviders.isNotEmpty()

    /** Whether any extension offers to find implementations in [file]; cheap enough for a data rule. */
    fun canFindImplementations(file: VirtualFile): Boolean =
        implementationProviders.isNotEmpty() && claiming(file, implementationProviders).isNotEmpty()

    /**
     * Asks every extension what could be typed here, exactly as the workbench does.
     *
     * Blocking, and called off the editor thread by the contributor. Every provider that claims the
     * file is asked at once, as VS Code asks them, so the lookup waits for the slowest rather than
     * for all of them in a row; the results are concatenated in registration order. VS Code merges
     * and re-ranks them with its own scoring, which IntelliJ would overrule anyway.
     *
     * Cached items are released as soon as they are read, unless something may still need them:
     * an item that runs a command, which the host keeps with the list, or a provider that resolves
     * items, which fills in the documentation of the one the user settles on from the list
     * ([resolveCompletion]). Those lists are [held] until nothing can be chosen from them.
     */
    fun completions(file: VirtualFile, line: Int, column: Int): List<CompletionItem> {
        val call = callHost ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        val position = Position(line, column)
        // All at once; the lookup going away withdraws every question still open, not just the one waited on.
        return runBlockingMaybeCancellable {
            claiming(file, completionProviders).map { handle ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val args = LanguageFeatures.provideCompletions(handle, resource, position)
                    try {
                        // Cancellable: the host appends the token itself, so it is absent from the arguments.
                        val json = within(COMPLETION_TIMEOUT_SECONDS) { call(EXT_HOST_LANGUAGE_FEATURES, Completions.PROVIDE, args, true) }
                        // Read, and the host's copy released, as soon as it arrives: a lookup the user
                        // typed past is abandoned, and the host would hold its list forever.
                        val parsed = Completions.parse(json)
                        val resolves = handle in resolvingCompletionProviders
                        parsed.cacheId?.let { cached ->
                            if (resolves || parsed.items.any { it.command != null }) held(handle, cached)
                            else releaseCompletions(handle, cached)
                        }
                        val cached = parsed.cacheId
                        if (!resolves || cached == null) parsed.items
                        else parsed.items.map { it.copy(resolvable = Resolvable(handle, cached, it.index)) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LOG.debug("Completion provider $handle failed", failure)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * The rest of [item] -- its detail and documentation -- as the workbench asks for the item the
     * user settles on; null when the provider has nothing to add or its list is gone.
     *
     * Blocking, with cancellation checked, and called off the editor thread by the lookup's
     * documentation. Gone means [HELD_COMPLETION_SECONDS] after the lookup opened, or once the
     * provider answered another lookup; VS Code keeps a list exactly as long as its lookup instead,
     * which the backend is not told about in split mode.
     */
    fun resolveCompletion(item: Resolvable): ResolvedCompletion? {
        val call = callHost ?: return null
        if (heldCompletions[item.handle]?.cacheId != item.cacheId) return null
        val reply = awaitCancellable("Completion provider ${item.handle} resolving an item", COMPLETION_TIMEOUT_SECONDS) {
            call(EXT_HOST_LANGUAGE_FEATURES, Completions.RESOLVE, Completions.resolve(item), true)
        } ?: return null
        return Completions.parseResolved(reply)
    }

    /** The list each provider last answered with that is kept for its commands or resolving, by provider. */
    private val heldCompletions = ConcurrentHashMap<Int, HeldCompletions>()

    /**
     * One hold of a list. Compared by identity, so a hold's timer never releases a later one: a host
     * started afresh numbers its lists from the start again, and the same cache id names another list.
     */
    private class HeldCompletions(val cacheId: Int)

    /**
     * Keeps [cacheId] in the host, so an item's command can still be run once it is chosen, and the
     * item the user settles on resolved.
     *
     * Released when the provider answers again, since only the newest lookup can still be chosen
     * from, or after [HELD_COMPLETION_SECONDS] in any case, for a lookup that is simply closed.
     */
    private fun held(handle: Int, cacheId: Int) {
        val hold = HeldCompletions(cacheId)
        heldCompletions.put(handle, hold)?.takeIf { it.cacheId != cacheId }?.let { releaseCompletions(handle, it.cacheId) }
        scope.launch {
            delay(HELD_COMPLETION_SECONDS.seconds)
            if (heldCompletions.remove(handle, hold)) releaseCompletions(handle, cacheId)
        }
    }

    private fun releaseCompletions(handle: Int, cacheId: Int) =
        tell(LanguageFeatures.RELEASE_COMPLETIONS, LanguageFeatures.releaseCompletions(handle, cacheId)) {
            "release completions $cacheId of provider $handle"
        }

    /**
     * Asks the extension for a hover, exactly as the workbench does.
     *
     * Blocking, and deliberately called off the editor thread by the documentation target. Every
     * registered provider is tried in turn; the first with content wins, which is a simplification
     * -- VS Code merges them -- worth revisiting once more than hover is drawn.
     *
     * [line] and [column] are 1-based, as VS Code's editor positions are.
     */
    fun hover(file: VirtualFile, line: Int, column: Int): String? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        val position = Position(line, column)
        for (handle in claiming(file, hoverProviders)) {
            val args = LanguageFeatures.provideHover(handle, resource, position)
            val reply = awaitCancellable("Hover provider $handle", HOVER_TIMEOUT_SECONDS) {
                // Cancellable: the host appends the token itself, so it is absent from the arguments.
                call(EXT_HOST_LANGUAGE_FEATURES, LanguageFeatures.PROVIDE_HOVER, args, true)
            } ?: continue
            LanguageFeatures.hoverText(reply)?.let { return it }
        }
        return null
    }

    /**
     * The characters that open signature help in [file], and those that only ask again while it is
     * open, from every provider that claims it; null when none does.
     */
    fun signatureHelpTriggers(file: VirtualFile): SignatureHelpTriggers? {
        if (signatureHelpProviders.isEmpty()) return null
        val claiming = claiming(file, signatureHelpProviders.mapValues { it.value.selector })
            .mapNotNull { signatureHelpProviders[it] }
        if (claiming.isEmpty()) return null
        return SignatureHelpTriggers(
            claiming.flatMap { it.triggerCharacters }.toSet(),
            claiming.flatMap { it.retriggerCharacters }.toSet(),
        )
    }

    /** What opens signature help, and what asks again once it is open; see [signatureHelpTriggers]. */
    data class SignatureHelpTriggers(val triggers: Set<String>, val retriggers: Set<String>)

    /** A signature help reply, and the provider that gave it, which is the one to release it to. */
    data class ShownSignatureHelp(val handle: Int, val help: SignatureHelp.Help)

    /**
     * Asks for signature help at [line] and [column], 1-based, as the workbench does: providers in
     * turn, the first with a signature wins.
     *
     * [shown] is the help on screen, if any. It is handed only to the provider that gave it: the
     * host looks its id up in the cache of whichever provider is asked, where another provider's
     * id names some other reply. The others are still told the help is open.
     *
     * Blocking, with cancellation checked, and called under a non-blocking read action by Parameter
     * Info, which restarts it when the user types.
     */
    fun signatureHelp(
        file: VirtualFile,
        line: Int,
        column: Int,
        trigger: SignatureHelp.Trigger,
        shown: ShownSignatureHelp? = null,
    ): ShownSignatureHelp? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        val position = Position(line, column)
        for (handle in claiming(file, signatureHelpProviders.mapValues { it.value.selector })) {
            val asked = trigger.copy(
                isRetrigger = trigger.isRetrigger || shown != null,
                active = shown?.takeIf { it.handle == handle }?.help?.dto,
            )
            val reply = awaitCancellable("Signature help provider $handle", HOVER_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, SignatureHelp.PROVIDE, SignatureHelp.provide(handle, resource, position, asked), true)
            } ?: continue
            val help = SignatureHelp.help(reply) ?: continue
            if (help.signatures.isNotEmpty()) return ShownSignatureHelp(handle, help)
            help.id?.let { releaseSignatureHelp(handle, it) }
        }
        return null
    }

    /**
     * Whether an extension proposes text to type next in [file], and `editor.inlineSuggest.enabled`
     * is not off for its language. Cheap, for the editor thread.
     */
    fun completesInline(file: VirtualFile): Boolean {
        if (inlineCompletionProviders.isEmpty() || claimingInline(file).isEmpty()) return false
        val enabled = project.service<HostedLanguageServers>().setting(InlineCompletions.SETTING, languageIds[file])
        return (enabled as? JsonPrimitive)?.booleanOrNull != false
    }

    /** The longest any provider claiming [file] asked to be left after typing, or null where none said. */
    fun inlineCompletionDebounceMillis(file: VirtualFile): Long? =
        claimingInline(file).mapNotNull { inlineCompletionProviders[it]?.debounceMillis }.maxOrNull()

    /** A provider's answer, which the host keeps as a list until [freeInlineCompletions]. */
    data class InlineAnswer(val handle: Int, val answer: InlineCompletions.Answer)

    /**
     * What the providers claiming [file] propose at [position] in [text]: all asked at once, as VS
     * Code asks them, once the host holds [text]. Answers come in handle order, those with nothing
     * to offer freed already.
     *
     * Suspends, and cancelling it withdraws the requests from the host.
     */
    suspend fun inlineCompletions(
        file: VirtualFile,
        text: String,
        position: Position,
        explicit: Boolean
    ): List<InlineAnswer> {
        val call = callHost ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        val caughtUp = withTimeoutOrNull(MIRROR_WAIT_MILLIS.milliseconds) {
            while (mirrored[file] != text) delay(MIRROR_POLL_MILLIS.milliseconds)
        }
        if (caughtUp == null) return emptyList()
        return coroutineScope {
            claimingInline(file).map { handle ->
                async {
                    val reply = try {
                        withTimeout(INLINE_COMPLETION_TIMEOUT_SECONDS.seconds) {
                            call(
                                EXT_HOST_LANGUAGE_FEATURES, InlineCompletions.PROVIDE,
                                InlineCompletions.provide(handle, resource, position, explicit), true
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        // A timeout is one too, but only this coroutine's: the others still answer.
                        if (cancelled !is TimeoutCancellationException) throw cancelled
                        LOG.debug("Inline completion provider $handle timed out")
                        null
                    } catch (failure: Exception) {
                        LOG.debug("Inline completion provider $handle failed", failure)
                        null
                    }
                    val answer = InlineCompletions.answer(reply) ?: return@async null
                    if (answer.items.isEmpty()) {
                        freeInlineCompletions(handle, answer.id)
                        return@async null
                    }
                    InlineAnswer(handle, answer)
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Lets the host forget an inline completion list, once nothing it offered is on screen. */
    fun freeInlineCompletions(handle: Int, id: Int) =
        tell(InlineCompletions.FREE, InlineCompletions.free(handle, id)) { "free inline completion list $id of provider $handle" }

    private fun claimingInline(file: VirtualFile): List<Int> =
        claiming(file, inlineCompletionProviders.mapValues { it.value.selector })

    /** Lets the host forget a reply, once the help it was shown in has gone or been replaced. */
    fun releaseSignatureHelp(handle: Int, id: Int) =
        tell(SignatureHelp.RELEASE, SignatureHelp.release(handle, id)) { "release signature help $id of provider $handle" }

    /**
     * Asks every extension where the symbol here is defined, exactly as the workbench does.
     *
     * Blocking, and called under a read action on a background thread by the declaration handler.
     * Unlike [hover], every provider's answer is kept: VS Code gathers definitions from all of them
     * and offers the union, and a symbol that genuinely has two definitions is a popup rather than
     * a silently preferred first one. Duplicates are dropped, because two providers backed by the
     * same language server answer identically.
     */
    fun definitions(file: VirtualFile, line: Int, column: Int): List<LanguageFeatures.DefinitionLink> =
        locations(file, line, column, definitionProviders, LanguageFeatures.PROVIDE_DEFINITION)

    /** Whether a definition provider claims [file]; one that does answers for it, even with nothing. */
    fun canFindDefinitions(file: VirtualFile): Boolean =
        definitionProviders.isNotEmpty() && claiming(file, definitionProviders).isNotEmpty()

    /** Where the type of the symbol here is defined, for Go To Type Declaration. */
    fun typeDefinitions(file: VirtualFile, line: Int, column: Int): List<LanguageFeatures.DefinitionLink> =
        locations(file, line, column, typeDefinitionProviders, LanguageFeatures.PROVIDE_TYPE_DEFINITION)

    /** What implements the symbol here, for Go To Implementation. */
    fun implementations(file: VirtualFile, line: Int, column: Int): List<LanguageFeatures.DefinitionLink> =
        locations(file, line, column, implementationProviders, LanguageFeatures.PROVIDE_IMPLEMENTATION)

    /** Asks each of [providers] that claims [file] by [method], which answers with definition links. */
    private fun locations(
        file: VirtualFile,
        line: Int,
        column: Int,
        providers: Map<Int, JsonElement>,
        method: String,
    ): List<LanguageFeatures.DefinitionLink> {
        val call = callHost ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        val position = Position(line, column)
        val links = mutableListOf<LanguageFeatures.DefinitionLink>()
        for (handle in claiming(file, providers)) {
            val args = LanguageFeatures.provideDefinition(handle, resource, position)
            // Timed, and reported by the caller. The wait here is one process hop plus whatever the
            // extension does, and it is the only part of following a definition that can take a
            // human-noticeable time on purpose; everything else is arithmetic. Without the split,
            // a slow jump is indistinguishable from a slow server.
            val started = System.nanoTime()
            val reply = awaitCancellable("$method provider $handle", DEFINITION_TIMEOUT_SECONDS) {
                // Cancellable: the host appends the token itself, so it is absent from the arguments.
                call(EXT_HOST_LANGUAGE_FEATURES, method, args, true)
            }
            LOG.debug("$method provider $handle answered in ${millisSince(started)}ms")
            links += LanguageFeatures.definitionLinks(reply ?: continue)
        }
        return links.distinct()
    }

    /** Whether any extension offers a hierarchy of [kind] in [file]; cheap enough for an action's update. */
    fun canShowHierarchy(kind: Hierarchies.Kind, file: VirtualFile): Boolean {
        val providers = hierarchyProviders.getValue(kind)
        return providers.isNotEmpty() && claiming(file, providers).isNotEmpty()
    }

    /** What a hierarchy was prepared with: the provider that answered, and the items it named. */
    data class PreparedHierarchy(val handle: Int, val items: List<Hierarchies.Item>)

    /**
     * Prepares a hierarchy of [kind] at a position, as Show Call Hierarchy and Show Type Hierarchy do.
     *
     * VS Code asks only the first provider that claims the file; this asks each in turn until one
     * names something, so a provider that knows nothing here does not hide one that does. A provider
     * that answers opens a session in the host, which the caller releases with [releaseHierarchy]
     * once it is done with the items -- and so with anything asked about them.
     *
     * Blocking, and called while a hierarchy tree is built in the background.
     */
    fun prepareHierarchy(kind: Hierarchies.Kind, file: VirtualFile, line: Int, column: Int): PreparedHierarchy? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        val position = Position(line, column)
        for (handle in claiming(file, hierarchyProviders.getValue(kind))) {
            val reply = awaitHierarchy(handle, kind.prepare) {
                // Cancellable: the host appends the token itself, so it is absent from the arguments.
                call(EXT_HOST_LANGUAGE_FEATURES, kind.prepare, Hierarchies.prepare(handle, resource, position), true)
            }
            val items = Hierarchies.items(reply)
            if (items.isNotEmpty()) return PreparedHierarchy(handle, items)
        }
        return null
    }

    /** Who calls [item], which [handle] prepared. Blocking. */
    fun incomingCalls(handle: Int, item: Hierarchies.Item): List<Hierarchies.Call> =
        Hierarchies.incomingCalls(askHierarchy(handle, item, Hierarchies.PROVIDE_INCOMING_CALLS))

    /** What [item], which [handle] prepared, calls. Blocking. */
    fun outgoingCalls(handle: Int, item: Hierarchies.Item): List<Hierarchies.Call> =
        Hierarchies.outgoingCalls(askHierarchy(handle, item, Hierarchies.PROVIDE_OUTGOING_CALLS))

    /** The types [item], which [handle] prepared, derives from. Blocking. */
    fun supertypes(handle: Int, item: Hierarchies.Item): List<Hierarchies.Item> =
        Hierarchies.items(askHierarchy(handle, item, Hierarchies.PROVIDE_SUPERTYPES))

    /** The types that derive from [item], which [handle] prepared. Blocking. */
    fun subtypes(handle: Int, item: Hierarchies.Item): List<Hierarchies.Item> =
        Hierarchies.items(askHierarchy(handle, item, Hierarchies.PROVIDE_SUBTYPES))

    /** Lets the host forget a session [prepareHierarchy] opened, and every item in it. */
    fun releaseHierarchy(kind: Hierarchies.Kind, handle: Int, sessionId: String) =
        tell(kind.release, Hierarchies.release(handle, sessionId)) { "release hierarchy session $sessionId of provider $handle" }

    private fun askHierarchy(handle: Int, item: Hierarchies.Item, method: String): String? {
        val call = callHost ?: return null
        // Cancellable: the host appends the token itself, so it is absent from the arguments.
        return awaitHierarchy(handle, method) { call(EXT_HOST_LANGUAGE_FEATURES, method, Hierarchies.ask(handle, item), true) }
    }

    /**
     * Waits for [request], giving way to the tree being cancelled, and gives up after a while.
     *
     * A hierarchy is built a level at a time while the user watches the tree, and a level a server
     * takes long over is still worth waiting for -- callers are a project-wide search -- but not
     * for ever.
     */
    private fun awaitHierarchy(handle: Int, method: String, request: suspend () -> String): String? {
        val started = System.nanoTime()
        return try {
            awaitCancellable("$method provider $handle", HIERARCHY_TIMEOUT_SECONDS, request)
        } finally {
            LOG.debug("$method provider $handle answered in ${millisSince(started)}ms")
        }
    }

    /** Whether any extension offers to find references in [file]; cheap enough for a data rule. */
    fun canFindReferences(file: VirtualFile): Boolean =
        referenceProviders.isNotEmpty() && claiming(file, referenceProviders).isNotEmpty()

    /**
     * Asks every extension where the symbol here is referenced, as Find All References does.
     *
     * Blocking, and called from the usage search on a background thread. Every provider that claims
     * the file is asked at once and the answers are joined, duplicates dropped, as VS Code does. A
     * project-wide search can take a language server a while, and the user is watching a progress
     * bar for exactly this, so the wait is long but gives way to cancelling the search.
     */
    fun references(file: VirtualFile, line: Int, column: Int): List<LanguageFeatures.Location> {
        val call = callHost ?: return emptyList()
        val resource = resourceOf(file) ?: return emptyList()
        val position = Position(line, column)
        // All at once; cancelling the search withdraws every question still open.
        return runBlockingMaybeCancellable {
            claiming(file, referenceProviders).map { handle ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val args = LanguageFeatures.provideReferences(handle, resource, position)
                    try {
                        // Cancellable: the host appends the token itself, so it is absent from the arguments.
                        LanguageFeatures.locations(within(REFERENCE_TIMEOUT_SECONDS) {
                            call(EXT_HOST_LANGUAGE_FEATURES, LanguageFeatures.PROVIDE_REFERENCES, args, true)
                        })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LOG.debug("Reference provider $handle failed", failure)
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinct()
        }
    }

    /** A workspace symbol and the provider that offered it, which is the one to resolve it with. */
    data class OfferedSymbol(val handle: Int, val symbol: WorkspaceSymbols.Symbol)

    /**
     * Asks every extension for the workspace symbols matching [query], as Go to Symbol in Workspace
     * does.
     *
     * Blocking, and called from Go to Class and Go to Symbol on a background thread. Those ask once
     * for the names and then once more per name for the symbols behind it, and both ask at once from
     * the Search Everywhere popup, so one search answers every caller asking the same query: a
     * language server is asked a query once, not once per name. A different query supersedes it --
     * the user has typed on -- and the superseded one is cancelled at the host.
     *
     * Every provider is asked at once and the answers are joined, as VS Code does. An empty query
     * asks nothing: VS Code would, but what a server answers then is either nothing or everything.
     */
    fun workspaceSymbols(query: String): List<OfferedSymbol> {
        val call = callHost ?: return emptyList()
        if (query.isBlank() || workspaceSymbolProviders.isEmpty()) return emptyList()
        val search = synchronized(this) {
            symbolSearch?.takeIf { it.answers(query) }
                ?: SymbolSearch(query, workspaceSymbolProviders.keys.toList(), call).also {
                    symbolSearch?.discard()
                    symbolSearch = it
                }
        }
        // Only the wait gives up with the caller: the search is every caller's, until superseded.
        return runBlockingMaybeCancellable { search.answer.await() }
    }

    /** Whether [offered] has to be resolved before it is navigated to; see [resolveWorkspaceSymbol]. */
    fun resolvesWorkspaceSymbol(offered: OfferedSymbol): Boolean =
        workspaceSymbolProviders[offered.handle]?.resolves == true

    /**
     * Where [offered] really is, from a provider that fills symbols in only once one is chosen.
     *
     * A language client whose server listed the symbol with only its file sends the start of the file
     * and leaves the range to `workspaceSymbol/resolve`; VS Code resolves on choosing whenever the
     * provider resolves at all, and so does this. Blocking. The symbol as offered stands in when the
     * extension has nothing to add, or its list has already been released.
     */
    suspend fun resolveWorkspaceSymbol(offered: OfferedSymbol): WorkspaceSymbols.Symbol {
        if (!resolvesWorkspaceSymbol(offered)) return offered.symbol
        val call = callHost ?: return offered.symbol
        val args = WorkspaceSymbols.resolve(offered.handle, offered.symbol)
        val reply = try {
            // Cancellable: the host appends the token itself, so it is absent from the arguments.
            within(WORKSPACE_SYMBOL_RESOLVE_TIMEOUT_SECONDS) { call(EXT_HOST_LANGUAGE_FEATURES, WorkspaceSymbols.RESOLVE, args, true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.debug("Could not resolve workspace symbol ${offered.symbol.name}", failure)
            null
        }
        return WorkspaceSymbols.resolved(reply) ?: offered.symbol
    }

    /**
     * One query, asked of every provider, and the lists the host holds for it.
     *
     * A list is kept until the search is superseded when its provider resolves symbols, since a symbol
     * chosen from the popup is resolved from it; the others are released as soon as they are read.
     */
    private inner class SymbolSearch(
        val query: String,
        handles: List<Int>,
        call: suspend (String, String, String, Boolean) -> String,
    ) {
        private val held = mutableListOf<Pair<Int, Int>>()
        private var discarded = false
        @Volatile private var answeredAt: Long? = null

        // In the project's scope: shared by every caller asking this query, and cancelled when the
        // search is superseded, not when one of them gives up waiting.
        private val replies: List<Pair<Int, Deferred<String>>> = handles.map { handle ->
            handle to scope.async(start = CoroutineStart.UNDISPATCHED) {
                // Cancellable: the host appends the token itself, so it is absent from the arguments.
                within(WORKSPACE_SYMBOL_TIMEOUT_SECONDS) {
                    call(EXT_HOST_LANGUAGE_FEATURES, WorkspaceSymbols.PROVIDE, WorkspaceSymbols.provide(handle, query), true)
                }
            }
        }

        val answer: Deferred<List<OfferedSymbol>> = scope.async {
            replies.forEach { (_, reply) -> reply.join() }
            collect()
        }

        /** Whether this search stands in for asking [query] again; once answered, only briefly. */
        fun answers(query: String): Boolean {
            if (query != this.query) return false
            val at = answeredAt ?: return true
            return System.nanoTime() - at < TimeUnit.MILLISECONDS.toNanos(WORKSPACE_SYMBOL_REUSE_MILLIS)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun collect(): List<OfferedSymbol> {
            val symbols = mutableListOf<OfferedSymbol>()
            for ((handle, reply) in replies) {
                // Every reply is in, or failed: what a failed or withdrawn one threw is logged.
                val text = runCatching { reply.getCompleted() }
                    .onFailure { LOG.debug("Workspace symbol provider $handle failed", it) }
                    .getOrNull()
                val offered = WorkspaceSymbols.offered(text)
                offered.symbols.mapTo(symbols) { OfferedSymbol(handle, it) }
                val cacheId = offered.cacheId ?: continue
                val keep = workspaceSymbolProviders[handle]?.resolves == true && offered.symbols.isNotEmpty()
                if (keep && synchronized(this) { !discarded && held.add(handle to cacheId) }) continue
                release(handle, cacheId)
            }
            answeredAt = System.nanoTime()
            return symbols.distinct()
        }

        /** Superseded: what is still being asked is cancelled, and what the host holds released. */
        fun discard() {
            replies.forEach { (_, reply) -> reply.cancel() }
            val lists = synchronized(this) {
                discarded = true
                held.toList().also { held.clear() }
            }
            lists.forEach { (handle, cacheId) -> release(handle, cacheId) }
        }

        private fun release(handle: Int, cacheId: Int) =
            tell(WorkspaceSymbols.RELEASE, WorkspaceSymbols.release(handle, cacheId)) {
                "release workspace symbols $cacheId of provider $handle"
            }
    }

    /** Whether any extension offers to rename in [file]; cheap enough to decide whether Rename is offered. */
    fun canRename(file: VirtualFile): Boolean =
        renameProviders.isNotEmpty() && claiming(file, renameProviders.mapValues { it.value.selector }).isNotEmpty()

    /**
     * What is to be renamed at a position, before the user is asked for the new name.
     *
     * [location] is the range and initial text the extension named, or null when none did and the
     * word at the position stands in for it. [providers] are the ones to ask for the edits, in the
     * order to ask them; [rejections] are the reasons extensions gave for refusing, which, when no
     * provider named a location, are what the user is shown instead of a rename.
     */
    data class RenamePlan(
        val location: LanguageFeatures.RenameLocation?,
        val providers: List<Int>,
        val rejections: List<String>,
    )

    /** The outcome of a rename: the edit to apply, or the reasons every provider gave for not making one. */
    data class RenameResult(val edit: WorkspaceEdit?, val rejections: List<String>)

    /**
     * Asks the providers what the rename at this position would rename, as VS Code's rename does.
     *
     * In order, until one names a location: a provider that cannot be asked ends the walk, since it
     * and everything after it are left to the word at the position; a refusal is noted and passed
     * over. The provider that named the location is the first asked for the edits.
     *
     * Blocking and cancellable; called under a progress indicator, off the editor thread.
     */
    fun prepareRename(file: VirtualFile, line: Int, column: Int): RenamePlan {
        val handles = claiming(file, renameProviders.mapValues { it.value.selector })
        val call = callHost ?: return RenamePlan(null, emptyList(), emptyList())
        val resource = resourceOf(file) ?: return RenamePlan(null, emptyList(), emptyList())
        val position = Position(line, column)
        val rejections = mutableListOf<String>()
        for ((index, handle) in handles.withIndex()) {
            if (renameProviders[handle]?.resolvesLocation != true) break
            val reply = awaitCancellable("Rename provider $handle", RENAME_LOCATION_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, LanguageFeatures.RESOLVE_RENAME_LOCATION,
                    LanguageFeatures.resolveRenameLocation(handle, resource, position), true)
            }
            val location = LanguageFeatures.renameLocation(reply) ?: continue
            val rejected = location.rejectReason
            if (rejected != null) {
                rejections += rejected
                continue
            }
            return RenamePlan(location, handles.drop(index), emptyList())
        }
        return RenamePlan(null, handles, rejections)
    }

    /**
     * Asks for the edit that renames the symbol at this position to [newName].
     *
     * The providers of [plan] in turn, until one answers with an edit; one that answers with nothing
     * or refuses is passed over, its reason kept, as VS Code does.
     */
    fun renameEdits(file: VirtualFile, line: Int, column: Int, newName: String, plan: RenamePlan): RenameResult {
        val call = callHost ?: return RenameResult(null, emptyList())
        val resource = resourceOf(file) ?: return RenameResult(null, emptyList())
        val position = Position(line, column)
        val rejections = mutableListOf<String>()
        for (handle in plan.providers) {
            val reply = awaitCancellable("Rename provider $handle", RENAME_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, LanguageFeatures.PROVIDE_RENAME_EDITS,
                    LanguageFeatures.provideRenameEdits(handle, resource, position, newName), true)
            }
            val edits = LanguageFeatures.renameEdits(reply)
            val edit = edits?.edit
            if (edit != null) return RenameResult(edit, emptyList())
            rejections += edits?.rejectReason ?: NO_RENAME_RESULT
        }
        return RenameResult(null, rejections)
    }

    /** Whether a code action provider claims [file], which only a mirrored one can be. */
    fun hasCodeActionProviders(file: VirtualFile): Boolean =
        codeActionProviders.isNotEmpty() && claiming(
            file,
            codeActionProviders.mapValues { it.value.selector }).isNotEmpty()

    /** One action an extension offered, and the provider to resolve it with. */
    data class OfferedAction(val handle: Int, val action: CodeActions.Action)

    /**
     * What the providers offered for one range, and the lists the host is holding for it.
     *
     * The host keeps every list it answered with until told to release it, because an action can be
     * resolved or run long after it was offered. Whoever holds this releases it with
     * [releaseCodeActions] once nothing can be chosen from it any more.
     */
    data class OfferedActions(val actions: List<OfferedAction>, val lists: List<Pair<Int, Int>>) {
        companion object {
            val NONE = OfferedActions(emptyList(), emptyList())
        }
    }

    /**
     * Asks every code action provider what it offers for [range], exactly as the lightbulb does.
     *
     * Blocking and cancellable, and called under a read action on a background thread by the
     * intentions. Every provider's actions are kept, in registration order, since VS Code offers
     * them all in one menu too. The host works out which of its diagnostics the range touches on
     * its own, so the quick fixes for the problem under the caret come back without being asked
     * for by name.
     */
    fun codeActions(file: VirtualFile, range: Range): OfferedActions {
        val call = callHost ?: return OfferedActions.NONE
        val resource = resourceOf(file) ?: return OfferedActions.NONE
        val actions = mutableListOf<OfferedAction>()
        val lists = mutableListOf<Pair<Int, Int>>()
        for (handle in claiming(file, codeActionProviders.mapValues { it.value.selector })) {
            val reply = awaitCancellable("Code action provider $handle", CODE_ACTION_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, CodeActions.PROVIDE, CodeActions.provide(handle, resource, range), true)
            } ?: continue
            val offered = CodeActions.offered(reply)
            offered.cacheId?.let { lists += handle to it }
            offered.actions.mapTo(actions) { OfferedAction(handle, it) }
        }
        return OfferedActions(actions, lists)
    }

    /** Lets the host drop lists nothing can be chosen from any more. */
    fun releaseCodeActions(offered: OfferedActions) {
        for ((handle, list) in offered.lists) {
            tell(CodeActions.RELEASE, CodeActions.release(handle, list)) { "release code actions $list of provider $handle" }
        }
    }

    /**
     * Fills in the edit or command of an action an extension computes only once it is chosen.
     *
     * Language servers routinely do this: a quick fix whose edit is expensive is offered with a title
     * alone, and `codeAction/resolve` is what produces it. Without this such an action does nothing.
     */
    fun resolveCodeAction(offered: OfferedAction): CodeActions.Resolved {
        val action = offered.action
        val cacheId = action.cacheId
        val provider = codeActionProviders[offered.handle]
        if (action.edit != null || cacheId == null || provider?.supportsResolve != true) {
            return CodeActions.Resolved(action.edit, action.command)
        }
        val call = callHost ?: return CodeActions.Resolved(null, action.command)
        val reply = awaitCancellable("Resolving code action '${action.title}'", RESOLVE_TIMEOUT_SECONDS) {
            call(EXT_HOST_LANGUAGE_FEATURES, CodeActions.RESOLVE, CodeActions.resolve(offered.handle, cacheId), true)
        }
        val resolved = CodeActions.resolved(reply)
        return CodeActions.Resolved(resolved.edit, resolved.command ?: action.command)
    }

    /**
     * Runs a command an action carries, without waiting for it.
     *
     * Not waited on because the command commonly calls back: a language client's "apply this fix"
     * command asks the workbench to apply an edit, which is served on the editor thread -- the one a
     * caller waiting here would be holding.
     *
     * A debug session it starts is kept to run again as this command; see [CodeBridgeDebugOrigins].
     * [noDebug] overrides whether that session debugs.
     */
    suspend fun executeCommand(command: CodeActions.Command, noDebug: Boolean? = null): String {
        val call = callHost ?: throw IllegalStateException("No extension host is running")
        return CodeBridgeDebugOrigins.during(project, CodeBridgeDebugOrigins.Origin(command, noDebug)) {
            call(EXT_HOST_COMMANDS, CodeActions.EXECUTE, CodeActions.execute(command), false)
        }
    }


    /**
     * `MainThreadBulkEdits.$tryApplyWorkspaceEdit`: `workspace.applyEdit`, answered with whether it
     * was applied.
     *
     * Every command-driven fix ends here rather than in a code action's own edit, so this is the half
     * of code actions many language servers actually use.
     */
    suspend fun applyWorkspaceEdit(argsJson: String): MainThreadResult {
        val edit = WorkspaceEdit.applied(argsJson)
        // As the user's client: an edit that asks to be confirmed is previewed in a dialog, which in
        // split mode reaches the user only from there.
        val applied = CodeBridgeTextEditors.getInstance(project).asClient {
            runCatching { CodeBridgeWorkspaceEdits.apply(project, edit, EDIT_COMMAND_NAME, ::isCurrent) }
                .onFailure { LOG.warn("Could not apply an extension's edit", it) }
                .getOrDefault(false)
        }
        return MainThreadResult(applied.toString())
    }

    /**
     * Asks the extensions what to change because of a file [operation] about to happen:
     * `onWillCreateFiles`, `onWillDeleteFiles` or `onWillRenameFiles`.
     *
     * Null when no host is running, no listener had an edit, or the host did not answer in time.
     * Blocking and cancellable; called under a progress indicator, outside a read action, since a
     * language server may take a while to work out every import a moved file breaks.
     *
     * [written] is text just put in files the host mirrors, waited for first: an extension's edit
     * that changes a file and then renames it is asked about with the change already made.
     */
    fun willRunFileOperation(
        operation: FileOperations.Operation,
        changes: List<FileOperations.Change>,
        written: Map<VirtualFile, String> = emptyMap(),
    ): FileOperations.Participation? {
        val call = callHost ?: return null
        for ((file, text) in written) {
            if (mirrored.containsKey(file) && !awaitMirrored(
                    file,
                    text
                )
            ) LOG.info("${file.name} was not mirrored before a file ${operation.noun}")
        }
        val reply = awaitCancellable("File ${operation.noun} participants", FILE_PARTICIPANTS_TIMEOUT_SECONDS) {
            call(
                FileOperations.SERVICE, FileOperations.WILL_RUN,
                FileOperations.willRun(
                    operation,
                    changes,
                    TimeUnit.SECONDS.toMillis(FILE_PARTICIPANTS_TIMEOUT_SECONDS)
                ), true
            )
        }
        return FileOperations.participation(reply)
    }

    /** Tells the extensions a file [operation] happened, which a language client forwards to its server. */
    fun didRunFileOperation(operation: FileOperations.Operation, changes: List<FileOperations.Change>) =
        tell(FileOperations.DID_RUN, FileOperations.didRun(operation, changes), FileOperations.SERVICE) {
            "report a file ${operation.noun}"
        }

    /**
     * Whether the host's copy of [file] is the editor's, so that offsets computed from it hold.
     *
     * The version alone is not enough. Edits reach the host from a queue, so the host can be a
     * keystroke behind with its version unchanged; the text it was last sent is compared as well.
     * A file the host is not mirroring is not the host's to be stale about.
     */
    fun isCurrent(file: VirtualFile, versionId: Int?): Boolean {
        val uri = uris[file] ?: return true
        val mirror = documents ?: return true
        if (versionId != null && mirror.versionOf(uri) != versionId) return false
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return true
        return mirrored[file] == document.text
    }

    /** The text the host was last sent for [file], or null if it holds no copy; for diagnosis. */
    fun hostTextOf(file: VirtualFile): String? {
        val uri = uris[file] ?: return null
        return documents?.textOf(uri)
    }

    /** The editor id the host knows [file]'s editor by, once the file is mirrored. */
    fun editorOf(file: VirtualFile): String? {
        val uri = uris[file] ?: return null
        return documents?.editorOf(uri)
    }

    /** The file behind an editor id the host names, or null for one it was never given. */
    fun fileOfEditor(editorId: String): VirtualFile? {
        val mirror = documents ?: return null
        return uris.entries.firstOrNull { mirror.editorOf(it.value) == editorId }?.key
    }

    /**
     * Whether an extension offers to format [file], by the selectors its formatters registered.
     *
     * Only a mirrored file: the host has no text for anything else to format.
     */
    fun canFormat(file: VirtualFile): Boolean {
        val languageId = languageIds[file] ?: return false
        val path = resourceOf(file)?.path ?: return false
        return (formattingProviders.values + rangeFormattingProviders.values)
            .any { DocumentSelector.matches(it.selector, languageId, path) }
    }

    /**
     * Waits until the host holds [text] for [file], or gives up.
     *
     * A formatter's edits are offsets into the host's copy. Edits are mirrored from a queue, so
     * reformatting straight after typing can reach the formatter before the typing does -- and its
     * answer would then land on the wrong characters.
     */
    fun awaitMirrored(file: VirtualFile, text: String, timeoutMillis: Long = MIRROR_WAIT_MILLIS): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (mirrored[file] != text) {
            if (System.nanoTime() > deadline) return false
            ProgressManager.checkCanceled()
            Thread.sleep(10)
        }
        return true
    }

    /**
     * The on-type formatter that formats [file] after [ch] is typed, if `editor.formatOnType` is on
     * for its language. As VS Code picks it: the first provider that claims the file, and only if
     * [ch] is one of its triggers -- another provider's triggers do not count.
     */
    fun onTypeFormatter(file: VirtualFile, ch: String): Int? {
        if (onTypeFormattingProviders.isEmpty()) return null
        val handle =
            claiming(file, onTypeFormattingProviders.mapValues { it.value.selector }).firstOrNull() ?: return null
        if (ch !in onTypeFormattingProviders[handle]?.triggers.orEmpty()) return null
        val enabled = project.service<HostedLanguageServers>().setting(OnTypeFormatting.SETTING, languageIds[file])
        return handle.takeIf { (enabled as? JsonPrimitive)?.booleanOrNull == true }
    }

    /**
     * The edits [handle] makes after [ch] was typed, leaving the caret at [position], about [text] --
     * waited for in the host first, since the keystroke has only just been sent. Null when the host
     * did not catch up, or the formatter failed or had nothing to do. Blocking.
     */
    fun onTypeFormatting(
        file: VirtualFile,
        handle: Int,
        text: String,
        position: Position,
        ch: String,
        options: Formatting.Options
    ): List<TextEdit>? {
        val call = callHost ?: return null
        val resource = resourceOf(file) ?: return null
        if (!awaitMirrored(file, text)) return null
        val reply = awaitCancellable("On-type formatter $handle", FORMATTING_TIMEOUT_SECONDS) {
            call(
                EXT_HOST_LANGUAGE_FEATURES,
                OnTypeFormatting.PROVIDE,
                OnTypeFormatting.provide(handle, resource, position, ch, options),
                true
            )
        } ?: return null
        return TextEdit.list(reply)?.takeIf { it.isNotEmpty() }
    }

    /** A formatter's edits, and whether they were computed for the whole document or a range of it. */
    data class FormattingResult(val edits: List<TextEdit>, val wholeDocument: Boolean)

    /**
     * Asks the formatters that claim [file] for edits, the first one with an answer winning.
     *
     * [range] null formats the whole document. A range goes to a range formatter, or, where the file
     * has none, to a whole-document one -- whose edits the caller then trims to the range. A file
     * with only a range formatter is formatted whole by giving it the whole document as the range.
     * A formatter answering with nothing passes the file to the next one; when all of them do, the
     * result is no edits. Returns null only when every formatter failed or timed out.
     */
    fun formatting(file: VirtualFile, range: Range?, whole: Range, options: Formatting.Options): FormattingResult? {
        val call = callHost ?: return null
        val languageId = languageIds[file] ?: return null
        val resource = resourceOf(file) ?: return null
        fun claiming(providers: Map<Int, Formatting.Registration>) = providers.entries
            .filter { DocumentSelector.matches(it.value.selector, languageId, resource.path) }
            .map { it.key }.sorted()
        val document = claiming(formattingProviders).map { handle ->
            Triple(handle, Formatting.PROVIDE_DOCUMENT, Formatting.provideDocument(handle, resource, options))
        }
        val ranged = claiming(rangeFormattingProviders).map { handle ->
            Triple(handle, Formatting.PROVIDE_RANGE, Formatting.provideRange(handle, resource, range ?: whole, options))
        }
        // The formatter that does exactly what was asked goes first, the other kind after it.
        val attempts = if (range != null) ranged + document else document + ranged
        var declined = false
        for ((handle, method, args) in attempts) {
            val reply = awaitCancellable("Formatter $handle", FORMATTING_TIMEOUT_SECONDS) {
                call(EXT_HOST_LANGUAGE_FEATURES, method, args, true)
            } ?: continue
            val edits = TextEdit.list(reply)
            if (edits == null) {
                declined = true
                continue
            }
            return FormattingResult(edits, wholeDocument = method == Formatting.PROVIDE_DOCUMENT)
        }
        // A formatter that answers with nothing may have found nothing to change -- rust-analyzer
        // answers so for a file that is already formatted. That is not a failure to report.
        return if (declined) FormattingResult(emptyList(), wholeDocument = range == null) else null
    }

    /**
     * Waits on one request for a caller that cannot suspend, giving up with its progress indicator or
     * job, or after the timeout.
     *
     * Returns null for a provider that failed or timed out, which callers treat as having nothing
     * to say; a cancellation is passed on, and the request withdrawn from the host with it.
     */
    private fun awaitCancellable(what: String, timeoutSeconds: Long, request: suspend () -> String): String? {
        ProgressManager.checkCanceled()
        return try {
            runBlockingMaybeCancellable { within(timeoutSeconds, request) }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.debug("$what failed", failure)
            null
        }
    }

    /**
     * [request], failing with a [TimeoutException] after [timeoutSeconds]: not with a cancellation,
     * which a caller would take for its own.
     */
    private suspend fun <T> within(timeoutSeconds: Long, request: suspend () -> T): T = try {
        withTimeout(timeoutSeconds.seconds) { request() }
    } catch (_: TimeoutCancellationException) {
        throw TimeoutException("No answer within ${timeoutSeconds}s")
    }

    /**
     * Sends [method] to [service] now, in order with what is sent after it, without waiting for the
     * answer, which says nothing anyone needs; a failure is only logged, as failing to [what].
     */
    private fun tell(method: String, args: String, service: String = EXT_HOST_LANGUAGE_FEATURES, what: () -> String) {
        val call = callHost ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                call(service, method, args, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.debug("Could not ${what()}", failure)
            }
        }
    }

    /** Mirrors an editor document into the host, so the extension's client synchronizes it upstream. */
    fun documentOpened(file: VirtualFile, languageId: String, snapshot: Snapshot) {
        val mirror = documents ?: return
        runCatching {
            // Not `file.url`: IntelliJ spells local files `file://C:/x`, where the drive parses as
            // the URI authority and the path comes out as `/x`. Everything downstream -- the host's
            // document key, the URI diagnostics come back under -- would then lose the drive.
            val uri = uriOf(file)
            // Reopening is not an error: a second editor split reports the file again, and so does
            // every file still open when an extension changes and the host is restarted under it.
            mirror.open(OWNER, uri, languageId, snapshot.text, dirty(uri, snapshot), encoding = encodingOf(file), active = activates(file),
                indentation = indentationOf(file))
            files[ExtensionHostDocuments.key(uri)] = file
            uris[file] = uri
            mirrored[file] = snapshot.text
            languageIds[file] = languageId
            LOG.info("Mirrored ${file.name} into the extension host as '$languageId'")
            // Straight away: there is nothing to wait for on a file that has just been opened, and
            // the sooner this lands the fewer passes run before the colouring is right.
            requestSemanticTokens(file, snapshot.text, quietMillis = 0)
            requestInlayHints(file, snapshot.text, quietMillis = 0, retryNothing = true)
            requestCodeLenses(file, snapshot.text, quietMillis = 0)
            // Asked rather than waited for: the editor's first passes may well have run already.
            requestDocumentSymbols(file, quietMillis = 0, retryNothing = true)
            requestFoldingRanges(file, snapshot.text, quietMillis = 0, retryNothing = true)
            if (documentColorProviders.isNotEmpty()) CodeBridgeDocumentColors.getInstance(project)
                .request(file, quietMillis = 0)
            // The daemon has usually finished with the file before the host is up to hold it.
            CodeBridgeIdeProblems.getInstance(project).refresh(file)
        }.onFailure { LOG.warn("Could not mirror ${file.name} into the extension host", it) }
    }

    /** Whether the host holds a copy of [file]; the cheap filter on the keystroke path. */
    fun isMirrored(file: VirtualFile): Boolean = documents != null && uris.containsKey(file)

    /** Whether the host has [file] in an editor, not merely held for an extension that opened it. */
    fun isShown(file: VirtualFile): Boolean {
        val uri = uris[file] ?: return false
        return documents?.isShown(OWNER, uri) == true
    }

    /**
     * The file last selected in an editor, whether or not the host holds it. Opening any other
     * file -- one restored with the project, after the selected one -- leaves the active editor be.
     */
    @Volatile private var selected: VirtualFile? = null

    /** Whether opening [file] should make it the host's active editor. */
    private fun activates(file: VirtualFile): Boolean {
        val current = selected ?: return true
        return current == file || !isShown(current)
    }

    /** Tells the host that [file]'s editor is the one in front of the user, if it has one. */
    fun documentSelected(file: VirtualFile) {
        selected = file
        val uri = uris[file] ?: return
        runCatching { documents?.activate(OWNER, uri) }
            .onFailure { LOG.warn("Could not tell the extension host that ${file.name} is the active editor", it) }
    }

    /** The language id the host holds [file] as, or null if it holds no copy. */
    fun languageOf(file: VirtualFile): String? = languageIds[file]

    /**
     * Puts [file] into the host without an editor, for an extension's `workspace.openTextDocument`,
     * and answers with the resource the host now holds it under.
     *
     * Edits and saves reach it through the same paths as an open file's, since both look only at
     * whether the host holds a copy. A file already held keeps the text and language it has: the
     * host is up to date with it, and a second language for one document is refused.
     *
     * [untitled] names a buffer with no file on disk -- `untitled:Untitled-1` -- which [file] stands
     * in for in IntelliJ.
     */
    fun documentHeld(file: VirtualFile, languageId: String, snapshot: Snapshot, untitled: String? = null): String {
        val mirror = checkNotNull(documents) { "The extension host is not running" }
        val uri = uris[file] ?: untitled ?: uriOf(file)
        mirror.open(HOLDER, uri, languageIds[file] ?: languageId, mirrored[file] ?: snapshot.text, dirty(uri, snapshot),
            shown = false, encoding = encodingOf(file), indentation = indentationOf(file))
        files[ExtensionHostDocuments.key(uri)] = file
        uris[file] = uri
        mirrored.putIfAbsent(file, snapshot.text)
        languageIds.putIfAbsent(file, languageId)
        return mirror.spellingOf(uri) ?: uri
    }

    /** Lets go of a file [documentHeld] put into the host; it stays while an editor shows it. */
    fun documentReleased(file: VirtualFile) {
        val mirror = documents ?: return
        val uri = uris[file] ?: return
        runCatching { mirror.close(HOLDER, uri) }
            .onFailure { LOG.warn("Could not withdraw ${file.name} from the extension host", it) }
        if (!mirror.isOpen(OWNER, uri)) forget(file, uri)
    }

    /**
     * Whether the host's copy of [uri] is unsaved. An untitled buffer with anything in it always is,
     * as in VS Code: it is saved nowhere, whatever IntelliJ's in-memory file reports.
     */
    private fun dirty(uri: String, snapshot: Snapshot) =
        snapshot.dirty || (uri.startsWith("${UriComponents.UNTITLED}:") && snapshot.text.isNotEmpty())

    /** VS Code's id for how IntelliJ decodes [file]. */
    private fun encodingOf(file: VirtualFile): String = Encodings.idOf(file.charset, file.bom != null)

    /**
     * How [file]'s editor indents where its text gives no hint: the code style set for its file type,
     * standing in for VS Code's `editor.tabSize` and `editor.insertSpaces`. The text, where it does
     * say, is read by the mirror as VS Code reads it.
     */
    private fun indentationOf(file: VirtualFile): Indentation = runCatching {
        val indent = CodeStyle.getSettings(project, file).getIndentOptions(file.fileType)
        Indentation(indent.TAB_SIZE, !indent.USE_TAB_CHARACTER)
    }.getOrDefault(Indentation.DEFAULT)

    /** Whichever owner has [uri] mirrored, which is who edits and saves are reported for. */
    private fun ownerOf(mirror: ExtensionHostDocuments, uri: String) = if (mirror.isOpen(OWNER, uri)) OWNER else HOLDER

    /**
     * Sends an edit on, so the extension's server sees the buffer rather than the file on disk.
     *
     * Without this an extension answers every hover and reports every problem against the text as
     * it was when the file was opened, and says so confidently.
     */
    fun documentChanged(file: VirtualFile, snapshot: Snapshot) {
        val mirror = documents ?: return
        val uri = uris[file] ?: return
        // Before the host is told, which is a round trip: the keystroke that brought this here
        // also redraws the file, and the sooner the colouring has moved the likelier that redraw
        // puts it on the right words rather than on where they used to be.
        synchronized(colourLock) {
            val previous = mirrored.put(file, snapshot.text)
            if (previous != null) colours.computeIfPresent(file) { _, tokens ->
                SemanticTokens.rebase(tokens, previous, snapshot.text)
            }
        }
        editedAt[file] = System.nanoTime()
        runCatching {
            // `change` answers false when the host already holds this text, and says nothing. That
            // is the one outcome indistinguishable from a working sync from anywhere else: the
            // editor moved on, the extension did not, and no error was raised by either.
            if (mirror.change(ownerOf(mirror, uri), uri, snapshot.text, dirty(uri, snapshot))) reportEdit(file, mirror, uri)
            else LOG.debug("The extension host already had the current text of ${file.name}; no edit sent")
            // Reloading a file in another encoding arrives here as an edit, and is where it is noticed.
            mirror.encodingChanged(ownerOf(mirror, uri), uri, encodingOf(file))
        }.onFailure { LOG.warn("Could not mirror an edit to ${file.name}", it) }
        // Colouring is for an editor to draw; a file an extension merely holds has none.
        if (mirror.isShown(OWNER, uri)) {
            requestSemanticTokens(file, snapshot.text, EDIT_QUIET_MILLIS)
            requestInlayHints(file, snapshot.text, INLAY_EDIT_QUIET_MILLIS, retryNothing = false)
            requestCodeLenses(file, snapshot.text, CODE_LENS_EDIT_QUIET_MILLIS)
            // Not document symbols: the bar and the sticky lines pass ask for those as they redraw.
            requestFoldingRanges(file, snapshot.text, FOLDING_RANGE_EDIT_QUIET_MILLIS, retryNothing = false)
            if (documentColorProviders.isNotEmpty()) CodeBridgeDocumentColors.getInstance(project)
                .request(file, DOCUMENT_COLOR_EDIT_QUIET_MILLIS)
        }
    }

    /**
     * Says, occasionally, that edits are reaching the host.
     *
     * An extension that answers only after a save is indistinguishable from one that was never told
     * the document changed: a change the host accepts is silent by design, and both look like a slow
     * language server from the editor. One line every [EDIT_REPORT_EVERY] edits separates "typing is
     * being mirrored and the extension is choosing not to answer" from "the extension never heard",
     * which are different problems with no other symptom between them.
     */
    private fun reportEdit(file: VirtualFile, mirror: ExtensionHostDocuments, uri: String) {
        val count = edits.computeIfAbsent(file) { AtomicLong() }.incrementAndGet()
        // The first one as well as every later batch: the question this answers -- does typing reach
        // the extension at all -- is worth being able to settle with a single keystroke.
        if (count == 1L || count % EDIT_REPORT_EVERY == 0L) LOG.info(
            "Mirrored $count edit(s) of ${file.name} into the extension host, now at version ${mirror.versionOf(uri)}"
        )
    }

    /**
     * Tells the host what IntelliJ itself found in [file], replacing what it was told before; see
     * [CodeBridgeIdeProblems]. Answers whether it was sent.
     */
    fun ideProblemsChanged(file: VirtualFile, markers: List<Marker>): Boolean {
        val call = callHost ?: return false
        if (!uris.containsKey(file)) return false
        val resource = resourceOf(file) ?: return false
        // Sent now, in order with what follows; only a failure is waited for, to report it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                call(Diagnostics.EXT_HOST, Diagnostics.ACCEPT_MARKERS_CHANGE, Diagnostics.acceptMarkersChange(listOf(resource to markers)), false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LOG.warn("Could not tell the extension host IntelliJ's problems in ${file.name}", failure)
            }
        }
        return true
    }

    /** Extensions act on saves -- formatters, linters that only run on disk content, build watchers. */
    fun documentSaved(file: VirtualFile) {
        val mirror = documents ?: return
        val uri = uris[file] ?: return
        // Logged because for some extensions this is the event that starts the real analysis, and
        // its absence is otherwise indistinguishable from a slow one. rust-analyzer defaults to
        // `checkOnSave`, so every error it reports is downstream of a save arriving here: no save,
        // no `cargo check`, no diagnostics, and nothing anywhere saying why.
        LOG.info("Told the extension host that ${file.name} was saved")
        runCatching { mirror.save(ownerOf(mirror, uri), uri) }
            .onFailure { LOG.warn("Could not report saving ${file.name} to the extension host", it) }
    }

    /**
     * Withdraws a closed document.
     *
     * Left open, it keeps the extension's server holding a buffer no editor shows, and its problems
     * keep arriving for a file the user has put away. Unless an extension opened it itself: then
     * only its editor goes, and the extension goes on being told about edits until it lets go.
     */
    fun documentClosed(file: VirtualFile) {
        val mirror = documents ?: return
        val uri = uris[file] ?: return
        // Withdrawn while the host still knows where to file it: no pass will bring them up to date.
        project.serviceIfCreated<CodeBridgeIdeProblems>()?.withdraw(file)
        colours.remove(file)
        colouredText.remove(file)
        highlights.forget(file)
        project.serviceIfCreated<CodeBridgeEditorDecorations>()?.forget(file)
        heldColours.remove(file)?.let(::releaseColours)
        redrawTimings.keys.removeIf { it.first == file }
        pendingColours.remove(file)
        colourJobs.remove(file)?.cancel()
        inlays.remove(file)?.release(this)
        inlaysChanged.remove(file)
        pendingInlays.remove(file)
        inlayJobs.remove(file)?.cancel()
        lenses.remove(file)?.release(this)
        pendingLenses.remove(file)
        lensJobs.remove(file)?.cancel()
        heldOutlines.remove(file)
        outlineAskedAt.remove(file)
        outlineRequests.remove(file)?.answer?.complete(Unit)
        symbolJobs.remove(file)?.cancel()
        heldLinks.remove(file)?.release(this)
        project.serviceIfCreated<CodeBridgeDocumentColors>()?.forget(file)
        heldFolds.remove(file)
        foldsChanged.remove(file)
        pendingFolds.remove(file)
        foldJobs.remove(file)?.cancel()
        runCatching { mirror.close(OWNER, uri) }
            .onFailure { LOG.warn("Could not withdraw ${file.name} from the extension host", it) }
        if (!mirror.isOpen(HOLDER, uri)) forget(file, uri)
    }

    /** Drops what was kept about a document the host no longer holds. */
    private fun forget(file: VirtualFile, uri: String) {
        uris.remove(file)
        files.remove(ExtensionHostDocuments.key(uri), file)
        mirrored.remove(file)
        languageIds.remove(file)
        editedAt.remove(file)
        edits.remove(file)
    }

    /**
     * `$changeMany(owner, entries)` where entries pair a URI with its markers, or with null to clear.
     *
     * VS Code has already converted whatever the extension produced -- LSP diagnostics or hand-built
     * ones -- into editor markers with 1-based lines and columns, which is what IntelliJ wants too.
     */
    private fun accept(argsJson: String) {
        val owner = Diagnostics.owner(argsJson)
        for ((resource, markers) in Diagnostics.changes(argsJson)) {
            val uri = resource.uri
            val key = runCatching { UriComponents.key(uri) }.getOrNull() ?: continue
            // A push that changes nothing drawn is not redrawn. rust-analyzer repeats "no problems"
            // two or three times after every keystroke, and with the redraw no longer waiting for
            // the auto-reparse delay each of those was a highlighting pass run between keystrokes.
            // The keystroke's own pass already draws whatever is held.
            if (markers == null) {
                // This collection has nothing to say about the file any more. Another one may still
                // have, so only its own entry goes.
                val removed = problems[key]?.remove(owner)
                problems.remove(key, EMPTY_PROBLEMS)
                LOG.info("Diagnostics cleared for $uri by '$owner'")
                if (removed.isNullOrEmpty()) continue
            } else {
                val previous = problems.computeIfAbsent(key) { ConcurrentHashMap() }.put(owner, markers)
                // Timed against the edit that provoked it. "Errors take seconds to appear" is the
                // one complaint this plugin cannot answer from a stack trace: the wait is an edit
                // going out, an extension thinking, and a push coming back, and only the extension
                // knows how long the middle took. This turns the whole round trip into a number,
                // which is the difference between reading the log and guessing at it.
                val file = files[key]
                val edited = file?.let { editedAt[it] }
                val since = edited?.let { ", ${(System.nanoTime() - it) / 1_000_000}ms after the last edit" } ?: ""
                if (previous.orEmpty() == markers) {
                    LOG.debug("Unchanged diagnostics for $uri from '$owner'$since; nothing to redraw")
                    continue
                }
                LOG.info("Received ${markers.size} diagnostic(s) for $uri from '$owner'$since")
                file?.let { timeRedraw(it, Redraw.DIAGNOSTICS, "Diagnostics for ${it.name} from '$owner'$since", edited) }
            }
            rehighlight(key, uri)
        }
    }

    /**
     * Diagnostics are pushed, and the daemon does not know that.
     *
     * [uri] defaults to [key] because the key is itself a normalized URI; the two differ only when
     * a push arrives, where the original spelling is already to hand.
     *
     * Without this the annotator would only run again when something else disturbed the file, so a
     * server that reports a problem a second after open would appear to report nothing.
     */
    private fun rehighlight(key: String, uri: String = key) {
        // Mirrored files first: that map is what the plugin itself opened. An extension may also
        // report a problem in a file it was never asked about -- a header, an import target -- and
        // if that one happens to be open it has to repaint too, so fall back to resolving the URI.
        val file = files[key] ?: localFile(uri) ?: return
        // Through [repaint], not straight to the daemon: two collections pushing about the same file
        // are the same redraw, and asking for it twice cancels it once.
        repaint(file)
    }

    /**
     * Redraws [file] soon, and once.
     *
     * `DaemonCodeAnalyzer.restart` does not queue a redraw -- it calls `stopProcess`, cancelling the
     * pass that is running. Two subsystems calling it independently therefore do not merely duplicate
     * work; they can each keep destroying the other's pass. After a save this plugin has exactly
     * that shape: the extension pushes diagnostics, and the colouring that the same save invalidated
     * arrives a moment behind them, each asking for a redraw of the same file. The errors were
     * computed and filed within ~200ms and then took seconds to appear, because the pass that would
     * have drawn them kept being cancelled by the colouring that followed. Colouring has since left
     * the daemon altogether -- [CodeBridgeSemanticHighlights] draws it without a pass -- but one extension
     * still pushes several collections about the same file in a burst.
     *
     * So every redraw in this class goes through here, and a burst costs one restart: the window is
     * not extended by later arrivals, so a steady stream cannot starve it.
     */
    private fun repaint(file: VirtualFile) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(REPAINT_QUIET_MILLIS.milliseconds)
            restartDaemon(file)
        }
        job.invokeOnCompletion { repaintJobs.remove(file, job) }
        // Already on its way: join it rather than pushing it further out.
        if (repaintJobs.putIfAbsent(file, job) != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private fun restartDaemon(file: VirtualFile) {
        scope.launch(Dispatchers.EDT) {
            if (project.isDisposed || !file.isValid) return@launch
            restarts.incrementAndGet()
            val now = System.nanoTime()
            for (what in Redraw.entries) redrawTimings[file to what]?.let { if (it.restartedAt == 0L) it.restartedAt = now }
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@launch
            // The inlay pass skips a file whose PSI has not changed since it last ran, and an answer
            // from an extension changes no PSI; without this the restart would not draw the hints.
            if (inlaysChanged.remove(file)) {
                LOG.info("Restarting highlighting of ${file.name} to draw its inlay hints")
                FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
                    EditorFactory.getInstance().getEditors(document, project).forEach { editor ->
                        CodeBridgeInlayHintsProvider.recompute(editor, project)
                    }
                }
            }
            // The folding pass keeps the folds it built until the PSI changes, which an answer does
            // not; this has each editor of the file build them afresh on the pass below.
            if (foldsChanged.remove(file)) {
                FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
                    val folding = CodeFoldingManager.getInstance(project)
                    EditorFactory.getInstance().getEditors(document, project).forEach(folding::scheduleAsyncFoldingUpdate)
                }
            }
            restartNow { DaemonCodeAnalyzer.getInstance(project).restart(psi, "VS Code extension answer arrived") }
        }
    }

    /**
     * Runs [restart] with the IDE's auto-reparse delay set aside, so the pass starts at once, when a
     * [CodeBridgeDaemonRestarter] can; otherwise the pass waits the delay like any other.
     */
    private fun restartNow(restart: () -> Unit) {
        CodeBridgeDaemonRestarter.EP_NAME.extensionsIfPointIsRegistered.firstOrNull()?.restartNow(restart) ?: restart()
    }

    /**
     * Starts timing the redraw an answer about [file] is waiting for.
     *
     * "Highlighting takes a second" is otherwise one number with four causes behind it: the wait for
     * typing to settle, the extension, this class collecting redraws, and the IDE's own delay before
     * a restarted pass runs -- `restart` schedules the pass after the auto-reparse delay, 300ms by
     * default, rather than running it. [drawn] finishes the line, so the log says which one it was.
     */
    private fun timeRedraw(file: VirtualFile, what: Redraw, summary: String, since: Long?) {
        redrawTimings[file to what] = RedrawTiming(summary, since, System.nanoTime())
    }

    /**
     * Called by the annotators each time they draw, and logs the line [timeRedraw] started once the
     * pass that drew it is one the restart asked for. A map read when nothing is being timed.
     *
     * Only after the restart: a pass the keystroke itself set off can draw the answer earlier than
     * the redraw that was asked for, and that is not the wait being measured.
     */
    fun drawn(file: VirtualFile, what: Redraw) {
        if (redrawTimings.isEmpty()) return
        val key = file to what
        val timing = redrawTimings[key] ?: return
        val restartedAt = timing.restartedAt
        if (restartedAt == 0L || !redrawTimings.remove(key, timing)) return
        val now = System.nanoTime()
        val total = timing.since?.let { "; ${(now - it) / 1_000_000}ms after the last edit in all" } ?: ""
        LOG.info("${timing.summary}; ${(restartedAt - timing.answeredAt) / 1_000_000}ms collecting the redraw, " +
            "${(now - restartedAt) / 1_000_000}ms from restarting highlighting until drawn$total")
    }

    /** The URI a diagnostic arrived under, as a file IntelliJ knows, or null if it is not one. */
    private fun localFile(uri: String): VirtualFile? = runCatching {
        LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(java.net.URI(uri)))
    }.getOrNull()

    /**
     * The resource a file is known by on this protocol.
     *
     * Not `VirtualFile.url`: IntelliJ spells local files `file://C:/x`, where the drive parses as
     * the URI authority and the path comes out as `/x`. [UriComponents] is the one place that
     * spelling is decided.
     */
    private fun resourceOf(file: VirtualFile): UriComponents? =
        runCatching { UriComponents.of(uriOf(file)) }.getOrNull()

    /**
     * The URI the host holds [file] under, or its path's when it holds none. An untitled buffer has
     * no path, and is known only by the URI it was mirrored with; a document an extension provided
     * -- a library class under Java's `jdt:` -- by the URI it was provided for.
     */
    private fun uriOf(file: VirtualFile): String = uris[file]
        ?: HostedContentProviders.getInstance(project).uriOf(file)
        ?: UriComponents.of(file.toNioPath()).uri

    private fun key(file: VirtualFile): String = UriComponents.key(uriOf(file))

    /** The file the host's [uri] names, if it holds one: an untitled buffer as well as a file. */
    fun fileOf(uri: String): VirtualFile? = files[ExtensionHostDocuments.key(uri)]

    /** Elapsed milliseconds since a `System.nanoTime()` reading, for the timing the log reports. */
    private fun millisSince(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    companion object {
        private val LOG = Logger.getInstance(HostedLanguageFeatures::class.java)

        private const val OWNER = "intellij"

        /** Who holds the documents extensions opened themselves; see [documentHeld]. */
        private const val HOLDER = "extensions"

        /** Compared by value, to drop a resource whose last collection has just withdrawn. */
        private val EMPTY_PROBLEMS = ConcurrentHashMap<String, List<Marker>>()

        /** The service every language-feature request goes back to the extension on. */
        private const val EXT_HOST_LANGUAGE_FEATURES = "ExtHostLanguageFeatures"

        private const val HOVER_TIMEOUT_SECONDS = 10L

        private const val HIGHLIGHT_TIMEOUT_SECONDS = 2L

        /** Where the commands an action carries are run. */
        private const val EXT_HOST_COMMANDS = "ExtHostCommands"

        /**
         * Short, because the intention menu waits on it under a read action, as a definition does.
         *
         * A language server's code actions are usually cheap -- it has the diagnostics already -- and
         * one that takes longer than this is better left out of the menu than holding the editor.
         */
        private const val CODE_ACTION_TIMEOUT_SECONDS = 3L

        /** Longer: the user has chosen the action and is waiting for exactly this. */
        private const val RESOLVE_TIMEOUT_SECONDS = 10L

        /** A formatter may have to run an external tool -- `rustfmt`, `prettier` -- over the whole file. */
        private const val FORMATTING_TIMEOUT_SECONDS = 20L

        /** How long a formatter waits for the host to catch up with the typing that preceded it. */
        private const val MIRROR_WAIT_MILLIS = 2_000L

        private const val MIRROR_POLL_MILLIS = 10L

        /**
         * Long, because an inline completion is commonly a model's answer from across the network,
         * and nothing waits on it: typing on cancels it, and the ghost text appears when it lands.
         */
        private const val INLINE_COMPLETION_TIMEOUT_SECONDS = 30L

        /** The undo entry an extension's edit is filed under. */
        private const val EDIT_COMMAND_NAME = "Extension Edit"

        /**
         * Generous, because nothing waits on it.
         *
         * The request is made in the background and the editor is repainted if and when it lands,
         * so the only cost of a long one is colour arriving late. The first answer of a session is
         * the expensive one -- a language server that has just started may still be indexing -- and
         * giving up early on it means a file that stays grammar-coloured until something else
         * disturbs it.
         */
        private const val SEMANTIC_TOKEN_TIMEOUT_SECONDS = 15L

        /**
         * How long a document has to be quiet before its colouring is asked for again.
         *
         * The whole of how long newly typed words go uncoloured, less the extension's answer -- about
         * 10ms from rust-analyzer. An answer is drawn without a highlighting pass, so nothing else
         * waits on this, and the colouring already held moves with each edit while it runs. Kept
         * clear of zero only so a burst of keystrokes typed faster than this is one request. Short
         * waits cannot pile requests up: only one is ever out per file, and a reply to text that has
         * since changed asks again as soon as it lands.
         */
        private const val EDIT_QUIET_MILLIS = 20L


        /**
         * The same for a provider that has just registered or just changed its mind.
         *
         * Extensions register their providers in a burst during activation, and a language server
         * announces a change once its project is loaded -- often several times in a row. One small
         * delay collapses a burst into a single request per open file.
         */
        private const val REGISTRATION_QUIET_MILLIS = 150L

        /**
         * How long redraws of one file are collected before the daemon is restarted.
         *
         * Short enough to be invisible next to the round trip that produced the answer, long enough
         * that the pushes of several diagnostic collections about one edit land in the same window --
         * rust-analyzer and `rustc` were measured pushing theirs within ~20ms of each other. The
         * window is fixed rather than sliding, so a provider answering continuously delays the
         * redraw by this much rather than preventing it.
         */
        private const val REPAINT_QUIET_MILLIS = 20L

        /** Generous for the same reason as [SEMANTIC_TOKEN_TIMEOUT_SECONDS]: nothing waits on it. */
        private const val INLAY_HINT_TIMEOUT_SECONDS = 15L

        /** Short: someone has just clicked and is waiting to be taken somewhere. */
        private const val INLAY_RESOLVE_TIMEOUT_SECONDS = 5L

        /**
         * How long a document has to be quiet before its hints are asked for again.
         *
         * Longer than colouring's, because an answer that changes the hints costs a highlighting
         * pass; short enough that the answer is usually in before the pass the keystroke itself set
         * off, 300ms out by default, which then draws it.
         */
        private const val INLAY_EDIT_QUIET_MILLIS = 100L

        /** Generous for the same reason as [SEMANTIC_TOKEN_TIMEOUT_SECONDS]: nothing waits on it. */
        private const val CODE_LENS_TIMEOUT_SECONDS = 15L

        /**
         * How long a document has to be quiet before its lenses are asked for again.
         *
         * Longer than hints': what a lens says rarely changes with a keystroke, and VS Code waits at
         * least this long too. The lenses held move with the edit meanwhile.
         */
        private const val CODE_LENS_EDIT_QUIET_MILLIS = 250L

        /** Generous for the same reason as [SEMANTIC_TOKEN_TIMEOUT_SECONDS]: nothing waits on it. */
        private const val DOCUMENT_SYMBOL_TIMEOUT_SECONDS = 15L

        /**
         * How long a document has to be quiet before its symbols are asked for again.
         *
         * As long as a lens's: an answer that changes the outline costs a highlighting pass, and the
         * outline held moves with the edit meanwhile. VS Code's outline waits about as long.
         */
        private const val DOCUMENT_SYMBOL_EDIT_QUIET_MILLIS = 250L

        /** Generous for the same reason as [SEMANTIC_TOKEN_TIMEOUT_SECONDS]: nothing waits on it. */
        private const val FOLDING_RANGE_TIMEOUT_SECONDS = 15L
        private const val DOCUMENT_COLOR_TIMEOUT_SECONDS = 15L

        /** As for folding ranges, which change about as often. */
        private const val DOCUMENT_COLOR_EDIT_QUIET_MILLIS = 250L

        /** Ctrl+hover and Ctrl+click wait on it, so it is short. */
        private const val DOCUMENT_LINK_TIMEOUT_SECONDS = 2L

        /**
         * How long a document has to be quiet before its folds are asked for again: as long as its
         * outline, since an answer that changes them costs a folding pass, and the folds held move
         * with the edit meanwhile.
         */
        private const val FOLDING_RANGE_EDIT_QUIET_MILLIS = 250L

        /** VS Code's default `editor.foldingMaximumRegions`: a file with more draws the first this many. */
        private const val MAX_FOLDS = 5000

        /** Often enough to appear while someone is typing a line, rare enough to stay readable. */
        private const val EDIT_REPORT_EVERY = 25L

        private const val NEWLINE = '\n'

        /** Long enough for a server that has to parse first, short enough not to hold up typing. */
        private const val COMPLETION_TIMEOUT_SECONDS = 5L

        /** How long a lookup's commands stay runnable after it closed without one being chosen. */
        private const val HELD_COMPLETION_SECONDS = 60L

        /**
         * Shorter than a hover's: this one is waited on under a read action.
         *
         * Everything that wants to write to the document -- the next keystroke included -- queues
         * behind it, so a server that has stopped answering must not hold the editor for as long
         * as a hover popup may sit empty.
         */
        private const val DEFINITION_TIMEOUT_SECONDS = 5L

        /** Long: a search over the whole project is what the user asked for, and they can cancel it. */
        private const val REFERENCE_TIMEOUT_SECONDS = 60L

        /** As long as a usage search: finding callers is one, and the user can close the tree. */
        private const val HIERARCHY_TIMEOUT_SECONDS = 60L

        /** Asked on every keystroke and superseded by the next, so a slow answer is given up on sooner. */
        private const val WORKSPACE_SYMBOL_TIMEOUT_SECONDS = 15L

        /** Asked as the popup closes, with the user waiting for the editor to open. */
        private const val WORKSPACE_SYMBOL_RESOLVE_TIMEOUT_SECONDS = 5L

        /**
         * How long an answered query stands in for asking it again.
         *
         * Long enough to cover the popup asking for every name's symbols after asking for the names,
         * short enough that reopening it later asks afresh.
         */
        private const val WORKSPACE_SYMBOL_REUSE_MILLIS = 3_000L

        /** Asked before the user types anything, so a server that has stopped answering is not waited out long. */
        private const val RENAME_LOCATION_TIMEOUT_SECONDS = 10L

        /** As long as a usage search: a rename is one, and the user can cancel it. */
        private const val RENAME_TIMEOUT_SECONDS = 60L

        /** VS Code's `files.participants.timeout`; the user can cancel sooner. */
        private const val FILE_PARTICIPANTS_TIMEOUT_SECONDS = 60L

        /** What VS Code says of a provider that answered a rename with nothing. */
        const val NO_RENAME_RESULT = "No result."

        fun getInstance(project: Project): HostedLanguageFeatures = project.service()
    }
}
