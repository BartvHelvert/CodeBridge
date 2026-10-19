package me.helvert.codebridge.messages

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickInputEventDto
import me.helvert.codebridge.rpc.QuickItemDto
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * A picker the extension drives while it is open (`createQuickPick`), as VS Code draws one: a box to
 * type in over the extension's items, filtered as VS Code filters them, and open until the extension
 * hides it or the user dismisses it. Choosing an item does not close it: the extension may take the
 * choice as a step -- GitLens's commit search puts an operator in the box and waits for the rest.
 *
 * What the user does goes to [send] as it happens; what the extension changes comes in [update].
 * Separators are left out, as [CodeBridgeQuickPickDialog] leaves them out. Used on the EDT.
 */
internal class CodeBridgeLiveQuickPick(private var question: QuickInputDto, private val send: (QuickInputEventDto) -> Unit) {

    private val field = JBTextField(question.value)
    private val prompt = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        border = JBUI.Borders.empty(2, 6, 4, 6)
    }
    private val model = CollectionListModel<IndexedValue<QuickItemDto>>()
    private val list = JBList(model)

    /** Selection the picker makes itself, not the user, which the extension is not told of. */
    private var selecting = false

    /** The value the extension set, going into the box, which is reported once rather than per edit. */
    private var settingValue = false
    private var closing = false

    val popup: JBPopup

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = QuickItemRenderer()
        list.isFocusable = false
        list.visibleRowCount = VISIBLE_ROWS
        field.border = JBUI.Borders.compound(JBUI.Borders.empty(4), field.border)
        // The arrows move through the list while typing goes to the box, as in VS Code's picker.
        ScrollingUtil.redirectExpandSelection(list, field)
        field.addActionListener { accept() }
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (settingValue) return
                typed()
            }
        })
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting || selecting) return@addListSelectionListener
            list.selectedValue?.let {
                send(
                    QuickInputEventDto(
                        QuickInputEventDto.ACTIVE,
                        chosen = listOf(it.index),
                        itemsRevision = this.question.itemsRevision
                    )
                )
            }
        }
        // A single click chooses, as in VS Code.
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val row = list.locationToIndex(event.point)
                if (row < 0 || list.getCellBounds(row, row)?.contains(event.point) != true) return
                list.selectedIndex = row
                accept()
            }
        })
        show(question, selectActive = true)

        val panel = JPanel(BorderLayout())
        panel.add(JPanel(BorderLayout()).apply {
            add(field, BorderLayout.NORTH)
            add(prompt, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(list, true).apply {
            preferredSize = Dimension(JBUI.scale(WIDTH), list.preferredScrollableViewportSize.height)
        }, BorderLayout.CENTER)
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field)
            .setTitle(question.title.orEmpty())
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelKeyEnabled(true)
            // `ignoreFocusOut`: the extension may open something else meanwhile, a file chooser say.
            .setCancelOnClickOutside(!question.ignoreFocusOut)
            .setCancelOnWindowDeactivation(!question.ignoreFocusOut)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!closing) send(QuickInputEventDto(QuickInputEventDto.HIDE))
                }
            })
            .createPopup()
    }

    /** The extension changed the picker. */
    fun update(next: QuickInputDto) {
        val previous = question
        // The backend's list comes again whenever any question in it changes.
        if (next == previous) return
        question = next
        if (next.title != previous.title) popup.setCaption(next.title.orEmpty())
        if (next.valueRevision != previous.valueRevision && next.value != field.text) {
            settingValue = true
            try {
                field.text = next.value
            } finally {
                settingValue = false
            }
            // VS Code reports a value the extension set as one typed, and extensions count on it.
            typed()
        }
        val itemsChanged = next.itemsRevision != previous.itemsRevision
        show(next, selectActive = itemsChanged || next.active != previous.active)
    }

    /** Closed by the extension, or because nobody is waiting any more; not reported. */
    fun close() {
        closing = true
        popup.cancel()
    }

    private fun typed() {
        send(QuickInputEventDto(QuickInputEventDto.VALUE, value = field.text))
        // VS Code makes the first match active as the filter changes.
        refilter(active = null)
    }

    private fun show(question: QuickInputDto, selectActive: Boolean) {
        field.emptyText.text = question.placeholder.orEmpty()
        prompt.text = question.prompt.orEmpty()
        prompt.isVisible = !question.prompt.isNullOrEmpty()
        list.emptyText.text = if (question.busy) "Loading..." else "No matching results"
        refilter(active = if (selectActive) question.active else list.selectedValue?.index)
    }

    /** Shows the items that match the box, with the one at position [active] selected if it is shown, else the first. */
    private fun refilter(active: Int?) {
        val words = field.text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val shown = question.items.withIndex().filter { (_, item) -> !item.separator && matches(item, words) }
        selecting = true
        try {
            if (shown != model.items) model.replaceAll(shown)
            val row = shown.indexOfFirst { it.index == active }.takeIf { it >= 0 } ?: 0
            if (shown.isNotEmpty()) ScrollingUtil.selectItem(list, row) else list.clearSelection()
        } finally {
            selecting = false
        }
    }

    /** VS Code's filter: every word typed is in the label, or the description or detail where the extension asked. */
    private fun matches(item: QuickItemDto, words: List<String>): Boolean {
        if (item.alwaysShow || words.isEmpty()) return true
        val text = listOfNotNull(
            item.label,
            item.description.takeIf { question.matchOnDescription },
            item.detail.takeIf { question.matchOnDetail },
        ).joinToString(" ").lowercase()
        return words.all { it in text }
    }

    private fun accept() {
        val chosen = listOfNotNull(list.selectedValue?.index)
        send(QuickInputEventDto(QuickInputEventDto.ACCEPT, chosen = chosen, itemsRevision = question.itemsRevision))
    }

    private companion object {
        /** VS Code's picker is about as wide. */
        const val WIDTH = 600
        const val VISIBLE_ROWS = 12
    }
}
