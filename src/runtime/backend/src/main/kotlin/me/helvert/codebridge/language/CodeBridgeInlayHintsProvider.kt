package me.helvert.codebridge.language

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.lang.Language
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.exthost.protocol.payload.InlayHints
import me.helvert.codebridge.exthost.protocol.payload.LanguageFeatures
import kotlin.coroutines.cancellation.CancellationException

/**
 * Draws an extension's inlay hints, as declarative inlay hints.
 *
 * Declarative rather than inlays put on the editor directly, because the platform forwards these to
 * a split-mode frontend, as it does the daemon's highlighting -- and they come with the IDE's own
 * settings, and its "Disable hints" action on a hint, for free.
 *
 * The pass that collects them must not wait on an extension, so it only reads the hints
 * [HostedLanguageFeatures] already holds; those are asked for in the background as the document
 * changes, and a redraw is asked for when they arrive.
 */
internal class CodeBridgeInlayHintsProvider : InlayHintsProvider, DumbAware {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val virtualFile = file.virtualFile ?: return null
        val features = HostedLanguageFeatures.getInstance(file.project)
        if (!features.hasInlayHintProviders() || !features.isMirrored(virtualFile)) return null
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                for (placed in features.inlayHints(virtualFile, editor.document.immutableCharSequence)) {
                    sink.whenOptionEnabled(optionOf(placed.hint)) { draw(sink, features, placed) }
                }
            }
        }
    }

    private fun draw(sink: InlayTreeSink, features: HostedLanguageFeatures, placed: HostedLanguageFeatures.PlacedInlay) {
        val hint = placed.hint
        // A parameter name stands in front of the argument it names; anything else -- a type, most
        // of all -- follows what it describes, and has to stay with it when typing at that offset.
        val position = InlineInlayPosition(placed.offset, relatedToPrevious = hint.kind != InlayHints.KIND_PARAMETER)
        sink.addPresentation(position, null, hint.tooltip, HintFormat.default) {
            for ((index, part) in hint.parts.withIndex()) {
                val click = features.inlayClick(placed, index)
                if (click == null) text(part)
                else clickHandlerScope(InlayActionData(StringInlayActionPayload(click), CodeBridgeInlayHintClickHandler.ID)) { text(part) }
            }
        }
    }

    /**
     * In pieces the platform will not shorten: it cuts each text node at 30 characters, and how long
     * a hint may be is the extension's to decide -- rust-analyzer has a setting.
     */
    private fun PresentationTreeBuilder.text(part: InlayHints.Part) = part.text.chunked(MAX_TEXT_NODE).forEach { text(it) }

    private fun optionOf(hint: InlayHints.Hint): String = when (hint.kind) {
        InlayHints.KIND_TYPE -> TYPES
        InlayHints.KIND_PARAMETER -> PARAMETERS
        else -> OTHER
    }

    companion object {
        const val ID: String = "codebridge.inlay.hints"

        /** The kinds VS Code's `InlayHintKind` distinguishes, each switchable on its own. */
        private const val TYPES = "types"
        private const val PARAMETERS = "parameters"
        private const val OTHER = "other"

        /** The longest text node the platform draws whole. */
        private const val MAX_TEXT_NODE = 30

        val INFO = InlayProviderInfo(
            CodeBridgeInlayHintsProvider(),
            ID,
            setOf(
                InlayOptionInfo(TYPES, true, "Types"),
                InlayOptionInfo(PARAMETERS, true, "Parameter names"),
                InlayOptionInfo(OTHER, true, "Other hints"),
            ),
            true,
            "VS Code extensions",
        )

        /**
         * Makes the inlay pass collect again on its next run.
         *
         * It skips an editor whose PSI has not changed since it last ran, which is right for hints
         * computed from the PSI and wrong for these: they arrive from another process and change no
         * PSI. Schedule recomputation for the affected editors through the public API.
         * EDT only.
         */
        fun recompute(editor: Editor, project: Project) {
            DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
        }
    }
}

