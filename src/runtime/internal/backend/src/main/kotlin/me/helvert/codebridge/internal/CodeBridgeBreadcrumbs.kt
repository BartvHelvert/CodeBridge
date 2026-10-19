// Internal API is this module's purpose; each use is listed in docs/platform-limitations.md.
@file:Suppress("UnstableApiUsage")

package me.helvert.codebridge.internal

import com.intellij.codeInsight.breadcrumbs.FileBreadcrumbsCollector
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.breadcrumbs.Crumb
import com.intellij.ui.components.breadcrumbs.StickyLineInfo
import com.intellij.xml.breadcrumbs.NavigatableCrumb
import com.intellij.xml.breadcrumbs.PsiFileBreadcrumbsCollector
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeOutlineListener
import me.helvert.codebridge.language.CodeBridgeWorkspaceSymbol
import javax.swing.Icon

/**
 * Breadcrumbs and sticky lines from an extension's document symbols, as VS Code draws both from
 * the outline.
 *
 * IntelliJ asks a file's breadcrumbs collector for both, and the collector it asks by default reads
 * them off the PSI through the language's `BreadcrumbsProvider` -- which a file an extension claims,
 * TextMate-highlighted or plain text, does not have. This one answers for those files from the
 * extension's outline of the text as it stands, asked for as the bar and the pass are drawn, as
 * IntelliJ's own LSP client does from a server's symbols. A file whose language the IDE structures
 * itself is left to the IDE.
 *
 * In `CodeBridge.internal.backend`: without the internal [requiresProvider] and [computeStickyLineInfos]
 * there would be neither a bar nor sticky lines, so nothing of it is worth keeping in a build
 * without that module.
 */
class CodeBridgeBreadcrumbsCollector(private val project: Project) : FileBreadcrumbsCollector() {

    override fun handlesFile(virtualFile: VirtualFile): Boolean {
        val features = project.serviceIfCreated<HostedLanguageFeatures>() ?: return false
        return features.outlines(virtualFile) && !PsiFileBreadcrumbsCollector(project).handlesFile(virtualFile)
    }

    /**
     * Whether the bar may only be shown for a language with a `BreadcrumbsProvider`, which these
     * files never have: so only while breadcrumbs are switched off. Asked about the bar alone --
     * sticky lines find this collector through [handlesFile] whatever it says.
     */
    override fun requiresProvider(): Boolean = !EditorSettingsExternalizable.getInstance().isBreadcrumbsShown

    /**
     * Updates the bar when the document changes, which has it ask for the outline of the new text,
     * and when an outline arrives that it did not ask for; the caret moving already does.
     */
    override fun watchForChanges(file: VirtualFile, editor: Editor, disposable: Disposable, changesHandler: Runnable) {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = changesHandler.run()
        }, disposable)
        project.messageBus.connect(disposable).subscribe(CodeBridgeOutlineListener.TOPIC, CodeBridgeOutlineListener { changed ->
            if (changed == file) changesHandler.run()
        })
    }

    /**
     * The symbols around [offset], outermost first, as VS Code's breadcrumbs list them: a symbol's
     * whole range counts, its end included, so a caret just after a closing brace still shows it.
     */
    override fun computeCrumbs(virtualFile: VirtualFile, document: Document, offset: Int, forcedShown: Boolean?): Iterable<Crumb> {
        if (forcedShown == false) return emptyList()
        val crumbs = ArrayList<Crumb>()
        var level = HostedLanguageFeatures.getInstance(project).currentOutline(virtualFile, document)
        while (true) {
            val symbol = level.firstOrNull { it.start <= offset && offset <= it.end } ?: break
            crumbs.add(SymbolCrumb(symbol, document.textLength))
            level = symbol.children
        }
        return crumbs
    }

    /**
     * The symbols around [offset] that span lines, outermost first, each sticking on the line its
     * name is on rather than where its range starts: the range may begin with a doc comment or a
     * decorator, and VS Code's sticky scroll shows the declaration. The pass asks at the end of every
     * line and keeps them all.
     */
    override fun computeStickyLineInfos(file: VirtualFile, document: Document, offset: Int): List<StickyLineInfo> {
        val around = ArrayList<StickyLineInfo>()
        var level = HostedLanguageFeatures.getInstance(project).currentOutline(file, document)
        while (true) {
            val symbol = level.firstOrNull { it.nameStart <= offset && offset < it.end } ?: break
            // Clamped, since a sticky line past the end of the document cannot be placed on it.
            val end = minOf(symbol.end, document.textLength)
            if (symbol.nameStart < end && document.getLineNumber(symbol.nameStart) < document.getLineNumber(end)) {
                around.add(StickyLineInfo(symbol.nameStart, end, null))
            }
            level = symbol.children
        }
        return around
    }
}

/**
 * One symbol in the bar. Clicking it goes to its name, as in VS Code; with selection, the whole of
 * it is selected, and hovering highlights it, as the IDE's own crumbs do.
 */
internal class SymbolCrumb(val symbol: HostedLanguageFeatures.OutlineSymbol, private val length: Int) : NavigatableCrumb {

    override fun getText(): String = symbol.name

    override fun getIcon(): Icon = CodeBridgeWorkspaceSymbol.icon(symbol.kind)

    override fun getTooltip(): String? = symbol.detail.takeIf { it.isNotBlank() }?.let { "${symbol.name} $it" }

    override fun getAnchorOffset(): Int = symbol.nameStart.coerceAtMost(length)

    override fun getHighlightRange(): TextRange = TextRange(symbol.start.coerceAtMost(length), symbol.end.coerceAtMost(length))

    override fun navigate(editor: Editor, withSelection: Boolean) {
        val length = editor.document.textLength
        editor.caretModel.moveToOffset(symbol.nameStart.coerceAtMost(length))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        if (withSelection) {
            editor.selectionModel.setSelection(symbol.start.coerceAtMost(length), symbol.end.coerceAtMost(length))
        }
    }

    override fun toString(): String = symbol.name
}
