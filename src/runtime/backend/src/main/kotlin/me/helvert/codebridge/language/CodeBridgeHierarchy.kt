package me.helvert.codebridge.language

import com.intellij.ide.hierarchy.*
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.ui.JBColor
import com.intellij.ui.tree.ui.DefaultTreeUI
import me.helvert.codebridge.exthost.protocol.payload.Hierarchies
import me.helvert.codebridge.exthost.protocol.payload.Range
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.util.function.Function
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.TreeNode

/**
 * A symbol a hierarchy is about: the place [offset] in [file], named [name].
 *
 * VS Code prepares a hierarchy at a position, and an extension's file has no element for the
 * symbol there, so this stands in for one, as the platform's own LSP client does it. It is what a
 * hierarchy is based on -- the caret, or a node "Base on This" was chosen on -- and what each node
 * of the tree holds.
 */
internal class CodeBridgeHierarchyElement(
    private val file: PsiFile,
    private val offset: Int,
    private val name: String,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextOffset(): Int = offset

    override fun getName(): String = name

    override fun isValid(): Boolean = file.isValid

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
    }

    /** Prepares a hierarchy of [kind] here, in VS Code's one-based line and column. Blocking. */
    fun prepare(kind: Hierarchies.Kind): HostedLanguageFeatures.PreparedHierarchy? {
        val virtualFile = file.virtualFile ?: return null
        val document = file.viewProvider.document ?: return null
        if (offset !in 0..document.textLength) return null
        val line = document.getLineNumber(offset)
        val column = offset - document.getLineStartOffset(line) + 1
        return HostedLanguageFeatures.getInstance(file.project).prepareHierarchy(kind, virtualFile, line + 1, column)
    }

    override fun equals(other: Any?): Boolean =
        other is CodeBridgeHierarchyElement && other.file == file && other.offset == offset && other.name == name

    override fun hashCode(): Int = (31 * file.hashCode() + offset) * 31 + name.hashCode()

    companion object {

        /** The symbol at the caret of the editor in [dataContext], where an extension offers a hierarchy of [kind]. */
        fun atCaret(kind: Hierarchies.Kind, dataContext: DataContext): CodeBridgeHierarchyElement? {
            val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
            val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
            val virtualFile = file.virtualFile ?: return null
            if (!HostedLanguageFeatures.getInstance(file.project).canShowHierarchy(kind, virtualFile)) return null
            val offset = editor.caretModel.offset
            val text = editor.document.charsSequence
            val name = identifierRange(text, offset)?.subSequence(text)?.toString() ?: file.name
            return CodeBridgeHierarchyElement(file, offset, name)
        }

        /** Where [range] of [resource] is, named [name]; null for a file the IDE cannot open. */
        fun at(project: Project, resource: UriComponents, range: Range, name: String): CodeBridgeHierarchyElement? {
            val virtualFile = fileOf(project, resource) ?: return null
            val psi = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
            return CodeBridgeHierarchyElement(psi, CodeBridgeDefinitionTargets.offsetOf(document, range.startLineNumber, range.startColumn), name)
        }

        /** The host's own document first, which covers an untitled buffer, then the file on disk. */
        private fun fileOf(project: Project, resource: UriComponents): VirtualFile? {
            HostedLanguageFeatures.getInstance(project).fileOf(resource.uri)?.let { return it }
            if (resource.scheme != UriComponents.FILE) return null
            val path = runCatching { resource.toPath() }.getOrNull() ?: return null
            return LocalFileSystem.getInstance().findFileByNioFile(path)
        }
    }
}

/**
 * One node of a hierarchy: [item] as the extension named it, which [handle] answers questions about.
 *
 * The base -- the caret the hierarchy was asked from -- has no item; the tree hides it, and shows
 * what preparing there named as its one child. [calls] is how many times the call this node stands
 * for is made, and [site] the first place it is made, where the node leads: a caller is shown where
 * it calls, as Java's callers are. Otherwise a node leads to the symbol itself. [order] is where the
 * extension listed it, which is the order shown unless the user sorts alphabetically.
 */
