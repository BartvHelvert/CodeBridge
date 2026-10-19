package me.helvert.codebridge.language

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import me.helvert.codebridge.exthost.protocol.payload.DocumentSymbols
import me.helvert.codebridge.language.IdeDocumentSymbols.OBJECT

/**
 * A file's outline from IntelliJ's own structure -- what the Structure view shows -- for a file no
 * extension outlines, so `vscode.executeDocumentSymbolProvider` answers in files IntelliJ owns.
 *
 * Only what the structure model lists by itself: the view's node providers (inherited members,
 * lambdas) are left out, as VS Code's outline lists a file's own declarations.
 */
internal object IdeDocumentSymbols {

    suspend fun of(project: Project, file: VirtualFile): List<DocumentSymbols.Symbol> =
        constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) { read(project, file) }

    private fun read(project: Project, file: VirtualFile): List<DocumentSymbols.Symbol> {
        if (!file.isValid) return emptyList()
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return emptyList()
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi)
                as? TreeBasedStructureViewBuilder ?: return emptyList()
        val model = builder.createStructureViewModel(null)
        try {
            val text = document.immutableCharSequence
            return symbolsOf(psi, text, model.root.children.filterIsInstance<StructureViewTreeElement>())
        } finally {
            Disposer.dispose(model)
        }
    }

    /** A node that is not an element of this file -- a group, say -- has its children stand in for it. */
    private fun symbolsOf(
        psi: PsiFile,
        text: CharSequence,
        nodes: List<StructureViewTreeElement>
    ): List<DocumentSymbols.Symbol> =
        nodes.flatMap { node ->
            val children = node.children.filterIsInstance<StructureViewTreeElement>()
            val element = node.value as? PsiElement
            val range = element?.takeIf { it.isValid && it.containingFile == psi }?.textRange
            if (element == null || range == null || range.endOffset > text.length) symbolsOf(psi, text, children)
            else listOf(symbolOf(psi, text, node, element, range, children))
        }

    private fun symbolOf(
        psi: PsiFile,
        text: CharSequence,
        node: StructureViewTreeElement,
        element: PsiElement,
        range: TextRange,
        children: List<StructureViewTreeElement>
    ): DocumentSymbols.Symbol {
        val presented = node.presentation.presentableText.orEmpty()
        val name = (element as? PsiNamedElement)?.name ?: presented
        // `foo(int): void` is named `foo`, with the rest as its detail, as VS Code's Java outline has it.
        val detail = if (presented.startsWith(name)) presented.removePrefix(name) else ""
        // An element with no name identifier -- an XML tag -- is named where its name first appears.
        val nameRange = ((element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange
            ?: nameAfter(text, element.textOffset, name, range)
            ?: TextRange.from(element.textOffset, 0))
            .takeIf { range.contains(it) } ?: TextRange.from(range.startOffset, 0)
        return DocumentSymbols.Symbol(
            name = name.ifEmpty { presented },
            kind = kindOf(element),
            range = CodeBridgeTextEdits.rangeOf(text, range),
            selectionRange = CodeBridgeTextEdits.rangeOf(text, nameRange),
            detail = detail,
            children = symbolsOf(psi, text, children),
        )
    }

    private fun nameAfter(text: CharSequence, from: Int, name: String, within: TextRange): TextRange? {
        if (name.isEmpty()) return null
        val at = text.indexOf(name, from.coerceIn(within.startOffset, within.endOffset))
        return TextRange.from(at, name.length).takeIf { at >= 0 && within.contains(it) }
    }

    /**
     * The editor's `SymbolKind`, numbered from 0, from the word the language's find usages names the
     * element's type by -- or, when a language pack has translated those words, from the names of
     * the element's PSI classes and interfaces, which no language pack translates: `PsiEnumConstant`
     * reads as "psi enum constant". An element neither names is an [OBJECT].
     */
    private fun kindOf(element: PsiElement): Int {
        val type = runCatching { LanguageFindUsages.getType(element) }.getOrNull().orEmpty().lowercase()
        kindIn(type)?.let { return it }
        return classNames(element.javaClass).firstNotNullOfOrNull { kindIn(words(it)) } ?: OBJECT
    }

    private fun kindIn(text: String): Int? = KINDS.firstOrNull { (word, _) -> word in text }?.second

    /** The class, then what it implements and extends, nearest first. */
    private fun classNames(type: Class<*>): List<String> {
        val names = LinkedHashSet<String>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            names += current.simpleName
            current.interfaces.forEach { names += it.simpleName }
            current = current.superclass
        }
        return names.toList()
    }

    /** `PsiEnumConstantImpl` as "psi enum constant impl". */
    private fun words(name: String): String = name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase()

    private const val OBJECT = 18

    /** More specific words first: an "enum constant" is not an enum, nor a "local variable" a local. */
    private val KINDS = listOf(
        "enum constant" to 21, "enum entry" to 21, "enum member" to 21,
        "type parameter" to 25, "constructor" to 8,
        "annotation" to 10, "interface" to 10, "trait" to 10, "protocol" to 10,
        "enum" to 9, "struct" to 22, "record" to 22, "class" to 4, "object" to 4,
        "method" to 5, "function" to 11, "property" to 6, "field" to 7,
        "constant" to 13, "variable" to 12, "parameter" to 12,
        "namespace" to 2, "package" to 3, "module" to 1, "event" to 23, "operator" to 24,
    )
}