/**
 * What Ctrl+clicking a piece of a hint does: open the place it leads to, or run its command.
 *
 * Ctrl, because that is the click the platform hands an inlay's handler -- as VS Code's Ctrl+click
 * on a hint goes to its place. Registered on the backend, where a split-mode frontend sends the
 * click, with the payload [InlayHints.click] wrote.
 *
 * A piece whose provider fills hints in on request is asked about first, since what it leads to is
 * typically left out until then -- rust-analyzer leaves out every place.
 */
internal class CodeBridgeInlayHintClickHandler : InlayActionHandler {

    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) = handle(e.editor, payload)

    /** What a click with [payload] in [editor] does; the mouse event itself is not needed. Internal for the tests. */
    internal fun handle(editor: Editor, payload: InlayActionPayload) {
        val project = editor.project ?: return
        val text = (payload as? StringInlayActionPayload)?.text ?: return
        val click = InlayHints.click(text)
        if (click == null) {
            LOG.debug("An inlay hint was clicked with a payload this handler did not write: $text")
            return
        }
        perform(project, click)
    }

    private fun perform(project: Project, click: InlayHints.Click) {
        when (click) {
            is InlayHints.Click.GoTo -> {
                val link = LanguageFeatures.DefinitionLink(click.location.resource, click.location.range, null)
                val target = ReadAction.computeBlocking<PsiElement?, RuntimeException> { CodeBridgeDefinitionTargets.target(project, link) }
                (target as? Navigatable)?.let {
                    NavigationService.getInstance(project).requestNavigate(it, NavigationOptions.requestFocus())
                }
            }
            is InlayHints.Click.Run -> {
                val features = HostedLanguageFeatures.getInstance(project)
                features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        features.executeCommand(click.command)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LOG.warn("The command behind an inlay hint (${click.command.id}) failed", failure)
                    }
                }
            }
            // Asked now, as the click is made; only the answer is waited for.
            is InlayHints.Click.Resolve -> Scope.getInstance(project).scope.launch(start = CoroutineStart.UNDISPATCHED) {
                resolved(project, click)
            }
        }
    }

    /** Does what the clicked part does once the host has filled it in. */
    private suspend fun resolved(project: Project, click: InlayHints.Click.Resolve) {
        val hint = try {
            HostedLanguageFeatures.getInstance(project).resolveInlayHint(click.handle, click.cacheId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LOG.warn("Could not resolve a clicked inlay hint", failure)
            return
        }
        val resolved = hint?.parts?.getOrNull(click.part)?.action
        if (resolved == null) LOG.info("A clicked inlay hint leads nowhere, once resolved")
        // Back on the editor thread, which opening an editor needs; the click was there too.
        else withContext(Dispatchers.EDT) { perform(project, resolved) }
    }

    /** Where a resolve runs: it outlives the click, and ends with the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        const val ID: String = "codebridge.inlay.click"

        private val LOG = Logger.getInstance(CodeBridgeInlayHintClickHandler::class.java)
    }
}

/**
 * Offers [CodeBridgeInlayHintsProvider], and [CodeBridgeDecorationInlaysProvider] for decorations' text, for every language.
 *
 * Declarative providers are otherwise registered per language, and a file an extension claims may
 * have any language at all -- plain text, TextMate, or one the IDE has its own support for. The
 * collector leaves straight away for a file no extension holds.
 */
internal class CodeBridgeInlayHintsProviderFactory : InlayHintsProviderFactory {

    override fun getProvidersForLanguage(language: Language): List<InlayProviderInfo> =
        listOf(CodeBridgeInlayHintsProvider.INFO, CodeBridgeDecorationInlaysProvider.INFO)

    /** Where the settings list the provider: the languages files claimed by an extension are read with. */
    override fun getSupportedLanguages(): Set<Language> =
        setOfNotNull(PlainTextLanguage.INSTANCE, Language.findLanguageByID(TEXTMATE))

    override fun getProviderInfo(language: Language, providerId: String): InlayProviderInfo? =
        when (providerId) {
            CodeBridgeInlayHintsProvider.ID -> CodeBridgeInlayHintsProvider.INFO
            CodeBridgeDecorationInlaysProvider.ID -> CodeBridgeDecorationInlaysProvider.INFO
            else -> null
        }

    private companion object {
        const val TEXTMATE = "textmate"
    }
}
