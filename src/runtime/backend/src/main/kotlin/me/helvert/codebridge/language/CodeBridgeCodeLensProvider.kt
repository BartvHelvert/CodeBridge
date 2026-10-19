package me.helvert.codebridge.language

import com.intellij.codeInsight.codeVision.*
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import me.helvert.codebridge.exthost.protocol.payload.CodeLenses
import kotlin.coroutines.cancellation.CancellationException

/**
 * Draws an extension's code lenses, as code vision.
 *
 * Code vision rather than declarative inlays above the line: those only hand a click to their
 * handler with Ctrl held, and a lens is clicked plainly, as code vision is. It is also what the
 * IDE's own lenses -- usages, implementations -- are drawn as, and what IntelliJ's LSP client draws
 * a server's lenses as; the platform forwards it to a split-mode frontend and back.
 *
 * A fixed set of slots, each drawing the lens at its index among those on a line, chained after one
 * another. One provider would draw one lens per line in split mode: the frontend stands in for a
 * backend provider with one of its own that does not carry `singleEntryPerLine` over, so the default
 * applies -- one entry per line, the last -- and "Run | Debug" came out as "Debug". The slots share a
 * group, so the settings switch them as one.
 *
 * Code vision must not wait on an extension, so this only reads the lenses [HostedLanguageFeatures]
 * already holds; those are asked for in the background as the document changes, and code vision is
 * told to ask again when they arrive.
 *
 * Every entry on a line spans the range of the line's first lens. A split-mode click reaches the
 * backend as the range of the line's first lens, and the backend only finds the clicked entry among
 * those of exactly that range. GitLens starts its two lenses on a line at columns 0 and 1, to order
 * them; at their own ranges only the first could be clicked. A line that also has another
 * provider's lens is still not clickable in split mode: that is a platform bug, written up with its
 * fix in docs/platform-limitations.md, "A split-mode lens click finds its entry by the line's first range".
 */
internal abstract class CodeBridgeCodeLensProvider(private val slot: Int) :
    CodeVisionProvider<Unit>, DumbAware {

    override val id: String get() = idOf(slot)
    override val groupId: String get() = GROUP_ID
    override val name: String get() = NAME
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = if (slot == 0) emptyList() else listOf(
            CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(
                idOf(
                    slot - 1
                )
            )
        )

    /** Each slot draws one lens per line by construction; said so, since it is what split mode assumes anyway. */
    override val singleEntryPerLine: Boolean get() = true

    override fun precomputeOnUiThread(editor: Editor) = Unit

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project ?: return CodeVisionState.READY_EMPTY
        val features = HostedLanguageFeatures.getInstance(project)
        if (!features.hasCodeLensProviders()) return CodeVisionState.READY_EMPTY
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return CodeVisionState.READY_EMPTY
        val entries = features.codeLenses(file, document.immutableCharSequence)
            .groupBy { document.getLineNumber(it.start) }
            .mapNotNull { (_, onLine) ->
                val shown = onLine.filter { readable(it) != null }
                val placed = shown.getOrNull(slot) ?: return@mapNotNull null
                // Computed alike in every slot, so the line's entries agree on it.
                val range = shown.first().let { TextRange(it.start, it.end) }
                entryOf(features, placed)?.let { range to it }
            }
        return CodeVisionState.Ready(entries)
    }

    private fun readable(placed: HostedLanguageFeatures.PlacedLens): String? =
        placed.lens.title?.let(CodeLenses::plainTitle)?.ifEmpty { null }

    private fun entryOf(features: HostedLanguageFeatures, placed: HostedLanguageFeatures.PlacedLens): CodeVisionEntry? {
        val lens = placed.lens
        val title = readable(placed) ?: return null
        val command = lens.command ?: return TextCodeVisionEntry(title, id, tooltip = lens.tooltip.orEmpty())
        return ClickableTextCodeVisionEntry(title, id, { _, _ ->
            features.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    features.executeCommand(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.warn("The command behind a code lens (${command.id}) failed", failure)
                }
            }
        }, tooltip = lens.tooltip.orEmpty())
    }

    companion object {
        const val GROUP_ID: String = "codebridge.code.lenses"

        /** How many lenses one line can show; past that, the rest are left out. */
        const val SLOTS: Int = 8

        /** Every slot's id, to tell code vision to ask them all again. */
        val IDS: List<String> = (0 until SLOTS).map(::idOf)

        private const val NAME = "VS Code extensions"

        private fun idOf(slot: Int) = "$GROUP_ID.$slot"

        private val LOG = Logger.getInstance(CodeBridgeCodeLensProvider::class.java)
    }
}

internal class CodeBridgeCodeLens0 : CodeBridgeCodeLensProvider(0)
internal class CodeBridgeCodeLens1 : CodeBridgeCodeLensProvider(1)
internal class CodeBridgeCodeLens2 : CodeBridgeCodeLensProvider(2)
internal class CodeBridgeCodeLens3 : CodeBridgeCodeLensProvider(3)
internal class CodeBridgeCodeLens4 : CodeBridgeCodeLensProvider(4)
internal class CodeBridgeCodeLens5 : CodeBridgeCodeLensProvider(5)
internal class CodeBridgeCodeLens6 : CodeBridgeCodeLensProvider(6)
internal class CodeBridgeCodeLens7 : CodeBridgeCodeLensProvider(7)

/** Where the settings list the [CodeBridgeCodeLensProvider] slots, as one group, to switch off. */
internal class CodeBridgeCodeLensSettingProvider : CodeVisionGroupSettingProvider {
    override val groupId: String get() = CodeBridgeCodeLensProvider.GROUP_ID
    override val groupName: String get() = "Code lenses from VS Code extensions"
    override val description: String get() = "What extensions show above a line: Run, Debug, a count of references."
}