internal class CodeBridgeHierarchyNode(
    project: Project,
    parent: HierarchyNodeDescriptor?,
    element: CodeBridgeHierarchyElement,
    val item: Hierarchies.Item?,
    val handle: Int,
    private val calls: Int = 0,
    private val site: CodeBridgeHierarchyElement? = null,
    val order: Int = 0,
    isBase: Boolean = false,
) : HierarchyNodeDescriptor(project, parent, element, isBase), Navigatable {

    override fun update(): Boolean {
        val old = myHighlightedText
        var changes = super.update()
        val item = item ?: return changes
        myHighlightedText = CompositeAppearance()
        myHighlightedText.ending.addText(item.name, if (item.deprecated) DEPRECATED else null)
        item.detail?.let { myHighlightedText.ending.addText(" $it", getPackageNameAttributes()) }
        if (calls > 1) myHighlightedText.ending.addText(" ($calls usages)", getUsageCountPrefixAttributes())
        myName = myHighlightedText.text
        installIcon(CodeBridgeWorkspaceSymbol.icon(item.kind), changes)
        if (myHighlightedText != old) changes = true
        return changes
    }

    override fun canNavigate(): Boolean = (site ?: psiElement)?.let { (it as? Navigatable)?.canNavigate() } == true

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val target = (site ?: psiElement) as? Navigatable ?: return
        NavigationService.getInstance(project)
            .requestNavigate(target, NavigationOptions.defaultOptions().requestFocus(requestFocus))
    }

    private companion object {
        /** Struck through, as VS Code draws a symbol tagged deprecated. */
        val DEPRECATED = TextAttributes(null, null, JBColor.GRAY, EffectType.STRIKEOUT, 0)
    }
}

/**
 * The host sessions a browser's trees have prepared, released together when it closes.
 *
 * Every item a session answers -- callers of callers included -- stays asked about through it, so
 * nothing is released while the tree that shows them is open. Refreshing prepares afresh, and the
 * sessions it replaced are held until the browser goes.
 */
internal class CodeBridgeHierarchySessions(private val project: Project, private val kind: Hierarchies.Kind) : Disposable {
    private val held = mutableSetOf<Pair<Int, String>>()
    private var disposed = false

    fun hold(handle: Int, sessionId: String) {
        // Prepared after the browser closed: nothing will show it, so the host can let it go at once.
        val late = synchronized(this) { disposed.also { if (!it) held.add(handle to sessionId) } }
        if (late) release(handle, sessionId)
    }

    override fun dispose() {
        val sessions = synchronized(this) {
            disposed = true
            held.toList().also { held.clear() }
        }
        sessions.forEach { (handle, sessionId) -> release(handle, sessionId) }
    }

    private fun release(handle: Int, sessionId: String) {
        if (project.isDisposed) return
        HostedLanguageFeatures.getInstance(project).releaseHierarchy(kind, handle, sessionId)
    }
}

/**
 * One view of a hierarchy: what [children] says about each node, a level at a time.
 *
 * The base is the caret, which is prepared first; the item that names is the tree's visible root.
 * Built in the background, as the platform builds any hierarchy, so the extension is waited for there.
 */
