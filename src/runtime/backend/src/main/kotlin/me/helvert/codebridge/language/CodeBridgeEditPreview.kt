package me.helvert.codebridge.language

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.exthost.protocol.payload.WorkspaceEdit
import org.jetbrains.annotations.TestOnly
import java.util.*
import javax.swing.JComponent
import javax.swing.JTree

/**
 * VS Code's Refactor Preview: an extension's edit laid out for the user to pick from before any of it
 * is applied.
 *
 * Shown when the edit asks for it -- a change marked `needsConfirmation` -- or when the user chose
 * to preview the edits extensions make for a file move. Grouped as VS Code groups them: by the
 * label the extension gave each change, the ones needing confirmation first, then by file. Every
 * change starts ticked except those needing confirmation, which is the point of asking.
 */
internal object CodeBridgeEditPreview {

    /** The changes the user kept, in the edit's own order, or null when they discarded it all. */
    fun choose(project: Project, edit: WorkspaceEdit, title: String): WorkspaceEdit? {
        testChooser?.let { return it(edit) }
        val dialog = Dialog(project, edit, title)
        if (!dialog.showAndGet()) return null
        val kept = dialog.kept()
        return WorkspaceEdit(edit.entries.filter { it in kept })
    }

    @Volatile
    private var testChooser: ((WorkspaceEdit) -> WorkspaceEdit?)? = null

    /** Stands in for the user: given the edit as previewed, answers what they keep. */
    @TestOnly
    fun chooseForTest(chooser: ((WorkspaceEdit) -> WorkspaceEdit?)?) {
        testChooser = chooser
    }

    /** Whether a change starts ticked. */
    fun checkedByDefault(entry: WorkspaceEdit.Entry): Boolean = entry.metadata?.needsConfirmation != true

    private class Dialog(private val project: Project, edit: WorkspaceEdit, title: String) :
        DialogWrapper(project) {
        private val root = CheckedTreeNode()
        private val tree: CheckboxTree

        init {
            this.title = title
            setOKButtonText("Apply")
            setCancelButtonText("Discard")
            val categories = edit.entries.groupBy { it.metadata?.label?.takeIf(String::isNotEmpty) ?: OTHER }
                .entries.sortedWith(
                    compareBy(
                        { (_, entries) -> !entries.any { it.metadata?.needsConfirmation == true } },
                        { it.key })
                )
            // A single group is no grouping: its files go at the top.
            for ((label, entries) in categories) {
                val parent = if (categories.size == 1) root else CheckedTreeNode(Group(label)).also { root.add(it) }
                for (inFile in entries.groupBy { UriComponents.key(resourceOf(it).uri) }.values) {
                    val fileNode = CheckedTreeNode(Group(describe(resourceOf(inFile.first()))))
                    for (entry in inFile) fileNode.add(CheckedTreeNode(entry).apply {
                        isChecked = checkedByDefault(entry)
                    })
                    fileNode.isChecked = inFile.all(::checkedByDefault)
                    parent.add(fileNode)
                }
                if (parent !== root) parent.isChecked = entries.all(::checkedByDefault)
            }
            // A group follows its changes, and ticking a group ticks all of them.
            @Suppress("BooleanLiteralArgument") // A Java constructor, which takes no named arguments.
            val policy = CheckboxTreeBase.CheckPolicy(true, true, true, true)
            tree = CheckboxTree(Renderer(), root, policy)
            TreeUtil.expandAll(tree)
            init()
        }

        fun kept(): Set<WorkspaceEdit.Entry> =
            tree.getCheckedNodes(WorkspaceEdit.Entry::class.java, null)
                .toCollection(Collections.newSetFromMap(IdentityHashMap()))

        override fun createCenterPanel(): JComponent =
            ScrollPaneFactory.createScrollPane(tree).apply { preferredSize = JBUI.size(640, 400) }

        /** A label or a file: a node that only groups changes. */
        private class Group(val text: String)

        private inner class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
            override fun customizeRenderer(
                tree: JTree,
                value: Any,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                val node = value as? CheckedTreeNode ?: return
                when (val item = node.userObject) {
                    is Group -> textRenderer.append(item.text)
                    is WorkspaceEdit.ResourceEdit -> {
                        textRenderer.append(describe(item))
                        item.metadata?.description?.let {
                            textRenderer.append(
                                "  $it",
                                SimpleTextAttributes.GRAYED_ATTRIBUTES
                            )
                        }
                    }

                    is WorkspaceEdit.FileEdit -> textRenderer.append(describe(item))
                }
            }
        }

        private fun resourceOf(entry: WorkspaceEdit.Entry): UriComponents = when (entry) {
            is WorkspaceEdit.ResourceEdit -> entry.resource
            is WorkspaceEdit.FileEdit -> entry.oldResource ?: entry.newResource!!
        }

        /** A file by its path in the project, where it is in it. */
        private fun describe(resource: UriComponents): String {
            val path = runCatching { resource.toPath() }.getOrNull() ?: return resource.path
            val base = project.guessProjectDir()?.toNioPath()
            return if (base != null && path.startsWith(base)) base.relativize(path).toString()
                .replace('\\', '/') else path.toString()
        }

        /** A text change as the line it changes, before and after. */
        private fun describe(entry: WorkspaceEdit.ResourceEdit): String {
            val range = entry.edit.range
            val line = range.startLineNumber
            val file = HostedLanguageFeatures.getInstance(project).fileOf(entry.resource.uri)
                ?: runCatching { LocalFileSystem.getInstance().findFileByNioFile(entry.resource.toPath()) }.getOrNull()
            val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
            val replaced = if (document != null && line - 1 < document.lineCount) {
                val start = document.getLineStartOffset(line - 1) + range.startColumn - 1
                val endLine = (range.endLineNumber - 1).coerceAtMost(document.lineCount - 1)
                val end = document.getLineStartOffset(endLine) + range.endColumn - 1
                if (start in 0..end && end <= document.textLength) document.getText(TextRange(start, end)) else null
            } else null

            fun shown(text: String) = "'" + StringUtil.escapeStringCharacters(StringUtil.first(text, 60, true)) + "'"
            return when {
                replaced.isNullOrEmpty() -> "Line $line: insert ${shown(entry.edit.text)}"
                entry.edit.text.isEmpty() -> "Line $line: delete ${shown(replaced)}"
                else -> "Line $line: ${shown(replaced)} → ${shown(entry.edit.text)}"
            }
        }

        private fun describe(entry: WorkspaceEdit.FileEdit): String {
            val old = entry.oldResource
            val new = entry.newResource
            return when {
                old != null && new != null -> "Rename to ${describe(new)}"
                old != null -> "Delete"
                else -> "Create"
            }
        }
    }

    /** VS Code's group for changes an extension gave no label. */
    private const val OTHER = "Other"
}
