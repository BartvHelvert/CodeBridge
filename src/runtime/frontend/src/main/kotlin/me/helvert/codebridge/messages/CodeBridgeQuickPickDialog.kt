package me.helvert.codebridge.messages

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.*
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickItemDto
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * An extension's quick pick as a modal dialog, as IntelliJ's Attach to Process is one: a filter field
 * over the extension's items, matched on their label and description -- and detail, where the
 * extension asked for that -- and the positions of those chosen as the answer.
 *
 * Separators, which group VS Code's list, are left out; positions stay the extension's.
 */
internal class CodeBridgeQuickPickDialog(project: Project, private val question: QuickInputDto) : DialogWrapper(project, true) {

    private val choices = question.items.withIndex().filter { !it.value.separator }
    private val model = CollectionListModel(choices)
    private val list = JBList(model)
    private val filter = SearchTextField(false)

    init {
        title = question.title ?: question.placeholder ?: question.prompt ?: "Select"
        list.selectionMode =
            if (question.canPickMany) ListSelectionModel.MULTIPLE_INTERVAL_SELECTION else ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = QuickItemRenderer()
        list.emptyText.text = "Nothing matches"
        filter.textEditor.emptyText.text = question.placeholder ?: question.prompt ?: ""
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refilter()
        })
        // The arrows move through the list while typing goes to the filter, as in VS Code's picker.
        ScrollingUtil.redirectExpandSelection(list, filter.textEditor)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedIndex < 0) return false
                doOKAction()
                return true
            }
        }.installOn(list)
        list.addListSelectionListener { isOKActionEnabled = list.selectedIndex >= 0 }
        init()
        val picked = choices.filter { it.value.picked }
        if (picked.isEmpty()) ScrollingUtil.selectItem(list, 0)
        else list.selectedIndices = picked.map { model.getElementIndex(it) }.toIntArray()
        isOKActionEnabled = list.selectedIndex >= 0
    }

    /** The extension's positions of the items chosen, in its order. */
    val chosen: List<Int> get() = list.selectedValuesList.map { it.index }.sorted()

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(filter, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(640), JBUI.scale(420)) }, BorderLayout.CENTER)
        // The prompt, when the title already says what to choose.
        question.prompt?.takeIf { question.title != null }?.let {
            add(com.intellij.ui.components.JBLabel(it).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = filter.textEditor

    override fun getDimensionServiceKey(): String = "CodeBridge.QuickPick"

    private fun refilter() {
        val words = filter.text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val kept = choices.filter { (_, item) ->
            val text = listOfNotNull(item.label, item.description, item.detail).joinToString(" ").lowercase()
            words.all { it in text }
        }
        model.replaceAll(kept)
        if (kept.isNotEmpty()) ScrollingUtil.selectItem(list, 0)
    }
}

/** A quick pick item as VS Code shows it: the label, then its description and detail, greyed. */
internal class QuickItemRenderer : ColoredListCellRenderer<IndexedValue<QuickItemDto>>() {
    override fun customizeCellRenderer(list: JList<out IndexedValue<QuickItemDto>>, value: IndexedValue<QuickItemDto>,
                                       index: Int, selected: Boolean, hasFocus: Boolean) {
        val item = value.value
        append(item.label)
        item.description?.let { append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        item.detail?.let { append("   $it", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES) }
    }
}