internal class CodeBridgeHierarchyTreeStructure(
    project: Project,
    base: CodeBridgeHierarchyNode,
    private val kind: Hierarchies.Kind,
    private val sessions: CodeBridgeHierarchySessions,
    private val children: (HostedLanguageFeatures, CodeBridgeHierarchyNode, Hierarchies.Item) -> List<Child>,
) : HierarchyTreeStructure(project, base) {

    /** What a node leads to: [item], and the [calls] made where [site] is the first. */
    data class Child(val item: Hierarchies.Item, val calls: Int = 0, val site: Pair<UriComponents, Range>? = null)

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val node = descriptor as? CodeBridgeHierarchyNode ?: return emptyArray()
        val element = node.psiElement as? CodeBridgeHierarchyElement ?: return emptyArray()
        val item = node.item ?: return root(node, element)
        val features = HostedLanguageFeatures.getInstance(myProject)
        return children(features, node, item).mapIndexedNotNull { order, child ->
            val target = CodeBridgeHierarchyElement.at(myProject, child.item.resource, child.item.selectionRange, child.item.name)
                ?: return@mapIndexedNotNull null
            val site = child.site?.let { (resource, range) -> CodeBridgeHierarchyElement.at(myProject, resource, range, child.item.name) }
            CodeBridgeHierarchyNode(myProject, node, target, child.item, node.handle, child.calls, site, order)
        }.toTypedArray()
    }

    /**
     * The one symbol preparing at the caret names.
     *
     * VS Code shows the first of what preparing answers, and so does this. The session it opened is
     * held whether or not it named anything that can be shown.
     */
    private fun root(base: CodeBridgeHierarchyNode, element: CodeBridgeHierarchyElement): Array<Any> {
        val prepared = element.prepare(kind) ?: return emptyArray()
        prepared.items.map { it.sessionId }.distinct().forEach { sessions.hold(prepared.handle, it) }
        val item = prepared.items.first()
        val target = CodeBridgeHierarchyElement.at(myProject, item.resource, item.selectionRange, item.name) ?: return emptyArray()
        return arrayOf(CodeBridgeHierarchyNode(myProject, base, target, item, prepared.handle, isBase = true))
    }

    companion object {
        /** The base of a tree: the caret, or the node it was rebased on, with nothing asked of it yet. */
        fun base(project: Project, element: PsiElement): CodeBridgeHierarchyNode =
            CodeBridgeHierarchyNode(project, null, element as CodeBridgeHierarchyElement, null, -1, isBase = true)

        /** Callers lead to where they call, in their own file. */
        fun callers(features: HostedLanguageFeatures, node: CodeBridgeHierarchyNode, item: Hierarchies.Item): List<Child> =
            features.incomingCalls(node.handle, item).map { call ->
                Child(call.item, call.ranges.size, call.ranges.firstOrNull()?.let { call.item.resource to it })
            }

        /** Callees lead to themselves: the calls are made in the caller, which is already in the tree. */
        fun callees(features: HostedLanguageFeatures, node: CodeBridgeHierarchyNode, item: Hierarchies.Item): List<Child> =
            features.outgoingCalls(node.handle, item).map { Child(it.item, it.ranges.size) }

        fun supertypes(features: HostedLanguageFeatures, node: CodeBridgeHierarchyNode, item: Hierarchies.Item): List<Child> =
            features.supertypes(node.handle, item).map { Child(it) }

        fun subtypes(features: HostedLanguageFeatures, node: CodeBridgeHierarchyNode, item: Hierarchies.Item): List<Child> =
            features.subtypes(node.handle, item).map { Child(it) }

        /** In the extension's order, unless the user sorts alphabetically. */
        fun comparator(project: Project): Comparator<NodeDescriptor<*>> =
            if (HierarchyBrowserManager.getSettings(project).SORT_ALPHABETICALLY) AlphaComparator.getInstance()
            else compareBy { (it as? CodeBridgeHierarchyNode)?.order ?: 0 }

        /**
         * A tree whose base is hidden, as the caret it stands for names nothing; the symbol preparing
         * names is shown open, and nothing below it opens by itself.
         */
        fun hideBase(tree: JTree): JTree = tree.apply {
            isRootVisible = false
            putClientProperty(DefaultTreeUI.AUTO_EXPAND_FILTER, Function<Any, Boolean> { (it as? TreeNode)?.parent?.parent != null })
        }
    }
}

/**
 * Show Call Hierarchy, answered by `$prepareCallHierarchy` and the incoming and outgoing calls of
 * what it names.
 *
 * Registered for every language and last, as the platform's own LSP client registers its own, so
 * a language the IDE knows keeps its own hierarchy. It offers a hierarchy where an extension
 * claims the file, without asking anything until the tree is built.
 */
internal class CodeBridgeCallHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? =
        CommonDataKeys.PSI_ELEMENT.getData(dataContext) as? CodeBridgeHierarchyElement
            ?: CodeBridgeHierarchyElement.atCaret(Hierarchies.Kind.CALL, dataContext)

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = CodeBridgeCallHierarchyBrowser(target.project, target)

    /** Callers first, as VS Code shows them. */
    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as? CodeBridgeCallHierarchyBrowser)?.changeView(CallHierarchyBrowserBase.getCallerType())
    }
}

/** Show Type Hierarchy, answered by `$prepareTypeHierarchy` and the supertypes and subtypes of what it names. */
internal class CodeBridgeTypeHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? =
        CommonDataKeys.PSI_ELEMENT.getData(dataContext) as? CodeBridgeHierarchyElement
            ?: CodeBridgeHierarchyElement.atCaret(Hierarchies.Kind.TYPE, dataContext)

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = CodeBridgeTypeHierarchyBrowser(target.project, target)

    /** Subtypes first, as VS Code shows them. */
    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as? CodeBridgeTypeHierarchyBrowser)?.changeView(TypeHierarchyBrowserBase.getSubtypesHierarchyType())
    }
}

/** Callers and callees, without the scope the platform's own offers: an extension searches where it searches. */
internal class CodeBridgeCallHierarchyBrowser(project: Project, element: PsiElement) : CallHierarchyBrowserBase(project, element) {

    private val sessions = CodeBridgeHierarchySessions(project, Hierarchies.Kind.CALL).also { Disposer.register(this, it) }

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun prependActions(actionGroup: DefaultActionGroup) {
        super.prependActions(actionGroup)
        actionGroup.childActionsOrStubs.filterIsInstance<ChangeScopeAction>().forEach(actionGroup::remove)
    }

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val baseOnThis = BaseOnThisMethodAction()
        val shortcuts = ActionManager.getInstance().getAction(IdeActions.ACTION_CALL_HIERARCHY).shortcutSet
        for (type in listOf(getCallerType(), getCalleeType())) {
            trees[type] = CodeBridgeHierarchyTreeStructure.hideBase(createTree(false)).also { baseOnThis.registerCustomShortcutSet(shortcuts, it) }
        }
    }

    override fun isApplicableElement(element: PsiElement): Boolean = element is CodeBridgeHierarchyElement

    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val base = CodeBridgeHierarchyTreeStructure.base(myProject, psiElement)
        return when (type) {
            getCallerType() -> CodeBridgeHierarchyTreeStructure(myProject, base, Hierarchies.Kind.CALL, sessions, CodeBridgeHierarchyTreeStructure::callers)
            getCalleeType() -> CodeBridgeHierarchyTreeStructure(myProject, base, Hierarchies.Kind.CALL, sessions, CodeBridgeHierarchyTreeStructure::callees)
            else -> null
        }
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = CodeBridgeHierarchyTreeStructure.comparator(myProject)
}

/** Supertypes and subtypes; VS Code has no whole class hierarchy, so neither has this. */
internal class CodeBridgeTypeHierarchyBrowser(project: Project, element: PsiElement) : TypeHierarchyBrowserBase(project, element) {

    private val sessions = CodeBridgeHierarchySessions(project, Hierarchies.Kind.TYPE).also { Disposer.register(this, it) }

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun prependActions(actionGroup: DefaultActionGroup) {
        actionGroup.add(ViewSupertypesHierarchyAction())
        actionGroup.add(ViewSubtypesHierarchyAction())
        actionGroup.add(AlphaSortAction())
    }

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val baseOnThis = createBaseOnThisAction()
        val shortcuts = ActionManager.getInstance().getAction(IdeActions.ACTION_TYPE_HIERARCHY).shortcutSet
        for (type in listOf(getSupertypesHierarchyType(), getSubtypesHierarchyType())) {
            trees[type] = CodeBridgeHierarchyTreeStructure.hideBase(createTree(false)).also { baseOnThis.registerCustomShortcutSet(shortcuts, it) }
        }
    }

    override fun createLegendPanel(): JPanel? = null

    override fun isApplicableElement(element: PsiElement): Boolean = element is CodeBridgeHierarchyElement

    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? {
        val base = CodeBridgeHierarchyTreeStructure.base(myProject, psiElement)
        return when (type) {
            getSupertypesHierarchyType() -> CodeBridgeHierarchyTreeStructure(myProject, base, Hierarchies.Kind.TYPE, sessions, CodeBridgeHierarchyTreeStructure::supertypes)
            getSubtypesHierarchyType() -> CodeBridgeHierarchyTreeStructure(myProject, base, Hierarchies.Kind.TYPE, sessions, CodeBridgeHierarchyTreeStructure::subtypes)
            else -> null
        }
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> = CodeBridgeHierarchyTreeStructure.comparator(myProject)

    override fun isInterface(psiElement: PsiElement): Boolean = false

    override fun canBeDeleted(psiElement: PsiElement?): Boolean = false

    override fun getQualifiedName(psiElement: PsiElement?): String? = (psiElement as? CodeBridgeHierarchyElement)?.name
}
